#!/usr/bin/env python3
"""격리된 User·Gateway 시험의 세션 준비, 관측, 반복 측정, 장애 측정을 수행합니다. Python 표준 라이브러리만 씁니다."""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import math
import os
from pathlib import Path
import platform
import re
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
RESULTS = HERE / "results"
SESSIONS = HERE / "data" / "sessions.json"
PROJECT = "travelpay-auth-load-test"
BASE_SERVICES = ("auth-gateway", "auth-user", "auth-eureka", "auth-postgres", "auth-redis")
# 업무 API 경로(성능 B·장애 C) 전용 구성입니다. compose의 business 프로필로만 뜹니다.
BUSINESS_SERVICES = ("auth-kafka", "auth-ledger", "auth-wallet")
APP_MODULES = {"auth-gateway": "gateway", "auth-user": "user-service", "auth-eureka": "eureka-server",
               "auth-ledger": "ledger-service", "auth-wallet": "wallet-service"}
# 업무 구성 컨테이너에도 exec할 수 있게 business 프로필을 켭니다. 이 도구는 compose up을 하지 않습니다.
COMPOSE = ["docker", "compose", "--project-directory", str(HERE), "-f", str(HERE / "compose.yaml"),
           "--profile", "business"]
SCENARIOS = {
    # 성능 A: 처음 기준선과 같은 조건이 되도록 업무 구성을 끈 상태에서만 측정합니다.
    "my-info": {"script": "my-info.js", "business": False, "service": "auth-user", "port": 18081,
                "uri": "/api/v1/users/me"},
    # 성능 B: Gateway → I01 → Wallet 경로. Wallet 코드는 바꾸지 않습니다.
    "wallet-me": {"script": "wallet-me.js", "business": True, "service": "auth-wallet", "port": 18082,
                  "uri": "/api/v1/wallets/me"},
}
# pause-db는 DB 서버가 멈춘 상황(SQL 취소 요청도 처리되지 않음)입니다. 이 시험의 모든 서비스가 같은 PostgreSQL을 쓰므로
# Wallet의 DB 조회도 함께 멈춥니다.
FAULT_TARGETS = {"pause": "auth-user", "pause-db": "auth-postgres", "stop": "auth-user",
                 "lock-users": "auth-postgres", "lock-credentials": "auth-postgres"}
# C2a는 인증 조회(회원 테이블만 읽음)를, C2b는 인증을 통과한 뒤의 마이페이지 조회(로그인 정보 테이블)만 막습니다.
LOCK_TABLES = {"lock-users": "user_service.users", "lock-credentials": "user_service.user_credentials"}


def command(args, timeout=60):
    return subprocess.check_output(args, cwd=ROOT, text=True, stderr=subprocess.PIPE, timeout=timeout).strip()


def compose(*args, timeout=60):
    return command(COMPOSE + list(args), timeout)


def container(service):
    return f"{PROJECT}-{service}-1"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def metric_total(text, name, labels=None):
    total = 0.0
    for line in text.splitlines():
        match = re.fullmatch(r"([^{}\s]+)(?:\{(.*)\})?\s+(\S+)(?:\s+\S+)?", line)
        if not match or match[1] != name:
            continue
        actual = dict(re.findall(r'(\w+)="((?:\\.|[^"\\])*)"', match[2] or ""))
        if all(actual.get(key) == value for key, value in (labels or {}).items()):
            total += float(match[3])
    return total


def sql_delta(before, after):
    previous = {row["queryid"]: row for row in before}
    current = {row["queryid"] for row in after}
    if previous.keys() - current:
        raise ValueError("측정 중 SQL 통계 초기화 또는 항목 유실을 발견했습니다.")
    result = []
    for row in after:
        old = previous.get(row["queryid"], {"calls": 0, "total_exec_time": 0})
        calls = row["calls"] - old["calls"]
        elapsed = row["total_exec_time"] - old["total_exec_time"]
        if calls < 0 or elapsed < -0.001:
            raise ValueError("측정 중 SQL 통계 초기화를 발견했습니다.")
        if calls:
            result.append({"query": row["query"], "calls": calls,
                           "total_exec_time_ms": elapsed})
    return result


def redis_delta(before, after):
    if before.keys() - after.keys() or any(after.get(key, 0) < value for key, value in before.items()):
        raise ValueError("측정 중 Redis 통계 초기화를 발견했습니다.")
    # INFO/AUTH는 측정 도구, PING은 healthcheck입니다. HELLO/CLIENT는 연결 관리입니다.
    auxiliary = {"info", "auth", "ping", "hello", "client|setinfo", "client|setname"}
    return {key: value - before.get(key, 0) for key, value in after.items()
            if key not in auxiliary and value > before.get(key, 0)}


CPU_FIELDS = ("usage_usec", "nr_periods", "nr_throttled", "throttled_usec")


def parse_cpu_stat(text):
    """cgroup v2 cpu.stat에서 사용 시간과 CPU 상한에 따른 제한 횟수·시간을 읽습니다."""
    values = dict(line.split() for line in text.splitlines() if len(line.split()) == 2)
    missing = [field for field in CPU_FIELDS if field not in values]
    if missing:
        raise ValueError(f"cpu.stat에 {missing}가 없습니다. cgroup v2와 CPU 상한이 있는 컨테이너인지 확인하세요.")
    return {field: int(values[field]) for field in CPU_FIELDS}


def cpu_delta(before, after, successes, seconds):
    """컨테이너별 요청당 CPU 시간(ms)·평균 사용 코어 수와, 상한 때문에 멈춘 주기 비율·시간을 계산합니다."""
    if before.keys() != after.keys():
        raise ValueError("측정 전후 CPU 표본의 컨테이너 목록이 다릅니다.")
    result = {}
    for service, old in before.items():
        new = after[service]
        diff = {field: new[field] - old[field] for field in CPU_FIELDS}
        if any(value < 0 for value in diff.values()):
            raise ValueError(f"{service}의 CPU 통계가 줄었습니다. 측정 중 재시작을 확인하세요.")
        result[service] = {
            "cpuMsPerSuccess": diff["usage_usec"] / 1000 / successes,
            "averageCores": diff["usage_usec"] / 1_000_000 / seconds,
            "throttledPeriodRatio": diff["nr_throttled"] / diff["nr_periods"] if diff["nr_periods"] else 0.0,
            "throttledMs": diff["throttled_usec"] / 1000,
        }
    return result


