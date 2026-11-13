// 성능 B: 로그인한 회원의 GET /api/v1/wallets/me. 개선 뒤에도 I01을 거치는 업무 API 경로가 나빠지지 않았는지 봅니다.
// Wallet 코드는 바꾸지 않고, Gateway → I01 → Wallet 경로의 기준으로만 씁니다.
import http from 'k6/http';
import { check } from 'k6';
import {
  GATEWAY, SUMMARY_TREND_STATS, SYSTEM_TAGS, currentUser, measurement, parseBody, requireUsers, sessionHeaders,
  summaryFor,
} from './common.js';

const vus = Number(__ENV.VUS || '10');
const users = requireUsers(vus, true);
const result = measurement('wallet');

export const options = {
  scenarios: {
    wallet_me: { executor: 'constant-vus', vus, duration: __ENV.DURATION || '60s', gracefulStop: '10s' },
  },
  thresholds: { wallet_failures: ['rate==0'], checks: ['rate==1'] },
  summaryTrendStats: SUMMARY_TREND_STATS,
  systemTags: SYSTEM_TAGS,
};

export default function () {
  const user = currentUser(users);
  const response = http.get(`${GATEWAY}/api/v1/wallets/me`, {
    headers: sessionHeaders(user), tags: { name: 'GET /api/v1/wallets/me' }, timeout: '10s',
  });
  const wallet = parseBody(response);
  // 다른 회원의 지갑이 오면 인증 전달이 잘못된 것이므로 실패로 셉니다.
  const valid = response.status === 200 && wallet?.success === true
    && wallet.data?.walletId === user.walletId && wallet.data?.status === 'ACTIVE';
  check(response, { '본인 지갑 200': () => valid });
  result.record(response, valid);
}

export function handleSummary(data) {
  return summaryFor('wallet', vus, data);
}
