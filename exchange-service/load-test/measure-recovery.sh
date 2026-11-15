#!/usr/bin/env bash
# 원장 장애로 쌓인 PROCESSING 주문을, 원장이 회복된 뒤 복구 스케줄러가 모두 확정하는 데 걸리는 시간을 잽니다(#317).
#
# 한 회차:
#   1. 가짜 원장을 응답 없음(지연 6초, 앱은 5초에 포기)으로 두고 환전 신청을 정확히 ORDERS건 보냄 → 서킷이 열려 모두 PROCESSING
#   2. 장애를 OUTAGE_AFTER_FILL초(기본 60초) 더 유지. 막 들어온 주문은 재시도 간격(최소 1분)이 지나야 복구 대상이 되므로,
#      "방금 들어와서 아직 대상이 아님"이 결과에 섞이지 않게 한다. 꼬리를 보는 긴 장애는 300초로 따로 잰다(README)
#   3. 가짜 원장의 지연을 RECOVERED_DELAY_MS(기본 3ms)로 바꿈 = 원장 회복 (3ms는 원장 속도 측정의 동시 1건 p50 2.87ms)
#   4. PROCESSING이 0이 될 때까지 1초마다 주문 상태별 수·서킷 상태·원장 호출 수·복구 주기 수를 기록
#
# 사용법: [OUTAGE_AFTER_FILL=60] [RECOVERED_DELAY_MS=3] APP_JAR=<jar> ./measure-recovery.sh <LABEL> <ORDERS>
#   예: APP_JAR=../../../travelpay-recovery-v0/$J ./measure-recovery.sh recovery-v0-1000-r1 1000
#   결과: results/<LABEL>-fill.json(신청 결과) + -server.tsv(1초 기록) + -server.json(요약·측정 조건)
set -euo pipefail

LABEL=${1:?LABEL을 주세요 (예: recovery-v0-1000-r1)}
ORDERS=${2:?쌓을 주문 수를 주세요 (예: 1000)}
OUTAGE_AFTER_FILL=${OUTAGE_AFTER_FILL:-60}
RECOVERED_DELAY_MS=${RECOVERED_DELAY_MS:-3}
OUTAGE_DELAY_MS=6000
HOURS=${HOURS:-24}
LEDGER_MAPPING_ID=8f1e2a64-2c1b-4d6e-9a1f-0c2b3d4e5f60   # wiremock/mappings/ledger-register.json의 id
# 확정이 끝나지 않을 때 기다리는 최대 시간: 개선 전 규칙(30초마다 50건)으로 걸릴 시간의 2배 + 여유 10분
MAX_WAIT=${MAX_WAIT:-$((ORDERS / 50 * 30 * 2 + 600))}

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 컨테이너 안 경로(/tmp/...)를 바꾸지 않게
# 앱이 가짜 지갑·원장을 부르게 한다 (compose.yaml에서 기본은 비어 있음)
export MEASURE_WALLET_URL=http://ledger-stub:8080 MEASURE_LEDGER_URL=http://ledger-stub:8080
mkdir -p results
samples="results/${LABEL}-server.tsv"
summary="results/${LABEL}-server.json"
rm -f "$summary" "results/${LABEL}-fill.json"

psql_q() {
    docker compose exec -T measure-postgres psql -U exchange_app -d travelpay -qAt "$@" </dev/null
}
set_ledger_delay() {
    sed "s/\"fixedDelayMilliseconds\": [0-9]*/\"fixedDelayMilliseconds\": $1/" wiremock/mappings/ledger-register.json \
        | curl -fs -X PUT "http://127.0.0.1:18090/__admin/mappings/${LEDGER_MAPPING_ID}" -H 'Content-Type: application/json' --data-binary @- >/dev/null
    local applied
    applied=$(curl -fs "http://127.0.0.1:18090/__admin/mappings/${LEDGER_MAPPING_ID}" | grep -o '"fixedDelayMilliseconds" *: *[0-9]*' | grep -o '[0-9]*$')
    if [ "$applied" != "$1" ]; then
        echo "가짜 원장의 지연이 $1ms로 바뀌지 않았습니다 (지금 '${applied}')." >&2
        exit 1
    fi
}
require_pc_state() {
    if [ -z "$1" ]; then
        echo "PC 상태를 읽지 못했습니다(pc-state.sh 출력 없음). 측정하지 않습니다." >&2
        exit 1
    fi
    if echo "$1" | grep -q '"os": "windows"' && echo "$1" | grep -q '"powerMode": "unknown"\|"charger": "unknown"'; then
        echo "PC 전원 모드나 충전기 상태를 읽지 못했습니다: $1. 측정하지 않습니다." >&2
        exit 1
    fi
}
pc_power() { echo "$1" | grep -o '"powerMode": [^,]*\|"charger": [^,]*' | tr '\n' ' '; }