def validate_summary(summary):
    errors = []
    if summary.get("requests", 0) <= 0:
        errors.append("측정 요청이 없습니다.")
    if summary.get("successes") != summary.get("requests") or summary.get("failures") != 0:
        errors.append("인증 오류·서버 오류 또는 다른 회원의 응답이 있습니다.")
    return errors


def require_current_jar(host_hash, mounted_hash, modified_at, created_at):
    if host_hash != mounted_hash:
        raise RuntimeError("호스트와 컨테이너의 JAR가 다릅니다. 앱 컨테이너를 재생성하세요.")
    if created_at < modified_at:
        raise RuntimeError("컨테이너 생성 뒤 JAR가 바뀌었습니다. --force-recreate로 앱을 재생성하세요.")


def require_stack(running, business):
    """성능 A는 업무 구성 없이, 성능 B·장애 C는 업무 구성을 켠 채로 측정해야 같은 조건끼리 비교됩니다."""
    missing = [service for service in BASE_SERVICES if service not in running]
    if missing:
        raise RuntimeError(f"기본 구성이 실행 중이 아닙니다: {missing}")
    business_running = [service for service in BUSINESS_SERVICES if service in running]
    if business and len(business_running) != len(BUSINESS_SERVICES):
        raise RuntimeError("업무 구성이 필요합니다. README의 --profile business 실행 절차를 따르세요.")
    if not business and business_running:
        raise RuntimeError(f"성능 A는 업무 구성을 끈 상태에서 측정합니다(기준선과 같은 조건). 실행 중: {business_running}")


def parse_k6_requests(lines):
    """k6 CSV(unix_milli)에서 요청별 시작 시각·종료 시각·이름·상태·지연을 꺼냅니다. 시작 = 종료 − 지연."""
    rows = []
    for row in csv.DictReader(lines):
        if row["metric_name"] != "http_req_duration":
            continue
        ended = int(row["timestamp"])
        duration = float(row["metric_value"])
        # 전송 오류(연결 끊김·응답 도중 중단 등)는 상태 코드와 별개인 k6 오류 코드로 남습니다.
        rows.append({"start": ended - duration, "end": ended, "name": row["name"],
                     "status": row["status"], "errorCode": row.get("error_code") or "", "durationMs": duration})
    return rows


def percentile(values, ratio):
    # 최근접 순위 방식: 값의 ratio 비율이 이 값 이하가 되는 가장 작은 표본입니다.
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[max(0, math.ceil(ratio * len(ordered)) - 1)]


def outcome(row):
    """상태 코드별로 세되, 전송 오류는 같은 상태 코드여도 따로 셉니다. 성공은 오류 없는 200뿐입니다.

    k6는 HTTP 4xx·5xx 응답에도 1000 + 상태 코드(예: 503 → 1503)를 오류 코드로 붙이므로, 그 짝은 일반 HTTP 응답입니다.
    짝이 맞지 않는 오류 코드(연결 실패·응답 도중 끊김 등, 상태 0 포함)만 전송 오류로 셉니다.
    """
    code, status = row.get("errorCode") or "", row["status"]
    if not code or (status.isdigit() and int(status) >= 400 and code == str(1000 + int(status))):
        return status
    return f"{status}/전송오류{code}"


def succeeded(row):
    return outcome(row) == "200"


