// 진짜 원장(ledger-service)이 환전 거래 한 건에 응답하는 시간을 잽니다(#317). measure-ledger-speed.sh가 실행합니다.
//
// 닫힌 모델(constant-vus): 가상 사용자 VUS명이 각자 "보내고 → 응답 받으면 다음"을 반복합니다.
//   VUS=1  : 한 번에 1건씩. 지금 복구 스케줄러가 원장에 보내는 방식과 같음
//   VUS=5·20: 동시에 5·20건. 복구를 "동시에 여러 건"으로 바꾸면 원장이 얼마나 느려지고 처리량이 정말 느는지 봄
// 거래 모양은 환전 서비스가 보내는 것과 같습니다(EXCHANGE, 6항목: 사용자 지갑 + 시스템 FX_POSITION·FEE_REVENUE).
// 환전 거래는 모두 같은 시스템 계정을 잠그므로, 동시에 보내면 서로 기다릴 수 있습니다.
//
// setup에서 지갑 WALLETS개의 원장 계정을 열고 원화를 충전합니다(측정 스크립트가 회차마다 빈 DB로 시작함).
// 측정 구간(예열 뒤)에 시작한 요청만 집계합니다.
//
// 환경 변수: VUS(기본 1), WARMUP(15s), DURATION(60s), TIMEOUT(10s), WALLETS(100), LABEL, BASE_URL(http://ledger-app:18086)

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const VUS = Number(__ENV.VUS || 1);
const WARMUP = __ENV.WARMUP || '15s';
const DURATION = __ENV.DURATION || '60s';
const TIMEOUT = __ENV.TIMEOUT || '10s';
const WALLETS = Number(__ENV.WALLETS || 100);
const LABEL = __ENV.LABEL || `ledger-speed-vus${VUS}`;
const BASE_URL = __ENV.BASE_URL || 'http://ledger-app:18086';
const WARMUP_MS = seconds(WARMUP) * 1000;
const MEASURE_SECONDS = seconds(DURATION);
const JSON_HEADERS = { 'Content-Type': 'application/json' };
// 지갑마다 충분히 충전해 측정 중 잔액 부족(422)이 나지 않게 한다 (거래당 원화 101,000원)
const CHARGE_KRW = 1_000_000_000_000;

const postDuration = new Trend('ledger_post_duration', true); // 측정 구간 거래 등록 한 건
const posted = new Counter('ledger_posted');                   // 201 POSTED
const otherStatus = new Counter('ledger_other_status');        // 그 밖의 상태 코드
const noResponse = new Counter('ledger_no_response');          // 시간 초과·연결 실패

export const options = {
    scenarios: {
        post: { executor: 'constant-vus', vus: VUS, duration: `${seconds(WARMUP) + MEASURE_SECONDS}s`, gracefulStop: '10s' },
    },
    setupTimeout: '180s',
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
    for (let i = 1; i <= WALLETS; i++) {
        const walletId = uuid('00000000', i);
        expectOk(http.post(`${BASE_URL}/internal/v1/ledger/accounts`, JSON.stringify({ walletId }), { headers: JSON_HEADERS }), 'accounts');
        expectOk(http.post(`${BASE_URL}/internal/v1/ledger/transactions`, JSON.stringify({
            referenceType: 'CHARGE',
            referenceId: uuid('10000000', i),
            entries: [
                { ownerType: 'SYSTEM', accountType: 'CHARGE_SOURCE', currency: 'KRW', direction: 'DEBIT', amount: CHARGE_KRW, entryType: 'CHARGE_OUT' },
                { ownerType: 'USER', walletId, currency: 'KRW', direction: 'CREDIT', amount: CHARGE_KRW, entryType: 'CHARGE_IN' },
            ],
        }), { headers: JSON_HEADERS }), 'charge');
    }
}

