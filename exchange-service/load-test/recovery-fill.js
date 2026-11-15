// 원장 장애 동안 환전 신청을 정확히 ORDERS건 보내 PROCESSING 주문을 쌓습니다(#317). measure-recovery.sh가 실행합니다.
//
// 가짜 원장이 응답하지 않는 상태(시간 초과)에서 보냅니다. 처음 몇 초는 원장을 기다리다 결과 모름(202)이 되고,
// 서킷이 열린 뒤에는 원장을 부르지 않고 바로 202(PROCESSING)입니다. 모두 202여야 쌓인 건수가 ORDERS와 같습니다.
// 쌓는 속도는 측정 대상이 아니므로 건수가 정해진 실행 방식(shared-iterations)으로 가상 사용자 VUS명이 나눠 보냅니다.
//
// 환경 변수: ORDERS(기본 1000), VUS(50), TIMEOUT(10s), LABEL, BASE_URL

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const ORDERS = Number(__ENV.ORDERS || 1000);
const VUS = Number(__ENV.VUS || 50);
const TIMEOUT = __ENV.TIMEOUT || '10s';
const LABEL = __ENV.LABEL || `recovery-fill-${ORDERS}`;
const BASE_URL = __ENV.BASE_URL || 'http://app:18084';
/** wiremock/mappings/wallet-balances.json의 walletId와 같아야 합니다(가짜 지갑이 이 지갑을 돌려줌) */
const WALLET_ID = '33333333-3333-4333-8333-333333333333';
const QUOTE_BODY = JSON.stringify({ fromCurrency: 'KRW', toCurrency: 'JPY', amount: '100000' });

const accepted = new Counter('fill_202');
const completed = new Counter('fill_200');
const quoteFailed = new Counter('fill_quote_failed');
const other = new Counter('fill_other');

export const options = {
    scenarios: {
        fill: { executor: 'shared-iterations', vus: VUS, iterations: ORDERS, maxDuration: '10m' },
    },
};

export default function () {
    // 가상 사용자 번호 + 그 사용자의 반복 횟수로 겹치지 않는 번호를 만든다(멱등 키·회원 분산)
    const n = exec.vu.idInTest * 1_000_000 + exec.vu.iterationInScenario;
    const userId = `00000000-0000-4000-8000-${String(n % 100).padStart(12, '0')}`;
    const headers = { 'Content-Type': 'application/json', 'User-Id': userId, 'User-Role': 'USER' };
    const quote = http.post(`${BASE_URL}/api/v1/exchange-quotes`, QUOTE_BODY, { headers, timeout: TIMEOUT });
    if (quote.status !== 201) {
        quoteFailed.add(1);
        return;
    }
    const response = http.post(`${BASE_URL}/api/v1/exchanges`,
        JSON.stringify({ quoteId: quote.json('data.quoteId'), walletId: WALLET_ID }),
        { headers: { ...headers, 'Idempotency-Key': `recovery-${LABEL}-${n}` }, timeout: TIMEOUT });
    if (response.status === 202) accepted.add(1);
    else if (response.status === 200) completed.add(1);
    else other.add(1);
}

export function handleSummary(data) {
    const count = name => (data.metrics[name] ? data.metrics[name].values.count : 0);
    const result = {
        label: LABEL,
        orders: ORDERS,
        vus: VUS,
        testRunDurationMs: Math.round(data.state.testRunDurationMs),
        status: { accepted202: count('fill_202'), completed200: count('fill_200'), quoteFailed: count('fill_quote_failed'), other: count('fill_other') },
    };
    return {
        stdout: `[${LABEL}] 신청 ${ORDERS}건: 202 ${result.status.accepted202} / 200 ${result.status.completed200} / 견적 실패 ${result.status.quoteFailed} / 그 밖 ${result.status.other}, ${result.testRunDurationMs}ms\n`,
        [`/results/${LABEL}-fill.json`]: JSON.stringify(result, null, 2),
    };
}