def analyze_fault(requests, fault_start, fault_end, stable_seconds=5):
    """요청 시작 시각으로 장애 전·중·후를 나누고, 회복 시각(장애 해제 뒤 stable_seconds 동안 모두 200이 된 첫 초)을 구합니다."""
    report = {}
    for name in sorted({row["name"] for row in requests}):
        rows = [row for row in requests if row["name"] == name]
        def window(predicate):
            part = [row for row in rows if predicate(row["start"])]
            statuses = {}
            for row in part:
                statuses[outcome(row)] = statuses.get(outcome(row), 0) + 1
            durations = [row["durationMs"] for row in part]
            return {"requests": len(part), "successes": statuses.get("200", 0), "statusCounts": statuses,
                    "p95Ms": percentile(durations, 0.95), "maxMs": max(durations) if durations else None}
        failures = sorted(row["end"] for row in rows if not succeeded(row) and row["start"] >= fault_start)
        seconds = {}
        for row in rows:
            if row["start"] >= fault_end:
                bucket = int((row["start"] - fault_end) // 1000)
                ok, total = seconds.get(bucket, (0, 0))
                seconds[bucket] = (ok + succeeded(row), total + 1)
        recovery = None
        for second in sorted(seconds):
            span = [seconds.get(second + offset) for offset in range(stable_seconds)]
            if all(item and item[0] == item[1] for item in span):
                recovery = second
                break
        report[name] = {
            "before": window(lambda start: start < fault_start),
            "during": window(lambda start: fault_start <= start < fault_end),
            "after": window(lambda start: start >= fault_end),
            "firstFailureAfterFaultMs": failures[0] - fault_start if failures else None,
            "recoverySecondsAfterRelease": recovery,
        }
    return report


def fault_timeline(requests, fault_start, step_seconds=5):
    """장애 시작 기준 step_seconds 간격의 요청 수·성공 수·p95. 문서의 시간 흐름 표에 씁니다."""
    buckets = {}
    for row in requests:
        offset = int((row["start"] - fault_start) // (step_seconds * 1000)) * step_seconds
        bucket = buckets.setdefault((row["name"], offset), {"requests": 0, "successes": 0, "durations": []})
        bucket["requests"] += 1
        bucket["successes"] += succeeded(row)
        bucket["durations"].append(row["durationMs"])
    return [{"name": name, "offsetSeconds": offset, "requests": item["requests"], "successes": item["successes"],
             "p95Ms": percentile(item["durations"], 0.95)}
            for (name, offset), item in sorted(buckets.items())]


def request(opener, path, body=None, csrf=None, expected=(200,), method=None):
    headers = {"Accept": "application/json"}
    if csrf:
        headers[csrf["headerName"]] = csrf["token"]
    encoded = None if body is None else json.dumps(body).encode()
    if encoded is not None:
        headers["Content-Type"] = "application/json"
    elif method == "POST":
        encoded = b""  # 본문 없는 POST도 길이 0을 명시합니다.
    req = urllib.request.Request("http://127.0.0.1:28080" + path, encoded, headers, method=method)
    try:
        with opener.open(req, timeout=10) as response:
            if response.status == 204 and 204 in expected:
                return None  # 비밀번호 변경처럼 본문 없이 성공하는 API
            payload = json.load(response)
            if response.status not in expected or payload.get("success") is not True:
                raise RuntimeError(f"준비 요청 실패: {path} HTTP {response.status}")
            return payload["data"]
    except urllib.error.HTTPError as error:
        # 응답 본문·쿠키·이메일·암호는 로그에 출력하지 않습니다.
        raise RuntimeError(f"준비 요청 실패: {path} HTTP {error.code}") from None


def require_sessions(needs_wallet):
    if not SESSIONS.exists():
        raise RuntimeError("시험 세션이 없습니다. prepare를 먼저 실행하세요.")
    users = json.loads(SESSIONS.read_text(encoding="utf-8"))["users"]
    if needs_wallet and any("walletId" not in user for user in users):
        raise RuntimeError("지갑이 없는 시험 회원이 있습니다. 업무 구성을 띄운 뒤 prepare --wallets를 실행하세요.")


def running_services():
    return set(compose("ps", "--status", "running", "--services").split())


def prepare(count, wallets):
    os.umask(0o077)
    if wallets:
        require_stack(running_services(), business=True)
    # 컨테이너 liveness와 Eureka 경로 준비는 다릅니다. 실제 공개 API가 열렸는지 제한 시간 안에 확인합니다.
    deadline = time.monotonic() + 90
    while True:
        try:
            request(urllib.request.build_opener(), "/api/v1/terms")
            break
        except (RuntimeError, urllib.error.URLError):
            if time.monotonic() >= deadline:
                raise RuntimeError("90초 안에 Gateway→User 경로가 준비되지 않았습니다.") from None
            time.sleep(1)
    sessions = []
    for index in range(1, count + 1):
        jar = http.cookiejar.CookieJar()
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        csrf = request(opener, "/api/v1/auth/csrf")
        terms = request(opener, "/api/v1/terms")["terms"]
        # 예약 도메인과 합성 번호입니다. 격리된 시험 DB에서만 생성합니다.
        email = f"auth-load-{index:04d}@example.test"
        password = "Auth-Load-Test-2026!"
        if not existing_member(email):
            request(opener, "/api/v1/users", {
                "loginEmail": email, "password": password, "realName": "부하시험",
                "phone": f"010-9900-{index:04d}",
                "consents": [{"termsCode": term["termsCode"], "termsVersion": term["termsVersion"]}
                             for term in terms if term["isRequired"]],
            }, csrf, expected=(201,))
        login = request(opener, "/api/v1/auth/login", {"loginEmail": email, "password": password}, csrf)
        # 로그인 시 교체된 세션으로 실제 Gateway 경로를 통과하는지도 확인합니다.
        profile = request(opener, "/api/v1/users/me")
        if profile["userId"] != login["userId"] or profile["loginEmail"] != email:
            raise RuntimeError("로그인한 회원과 프로필 응답이 다릅니다.")
        session = {"userId": login["userId"], "email": email}
        if wallets:
            # 로그인하면 CSRF 토큰이 바뀌므로 다시 받습니다. 이미 ACTIVE면 200, 이번에 개설하면 201입니다.
            fresh = request(opener, "/api/v1/auth/csrf")
            opened = request(opener, "/api/v1/wallets", csrf=fresh, expected=(200, 201), method="POST")
            mine = request(opener, "/api/v1/wallets/me")
            if mine["walletId"] != opened["walletId"] or mine["status"] != "ACTIVE":
                raise RuntimeError("개설한 지갑과 조회한 지갑이 다릅니다.")
            session["walletId"] = mine["walletId"]
        cookies = [cookie.value for cookie in jar if cookie.name == "SESSION"]
        if len(cookies) != 1:
            raise RuntimeError("로그인 세션 쿠키가 하나가 아닙니다.")
        session["session"] = cookies[0]
        sessions.append(session)
    write_json(SESSIONS, {"preparedAt": datetime.now(timezone.utc).isoformat(), "wallets": wallets,
                          "users": sessions})
    SESSIONS.chmod(0o600)
    print(f"서로 다른 회원·로그인 세션 {len(sessions)}개 준비 완료{' (지갑 포함)' if wallets else ''}. "
          "쿠키는 Git 제외 파일에만 저장했습니다.")


def db(query):
    return compose("exec", "-T", "auth-postgres", "psql", "-U", "auth_test_admin", "-d", "travelpay",
                   "-At", "-v", "ON_ERROR_STOP=1", "-c", query)


def existing_member(email):
    # 이메일은 이 파일에서 만든 숫자 접미사뿐이며 외부 입력을 SQL에 보간하지 않습니다.
    return db("SELECT count(*) FROM user_service.user_credentials WHERE login_email='" + email + "'") == "1"


def app_metrics(service, port):
    return compose("exec", "-T", service, "curl", "-fsS", f"http://localhost:{port}/actuator/prometheus")


def cpu_stats(services):
    return {service: parse_cpu_stat(command(["docker", "exec", container(service), "cat", "/sys/fs/cgroup/cpu.stat"]))
            for service in services}


def sql_stats(role):
    return json.loads(db(f"""SELECT coalesce(json_agg(t), '[]') FROM (
        SELECT queryid::text, query, calls, total_exec_time FROM public.pg_stat_statements
        WHERE userid=(SELECT oid FROM pg_roles WHERE rolname='{role}')
    ) t"""))


def snapshot(scenario, services):
    user = app_metrics("auth-user", 18081)
    target = user if scenario["service"] == "auth-user" else app_metrics(scenario["service"], scenario["port"])
    def http_count(text, uri, status=None):
        labels = {"uri": uri}
        if status:
            labels["status"] = status
        return metric_total(text, "http_server_requests_seconds_count", labels)
    observations = {
        "targetRequests": http_count(target, scenario["uri"]),
        "targetSuccesses": http_count(target, scenario["uri"], "200"),
        "i01Requests": http_count(user, "/internal/v1/auth/session"),
        "i01Successes": http_count(user, "/internal/v1/auth/session", "200"),
    }
    for key, metric in {
        "usageSeconds": "hikaricp_connections_usage_seconds_sum",
        "usageCount": "hikaricp_connections_usage_seconds_count",
        "acquireSeconds": "hikaricp_connections_acquire_seconds_sum",
        "acquireCount": "hikaricp_connections_acquire_seconds_count",
        "connectionTimeouts": "hikaricp_connections_timeout_total",
        "gcPauseSeconds": "jvm_gc_pause_seconds_sum",
    }.items():
        observations[key] = metric_total(user, metric)
    roles = ["user_app"] + (["wallet_app"] if scenario["business"] else [])
    redis = compose("exec", "-T", "auth-redis", "redis-cli", "INFO", "commandstats")
    commands = {name: int(calls) for name, calls in re.findall(r"cmdstat_([^:]+):calls=(\d+)", redis)}
    return {"at": datetime.now(timezone.utc).isoformat(), "observations": observations,
            "sql": {role: sql_stats(role) for role in roles}, "redisCommands": commands,
            "cpu": cpu_stats(services)}


def environment(services):
    images = []
    for service in services:
        container_id = compose("ps", "-q", service)
        # 전체 inspect에는 비밀값이 포함될 수 있으므로 필요한 필드만 조회합니다.
        images.append(json.loads(command(["docker", "inspect", "--format",
            '{"service":"' + service + '","imageId":{{json .Image}},"memoryBytes":{{.HostConfig.Memory}},'
            '"nanoCpus":{{.HostConfig.NanoCpus}},"restartCount":{{.RestartCount}},'
            '"oomKilled":{{.State.OOMKilled}},"createdAt":{{json .Created}},"startedAt":{{json .State.StartedAt}}}',
            container_id])))
    jars = {}
    for container_name, module in APP_MODULES.items():
        if container_name not in services:
            continue
        jar = ROOT / module / "build" / "libs" / f"{module}-0.0.1-SNAPSHOT.jar"
        host_hash = hashlib.sha256(jar.read_bytes()).hexdigest()
        mounted_hash = compose("exec", "-T", container_name, "sha256sum", "/app.jar").split()[0]
        item = next(entry for entry in images if entry["service"] == container_name)
        created_at = datetime.fromisoformat(item["createdAt"].replace("Z", "+00:00")).timestamp()
        require_current_jar(host_hash, mounted_hash, jar.stat().st_mtime, created_at)
        jars[module] = host_hash
    return {
        "sourceCommit": command(["git", "rev-parse", "HEAD"]),
        "sourceStatus": command(["git", "status", "--short"]),
        "platform": platform.platform(), "cpuCount": os.cpu_count(),
        "loadAverage": os.getloadavg() if hasattr(os, "getloadavg") else None,
        "memory": Path("/proc/meminfo").read_text(encoding="utf-8") if Path("/proc/meminfo").exists() else "OS에서 별도 확인",
        "dockerVersion": command(["docker", "version", "--format", "{{.Server.Version}}"]),
        "composeVersion": command(["docker", "compose", "version", "--short"]),
        "runtimeJava": compose("exec", "-T", "auth-user", "java", "--version"),
        "services": list(services), "containers": images, "jarSha256": jars,
        "fixtureCount": len(json.loads(SESSIONS.read_text(encoding="utf-8"))["users"]),
        "databaseRows": json.loads(db("SELECT json_build_object('users', (SELECT count(*) FROM user_service.users),"
                                     "'credentials', (SELECT count(*) FROM user_service.user_credentials))")),
        "configSha256": hashlib.sha256((HERE / "compose.yaml").read_bytes()).hexdigest(),
        "k6ScriptSha256": {name: hashlib.sha256((HERE / name).read_bytes()).hexdigest()
                           for name in ("common.js", "my-info.js", "wallet-me.js", "fault.js")},
    }


def sample_resources(stop, samples, names):
    while not stop.is_set():
        try:
            raw = command(["docker", "stats", "--no-stream", "--format", "{{json .}}"] + names, 15)
            samples.append({"at": datetime.now(timezone.utc).isoformat(),
                            "containers": [json.loads(line) for line in raw.splitlines()]})
        except (subprocess.SubprocessError, ValueError) as error:
            samples.append({"at": datetime.now(timezone.utc).isoformat(), "error": type(error).__name__})
        stop.wait(3)


K6_NAME = "travelpay-auth-measure-k6"


def k6_command(script, directory, summary_name, env, extra=()):
    args = COMPOSE + ["run", "--rm", "--no-deps", "-T", "--name", K6_NAME,
                      "-e", f"SUMMARY_PATH=/results/{directory.name}/{summary_name}"]
    for key, value in env.items():
        args += ["-e", f"{key}={value}"]
    return args + ["k6", "run", *extra, f"/scripts/{script}"]


def k6_environment():
    return dict(os.environ, LOAD_TEST_UID=str(getattr(os, "getuid", lambda: 1000)()),
                LOAD_TEST_GID=str(getattr(os, "getgid", lambda: 1000)()))


def k6_run(directory, name, scenario, services, vus, duration, resources=False):
    path = directory / f"{name}.json"
    args = k6_command(scenario["script"], directory, path.name, {"VUS": vus, "DURATION": duration})
    stop, samples = threading.Event(), []
    started = datetime.now(timezone.utc).isoformat()
    with (directory / f"{name}.log").open("w") as output:
        process = subprocess.Popen(args, cwd=ROOT, env=k6_environment(), stdout=output, stderr=subprocess.STDOUT)
        names = [container(service) for service in services] + [K6_NAME]
        sampler = threading.Thread(target=sample_resources, args=(stop, samples, names), daemon=True)
        if resources:
            sampler.start()
        try:
            code = process.wait(timeout=int(re.fullmatch(r"(\d+)s", duration)[1]) + 90)
        except BaseException:
            subprocess.run(["docker", "stop", "-t", "1", K6_NAME], capture_output=True, timeout=15)
            process.wait(timeout=20)
            raise
        finally:
            stop.set()
            if resources:
                sampler.join(timeout=20)
    ended = datetime.now(timezone.utc).isoformat()
    if resources:
        write_json(directory / f"{name}-resources.json", samples)
    if not path.exists():
        raise RuntimeError(f"k6 결과가 없습니다. {directory.name}/{name}.log를 확인하세요.")
    summary = json.loads(path.read_text(encoding="utf-8"))
    summary.update({"startedAt": started, "endedAt": ended, "exitCode": code})
    write_json(path, summary)
    errors = validate_summary(summary)
    if code or errors:
        raise RuntimeError(f"유효하지 않은 측정: {name}, 종료 코드 {code}, {errors}. 원본 결과를 보존했습니다.")
    return summary


def selects(queries):
    return sum(row["calls"] for row in queries if re.match(r"\s*select\b", row["query"], re.I))


def new_result_directory(label):
    RESULTS.mkdir(parents=True, exist_ok=True)
    directory = RESULTS / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + label)
    directory.mkdir()
    return directory


def measure(args):
    scenario = SCENARIOS[args.scenario]
    require_stack(running_services(), scenario["business"])
    require_sessions(scenario["business"])
    services = BASE_SERVICES + (BUSINESS_SERVICES if scenario["business"] else ())
    directory = new_result_directory(args.label)
    initial_environment = dict(environment(services), workload=vars(args))
    write_json(directory / "environment.json", initial_environment)
    if args.prewarm:
        # 새로 띄운 JVM의 JIT 컴파일 비용이 첫 회차에 섞이지 않게, 가장 높은 동시성으로 한 번 돌리고 버립니다.
        # 개선 전후 모두 앱을 새로 띄운 뒤 같은 값으로 실행해야 비교가 공정합니다.
        print(f"{args.scenario}: 사전 예열 {args.prewarm}s(기록하지 않음)", flush=True)
        k6_run(directory, f"prewarm-vu{max(args.vus)}", scenario, services, max(args.vus), f"{args.prewarm}s")
    runs = []
    for repeat in range(1, args.repeats + 1):
        for vus in args.vus:
            name = f"vu{vus}-r{repeat}"
            print(f"{args.scenario} {name}: 예열 {args.warmup}s → 측정 {args.seconds}s", flush=True)
            k6_run(directory, name + "-warmup", scenario, services, vus, f"{args.warmup}s")
            before = snapshot(scenario, services)
            write_json(directory / f"{name}-before.json", before)
            try:
                summary = k6_run(directory, name, scenario, services, vus, f"{args.seconds}s", resources=True)
            finally:
                after = snapshot(scenario, services)
                write_json(directory / f"{name}-after.json", after)
            delta = {key: after["observations"][key] - before["observations"][key]
                     for key in before["observations"]}
            if delta["targetSuccesses"] != summary["successes"] or delta["targetRequests"] != summary["requests"]:
                raise RuntimeError("k6·서버 지표의 요청 수가 다릅니다. 다른 트래픽·재시작·누락을 확인하세요.")
            queries = {role: sql_delta(before["sql"][role], after["sql"][role]) for role in before["sql"]}
            redis_commands = redis_delta(before["redisCommands"], after["redisCommands"])
            successes = summary["successes"]
            run = dict(summary, scenario=args.scenario, observations=delta, sql=queries,
                       selectsPerSuccess={role: selects(rows) / successes for role, rows in queries.items()},
                       redisCommands=redis_commands,
                       redisCommandsPerSuccess=sum(redis_commands.values()) / successes,
                       i01PerSuccess=delta["i01Requests"] / successes,
                       cpu=cpu_delta(before["cpu"], after["cpu"], successes, args.seconds))
            write_json(directory / f"{name}-analysis.json", run)
            runs.append(run)
            print(f"  정상 {successes}건, {summary['successfulRps']:.1f} RPS, p95 {summary['latencyMs']['p(95)']:.1f}ms, "
                  f"User CPU {run['cpu']['auth-user']['cpuMsPerSuccess']:.3f}ms/요청, "
                  f"Gateway CPU {run['cpu']['auth-gateway']['cpuMsPerSuccess']:.3f}ms/요청", flush=True)
    write_json(directory / "runs.json", runs)
    final_environment = environment(services)
    write_json(directory / "environment-after.json", final_environment)
    for key in ("jarSha256", "configSha256", "k6ScriptSha256", "databaseRows"):
        if final_environment[key] != initial_environment[key]:
            raise RuntimeError(f"측정 중 조건이 바뀌었습니다: {key}. 비교 결과로 사용하지 마세요.")
    if any(item["oomKilled"] or item["restartCount"] or item["startedAt"] != before_item["startedAt"]
           for item, before_item in zip(final_environment["containers"], initial_environment["containers"])):
        raise RuntimeError("시험 컨테이너의 OOM·재시작을 발견했습니다. 결과를 정상 측정으로 사용하지 마세요.")
    write_summary(directory, args, runs, services)
    print(f"결과 저장: {directory.relative_to(ROOT)}", flush=True)


def write_summary(directory, args, runs, services):
    med = statistics.median
    cpu_services = [service for service in ("auth-gateway", "auth-user", "auth-wallet") if service in services]
    lines = ["# User·Gateway 측정 결과", "", f"측정: {directory.name} · 시나리오 {args.scenario}", "",
             "| VU | 반복 | 정상 RPS 중앙값 | RPS 범위 | p50(ms) | p95(ms) | p99(ms) | 오류 | I01/요청 | SELECT/요청 | "
             + " | ".join(f"{service} CPU ms/요청 · 제한 주기" for service in cpu_services) + " |",
             "|" + "---|" * (10 + len(cpu_services))]
    for vus in args.vus:
        group = [run for run in runs if run["vus"] == vus]
        rps = [run["successfulRps"] for run in group]
        latency = [med([run["latencyMs"][key] for run in group]) for key in ("med", "p(95)", "p(99)")]
        select_text = ", ".join(f"{role} {med([run['selectsPerSuccess'][role] for run in group]):.2f}"
                                for role in group[0]["selectsPerSuccess"])
        cpu_text = " | ".join(
            f"{med([run['cpu'][service]['cpuMsPerSuccess'] for run in group]):.3f} · "
            f"{med([run['cpu'][service]['throttledPeriodRatio'] for run in group]) * 100:.0f}%"
            for service in cpu_services)
        lines.append(f"| {vus} | {len(group)} | {med(rps):.2f} | {min(rps):.1f}~{max(rps):.1f} | "
                     + " | ".join(f"{value:.2f}" for value in latency)
                     + f" | {sum(run['failures'] for run in group)} | {med([r['i01PerSuccess'] for r in group]):.2f}"
                     + f" | {select_text} | {cpu_text} |")
    lines += ["", "p50·p95·p99는 각 회차의 백분위수를 구한 뒤 중앙값으로 표시했습니다. 전체 표본을 합친 백분위수가 아닙니다.",
              "CPU ms/요청은 cgroup cpu.stat 사용 시간 차이를 정상 건수로 나눈 값이고, 제한 주기는 CPU 상한 때문에 멈춘 주기의 비율입니다.",
              "현재 구성·데이터·동시성에서의 비교 기준이며 운영 처리량이나 최대 수용 인원은 의미하지 않습니다."]
    (directory / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def inject(kind, seconds, events):
    """장애를 넣고 seconds 뒤 풉니다. 시각(ms)은 events에 기록합니다. 대상은 k6가 아니라 이 시험의 컨테이너뿐입니다."""
    now = lambda: time.time() * 1000
    target = container(FAULT_TARGETS[kind])
    if kind in ("pause", "pause-db"):
        command(["docker", "pause", target])
        events["faultStart"] = now()
        time.sleep(seconds)
        events["releaseRequested"] = now()
        command(["docker", "unpause", target])
        events["faultEnd"] = now()
    elif kind == "stop":
        events["faultStart"] = now()
        command(["docker", "stop", "-t", "30", target], timeout=60)
        events["stopped"] = now()
        time.sleep(seconds)
        events["releaseRequested"] = now()
        command(["docker", "start", target])
        events["faultEnd"] = now()
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            state = command(["docker", "inspect", "--format", "{{.State.Health.Status}}", target])
            if state == "healthy":
                events["healthy"] = now()
                break
            time.sleep(1)
    else:
        # 마이그레이션의 테이블 잠금을 재현합니다. ACCESS EXCLUSIVE는 일반 SELECT도 막습니다(PostgreSQL 잠금 문서).
        sql = f"BEGIN; LOCK TABLE {LOCK_TABLES[kind]} IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep({seconds}); COMMIT;"
        events["faultStart"] = now()
        compose("exec", "-T", "auth-postgres", "psql", "-U", "auth_test_admin", "-d", "travelpay",
                "-v", "ON_ERROR_STOP=1", "-c", sql, timeout=seconds + 30)
        events["faultEnd"] = now()


def fault(args):
    require_stack(running_services(), business=True)
    require_sessions(True)
    services = BASE_SERVICES + BUSINESS_SERVICES
    directory = new_result_directory(f"fault-{args.type}-{args.label}")
    write_json(directory / "environment.json", dict(environment(services), workload=vars(args)))
    before = app_metrics("auth-user", 18081)
    args_k6 = k6_command("fault.js", directory, "k6-summary.json",
                         {"RATE": args.rate, "DURATION": f"{args.seconds}s", "K6_CSV_TIME_FORMAT": "unix_milli"},
                         extra=("--out", f"csv=/results/{directory.name}/requests.csv"))
    events = {"k6Launched": time.time() * 1000}
    with (directory / "k6.log").open("w") as output:
        process = subprocess.Popen(args_k6, cwd=ROOT, env=k6_environment(), stdout=output, stderr=subprocess.STDOUT)
        try:
            time.sleep(args.at)
            inject(args.type, args.duration, events)
            code = process.wait(timeout=args.seconds + 120)
        except BaseException:
            subprocess.run(["docker", "stop", "-t", "1", K6_NAME], capture_output=True, timeout=15)
            # 장애를 넣은 채 끝나지 않게 되돌립니다.
            target = container(FAULT_TARGETS[args.type])
            subprocess.run(["docker", "unpause", target], capture_output=True, timeout=15)
            subprocess.run(["docker", "start", target], capture_output=True, timeout=30)
            raise
    after = app_metrics("auth-user", 18081)
    with (directory / "requests.csv").open(encoding="utf-8") as lines:
        requests = parse_k6_requests(lines)
    summary = json.loads((directory / "k6-summary.json").read_text(encoding="utf-8"))
    report = analyze_fault(requests, events["faultStart"], events["faultEnd"])
    i01 = {"uri": "/internal/v1/auth/session"}
    def counter_delta(name, labels=None):
        # stop 시험처럼 User가 다시 시작되면 카운터가 0부터 다시 세어 전후 차이가 의미 없습니다.
        delta = metric_total(after, name, labels) - metric_total(before, name, labels)
        return delta if delta >= 0 else None
    observations = {
        "hikariConnectionTimeouts": counter_delta("hikaricp_connections_timeout_total"),
        "i01Requests": counter_delta("http_server_requests_seconds_count", i01),
        "note": "User 재시작으로 카운터가 초기화된 값은 null입니다.",
    }
    result = {"type": args.type, "rate": args.rate, "events": events, "k6": summary, "k6ExitCode": code,
              "userObservations": observations, "endpoints": report,
              "timeline": fault_timeline(requests, events["faultStart"])}
    write_json(directory / "fault-analysis.json", result)
    lines = [f"# 장애 {args.type} 측정", "", f"측정: {directory.name} · 엔드포인트별 도착률 {args.rate}/초 · "
             f"장애 {args.duration}초 · 손실 요청 {summary['droppedIterations']}", "",
             "| API | 장애 전 성공률 | 장애 중 요청 | 장애 중 상태 | 장애 중 p95(ms) | 장애 중 최대(ms) | 첫 실패(장애 후 ms) | 해제 뒤 회복(초) |",
             "|---|---|---|---|---|---|---|---|"]
    for name, item in report.items():
        before_window = item["before"]
        rate = before_window["successes"] / before_window["requests"] * 100 if before_window["requests"] else 0
        number = lambda value: "-" if value is None else f"{value:,.0f}"
        statuses = ", ".join(f"{key} {count}" for key, count in sorted(item["during"]["statusCounts"].items()))
        lines.append(f"| {name} | {rate:.1f}% | {item['during']['requests']} | {statuses} | "
                     f"{number(item['during']['p95Ms'])} | {number(item['during']['maxMs'])} | "
                     f"{number(item['firstFailureAfterFaultMs'])} | {item['recoverySecondsAfterRelease']} |")
    (directory / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    invalid = [name for name, item in report.items() if item["before"]["successes"] != item["before"]["requests"]]
    if code or invalid:
        # 장애 시험 스크립트에는 임계값이 없으므로 0이 아닌 종료 코드는 실행 오류입니다.
        raise RuntimeError(f"유효하지 않은 장애 측정: 종료 코드 {code}, 장애 전부터 실패한 API {invalid}. 원본을 보존했습니다.")
    print(f"결과 저장: {directory.relative_to(ROOT)}", flush=True)


PROBE_PASSWORDS = ("Auth-Load-Probe-2026!", "Auth-Load-Probe-2026?")


def timed_request(opener, path, body=None, csrf=None, expected=(200,), method=None):
    started = time.perf_counter()
    data = request(opener, path, body, csrf, expected, method)
    return (time.perf_counter() - started) * 1000, data


def summarize_ms(values):
    return {"samples": len(values), "p50": percentile(values, 0.5), "p95": percentile(values, 0.95),
            "max": max(values) if values else None}


def ensure_member(email, password, phone):
    if existing_member(email):
        return
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = request(opener, "/api/v1/auth/csrf")
    terms = request(opener, "/api/v1/terms")["terms"]
    request(opener, "/api/v1/users", {
        "loginEmail": email, "password": password, "realName": "부하시험", "phone": phone,
        "consents": [{"termsCode": term["termsCode"], "termsVersion": term["termsVersion"]}
                     for term in terms if term["isRequired"]],
    }, csrf, expected=(201,))


def logged_in(email, password):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = request(opener, "/api/v1/auth/csrf")
    request(opener, "/api/v1/auth/login", {"loginEmail": email, "password": password}, csrf)
    return opener


def slow_ops(args):
    """User 라우트 응답 제한 값의 근거: 비싼 정상 처리의 가장 느린 시간을 쉬는 상태와 User CPU가 꽉 찬 상태에서 잽니다.

    측정 요청은 수동 확인용 공개 포트(28080)로 Gateway를 거칩니다. 포트 전달 시간이 더해지므로 제한 값을 정할 때는 보수적인 쪽입니다.
    시험 DB 전용 계정만 만들고, 관리자 계정은 격리된 시험 DB에서만 MASTER로 바꿉니다.
    """
    running = running_services()
    require_sessions(False)
    services = BASE_SERVICES + tuple(service for service in BUSINESS_SERVICES if service in running)
    directory = new_result_directory(f"slow-ops-{args.label}")
    write_json(directory / "environment.json", dict(environment(services), workload=vars(args)))
    probe, master = "auth-load-probe@example.test", "auth-load-master@example.test"
    ensure_member(probe, PROBE_PASSWORDS[0], "010-9800-0001")
    ensure_member(master, PROBE_PASSWORDS[0], "010-9800-0002")
    db("UPDATE user_service.users SET role='MASTER' WHERE id=(SELECT user_id FROM user_service.user_credentials "
       f"WHERE login_email='{master}')")
    # 이전 실행에서 비밀번호를 바꿔 둔 채 끝났을 수 있으므로 두 후보로 로그인을 시도합니다.
    def login_probe():
        for index, password in enumerate(PROBE_PASSWORDS):
            try:
                return logged_in(probe, password), index
            except RuntimeError:
                continue
        raise RuntimeError("측정용 회원으로 로그인할 수 없습니다.")
    stamp = int(time.time())

    def run_phase(name):
        samples = {op: [] for op in ("login", "password-change", "signup", "admin-users", "admin-terms", "my-info")}
        session, current = login_probe()
        admin = logged_in(master, PROBE_PASSWORDS[0])
        for index in range(args.samples):
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            csrf = request(opener, "/api/v1/auth/csrf")
            elapsed, _ = timed_request(opener, "/api/v1/auth/login",
                                       {"loginEmail": probe, "password": PROBE_PASSWORDS[current]}, csrf)
            samples["login"].append(elapsed)
            # 비밀번호 변경은 세션 ID·CSRF를 바꾸므로 매번 CSRF를 다시 받고, 두 비밀번호를 번갈아 씁니다.
            csrf = request(session, "/api/v1/auth/csrf")
            elapsed, _ = timed_request(session, "/api/v1/users/me/password",
                                       {"currentPassword": PROBE_PASSWORDS[current],
                                        "newPassword": PROBE_PASSWORDS[1 - current]}, csrf, expected=(204,),
                                       method="PATCH")
            samples["password-change"].append(elapsed)
            current = 1 - current
            fresh = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            csrf = request(fresh, "/api/v1/auth/csrf")
            terms = request(fresh, "/api/v1/terms")["terms"]
            elapsed, _ = timed_request(fresh, "/api/v1/users", {
                "loginEmail": f"auth-load-signup-{stamp}-{name}-{index}@example.test", "password": PROBE_PASSWORDS[0],
                # 가입마다 다른 번호(실행마다 바뀌는 가운데 자리 + 단계·순번)를 써서 중복 409를 피합니다.
                "realName": "부하시험", "phone": f"010-{6000 + stamp % 3000:04d}-{(name == 'load') * 5000 + index:04d}",
                "consents": [{"termsCode": term["termsCode"], "termsVersion": term["termsVersion"]}
                             for term in terms if term["isRequired"]],
            }, csrf, expected=(201,))
            samples["signup"].append(elapsed)
            samples["admin-users"].append(timed_request(admin, "/api/v1/admin/users?page=0&size=20")[0])
            samples["admin-terms"].append(timed_request(admin, "/api/v1/admin/terms")[0])
            samples["my-info"].append(timed_request(session, "/api/v1/users/me")[0])
        return {op: summarize_ms(values) for op, values in samples.items()}, samples

    print("쉬는 상태 측정", flush=True)
    idle, idle_raw = run_phase("idle")
    print(f"부하 상태 측정: 내 정보 조회 {args.vus} VU를 함께 실행", flush=True)
    load = k6_command("my-info.js", directory, "load-summary.json",
                      {"VUS": args.vus, "DURATION": f"{args.load_seconds}s"})
    with (directory / "load.log").open("w") as output:
        process = subprocess.Popen(load, cwd=ROOT, env=k6_environment(), stdout=output, stderr=subprocess.STDOUT)
        try:
            time.sleep(20)  # User CPU가 상한에 닿을 때까지 기다립니다.
            loaded, loaded_raw = run_phase("load")
            if process.poll() is not None:
                raise RuntimeError("부하가 측정 도중 끝났습니다. --load-seconds를 늘리세요.")
        finally:
            if process.poll() is None:
                subprocess.run(["docker", "stop", "-t", "1", K6_NAME], capture_output=True, timeout=15)
            process.wait(timeout=30)
    write_json(directory / "slow-ops.json", {"idle": idle, "load": loaded,
                                             "raw": {"idle": idle_raw, "load": loaded_raw}})
    lines = ["# User 정상 처리의 가장 느린 시간", "", f"측정: {directory.name} · 작업마다 {args.samples}회 · "
             f"부하 상태는 내 정보 조회 {args.vus} VU 동시 실행", "",
             "| 작업 | 쉬는 상태 p50 / p95 / 최대(ms) | 부하 상태 p50 / p95 / 최대(ms) |", "|---|---|---|"]
    for op in idle:
        cell = lambda item: f"{item['p50']:.1f} / {item['p95']:.1f} / {item['max']:.1f}"
        lines.append(f"| {op} | {cell(idle[op])} | {cell(loaded[op])} |")
    lines += ["", "Gateway를 거친 전체 시간이며 공개 포트 전달 시간이 포함됩니다."]
    (directory / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"결과 저장: {directory.relative_to(ROOT)}", flush=True)


def monitoring():
    env = dict(os.environ, MONITORING_LOAD_TEST_NETWORK="travelpay-auth-load-test_default",
               MONITORING_LOAD_TEST_TARGETS_FILE=str(HERE / "prometheus-targets.yml"))
    monitoring_compose = ["docker", "compose", "-f", "compose.monitoring.yaml", "-f",
                          "compose.monitoring.load-test.yaml"]
    # 브랜치 전환 등으로 호스트 폴더가 교체되면 기존 bind mount가 이전 폴더를 계속 볼 수 있습니다.
    # 설정을 다시 마운트하되 메트릭·로그·Grafana 저장 볼륨은 그대로 사용합니다.
    subprocess.run(monitoring_compose + ["up", "-d", "--force-recreate", "prometheus", "alloy", "grafana"],
                   cwd=ROOT, env=env, check=True)
    subprocess.run(monitoring_compose + ["exec", "-T", "alloy", "alloy", "validate", "/etc/alloy/config.alloy"],
                   cwd=ROOT, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    prep = sub.add_parser("prepare", help="실제 가입·로그인으로 사용자별 세션 준비")
    prep.add_argument("--users", type=int, default=100, choices=range(1, 1001), metavar="1..1000")
    prep.add_argument("--wallets", action="store_true", help="업무 구성을 띄운 상태에서 회원마다 지갑도 개설")
    sub.add_parser("monitoring", help="기존 공용 모니터링을 시험 네트워크에 연결")
    run = sub.add_parser("run", help="예열·반복 측정·SQL/지표·CPU 검산")
    run.add_argument("--scenario", choices=sorted(SCENARIOS), default="my-info")
    run.add_argument("--label", default="baseline")
    run.add_argument("--vus", type=int, nargs="+", default=[10, 30, 60])
    run.add_argument("--seconds", type=int, default=60)
    run.add_argument("--warmup", type=int, default=30)
    run.add_argument("--repeats", type=int, default=3)
    run.add_argument("--prewarm", type=int, default=120, help="측정 전 기록하지 않는 예열(초). 0이면 생략")
    broken = sub.add_parser("fault", help="일정한 도착률의 부하 중 장애를 넣고 상태·지연·회복을 초 단위로 기록")
    broken.add_argument("--type", choices=sorted(FAULT_TARGETS), required=True)
    broken.add_argument("--label", default="baseline")
    broken.add_argument("--rate", type=int, default=40, help="API마다 초당 요청 수")
    broken.add_argument("--seconds", type=int, default=150, help="전체 부하 시간")
    broken.add_argument("--at", type=int, default=30, help="부하 시작 뒤 장애를 넣을 시각(초)")
    broken.add_argument("--duration", type=int, default=20, help="장애 유지 시간(초)")
    slow = sub.add_parser("slow-ops", help="로그인·비밀번호 변경·가입·관리자 조회의 가장 느린 시간(쉬는 상태·부하 상태)")
    slow.add_argument("--label", default="baseline")
    slow.add_argument("--samples", type=int, default=20)
    slow.add_argument("--vus", type=int, default=60, help="부하 상태에서 함께 돌릴 내 정보 조회 동시 사용자 수")
    slow.add_argument("--load-seconds", type=int, default=300)
    args = parser.parse_args()
    label = getattr(args, "label", None)
    if label is not None and not re.fullmatch(r"[a-zA-Z0-9_-]+", label):
        parser.error("label에는 영문·숫자·밑줄·하이픈만 사용하세요.")
    if args.operation == "prepare":
        prepare(args.users, args.wallets)
    elif args.operation == "monitoring":
        monitoring()
    elif args.operation == "slow-ops":
        if min(args.samples, args.vus, args.load_seconds) < 1:
            parser.error("횟수·동시성·부하 시간은 양수여야 합니다.")
        slow_ops(args)
    elif args.operation == "run":
        if min(args.vus + [args.seconds, args.warmup, args.repeats]) < 1 or args.prewarm < 0:
            parser.error("동시성·시간·반복 횟수는 양수, 사전 예열은 0 이상이어야 합니다.")
        measure(args)
    else:
        if min(args.rate, args.at, args.duration) < 1 or args.at + args.duration + 30 > args.seconds:
            parser.error("도착률·시각은 양수이고, 장애 해제 뒤 회복을 볼 30초 이상이 남아야 합니다.")
        fault(args)


if __name__ == "__main__":
    main()