export default function () {
    // 거래 번호는 가상 사용자 번호 × 1,000만 + 그 사용자의 반복 횟수로 만든다. 겹치면 원장이 같은 거래로 보고
    // 기록 없이 바로 200을 돌려줘 응답 시간이 비정상적으로 빨라진다(k6 1.3의 constant-vus에서 iterationInTest로 만들었을 때 실제로 겪음)
    const n = exec.vu.idInTest * 10_000_000 + exec.vu.iterationInScenario + 1;
    const walletId = uuid('00000000', (n % WALLETS) + 1);
    const measuring = exec.instance.currentTestRunDuration >= WARMUP_MS;
    // 원화 100,000원을 엔화로: 보내는 금액 + 수수료 1,000원 + 받는 엔화 (환전 서비스가 보내는 6항목과 같은 모양)
    const body = JSON.stringify({
        referenceType: 'EXCHANGE',
        referenceId: uuid('20000000', n),
        userId: walletId,
        entries: [
            { ownerType: 'USER', walletId, currency: 'KRW', direction: 'DEBIT', amount: 100000, entryType: 'EXCHANGE_OUT' },
            { ownerType: 'SYSTEM', accountType: 'FX_POSITION', currency: 'KRW', direction: 'CREDIT', amount: 100000, entryType: 'EXCHANGE_OUT' },
            { ownerType: 'USER', walletId, currency: 'KRW', direction: 'DEBIT', amount: 1000, entryType: 'EXCHANGE_FEE' },
            { ownerType: 'SYSTEM', accountType: 'FEE_REVENUE', currency: 'KRW', direction: 'CREDIT', amount: 1000, entryType: 'EXCHANGE_FEE' },
            { ownerType: 'SYSTEM', accountType: 'FX_POSITION', currency: 'JPY', direction: 'DEBIT', amount: 11928, entryType: 'EXCHANGE_IN' },
            { ownerType: 'USER', walletId, currency: 'JPY', direction: 'CREDIT', amount: 11928, entryType: 'EXCHANGE_IN' },
        ],
    });
    const response = http.post(`${BASE_URL}/internal/v1/ledger/transactions`, body,
        { headers: JSON_HEADERS, timeout: TIMEOUT, tags: { phase: measuring ? 'measure' : 'warmup' } });
    if (!measuring) {
        return;
    }
    if (response.status === 0) {
        noResponse.add(1);
        return;
    }
    postDuration.add(response.timings.duration);
    if (response.status === 201) {
        posted.add(1);
    } else {
        otherStatus.add(1);
    }
}

export function handleSummary(data) {
    const metric = name => (data.metrics[name] ? data.metrics[name].values : {});
    const latency = metric('ledger_post_duration');
    const count = latency.count || 0;
    const result = {
        label: LABEL,
        vus: VUS,
        warmup: WARMUP,
        measureSeconds: MEASURE_SECONDS,
        wallets: WALLETS,
        testRunDurationMs: Math.round(data.state.testRunDurationMs),
        requests: count,
        throughputPerSec: round(count / MEASURE_SECONDS),
        latencyMs: { avg: round(latency.avg), p50: round(latency.med), p95: round(latency['p(95)']), p99: round(latency['p(99)']), max: round(latency.max) },
        status: { posted: metric('ledger_posted').count || 0, other: metric('ledger_other_status').count || 0, noResponse: metric('ledger_no_response').count || 0 },
    };
    const line = `[${LABEL}] 동시 ${VUS}건: ${count}건, 초당 ${result.throughputPerSec}건, p50 ${result.latencyMs.p50}ms, p95 ${result.latencyMs.p95}ms, `
        + `POSTED ${result.status.posted} / 그 밖 ${result.status.other} / 응답 없음 ${result.status.noResponse}\n`;
    return { stdout: line, [`/results/${LABEL}.json`]: JSON.stringify(result, null, 2) };
}

function expectOk(response, what) {
    if (response.status !== 200 && response.status !== 201) {
        throw new Error(`준비 실패(${what}): HTTP ${response.status} ${response.body}`);
    }
}

/** 접두 8자리 + 일련번호로 겹치지 않는 UUID를 만든다(회차마다 빈 DB라 같은 번호를 다시 써도 됨) */
function uuid(prefix, n) {
    return `${prefix}-0000-4000-8000-${n.toString(16).padStart(12, '0')}`;
}

function seconds(text) {
    const match = /^(\d+)(ms|s|m)$/.exec(text);
    if (!match) {
        throw new Error(`시간 형식 오류: ${text}`);
    }
    const value = Number(match[1]);
    return match[2] === 'ms' ? value / 1000 : match[2] === 'm' ? value * 60 : value;
}

function round(value) {
    return value === undefined ? null : Math.round(value * 100) / 100;
}