echo "[1/6] 측정 DB·Redis, 가짜 지갑·원장(장애: 지연 ${OUTAGE_DELAY_MS}ms)"
docker compose up -d --wait measure-postgres measure-redis >/dev/null 2>&1
redis_policy=$(docker compose exec -T measure-redis redis-cli CONFIG GET maxmemory-policy </dev/null | tr -d '\r' | sed -n 2p)
if [ "$redis_policy" != noeviction ]; then
    echo "측정 Redis 정책이 noeviction이 아닙니다 (지금 ${redis_policy})." >&2
    echo "  export MEASURE_REDIS_MAXMEMORY=2gb MEASURE_REDIS_POLICY=noeviction 후 docker compose up -d --force-recreate --wait measure-redis" >&2
    exit 1
fi
if ! docker compose --profile stub up -d --force-recreate --wait ledger-stub >/dev/null 2>&1; then
    echo "가짜 지갑·원장(ledger-stub)이 뜨지 않았습니다." >&2
    docker compose --profile stub logs --tail 20 ledger-stub >&2
    exit 1
fi
set_ledger_delay "$OUTAGE_DELAY_MS"

echo "[2/6] 환전 서비스 재시작, 환율 이력 적재(주문 표도 비움), 캐시 비우기"
docker compose --profile app up -d --force-recreate --wait app >/dev/null 2>&1
psql_q -v hours="${HOURS}" -f /load-test/seed-rate-history.sql >/dev/null
docker compose exec -T measure-redis redis-cli FLUSHALL </dev/null >/dev/null
sleep 6   # 비운 캐시를 수집기가 다시 채울 때까지(첫 견적이 환율 없음으로 실패하지 않게)

pc_before=$(bash pc-state.sh)
require_pc_state "$pc_before"
echo "      측정 조건: 주문 ${ORDERS}건, 신청 뒤 장애 유지 ${OUTAGE_AFTER_FILL}초, 회복 후 원장 지연 ${RECOVERED_DELAY_MS}ms, PC ${pc_before}"

echo "[3/6] 1초 기록 시작(주문 상태별 수, 서킷 상태, 원장 호출 수, 복구 주기)"
stop_at=$(($(date +%s) + OUTAGE_AFTER_FILL + MAX_WAIT + 900))
docker compose exec -T app sh -c 'rm -f /tmp/stop-sampler' </dev/null >/dev/null
docker compose exec -T measure-postgres sh -c "rm -f /tmp/stop-sampler; while [ ! -f /tmp/stop-sampler ] && [ \$(date +%s) -lt $stop_at ]; do
        printf '%s\t' \$(date +%s); psql -U exchange_app -d travelpay -qAt -F '	' -c \"select count(*) filter (where status = 'PROCESSING'), count(*) filter (where status = 'COMPLETED'), count(*) filter (where status not in ('PROCESSING', 'COMPLETED')) from exchange_service.exchange_orders\"; sleep 1; done" </dev/null >"$samples.db" &
