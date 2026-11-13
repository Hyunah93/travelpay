#!/usr/bin/env bash
# 진짜 원장(ledger-service)이 환전 거래 한 건에 응답하는 시간을 동시 건수별로 잽니다(#317).
# 동시 1건 / 5건 / 20건을 회차마다 순서를 한 칸씩 돌려 3번씩(기본 9회) 잽니다(measure-ledger-compare.sh와 같은 생각).
#
# 왜 재나:
#   동시 1건  = 지금 복구 스케줄러가 원장에 보내는 방식. 이 p50을 #317 측정에서 가짜 원장의 "회복 후 응답 시간"으로 쓰고,
#              "쉬지 않고 1건씩" 보내면 얼마나 걸릴지 계산하는 근거로 씀
#   동시 5·20건 = 복구를 "동시에 여러 건"으로 바꿀 때 원장이 얼마나 느려지는지, 처리량이 정말 느는지 판단하는 기준
#              (환전 거래는 모두 같은 시스템 계정 FX_POSITION·FEE_REVENUE를 잠가 서로 기다릴 수 있음)
#
# 회차마다 원장 DB와 원장 앱을 새로 만들어(빈 DB, Flyway가 시스템 계정 생성) 같은 출발점에서 잽니다.
# 원장·원장 DB·k6를 같은 Docker 네트워크에 둡니다(README "측정 환경을 Docker 안에 두는 이유").
#
# 사용법: 저장소 루트에서 ./gradlew :ledger-service:bootJar 후
#   [SCENARIOS="1 5 20"] [REPS=3] [START_AT=1] [LEDGER_JAR=<jar>] ./measure-ledger-speed.sh <PREFIX>
#   예: ./measure-ledger-speed.sh ledger-speed
#   결과: results/<PREFIX>-vus<N>-r<회차>.json(k6) + -server.json(원장 DB 잠금 대기·커넥션 풀·CPU, 측정 조건),
#         진행 기록 results/<PREFIX>-progress.log. 중간에 멈추면 진행 기록의 번호부터 START_AT으로 이어서 잽니다.
set -euo pipefail

PREFIX=${1:?PREFIX를 주세요 (예: ledger-speed)}
SCENARIOS=${SCENARIOS:-"1 5 20"}
REPS=${REPS:-3}
START_AT=${START_AT:-1}
WARMUP_SECONDS=${WARMUP_SECONDS:-15}
MEASURE_SECONDS=${MEASURE_SECONDS:-60}

