// 성능 A: 로그인한 회원의 GET /api/v1/users/me. User 보호 API의 중복 인증(I01) 개선 효과를 봅니다.
import http from 'k6/http';
import { check } from 'k6';
import {
  GATEWAY, SUMMARY_TREND_STATS, SYSTEM_TAGS, currentUser, measurement, parseBody, requireUsers, sessionHeaders,
  summaryFor,
} from './common.js';

const vus = Number(__ENV.VUS || '10');
const users = requireUsers(vus, false);
const result = measurement('me');

export const options = {
  scenarios: {
    my_info: { executor: 'constant-vus', vus, duration: __ENV.DURATION || '60s', gracefulStop: '10s' },
  },
  thresholds: { me_failures: ['rate==0'], checks: ['rate==1'] },
  summaryTrendStats: SUMMARY_TREND_STATS,
  systemTags: SYSTEM_TAGS,
};

export default function () {
  const user = currentUser(users);
  const response = http.get(`${GATEWAY}/api/v1/users/me`, {
    headers: sessionHeaders(user), tags: { name: 'GET /api/v1/users/me' }, timeout: '10s',
  });
  const profile = parseBody(response);
  const valid = response.status === 200 && profile?.success === true
    && profile.data?.userId === user.userId && profile.data?.loginEmail === user.email
    && profile.data?.status === 'ACTIVE' && profile.data?.role === 'USER';
  check(response, { '본인 프로필 200': () => valid });
  result.record(response, valid);
}

export function handleSummary(data) {
  return summaryFor('me', vus, data);
}
