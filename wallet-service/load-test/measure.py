"""충전 부하 한 번 측정: 사전 확인 → k6 실행(서비스·DB 지표 1초 표본) → 종결 대기 → 지표 → 출금 정합성 → 대사.

로컬 전체 스택(PC)에 k6 컨테이너만 붙여 wallet-service를 직접 호출합니다(Gateway 제외). README.md를 먼저 봅니다.

사용:
  python measure.py <LABEL> --count 1000 --rate 50 --concurrency 1 [--amount 10000] [--pre-vus N] [--max-vus N]
                    [--drain-timeout 600] [--fault-rate 0.0]
  python measure.py --recompute <LABEL> [--ignore-members]
결과: results/<LABEL>-c<CONCURRENCY>.json(k6) + results/<LABEL>-c<CONCURRENCY>-metrics.json(서버·DB 지표, 정합성, 대사)

--recompute는 results/<LABEL>-c*-metrics.json(정확히 1개)의 window로 측정 DB에서 충전 지표를 다시 계산해
results/<원래 파일명>-recompute.json에 씁니다(원래 결과 파일은 그대로).

--concurrency는 결과에 남기는 값입니다. 실제 동시성은 지갑 실행 인자로 바꾸고 재기동합니다(스크립트가 바꾸지 않음).
서비스 지표 구간은 k6 시작 ~ 마지막 종결 시각입니다(접수 뒤 SAGA가 은행·원장을 부르므로 종결까지 봅니다).
"""
import argparse
import json
import re
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone

from lt_common import (HERE, PSQL, RESULTS, SERVICES, WALLET_URL, load_data, member_headers, psql, request, scrape,
                       sql_array, sql_text, summarize_scrape)

# 서비스별로 볼 HTTP 경로 (충전 한 건이 지나는 곳)
WATCHED = {
    "wallet": ["POST /api/v1/charges"],
    "user": ["GET /internal/v1/users/{userId}/funding-identity"],
    "ledger": ["POST /internal/v1/ledger/transactions"],
    "mock-bank": ["POST /internal/v1/bank/withdrawals", "POST /internal/v1/bank/refunds"],
}
TERMINAL = ("COMPLETED", "FAILED", "REFUNDED", "MANUAL_REVIEW")
TERMINAL_SQL = "(" + ",".join(f"'{status}'" for status in TERMINAL) + ")"
# 원장 잠금 대기·실행 중 세션, 지갑 실행 중 세션, DB 교착 누적 수, Outbox 미발행 수
PG_QUERY = (
    "SELECT extract(epoch FROM now())::bigint,"
    " count(*) FILTER (WHERE usename = 'ledger_app' AND wait_event_type = 'Lock'),"
    " count(*) FILTER (WHERE usename = 'ledger_app' AND state = 'active'),"
    " count(*) FILTER (WHERE usename = 'wallet_app' AND state = 'active'),"
    " (SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()),"
    " (SELECT count(*) FROM wallet_service.outbox_events WHERE status = 'PENDING')"
    " FROM pg_stat_activity WHERE pid <> pg_backend_pid()"
)
PG_COLUMNS = 6
MASTER = member_headers("00000000-0000-4000-8000-00000000a0a0", "MASTER")   # 관리자 대사용(측정 전용 ID)
RECONCILIATION_GRACE_SECONDS = 10   # 지갑 실행 인자 grace=10s와 같게
RECONCILIATION_URL = f"{WALLET_URL}/api/v1/admin/charges/reconciliations"
RECONCILIATION_FIELDS = ("runId", "status", "targetCount", "matchedCount", "mismatchCount", "unverifiableCount")
K6_FIELDS = ("requests", "droppedIterations", "errorResponses", "latencyMs", "statusCounts")
K6_STATUS_KEYS = ("202", "200", "4xx", "5xx", "noResponse")
# Mock 은행 출금 응답 유실 카운터(비율 주입분)와 복구 스케줄러가 상태 이력에 남기는 trace_id 접두어
LOST_COUNTER = "mock_bank_fault_withdrawal_lost_rate_total"
RECOVERY_TRACE_PREFIX = "recovery-"
WITHDRAWAL_PATH = "POST /internal/v1/bank/withdrawals"
RECOVERED_DEFINITION = ("recoveredCharges = 측정 충전 중 상태 이력 trace_id가 recovery-로 시작하는 행이 하나 이상 있는 충전 수"
                        " (복구 스케줄러가 한 번 이상 처리한 충전, 큐에서 30초 넘게 기다린 충전 포함)."
                        " 복구 성공 여부는 기존 실패 조건으로 판정")


def iso(moment):
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


# ---- 사전 확인 ----

def precheck(data):
    for name, url in SERVICES.items():
        health = request("GET", f"{url}/actuator/health", timeout=5)
        if health.status != 200:
            raise SystemExit(f"{name}({url})이 응답하지 않습니다: {health}")
    in_progress = int(psql(f"SELECT count(*) FROM wallet_service.charge_requests WHERE status NOT IN {TERMINAL_SQL};")[0][0])
    if in_progress:
        raise SystemExit(f"처리 중 충전 {in_progress}건이 남아 있습니다. 모두 종결된 뒤 측정합니다(복구 재실행이 섞임).")
    accounts = int(psql(f"SELECT count(*) FROM ({accounts_sql(data)}) a;")[0][0])
    if accounts != len(data["users"]):
        raise SystemExit(f"mock-bank 측정 계좌가 {accounts}개입니다(회원 {len(data['users'])}명). prepare.py를 다시 실행하세요.")


def user_bank_code(data, user):
    """회원의 은행 코드. 회원별 은행 코드가 없는 이전 users.json은 공통 bankCode를 씁니다."""
    return user.get("bankCode") or data["bankCode"]


def accounts_sql(data):
    """측정 계좌 ID: 회원별 (은행, 계좌번호) 쌍과 정확히 맞는 계좌"""
    banks = [user_bank_code(data, user) for user in data["users"]]
    numbers = [user["accountNumber"] for user in data["users"]]
    return ("SELECT a.id FROM mock_bank.bank_accounts a"
            f" JOIN unnest({sql_array(banks, 'varchar')}, {sql_array(numbers, 'varchar')}) AS u(bank_code, account_number)"
            " ON a.bank_code = u.bank_code AND a.account_number = u.account_number")


def bank_balance(data):
    return int(psql(f"SELECT coalesce(sum(balance), 0) FROM mock_bank.bank_accounts WHERE id IN ({accounts_sql(data)});")[0][0])