cd "$(dirname "$0")"
export MSYS_NO_PATHCONV=1   # Windows Git Bash가 컨테이너 안 경로(/scripts/...)를 바꾸지 않게
mkdir -p results
read -ra scenario_list <<<"$SCENARIOS"
count=${#scenario_list[@]}
progress="results/${PREFIX}-progress.log"
ledger_jar=${LEDGER_JAR:-../../ledger-service/build/libs/ledger-service-0.0.1-SNAPSHOT.jar}
if [ ! -f "$ledger_jar" ]; then
    echo "원장 jar가 없습니다: ${ledger_jar}. 저장소 루트에서 ./gradlew :ledger-service:bootJar 를 먼저 실행하세요." >&2
    exit 1
fi
{
    echo "# $(date '+%F %T') 시작: PREFIX=${PREFIX}, SCENARIOS=${SCENARIOS}, REPS=${REPS}, 측정 $((count * REPS))회, START_AT=${START_AT}"
    echo "#   LEDGER_JAR=${ledger_jar} ($(date -r "$ledger_jar" '+%F %T'))"
} | tee -a "$progress"

# 측정 조건(PC 상태): 읽지 못하면 측정하지 않는다(measure.sh·measure-ledger.sh와 같은 기준)
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

run_one() {
    local label=$1 vus=$2
    local k6_result="results/${label}.json" samples="results/${label}-server.tsv" summary="results/${label}-server.json"
    rm -f "$k6_result" "$summary"

    echo "      원장 DB·원장 앱 새로 시작"
    # --renew-anon-volumes: Postgres 이미지의 데이터 폴더(익명 볼륨)까지 새로 만든다. 없으면 컨테이너를 새로 만들어도 앞 회차 거래가
    # 남아, 같은 거래 번호가 "이미 있는 거래"(200)로 응답돼 기록 시간이 아닌 조회 시간이 잡힌다(2026-11-13 시험에서 겪음)
    docker compose --profile ledger up -d --force-recreate --renew-anon-volumes --wait ledger-postgres ledger-app >/dev/null 2>&1

    local pc_before pc_after
    pc_before=$(bash pc-state.sh)
    require_pc_state "$pc_before"

    # 1초마다 원장 DB의 잠금 대기 세션 수·실행 중 세션 수와 원장 커넥션 풀·CPU를 기록한다. k6가 끝나면 표시 파일로 멈춘다
    # 멈춤 신호가 오지 않아도 정해진 시간(예열 + 측정 + 준비 여유 300초)이 지나면 스스로 멈춘다
    local stop_at=$(($(date +%s) + WARMUP_SECONDS + MEASURE_SECONDS + 300))
    docker compose exec -T ledger-app sh -c 'rm -f /tmp/stop-sampler' </dev/null >/dev/null
    docker compose exec -T ledger-postgres sh -c "rm -f /tmp/stop-sampler; while [ ! -f /tmp/stop-sampler ] && [ \$(date +%s) -lt $stop_at ]; do
            printf '%s\t' \$(date +%s); psql -U ledger_app -d travelpay -qAt -F '	' -c \"select count(*) filter (where wait_event_type = 'Lock'), count(*) filter (where state = 'active') from pg_stat_activity where usename = 'ledger_app' and pid <> pg_backend_pid()\"; sleep 1; done" </dev/null >"$samples.db" &
    local db_sampler=$!
    docker compose exec -T ledger-app sh -c "while [ ! -f /tmp/stop-sampler ] && [ \$(date +%s) -lt $stop_at ]; do echo \"ts \$(date +%s)\"; curl -s --max-time 2 http://localhost:18087/actuator/prometheus; sleep 1; done" </dev/null \
        | awk '
            function flush() { if (ts != "") printf "%s\t%s\t%s\t%s\n", ts, a, p, pc; a = p = pc = "" }
            $1 == "ts" { flush(); ts = $2; next }
            $1 ~ /^hikaricp_connections_active\{/ { a = $2 }
            $1 ~ /^hikaricp_connections_pending\{/ { p = $2 }
            $1 == "process_cpu_usage" { pc = $2 }
            END { flush() }' >"$samples" &
    local app_sampler=$!

    local k6_started k6_ended
    k6_started=$(date +%s)
    docker compose run --rm -e BASE_URL=http://ledger-app:18086 -e VUS="$vus" -e LABEL="$label" \
        -e WARMUP="${WARMUP_SECONDS}s" -e DURATION="${MEASURE_SECONDS}s" k6 run --quiet /scripts/ledger-speed.js </dev/null
    k6_ended=$(date +%s)
    echo "      k6 끝, 기록 멈춤 신호 보냄"
    docker compose exec -T ledger-app touch /tmp/stop-sampler </dev/null >/dev/null 2>&1 || true
    docker compose exec -T ledger-postgres touch /tmp/stop-sampler </dev/null >/dev/null 2>&1 || true
    wait "$db_sampler" 2>/dev/null || true
    wait "$app_sampler" 2>/dev/null || true

    pc_after=$(bash pc-state.sh)
    require_pc_state "$pc_after"
    local standby
    standby=$(bash standby-events.sh "$k6_started" "$k6_ended")
    if [ "$standby" != null ] && [ "$standby" != 0 ]; then
        echo "무효 측정: k6가 도는 동안 PC가 대기 모드에 ${standby}번 들어갔습니다." >&2
        exit 1
    fi
    if [ "$(pc_power "$pc_before")" != "$(pc_power "$pc_after")" ]; then
        echo "무효 측정: 측정 중에 전원 모드나 충전기 연결이 바뀌었습니다. 직전 ${pc_before} / 직후 ${pc_after}" >&2
        exit 1
    fi
    if [ ! -f "$k6_result" ]; then
        echo "k6 결과가 없습니다: ${k6_result}" >&2
        exit 1
    fi

    # 측정 구간: k6가 끝난 시각에서 거꾸로(예열 뒤 ~ 끝). 표본은 그 구간만 요약한다
    local run_ms measure_from measure_to
    run_ms=$(grep -o '"testRunDurationMs": [0-9]*' "$k6_result" | grep -o '[0-9]*$')
    measure_to=$k6_ended
    measure_from=$((k6_ended - (run_ms + 999) / 1000 + WARMUP_SECONDS))
    awk -F'\t' -v from="$measure_from" -v to="$measure_to" '
        FNR == NR { if ($1 >= from && $1 < to) { n++; lw += $2; if ($2 > lwm) lwm = $2; ac += $3 } next }
        $1 >= from && $1 < to { m++; ha += $2; if ($3 > hpm) hpm = $3; hp += $3; cpu += $4 }
        END {
            printf "  \"db\": {\"samples\": %d, \"lockWaitAvg\": %.2f, \"lockWaitMax\": %d, \"activeSessionsAvg\": %.2f},\n", n, (n ? lw / n : 0), lwm, (n ? ac / n : 0)
            printf "  \"hikari\": {\"samples\": %d, \"activeAvg\": %.2f, \"pendingAvg\": %.2f, \"pendingMax\": %d},\n", m, (m ? ha / m : 0), (m ? hp / m : 0), hpm
            printf "  \"appCpuPercent\": %.1f,\n", (m ? cpu / m * 100 : 0)
        }' "$samples.db" "$samples" >"$summary.tmp"
    {
        echo "{"
        echo "  \"label\": \"${label}\","
        cat "$summary.tmp"
        echo "  \"conditions\": {\"vus\": ${vus}, \"measureFrom\": ${measure_from}, \"measureTo\": ${measure_to}, \"ledgerJar\": \"${ledger_jar}\", \"pcBefore\": ${pc_before}, \"pcAfter\": ${pc_after}, \"standbyEntries\": ${standby}}"
        echo "}"
    } >"$summary"
    rm -f "$summary.tmp"
    # 모두 새 기록(201 POSTED)이어야 원장이 실제로 거래를 쓴 시간이다. 200(같은 거래 재요청)·오류가 섞이면 무효
    if ! grep -q "\"other\": 0," "$k6_result" || ! grep -q "\"noResponse\": 0$" "$k6_result"; then
        echo "무효 측정: POSTED가 아닌 응답이 있습니다. $(tr -d "[:space:]" < "$k6_result" | grep -o "\"status\":{[^}]*}")" >&2
        exit 1
    fi
    echo "      원장 DB 잠금 대기·커넥션 풀: $(grep -o '"lockWaitAvg": [0-9.]*\|"pendingAvg": [0-9.]*' "$summary" | tr '\n' ' ')"
}

n=0
for rep in $(seq 1 "$REPS"); do
    for i in $(seq 0 $((count - 1))); do
        n=$((n + 1))
        # 회차마다 시작 시나리오를 한 칸씩 민다 (1회차 1·5·20, 2회차 5·20·1, 3회차 20·1·5)
        vus=${scenario_list[$(((i + rep - 1) % count))]}
        label="${PREFIX}-vus${vus}-r${rep}"
        if [ "$n" -lt "$START_AT" ]; then
            continue
        fi
        echo "=== [${n}/$((count * REPS))] $(date +%T) 동시 ${vus}건, ${rep}회차" | tee -a "$progress"
        if ! run_one "$label" "$vus" 2>&1 | tee -a "$progress"; then
            echo "중단: ${label} 실패. 원인을 고친 뒤 START_AT=${n}으로 이어서 잽니다." | tee -a "$progress"
            exit 1
        fi
    done
done

# 요약: 시나리오별 3회 중앙값 (p50, p95, 초당 처리량, 잠금 대기)
echo "=== 요약 (회차 중앙값)" | tee -a "$progress"
for vus in "${scenario_list[@]}"; do
    for key in p50 p95 throughputPerSec; do
        case $key in
            throughputPerSec) pattern='"throughputPerSec": [0-9.]*' ;;
            *) pattern="\"${key}\": [0-9.]*" ;;
        esac
        values=$(for f in results/"${PREFIX}"-vus"${vus}"-r*.json; do case $f in *-server.json) continue ;; esac; grep -o "$pattern" "$f" | head -1 | grep -o '[0-9.]*$'; done | sort -n)
        printf -v "med_${key}" '%s' "$(echo "$values" | awk '{ v[NR] = $1 } END { if (NR) print v[int((NR + 1) / 2)] }')"
    done
    lock=$(for f in results/"${PREFIX}"-vus"${vus}"-r*-server.json; do grep -o '"lockWaitAvg": [0-9.]*' "$f" | grep -o '[0-9.]*$'; done | sort -n | awk '{ v[NR] = $1 } END { if (NR) print v[int((NR + 1) / 2)] }')
    echo "  동시 ${vus}건: p50 ${med_p50}ms, p95 ${med_p95}ms, 초당 ${med_throughputPerSec}건, 잠금 대기 평균 ${lock}" | tee -a "$progress"
done