db_sampler=$!
# 서킷 상태(열림 1/0), 원장 호출 수(서킷이 허락해 실제로 보낸 호출, 누적), 서킷이 막은 호출(누적), 복구 주기 수·누적 시간
docker compose exec -T app sh -c "while [ ! -f /tmp/stop-sampler ] && [ \$(date +%s) -lt $stop_at ]; do echo \"ts \$(date +%s)\"; curl -s --max-time 2 http://localhost:18085/actuator/prometheus; sleep 1; done" </dev/null \
    | awk '
        function flush() { if (ts != "") printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\n", ts, open, half, calls + 0, blocked + 0, rc + 0, rs + 0; open = half = ""; calls = blocked = rc = rs = 0 }
        $1 == "ts" { flush(); ts = $2; next }
        $1 ~ /^resilience4j_circuitbreaker_state\{.*state="open"/ { open = $2 }
        $1 ~ /^resilience4j_circuitbreaker_state\{.*state="half_open"/ { half = $2 }
        $1 ~ /^resilience4j_circuitbreaker_calls_seconds_count\{/ { calls += $2 }
        $1 ~ /^resilience4j_circuitbreaker_not_permitted_calls_total\{/ { blocked += $2 }
        $1 ~ /^tasks_scheduled_execution_seconds_count\{.*code_function="recover"/ { rc += $2 }
        $1 ~ /^tasks_scheduled_execution_seconds_sum\{.*code_function="recover"/ { rs += $2 }
        END { flush() }' >"$samples" &
app_sampler=$!
stop_samplers() {
    docker compose exec -T app touch /tmp/stop-sampler </dev/null >/dev/null 2>&1 || true
    docker compose exec -T measure-postgres touch /tmp/stop-sampler </dev/null >/dev/null 2>&1 || true
    wait "$db_sampler" 2>/dev/null || true
    wait "$app_sampler" 2>/dev/null || true
}
trap 'stop_samplers' EXIT

echo "[4/6] 장애 중 신청 ${ORDERS}건 보내기"
fill_started=$(date +%s)
docker compose run --rm -e LABEL="${LABEL}" -e ORDERS="${ORDERS}" -e BASE_URL=http://app:18084 \
    k6 run --quiet /scripts/recovery-fill.js </dev/null
fill_ended=$(date +%s)
processing=$(psql_q -c "SELECT count(*) FROM exchange_service.exchange_orders WHERE status = 'PROCESSING'")
total=$(psql_q -c "SELECT count(*) FROM exchange_service.exchange_orders")
if [ "$processing" != "$ORDERS" ] || [ "$total" != "$ORDERS" ]; then
    echo "무효 측정: 쌓인 주문이 ${ORDERS}건이 아닙니다 (PROCESSING ${processing}, 전체 ${total})." >&2
    exit 1
fi
echo "      PROCESSING ${processing}건 쌓임 ($((fill_ended - fill_started))초). 장애를 ${OUTAGE_AFTER_FILL}초 더 유지"
sleep "$OUTAGE_AFTER_FILL"

echo "[5/6] 원장 회복: 지연 ${RECOVERED_DELAY_MS}ms. PROCESSING이 0이 될 때까지 기다림(최대 ${MAX_WAIT}초)"
set_ledger_delay "$RECOVERED_DELAY_MS"
recovered_at=$(date +%s)
drained_at=null
while [ $(($(date +%s) - recovered_at)) -lt "$MAX_WAIT" ]; do
    left=$(psql_q -c "SELECT count(*) FROM exchange_service.exchange_orders WHERE status = 'PROCESSING'")
    if [ "$left" = 0 ]; then
        drained_at=$(date +%s)
        break
    fi
    sleep 2
done
sleep 3   # 마지막 기록이 남게 잠깐 더 기록
stop_samplers
trap - EXIT

pc_after=$(bash pc-state.sh)
require_pc_state "$pc_after"
standby=$(bash standby-events.sh "$fill_started" "$(date +%s)")
if [ "$standby" != null ] && [ "$standby" != 0 ]; then
    echo "무효 측정: 측정 중 PC가 대기 모드에 ${standby}번 들어갔습니다." >&2
    exit 1
fi
if [ "$(pc_power "$pc_before")" != "$(pc_power "$pc_after")" ]; then
    echo "무효 측정: 측정 중에 전원 모드나 충전기 연결이 바뀌었습니다. 직전 ${pc_before} / 직후 ${pc_after}" >&2
    exit 1
fi

echo "[6/6] 정리"
statuses=$(psql_q -F ' ' -c "SELECT status, count(*) FROM exchange_service.exchange_orders GROUP BY status ORDER BY status" | awk '{ printf "%s\"%s\": %d", (n++ ? ", " : ""), $1, $2 }')
# 회복 뒤 구간만: 50·90·99% 처리 시각과 꼬리 주문 수(99%에 닿은 순간 남은 PROCESSING), 원장 호출 수(초당 최대),
# 서킷이 닫힌 시각, 복구 주기 수와 평균 주기 시간(빈 주기 포함), 첫 확정 시각(회복을 알아채기까지 걸린 시간).
# 장애 구간(신청이 끝난 뒤 ~ 회복 전)에 원장에 실제로 보낸 호출 수(서킷 반열림 시험·복구가 보낸 것)도 센다
# 앱 지표를 못 읽은 초(서킷 상태가 빈 줄)는 건너뛴다. 누적값이 0으로 읽혀 초당 호출 수가 부풀지 않게.
# 그래서 표본 사이가 1초보다 길 수 있어, 초당 호출 수는 늘어난 호출을 실제 지난 초로 나눈다.
# 회복 뒤 호출 수·복구 주기 수는 회복 직전 마지막 표본을 기준으로 센다(회복과 첫 표본 사이의 호출이 빠지지 않게)
awk -F'\t' -v from="$recovered_at" -v n="$ORDERS" -v fe="$fill_ended" '
    FNR == NR {
        if ($1 >= from) {
            done = n - $2
            if (tFirst == "" && $3 > 0) tFirst = $1 - from
            if (t50 == "" && done >= n * 0.5) t50 = $1 - from
            if (t90 == "" && done >= n * 0.9) t90 = $1 - from
            if (t99 == "" && done >= n * 0.99) { t99 = $1 - from; tail = $2 }
        }
        next
    }
    $1 >= fe && $1 < from && $2 != "" {
        if (outFirst == "") outFirst = $4
        outLast = $4
    }
    $1 < from && $2 != "" { pre = $1; preCalls = $4; preRc = $6; preRs = $7 }
    $1 >= from && $2 != "" {
        if (first == "") {
            first = $1
            if (pre != "") { c0 = preCalls; rc0 = preRc; rs0 = preRs; prev = pre; prevCalls = preCalls }
            else { c0 = $4; rc0 = $6; rs0 = $7 }
        }
        if (prev != "" && $1 > prev && ($4 - prevCalls) / ($1 - prev) > maxCalls) maxCalls = ($4 - prevCalls) / ($1 - prev)
        if (closedAt == "" && $2 != "" && $2 + 0 == 0 && $3 + 0 == 0) closedAt = $1 - from
        prev = $1; prevCalls = $4; lastCalls = $4; rc1 = $6; rs1 = $7
    }
    END {
        cycles = rc1 - rc0
        printf "  \"secondsTo50Percent\": %s,\n  \"secondsTo90Percent\": %s,\n  \"secondsTo99Percent\": %s,\n  \"tailOrdersAt99Percent\": %s,\n", \
            (t50 == "" ? "null" : t50), (t90 == "" ? "null" : t90), (t99 == "" ? "null" : t99), (tail == "" ? "null" : tail)
        printf "  \"ledgerCallsAfterRecovery\": %d,\n  \"ledgerCallsPerSecMax\": %.1f,\n", lastCalls - c0, maxCalls
        printf "  \"circuitClosedSecondsAfterRecovery\": %s,\n", (closedAt == "" ? "null" : closedAt)
        printf "  \"secondsToFirstConfirm\": %s,\n", (tFirst == "" ? "null" : tFirst)
        printf "  \"ledgerCallsDuringOutage\": %s,\n", (outFirst == "" ? "null" : outLast - outFirst)
        printf "  \"recoveryCycles\": %d,\n  \"recoveryCycleAvgSeconds\": %.2f,\n", cycles, (cycles > 0 ? (rs1 - rs0) / cycles : 0)
    }' "$samples.db" "$samples" >"$summary.tmp"
# 환율 수집이 복구에 밀리지 않았는지(스케줄러 스레드 2개를 복구·예약 만료와 나눠 씀): 가짜 환율 JPY 수집 시각 사이
# 최대 간격(초, 평소 5초). 장애 구간(신청 시작 ~ 회복)과 회복 구간(회복 ~ 다 처리)을 따로 본다
rate_gap() {
    local v
    v=$(psql_q -c "SELECT round(max(gap)::numeric, 1) FROM (SELECT collected_at, extract(epoch FROM collected_at - lag(collected_at) OVER (ORDER BY collected_at)) AS gap FROM exchange_service.exchange_rates WHERE source = 'MOCK' AND target_currency = 'JPY') g WHERE collected_at > to_timestamp($1) AND collected_at <= to_timestamp($2)")
    echo "${v:-null}"
}
rate_gap_outage=$(rate_gap "$fill_started" "$recovered_at")
rate_gap_recovery=$(rate_gap "$recovered_at" "$([ "$drained_at" != null ] && echo "$drained_at" || date +%s)")
echo "  \"rateCollectionMaxGapSeconds\": {\"outage\": ${rate_gap_outage}, \"recovery\": ${rate_gap_recovery}}," >>"$summary.tmp"
drain_seconds=null
per_minute=null
if [ "$drained_at" != null ]; then
    drain_seconds=$((drained_at - recovered_at))
    per_minute=$(awk -v n="$ORDERS" -v s="$drain_seconds" 'BEGIN { printf "%.1f", (s > 0 ? n / s * 60 : n * 60) }')
fi
{
    echo "{"
    echo "  \"label\": \"${LABEL}\","
    echo "  \"orders\": ${ORDERS},"
    echo "  \"drainSeconds\": ${drain_seconds},"
    echo "  \"confirmedPerMinute\": ${per_minute},"
    echo "  \"finalStatus\": {${statuses}},"
    cat "$summary.tmp"
    echo "  \"conditions\": {\"outageAfterFill\": ${OUTAGE_AFTER_FILL}, \"recoveredDelayMs\": ${RECOVERED_DELAY_MS}, \"fillStarted\": ${fill_started}, \"fillEnded\": ${fill_ended}, \"recoveredAt\": ${recovered_at}, \"drainedAt\": ${drained_at}, \"pcBefore\": ${pc_before}, \"pcAfter\": ${pc_after}, \"standbyEntries\": ${standby}}"
    echo "}"
} >"$summary"
rm -f "$summary.tmp"
if [ "$drained_at" = null ]; then
    echo "      ${MAX_WAIT}초 안에 PROCESSING이 0이 되지 않았습니다. 결과의 finalStatus를 보세요." >&2
fi
# 다 처리는 PROCESSING이 0건(서비스와 같이 COMPLETED·FAILED·MANUAL_REVIEW 모두 확정)으로 잰다. 다만 이 측정은 지갑을 충분히
# 충전하고 회복한 가짜 원장이 늘 성공하므로 전부 COMPLETED여야 정상이다. 다른 상태가 있으면 원장 호출 없이 끝난 주문이 처리
# 속도에 섞였을 수 있어 무효로 둔다
not_completed=$(psql_q -c "SELECT count(*) FROM exchange_service.exchange_orders WHERE status NOT IN ('COMPLETED', 'PROCESSING')")
if [ "$not_completed" != 0 ]; then
    echo "무효 측정: COMPLETED가 아닌 확정 주문이 ${not_completed}건 있습니다. 최종 상태: {${statuses}}" >&2
    exit 1
fi
echo "      결과: $(grep -o '"drainSeconds": [0-9a-z]*\|"secondsToFirstConfirm": [0-9a-z]*\|"ledgerCallsDuringOutage": [0-9a-z]*\|"secondsTo50Percent": [0-9a-z]*\|"secondsTo99Percent": [0-9a-z]*\|"tailOrdersAt99Percent": [0-9a-z]*\|"confirmedPerMinute": [0-9.a-z]*\|"recoveryCycles": [0-9]*\|"ledgerCallsPerSecMax": [0-9.]*\|"circuitClosedSecondsAfterRecovery": [0-9a-z]*' "$summary" | tr '\n' ' ')"
echo "      최종 상태: {${statuses}}"