# ---- 표본 수집 ----

class ServiceSampler(threading.Thread):
    """1초마다 네 서비스의 /actuator/prometheus를 읽어 (시각, 서비스별 요약)을 쌓습니다."""

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self.error = None
        self.stop = threading.Event()

    def run(self):
        try:
            self._run()
        except Exception as error:   # 표본이 중간에 끊기면 실패 사유로 남긴다
            self.error = repr(error)

    def _run(self):
        while not self.stop.is_set():
            started = time.time()
            snapshot = {}
            for name, url in SERVICES.items():
                samples = scrape(url)
                snapshot[name] = summarize_scrape(samples) if samples is not None else None
                if name == "mock-bank" and samples is not None:
                    lost = [value for metric, _, value in samples if metric == LOST_COUNTER]
                    if lost:
                        snapshot[name]["faultLost"] = sum(lost)
            self.samples.append((started, snapshot))
            self.stop.wait(max(0.0, 1.0 - (time.time() - started)))


class DbSampler(threading.Thread):
    """Postgres 컨테이너 안 psql \\watch로 1초마다 잠금 대기·세션·교착·Outbox 수를 읽습니다.

    측정 길이(종결 대기 포함)를 미리 모르므로 30회짜리 \\watch를 멈출 때까지 이어서 실행합니다.
    실패하면 표본 없이 끝나고 측정은 계속됩니다(db.error).
    """
    CHUNK = 30

    def __init__(self):
        super().__init__(daemon=True)
        self.samples = []
        self.error = None
        self.stop = threading.Event()

    def run(self):
        try:
            self._run()
        except Exception as error:   # 출력 파싱 등에서 멈추면 표본이 끊긴 것으로 남긴다
            self.error = repr(error)

    def _run(self):
        script = f"{PG_QUERY}\n\\watch i=1 c={self.CHUNK}\n"
        while not self.stop.is_set():
            try:
                done = subprocess.run(PSQL, input=script, capture_output=True, text=True, encoding="utf-8",
                                      timeout=self.CHUNK + 30)
            except (OSError, subprocess.TimeoutExpired) as error:
                self.error = str(error)
                return
            for line in done.stdout.splitlines():
                parts = line.split("\t")
                if len(parts) == PG_COLUMNS and parts[0].isdigit():
                    self.samples.append(tuple(int(part) for part in parts))
            if done.returncode != 0:
                self.error = f"종료 코드 {done.returncode}: {done.stderr.strip()[:300]}"
                return


# ---- 서버·DB 지표 ----

def nearest(samples, moment):
    return min(samples, key=lambda sample: abs(sample[0] - moment))


def service_metrics(samples, start, end):
    window = [sample for sample in samples if start <= sample[0] <= end]
    result = {"samples": len(window)}
    if not window:
        return result
    first, last = nearest(samples, start), nearest(samples, end)
    for name, paths in WATCHED.items():
        before, after = first[1].get(name), last[1].get(name)
        service = {}
        if before and after:
            for path in paths:
                b = before["http"].get(path, {"count": 0, "sum": 0})
                a = after["http"].get(path, {"count": 0, "sum": 0})
                calls = a["count"] - b["count"]
                if calls > 0:
                    service[path] = {"calls": int(calls), "avgMs": round((a["sum"] - b["sum"]) / calls * 1000, 2)}
        gauges = [snapshot[1][name] for snapshot in window if snapshot[1].get(name)]
        for gauge, label in (("hikaricp_connections_pending", "hikariPending"),
                             ("hikaricp_connections_active", "hikariActive"),
                             ("tomcat_threads_busy_threads", "tomcatBusyThreads")):
            values = [g[gauge] for g in gauges if gauge in g]
            if values:
                service[label] = {"avg": round(sum(values) / len(values), 2), "max": max(values)}
        cpu = [g["process_cpu_usage"] for g in gauges if "process_cpu_usage" in g]
        if cpu:
            service["processCpuPercentAvg"] = round(sum(cpu) / len(cpu) * 100, 1)
        if gauges and gauges[0].get("hikaricp_connections_max") is not None:
            service["hikariMax"] = gauges[0]["hikaricp_connections_max"]
        result[name] = service
    system = [snapshot[1]["wallet"]["system_cpu_usage"] for snapshot in window
              if snapshot[1].get("wallet") and "system_cpu_usage" in snapshot[1]["wallet"]]
    if system:
        result["systemCpuPercent"] = {"avg": round(sum(system) / len(system) * 100, 1),
                                      "max": round(max(system) * 100, 1)}
    return result


def incomplete_services(samples, start, end):
    """HTTP 평균 계산에 쓰는 구간 시작·끝 경계 표본(service_metrics의 nearest)이 없는 서비스"""
    window = [sample for sample in samples if start <= sample[0] <= end]
    if not window:
        return list(SERVICES)
    first, last = nearest(samples, start), nearest(samples, end)
    return [name for name in SERVICES if not (first[1].get(name) and last[1].get(name))]


def db_metrics(sampler, start, end):
    if sampler.error and not sampler.samples:
        return {"error": sampler.error}
    window = [sample for sample in sampler.samples if start <= sample[0] <= end]
    if not window:
        return {"samples": 0, "error": sampler.error}

    def stat(index):
        values = [sample[index] for sample in window]
        return {"avg": round(sum(values) / len(values), 2), "max": max(values)}

    return {
        "samples": len(window),
        "ledgerLockWaits": stat(1),
        "ledgerActiveSessions": stat(2),
        "walletActiveSessions": stat(3),
        "deadlocksDuringMeasure": window[-1][4] - window[0][4],
        "outboxPending": stat(5),
        "error": sampler.error,
    }


# ---- 충전 지표 (wallet DB) ----

def measured_sql(data, since, until=None, ignore_members=False):
    """이번 측정의 충전: 측정 회원 + created_at ≥ since (+ 다시 계산할 때는 created_at ≤ until).

    ignore_members는 --recompute --ignore-members에서만 씁니다(회원 조건 없이 기간만으로 고름).
    """
    user_ids = [user["userId"] for user in data["users"]]
    members = "TRUE" if ignore_members else f"user_id = ANY({sql_array(user_ids, 'uuid')})"
    sql = (f"SELECT * FROM wallet_service.charge_requests WHERE {members}"
           f" AND created_at >= {sql_text(iso(since))}::timestamptz")
    if until is not None:
        sql += f" AND created_at <= {sql_text(iso(until))}::timestamptz"
    return sql


def recovered_condition(alias):
    """복구 개입 충전 조건(recoveredCharges 기준): 상태 이력 trace_id가 recovery-로 시작하는 행이 하나 이상"""
    return (f"EXISTS (SELECT 1 FROM wallet_service.charge_status_history h"
            f"        WHERE h.charge_id = {alias}.id AND h.trace_id LIKE {sql_text(RECOVERY_TRACE_PREFIX + '%')})")


def optional_ms(value):
    return round(float(value), 1) if value not in ("", None) else None


def wait_terminal(data, since, timeout_seconds):
    """이번 측정 충전이 모두 종결될 때까지 기다리고 걸린 초를 돌려줍니다. 시간을 넘기면 None."""
    started = time.monotonic()
    while True:
        left = int(psql(f"SELECT count(*) FROM ({measured_sql(data, since)}) m WHERE status NOT IN {TERMINAL_SQL};")[0][0])
        if left == 0:
            return round(time.monotonic() - started, 1)
        if time.monotonic() - started > timeout_seconds:
            print(f"  {timeout_seconds}초 안에 종결되지 않은 충전 {left}건", flush=True)
            return None
        print(f"  처리 중 {left}건 종결 대기", flush=True)
        time.sleep(5)


def charge_metrics(data, since, until=None, ignore_members=False):
    measured = measured_sql(data, since, until, ignore_members)
    statuses = {row[0]: {"count": int(row[1]), "amount": int(row[2])}
                for row in psql(f"SELECT status, count(*), coalesce(sum(amount), 0) FROM ({measured}) m GROUP BY status;")}
    accepted = sum(entry["count"] for entry in statuses.values())
    if accepted == 0:
        return {"accepted": 0, "statuses": statuses}
    # 첫 접수 시각은 대사 기간 시작으로 그대로 쓰므로 마이크로초 문자열로 받는다
    first_created_iso, first_created, last_terminal = psql(
        "SELECT to_char(min(m.created_at) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),"
        " extract(epoch FROM min(m.created_at)),"
        " (SELECT extract(epoch FROM max(h.created_at)) FROM wallet_service.charge_status_history h"
        f"   WHERE h.charge_id IN (SELECT id FROM ({measured}) x) AND h.to_status IN {TERMINAL_SQL})"
        f" FROM ({measured}) m;")[0]
    p50, p95, completed_count, p99, max_ms = psql(
        "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY ms), percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), count(*),"
        " percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), max(ms)"
        " FROM (SELECT extract(epoch FROM (min(h.created_at) - m.created_at)) * 1000 AS ms"
        f"       FROM ({measured}) m JOIN wallet_service.charge_status_history h ON h.charge_id = m.id"
        "       WHERE h.to_status = 'COMPLETED' GROUP BY m.id, m.created_at) c;")[0]
    first_created, last_terminal = float(first_created), float(last_terminal) if last_terminal else None
    span = (last_terminal - first_created) if last_terminal else None
    result = {
        "accepted": accepted,
        "statuses": statuses,
        "firstCreatedAt": first_created_iso,
        "firstCreatedEpoch": first_created,
        "lastTerminalAt": iso(datetime.fromtimestamp(last_terminal, timezone.utc)) if last_terminal else None,
        "lastTerminalEpoch": last_terminal,
        "spanSeconds": round(span, 3) if span else None,
        # 완료 처리량 = 실제 접수 건수 ÷ (첫 접수 ~ 마지막 종결)
        "completionTps": round(accepted / span, 2) if span else None,
        "completionLatencyMs": {
            "count": int(completed_count),
            "p50": round(float(p50), 1) if p50 else None,
            "p95": round(float(p95), 1) if p95 else None,
        },
        "byBank": charges_by_bank(measured),
        # 꼬리 지표 1: 완료 지연 분포(충전 하나의 접수 → 첫 COMPLETED). completionLatencyMs와 같은 데이터·같은 백분위 함수
        "completionLatencyTailMs": {"count": int(completed_count), "p99": optional_ms(p99), "max": optional_ms(max_ms)},
    }
    # 꼬리 지표 2·3: 종결 진행(전체 첫 접수 기준), 꼬리 구간(종결 순서 앞 99% 뒤) 충전 구성
    result.update(terminal_progress(measured, first_created, result["byBank"]))
    return result


def charges_by_bank(measured):
    """충전의 은행 코드(charge_requests.bank_code)별 상태와 완료 지연(접수 → 첫 COMPLETED 이력). 정의는 전체 값과 같습니다."""
    by_bank = {}
    for bank, status, count, amount in psql(
            f"SELECT bank_code, status, count(*), coalesce(sum(amount), 0) FROM ({measured}) m"
            " GROUP BY bank_code, status ORDER BY bank_code, status;"):
        entry = by_bank.setdefault(bank, {"accepted": 0, "statuses": {},
                                          "completionLatencyMs": {"count": 0, "p50": None, "p95": None},
                                          "completionLatencyTailMs": {"count": 0, "p99": None, "max": None}})
        entry["statuses"][status] = {"count": int(count), "amount": int(amount)}
        entry["accepted"] += int(count)
    for bank, p50, p95, completed_count, p99, max_ms in psql(
            "SELECT bank_code, percentile_cont(0.5) WITHIN GROUP (ORDER BY ms),"
            " percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), count(*),"
            " percentile_cont(0.99) WITHIN GROUP (ORDER BY ms), max(ms)"
            " FROM (SELECT m.bank_code, extract(epoch FROM (min(h.created_at) - m.created_at)) * 1000 AS ms"
            f"       FROM ({measured}) m JOIN wallet_service.charge_status_history h ON h.charge_id = m.id"
            "       WHERE h.to_status = 'COMPLETED' GROUP BY m.id, m.created_at, m.bank_code) c"
            " GROUP BY bank_code;"):
        by_bank[bank]["completionLatencyMs"] = {
            "count": int(completed_count),
            "p50": round(float(p50), 1) if p50 else None,
            "p95": round(float(p95), 1) if p95 else None,
        }
        by_bank[bank]["completionLatencyTailMs"] = {"count": int(completed_count), "p99": optional_ms(p99),
                                                    "max": optional_ms(max_ms)}
    return by_bank


PROGRESS_POINTS = (50, 90, 99, 100)   # 종결 진행 시각을 낼 비율(%)
TAIL_FROM = 99                        # 꼬리 구간: 종결 순서 앞 99%(건수 올림) 뒤
PROGRESS_DEFINITION = ("terminalProgress = 전체 첫 접수(firstCreatedAt)부터 측정 충전 중 N%(건수 올림)가 종결된 시각(초)."
                       " 충전의 종결 시각 = 종결 상태(lastTerminalAt과 같은 상태) 이력 중 가장 늦은 시각."
                       " 은행별도 기준점은 전체 firstCreatedAt. tailSeconds = 99% 시각 → 100% 시각."
                       " 충전 하나의 접수 → 완료 지연(completionLatencyMs·completionLatencyTailMs)과 다른 지표")
TAIL_DEFINITION = ("tailCharges = 종결 시각(같으면 충전 ID) 순으로 정렬해 앞 99%(건수 올림) 뒤에 오는 충전(전체 기준)."
                   " 종결되지 않은 충전은 맨 뒤. recovered는 recoveredCharges와 같은 기준")


def progress_seconds(ordered, total, first_created):
    """종결 순서로 정렬한 목록에서 N%(올림)번째 충전의 종결 시각 − 첫 접수(초). 그만큼 종결되지 않았으면 None"""
    points = {}
    for percent in PROGRESS_POINTS:
        rank = -(-total * percent // 100)
        moment = ordered[rank - 1][0] if 0 < rank <= len(ordered) else None
        points[f"p{percent}"] = round(moment - first_created, 3) if moment is not None else None
    tail = (round(points["p100"] - points["p99"], 3)
            if points["p100"] is not None and points["p99"] is not None else None)
    return {"total": total, "terminated": len(ordered), "secondsFromFirstCreated": points, "tailSeconds": tail}


def terminal_progress(measured, first_created, by_bank):
    """종결 진행(전체·은행별)과 꼬리 구간 구성. by_bank 항목에 은행별 terminalProgress를 넣습니다."""
    rows = psql(
        "SELECT m.id, m.bank_code,"
        " (SELECT extract(epoch FROM max(h.created_at)) FROM wallet_service.charge_status_history h"
        f"   WHERE h.charge_id = m.id AND h.to_status IN {TERMINAL_SQL}),"
        f" {recovered_condition('m')}"
        f" FROM ({measured}) m;")
    charges = [(float(moment) if moment else None, charge_id, bank, recovered == "t")
               for charge_id, bank, moment, recovered in rows]
    terminated = sorted((charge for charge in charges if charge[0] is not None), key=lambda c: (c[0], c[1]))
    progress = progress_seconds(terminated, len(charges), first_created)
    progress["byBank"] = {}
    for bank in by_bank:
        bank_total = sum(1 for charge in charges if charge[2] == bank)
        bank_terminated = [charge for charge in terminated if charge[2] == bank]
        progress["byBank"][bank] = progress_seconds(bank_terminated, bank_total, first_created)
        by_bank[bank]["terminalProgress"] = progress["byBank"][bank]
    progress["definition"] = PROGRESS_DEFINITION

    ordered = terminated + [charge for charge in charges if charge[0] is None]
    tail = ordered[-(-len(ordered) * TAIL_FROM // 100):]
    return {
        "terminalProgress": progress,
        "tailCharges": {
            "count": len(tail),
            "recovered": sum(1 for charge in tail if charge[3]),
            "notTerminated": sum(1 for charge in tail if charge[0] is None),
            "byBank": {bank: {"count": sum(1 for charge in tail if charge[2] == bank),
                              "recovered": sum(1 for charge in tail if charge[2] == bank and charge[3])}
                       for bank in by_bank},
            "definition": TAIL_DEFINITION,
        },
    }


# ---- 장애(응답 유실) 지표 ----

def read_lost_counter():
    """Mock 은행 유실 카운터 현재값. 읽지 못하거나 카운터가 없으면 None"""
    samples = scrape(SERVICES["mock-bank"])
    if samples is None:
        return None
    lost = [value for metric, _, value in samples if metric == LOST_COUNTER]
    return sum(lost) if lost else None


def lost_responses(samples, end, before):
    """구간 끝 표본(nearest)의 유실 카운터 − k6 시작 전에 읽은 시작값. 둘 중 하나라도 없으면 None"""
    if not samples:
        return None
    after = (nearest(samples, end)[1].get("mock-bank") or {}).get("faultLost")
    if before is None or after is None:
        return None
    return int(after - before)


def fault_metrics(data, since, fault_rate, samples, end, lost_before, charges, services):
    withdrawals = (services.get("mock-bank") or {}).get(WITHDRAWAL_PATH) or {}
    return {
        "faultRate": fault_rate,
        "lostResponses": lost_responses(samples, end, lost_before),
        "bankWithdrawalCalls": withdrawals.get("calls", 0),
        "acceptedCharges": charges["accepted"],
        **recovered_metrics(measured_sql(data, since)),
        "definition": RECOVERED_DEFINITION,
    }


def recovered_metrics(measured):
    """복구 처리 충전 수, 그중 COMPLETED 이력이 있는 충전의 완료 지연(완료 이력 trace_id는 조건에 넣지 않음)"""
    recovered, p50, p95, completed_count = psql(
        f"WITH m AS ({measured}),"
        f" r AS (SELECT m.id, m.created_at FROM m WHERE {recovered_condition('m')})"
        " SELECT (SELECT count(*) FROM r),"
        " percentile_cont(0.5) WITHIN GROUP (ORDER BY ms), percentile_cont(0.95) WITHIN GROUP (ORDER BY ms), count(*)"
        " FROM (SELECT extract(epoch FROM (min(h.created_at) - r.created_at)) * 1000 AS ms"
        "       FROM r JOIN wallet_service.charge_status_history h ON h.charge_id = r.id"
        "       WHERE h.to_status = 'COMPLETED' GROUP BY r.id, r.created_at) c;")[0]
    return {
        "recoveredCharges": int(recovered),
        "recoveredLatencyMs": {
            "count": int(completed_count),
            "p50": round(float(p50), 1) if p50 else None,
            "p95": round(float(p95), 1) if p95 else None,
        },
    }


# ---- 출금 정합성 (mock-bank DB) ----

def bank_consistency(data, since, statuses, balance_before, by_bank_statuses):
    rows = psql(
        "SELECT type, count(*), coalesce(sum(amount), 0) FROM mock_bank.bank_transactions"
        f" WHERE status = 'SUCCEEDED' AND created_at >= {sql_text(iso(since))}::timestamptz"
        f" AND ((type = 'DEBIT' AND from_account_id IN ({accounts_sql(data)}))"
        f"   OR (type = 'REFUND' AND to_account_id IN ({accounts_sql(data)})))"
        " GROUP BY type;")
    bank = {row[0]: {"count": int(row[1]), "amount": int(row[2])} for row in rows}
    debit = bank.get("DEBIT", {"count": 0, "amount": 0})
    refund = bank.get("REFUND", {"count": 0, "amount": 0})
    empty = {"count": 0, "amount": 0}
    completed, refunded = statuses.get("COMPLETED", empty), statuses.get("REFUNDED", empty)
    expected_debit = {"count": completed["count"] + refunded["count"], "amount": completed["amount"] + refunded["amount"]}
    balance_after = bank_balance(data)
    decrease = balance_before - balance_after
    violations = []
    if debit != expected_debit:
        violations.append(f"DEBIT 성공 {debit} ≠ COMPLETED·REFUNDED 충전 {expected_debit}")
    if refund != refunded:
        violations.append(f"REFUND 성공 {refund} ≠ REFUNDED 충전 {refunded}")
    if decrease != debit["amount"] - refund["amount"]:
        violations.append(f"계좌 잔액 감소 {decrease} ≠ DEBIT 합계 − REFUND 합계 {debit['amount'] - refund['amount']}")
    by_bank = bank_consistency_by_bank(data, since, by_bank_statuses)
    for bank, entry in by_bank.items():
        violations += [f"은행 {bank}: {violation}" for violation in entry.pop("violations")]
    return {
        "debit": debit,
        "refund": refund,
        "expectedDebit": expected_debit,
        "expectedRefund": refunded,
        "balance": {"before": balance_before, "after": balance_after, "decrease": decrease},
        "byBank": by_bank,
        "violations": violations,
    }


def bank_consistency_by_bank(data, since, by_bank_statuses):
    """은행 코드별 DEBIT·REFUND 성공 = 그 은행 COMPLETED·REFUNDED 충전. 계좌의 은행은 mock-bank 계좌(bank_code)로 나눕니다."""
    rows = psql(
        "SELECT a.bank_code, t.type, count(*), coalesce(sum(t.amount), 0) FROM mock_bank.bank_transactions t"
        " JOIN mock_bank.bank_accounts a ON a.id = CASE WHEN t.type = 'DEBIT' THEN t.from_account_id ELSE t.to_account_id END"
        f" WHERE t.status = 'SUCCEEDED' AND t.created_at >= {sql_text(iso(since))}::timestamptz"
        f" AND a.id IN ({accounts_sql(data)})"
        " GROUP BY a.bank_code, t.type;")
    actual = {}
    for bank, kind, count, amount in rows:
        actual.setdefault(bank, {})[kind] = {"count": int(count), "amount": int(amount)}
    empty = {"count": 0, "amount": 0}
    result = {}
    for bank in sorted(set(actual) | set(by_bank_statuses)):
        statuses = by_bank_statuses.get(bank, {})
        completed, refunded = statuses.get("COMPLETED", empty), statuses.get("REFUNDED", empty)
        expected_debit = {"count": completed["count"] + refunded["count"],
                          "amount": completed["amount"] + refunded["amount"]}
        debit = actual.get(bank, {}).get("DEBIT", empty)
        refund = actual.get(bank, {}).get("REFUND", empty)
        violations = []
        if debit != expected_debit:
            violations.append(f"DEBIT 성공 {debit} ≠ COMPLETED·REFUNDED 충전 {expected_debit}")
        if refund != refunded:
            violations.append(f"REFUND 성공 {refund} ≠ REFUNDED 충전 {refunded}")
        result[bank] = {"debit": debit, "refund": refund, "expectedDebit": expected_debit,
                        "expectedRefund": refunded, "violations": violations}
    return result


# ---- 대사 (운영 API) ----

def reconcile(first_created_iso, last_terminal):
    """마지막 종결 후 grace보다 넉넉히 기다린 뒤 [첫 접수, 지금 − grace]로 수동 대사를 실행합니다."""
    wait = last_terminal + RECONCILIATION_GRACE_SECONDS + 1 - time.time()
    if wait > 0:
        print(f"  마지막 종결 후 {RECONCILIATION_GRACE_SECONDS}초가 지나도록 {wait:.1f}초 대기", flush=True)
        time.sleep(wait)
    period_to = iso(datetime.now(timezone.utc) - timedelta(seconds=RECONCILIATION_GRACE_SECONDS))
    started = time.monotonic()
    result = request("POST", f"{RECONCILIATION_URL}?from={first_created_iso}&to={period_to}", MASTER, timeout=300)
    elapsed = round(time.monotonic() - started, 2)
    outcome = {"periodFrom": first_created_iso, "periodTo": period_to, "httpStatus": result.status, "elapsedSeconds": elapsed}
    if result.status != 200:
        outcome["error"] = result.body
        return outcome
    summary = result.data
    outcome["summary"] = summary
    if summary.get("mismatchCount") or summary.get("unverifiableCount"):
        detail = request("GET", f"{RECONCILIATION_URL}/{summary['runId']}?size=100", MASTER)
        if detail.status == 200:
            outcome["results"] = [{key: item.get(key) for key in ("chargeId", "mismatchType", "detail", "sourceType")}
                                  for item in detail.data["results"]["items"]]
        else:
            outcome["resultsError"] = f"{detail} {detail.body}"
    return outcome


# ---- 실행 ----

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("label")
    parser.add_argument("--count", type=int, required=True, help="시작할 충전 건수")
    parser.add_argument("--rate", type=int, required=True, help="초당 시작 건수")
    parser.add_argument("--concurrency", required=True, help="지갑 SAGA 컨슈머 동시성 (결과에 남기는 값)")
    parser.add_argument("--amount", type=int, default=10000)
    parser.add_argument("--pre-vus", type=int, help="기본 max(20, rate)")
    parser.add_argument("--max-vus", type=int, help="기본 max(100, rate × 4)")
    parser.add_argument("--drain-timeout", type=int,
                        help="k6 종료 후 모든 충전 종결을 기다릴 최대 초 (기본 600, --fault-rate > 0이면 900)")
    parser.add_argument("--fault-rate", type=float, default=0.0,
                        help="Mock 은행 출금 응답 유실 비율 0.0~1.0 (결과에 남기는 값, 실제 비율은 Mock 은행 실행 인자)")
    args = parser.parse_args()
    if args.count < 1 or args.rate < 1 or args.amount < 1:
        raise SystemExit("--count·--rate·--amount는 1 이상입니다.")
    if not 0.0 <= args.fault_rate <= 1.0:
        raise SystemExit("--fault-rate는 0.0~1.0입니다.")
    if args.drain_timeout is None:
        args.drain_timeout = 900 if args.fault_rate > 0 else 600
    pre_vus = args.pre_vus or max(20, args.rate)
    max_vus = args.max_vus or max(100, args.rate * 4)

    data = load_data()
    RESULTS.mkdir(exist_ok=True)
    k6_result = RESULTS / f"{args.label}-c{args.concurrency}.json"
    metrics_result = RESULTS / f"{args.label}-c{args.concurrency}-metrics.json"
    for stale in (k6_result, metrics_result):
        stale.unlink(missing_ok=True)   # 앞 실행 결과를 이번 결과로 오인하지 않게

    print("[1/6] 사전 확인 (서비스 health, 처리 중 충전, 측정 계좌)")
    precheck(data)
    balance_before = bank_balance(data)
    lost_before = read_lost_counter()   # 유실 카운터 시작값 (k6 시작 전)

    print(f"[2/6] k6 실행 (초당 {args.rate}건 × {args.count}건, 동시성 {args.concurrency}), 서비스·DB 지표 1초 간격 기록")
    service_sampler = ServiceSampler()
    db_sampler = DbSampler()
    service_sampler.start()
    db_sampler.start()
    run_started = datetime.now(timezone.utc)
    since = run_started - timedelta(seconds=5)
    env = {"COUNT": args.count, "RATE": args.rate, "PRE_VUS": pre_vus, "MAX_VUS": max_vus, "AMOUNT": args.amount,
           "LABEL": args.label, "CONCURRENCY": args.concurrency}
    command = ["docker", "compose", "run", "--rm"]
    for key, value in env.items():
        command += ["-e", f"{key}={value}"]
    command += ["k6", "run", "--quiet", "/scripts/charge-request.js"]
    k6 = subprocess.run(command, cwd=HERE)
    k6_ended = time.time()
    if k6.returncode != 0 or not k6_result.exists():
        service_sampler.stop.set()
        db_sampler.stop.set()
        raise SystemExit(f"k6 실행 실패(종료 코드 {k6.returncode}). 결과 파일이 없습니다: {k6_result}")
    try:
        summary = json.loads(k6_result.read_text(encoding="utf-8"))
    except ValueError as error:
        raise SystemExit(f"k6 결과 파일을 읽지 못했습니다: {k6_result} ({error})")
    missing = [key for key in K6_FIELDS if key not in summary]
    missing += [f"latencyMs.{key}" for key in ("p50", "p95") if key not in (summary.get("latencyMs") or {})]
    missing += [f"statusCounts.{key}" for key in K6_STATUS_KEYS if key not in (summary.get("statusCounts") or {})]
    if missing:
        raise SystemExit(f"k6 결과 파일에 필드가 없습니다: {', '.join(missing)} ({k6_result})")

    print(f"[3/6] 종결 대기 (최대 {args.drain_timeout}초)")
    drain_seconds = wait_terminal(data, since, args.drain_timeout)
    drain_ended = time.time()
    service_sampler.stop.set()
    db_sampler.stop.set()
    service_sampler.join()
    db_sampler.join(timeout=DbSampler.CHUNK + 60)

    print("[4/6] 지표 정리")
    charges = charge_metrics(data, since)
    window_end = charges.get("lastTerminalEpoch") or drain_ended
    window_start = run_started.timestamp()
    metrics = {
        "label": args.label, "concurrency": args.concurrency, "count": args.count, "rate": args.rate,
        "amount": args.amount, "preAllocatedVUs": pre_vus, "maxVUs": max_vus,
        "window": {"from": iso(run_started), "to": iso(datetime.fromtimestamp(window_end, timezone.utc)),
                   "k6EndedAt": iso(datetime.fromtimestamp(k6_ended, timezone.utc))},
        "accept": {"requests": summary["requests"], "droppedIterations": summary["droppedIterations"],
                   "errorResponses": summary["errorResponses"], "latencyMs": summary["latencyMs"]},
        "drainSeconds": drain_seconds,
        "charges": charges,
        "services": service_metrics(service_sampler.samples, window_start, window_end),
        "db": db_metrics(db_sampler, window_start, window_end),
    }
    metrics["fault"] = fault_metrics(data, since, args.fault_rate, service_sampler.samples, window_end, lost_before,
                                     charges, metrics["services"])

    reasons = []
    if summary["errorResponses"]:
        reasons.append(f"202 외 응답 {summary['errorResponses']}건 {summary['statusCounts']}")
    if summary["droppedIterations"]:
        reasons.append(f"dropped_iterations {summary['droppedIterations']}건 (--max-vus를 올립니다)")
    manual = charges["statuses"].get("MANUAL_REVIEW", {}).get("count", 0)
    if manual:
        reasons.append(f"MANUAL_REVIEW {manual}건")
    failed = {status: charges["statuses"].get(status, {}).get("count", 0) for status in ("FAILED", "REFUNDED")}
    if any(failed.values()):
        reasons.append(", ".join(f"{status} {count}건" for status, count in failed.items() if count))
    accepted_202 = summary["statusCounts"]["202"]
    if charges["accepted"] != accepted_202:
        reasons.append(f"DB 접수 {charges['accepted']}건 ≠ k6 202 응답 {accepted_202}건")
    responded = sum(summary["statusCounts"][key] for key in K6_STATUS_KEYS)
    if responded != args.count:
        reasons.append(f"k6 응답 집계 {responded}건 ≠ --count {args.count}건")
    incomplete = incomplete_services(service_sampler.samples, window_start, window_end)
    if incomplete:
        reasons.append(f"서비스 지표 구간 불완전: {', '.join(incomplete)}")
    if service_sampler.error:
        reasons.append(f"서비스 표본 수집 중단: {service_sampler.error}")
    if db_sampler.error:
        reasons.append(f"DB 표본 수집 실패: {db_sampler.error}")
    elif db_sampler.is_alive():
        reasons.append("DB 표본 수집이 끝나지 않음 (마지막 표본 누락)")
    elif not metrics["db"].get("samples"):
        reasons.append("DB 표본이 측정 구간에 없음")
    completed = charges["statuses"].get("COMPLETED", {}).get("count", 0)
    latency_count = (charges.get("completionLatencyMs") or {}).get("count", 0)
    if completed != latency_count:
        reasons.append(f"완료 지연 집계 {latency_count}건 ≠ COMPLETED {completed}건 (상태 이력 누락)")
    lost = metrics["fault"]["lostResponses"]
    if lost is None:
        reasons.append(f"응답 유실 카운터 수집 안 됨 ({LOST_COUNTER})")
    elif args.fault_rate > 0 and lost == 0:
        reasons.append("응답 유실 0건. Mock 은행 실행 인자의 유실 비율 확인")
    elif args.fault_rate == 0 and lost:
        reasons.append(f"기준선 측정에 응답 유실 {lost}건. Mock 은행의 유실 비율이 남아 있음")

    if drain_seconds is None:
        reasons.append(f"{args.drain_timeout}초 안에 종결되지 않은 충전이 있음 (출금 정합성·대사 생략)")
        print("[5/6] 출금 정합성: 종결되지 않은 충전이 있어 생략")
        print("[6/6] 대사: 생략")
    elif charges["accepted"] == 0:
        reasons.append("접수된 충전이 없음")
    elif charges.get("lastTerminalEpoch") is None:
        reasons.append("종결 상태 이력이 없음 (출금 정합성·대사 생략)")
    else:
        print("[5/6] 출금 정합성 (mock-bank 측정 계좌)")
        by_bank_statuses = {bank: entry["statuses"] for bank, entry in charges["byBank"].items()}
        metrics["bankConsistency"] = bank_consistency(data, since, charges["statuses"], balance_before,
                                                      by_bank_statuses)
        reasons += metrics["bankConsistency"]["violations"]
        print("[6/6] 대사 (POST /api/v1/admin/charges/reconciliations)")
        metrics["reconciliation"] = reconcile(charges["firstCreatedAt"], charges["lastTerminalEpoch"])
        run = metrics["reconciliation"].get("summary")
        missing = [key for key in RECONCILIATION_FIELDS if run and run.get(key) is None]
        if not run:
            reasons.append(f"대사 실행 실패 HTTP {metrics['reconciliation']['httpStatus']}")
        elif missing:
            reasons.append(f"대사 응답 필드 누락: {', '.join(missing)}")
        else:
            if run["targetCount"] < charges["accepted"]:
                reasons.append(f"대사 대상 {run['targetCount']}건 < 측정 접수 {charges['accepted']}건")
            if run["status"] != "COMPLETED" or run["mismatchCount"] or run["unverifiableCount"]:
                reasons.append(f"대사 {run['status']}, 어긋남 {run['mismatchCount']}건, 판정 보류 {run['unverifiableCount']}건")

    metrics["failures"] = reasons
    metrics_result.write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    print_brief(metrics)
    if reasons:
        print("\n주의: 이 측정은 원인을 확인하기 전까지 기준값으로 쓰지 않습니다.")
        for reason in reasons:
            print(f"  - {reason}")
        return 1
    return 0


def print_brief(m):
    c, a = m["charges"], m["accept"]
    latency = c.get("completionLatencyMs") or {}
    print("")
    print(f"[{m['label']}] 동시성 {m['concurrency']}, 초당 {m['rate']}건, 목표 {m['count']}건")
    print(f"  접수 {c['accepted']}건(DB) | 접수 P50 {a['latencyMs']['p50']}ms P95 {a['latencyMs']['p95']}ms"
          f" | dropped {a['droppedIterations']} | 202 외 {a['errorResponses']}")
    print(f"  완료 처리량 {c.get('completionTps')}건/초 | 완료 지연 P50 {latency.get('p50')}ms P95 {latency.get('p95')}ms"
          f" | 종결 대기 {m['drainSeconds']}초")
    print_tail(c, "  ")
    statuses = ", ".join(f"{status} {entry['count']}" for status, entry in c["statuses"].items())
    print(f"  상태 {statuses}")
    for bank, entry in (c.get("byBank") or {}).items():
        bank_latency = entry["completionLatencyMs"]
        bank_statuses = ", ".join(f"{status} {s['count']}" for status, s in entry["statuses"].items())
        print(f"  은행 {bank}: 접수 {entry['accepted']}건 | 완료 지연 P50 {bank_latency['p50']}ms"
              f" P95 {bank_latency['p95']}ms ({bank_latency['count']}건) | {bank_statuses}")
        print_tail(entry, "    ", (c.get("tailCharges") or {}).get("byBank", {}).get(bank))
    f = m.get("fault") or {}
    if f.get("faultRate") or f.get("lostResponses"):
        recovered = f.get("recoveredLatencyMs") or {}
        print(f"  장애 비율 {f.get('faultRate')} | 응답 유실 {f.get('lostResponses')} | 출금 호출 {f.get('bankWithdrawalCalls')}"
              f" | 접수 {f.get('acceptedCharges')} | 복구 처리 {f.get('recoveredCharges')}건"
              f" (완료 지연 P50 {recovered.get('p50')}ms P95 {recovered.get('p95')}ms)")
    rec = (m.get("reconciliation") or {}).get("summary")
    if rec:
        print(f"  대사 {rec.get('status')} 대상 {rec.get('targetCount')} 일치 {rec.get('matchedCount')}"
              f" 어긋남 {rec.get('mismatchCount')} 보류 {rec.get('unverifiableCount')}"
              f" ({m['reconciliation']['elapsedSeconds']}초)")
    print(f"  결과: results/{m['label']}-c{m['concurrency']}-metrics.json")


def print_tail(c, indent, bank_tail=None):
    """꼬리 지표 줄. 완료 지연 분포(충전별)와 종결 진행(전체 첫 접수 기준)을 이름으로 나눠 씁니다."""
    tail_latency, progress = c.get("completionLatencyTailMs"), c.get("terminalProgress")
    if tail_latency:
        print(f"{indent}완료 지연 분포(충전별 접수→완료) P99 {tail_latency['p99']}ms 최댓값 {tail_latency['max']}ms")
    if progress:
        points = progress["secondsFromFirstCreated"]
        print(f"{indent}종결 진행(전체 첫 접수 기준, N% 종결 시각) 50% {points['p50']}초 90% {points['p90']}초"
              f" 99% {points['p99']}초 100% {points['p100']}초 | 꼬리(99%→100%) {progress['tailSeconds']}초")
    tail = c.get("tailCharges") if bank_tail is None else bank_tail
    if tail:
        banks = ", ".join(f"{bank} {entry['count']}" for bank, entry in (tail.get("byBank") or {}).items())
        print(f"{indent}꼬리 구간 충전(전체 종결 순서 앞 99% 뒤) {tail['count']}건"
              + (f" (은행별 {banks})" if banks else "") + f" | 그중 복구 개입 {tail['recovered']}건")


# ---- 다시 계산 (--recompute) ----

RECOMPUTE_SELECTION = ("측정 회원(data/users.json) + window.from − 5초 ≤ created_at ≤ window.to"
                       " (측정 때 since = k6 시작 − 5초, window.to = 마지막 종결 시각)")
RECOMPUTE_SELECTION_IGNORE_MEMBERS = ("회원 조건 없음(--ignore-members) + window.from − 5초 ≤ created_at ≤ window.to."
                                      " 측정 뒤 data/users.json이 바뀌어 측정 회원으로 고를 수 없을 때만 씀")
INDIRECT_ONLY = "동일 충전 집합 미증명(통계값 일치로 간접 확인)"


def parse_iso(text):
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)


def recompute_checks(original, charges, recovered, members, member_limit):
    """다시 계산한 값과 결과 파일 값 (이름, 결과 파일, 다시 계산) 목록"""
    source = original["charges"]
    checks = [("accepted", source["accepted"], charges["accepted"]),
              ("firstCreatedAt", source.get("firstCreatedAt"), charges.get("firstCreatedAt")),
              ("lastTerminalAt", source.get("lastTerminalAt"), charges.get("lastTerminalAt"))]
    for key in ("p50", "p95"):
        checks.append((f"completionLatencyMs.{key}", source["completionLatencyMs"][key],
                       charges["completionLatencyMs"][key]))
    for bank in sorted(set(source.get("byBank") or {}) | set(charges.get("byBank") or {})):
        before, after = (source.get("byBank") or {}).get(bank, {}), (charges.get("byBank") or {}).get(bank, {})
        checks.append((f"byBank.{bank}.accepted", before.get("accepted"), after.get("accepted")))
        for key in ("p50", "p95"):
            checks.append((f"byBank.{bank}.completionLatencyMs.{key}",
                           (before.get("completionLatencyMs") or {}).get(key),
                           (after.get("completionLatencyMs") or {}).get(key)))
    fault = original.get("fault") or {}
    checks.append(("recoveredCharges", fault.get("recoveredCharges"), recovered["recoveredCharges"]))
    for key in ("p50", "p95"):
        checks.append((f"recoveredLatencyMs.{key}", (fault.get("recoveredLatencyMs") or {}).get(key),
                       recovered["recoveredLatencyMs"][key]))
    if member_limit is not None:
        # 측정 당시 회원 수는 결과 파일에 없어 지금 users.json 회원 수(같은 prepare.py 설정)와 비교
        checks.append(("memberCount(users.json 회원 수와 비교)", member_limit, members))
    return [{"name": name, "original": before, "recomputed": after, "same": before == after}
            for name, before, after in checks]


def recompute(label, ignore_members=False):
    """결과 파일의 window로 충전을 다시 골라 충전 지표(기존 키 + 꼬리 지표)를 다시 계산합니다."""
    pattern = re.compile(rf"^{re.escape(label)}-c[^-]+-metrics\.json$")
    found = sorted(path for path in RESULTS.glob(f"{label}-c*-metrics.json") if pattern.match(path.name))
    if len(found) != 1:
        raise SystemExit(f"results/{label}-c*-metrics.json이 {len(found)}개입니다(1개여야 함): {[p.name for p in found]}")
    source = found[0]
    original = json.loads(source.read_text(encoding="utf-8"))
    window = original["window"]
    since = parse_iso(window["from"]) - timedelta(seconds=5)
    until = parse_iso(window["to"])
    data = load_data()
    charges = charge_metrics(data, since, until, ignore_members)
    expected = original["charges"]["accepted"]
    if charges["accepted"] != expected:
        raise SystemExit(f"window로 조회한 충전 {charges['accepted']}건 ≠ 결과 파일 접수 {expected}건 ({source.name})."
                         " 다시 계산하지 않습니다.")
    measured = measured_sql(data, since, until, ignore_members)
    recovered = recovered_metrics(measured)
    members = int(psql(f"SELECT count(DISTINCT user_id) FROM ({measured}) m;")[0][0])
    checks = recompute_checks(original, charges, recovered, members,
                              len(data["users"]) if ignore_members else None)
    differ = [check for check in checks if not check["same"]]
    if differ:
        for check in differ:
            print(f"  다름: {check['name']} 결과 파일 {check['original']} ≠ 다시 계산 {check['recomputed']}")
        raise SystemExit(f"다시 계산한 값이 결과 파일과 다릅니다({len(differ)}개, {source.name}). 결과 파일을 쓰지 않습니다.")
    selection = {"since": iso(since), "until": iso(until), "ignoreMembers": ignore_members, "memberCount": members,
                 "rule": RECOMPUTE_SELECTION_IGNORE_MEMBERS if ignore_members else RECOMPUTE_SELECTION}
    if ignore_members:
        selection["verification"] = INDIRECT_ONLY
    result = {"source": source.name, "label": original["label"], "concurrency": original["concurrency"],
              "window": window, "selection": selection, "checks": checks, "charges": charges, "recovered": recovered}
    target = RESULTS / f"{source.stem}-recompute.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    latency = charges.get("completionLatencyMs") or {}
    print(f"[{result['label']}] 다시 계산 ({source.name}, 조회 {charges['accepted']}건 = 결과 파일 {expected}건,"
          f" 회원 {members}명, 검증 {len(checks)}개 값 일치)")
    if ignore_members:
        print(f"  회원 조건 없이 고름(--ignore-members): {INDIRECT_ONLY}")
    print(f"  완료 처리량 {charges.get('completionTps')}건/초 | 완료 지연 P50 {latency.get('p50')}ms P95 {latency.get('p95')}ms")
    print_tail(charges, "  ")
    for bank, entry in charges["byBank"].items():
        bank_latency = entry["completionLatencyMs"]
        print(f"  은행 {bank}: 접수 {entry['accepted']}건 | 완료 지연 P50 {bank_latency['p50']}ms P95 {bank_latency['p95']}ms")
        print_tail(entry, "    ", charges["tailCharges"]["byBank"].get(bank))
    print(f"  결과: results/{target.name}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "--recompute":
        options = sys.argv[3:]
        if len(sys.argv) < 3 or options not in ([], ["--ignore-members"]):
            raise SystemExit("사용: python measure.py --recompute <LABEL> [--ignore-members]")
        sys.exit(recompute(sys.argv[2], ignore_members=bool(options)))
    sys.exit(main())
