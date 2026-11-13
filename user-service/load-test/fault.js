// 장애 C: User API와 업무 API에 일정한 도착률로 요청을 보내는 동안 measure.py가 장애를 넣고 풉니다.
// 응답이 멈춰도 도착률이 줄지 않도록 constant-arrival-rate를 씁니다(VU가 응답을 기다리느라 요청을 덜 보내는 착시 방지).
// 요청별 시각·지연·상태는 --out csv로 남기고 measure.py가 초 단위로 집계합니다.
import http from 'k6/http';
import { GATEWAY, requireUsers, sessionHeaders } from './common.js';

const rate = Number(__ENV.RATE || '40');
const duration = __ENV.DURATION || '120s';
const users = requireUsers(1, true);

function scenario(exec) {
  // 응답이 최대 5초 멈추면 동시에 rate × 5개가 떠 있습니다. 장애 중에 VU를 새로 만드느라 요청을 못 보내지 않도록
  // rate × 6개를 미리 만들어 둡니다. 그래도 부족하면 dropped_iterations(손실 요청)로 드러납니다.
  return {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, exec,
    preAllocatedVUs: rate * 6, maxVUs: rate * 10, gracefulStop: '40s',
  };
}

export const options = {
  scenarios: { my_info: scenario('myInfo'), wallet_me: scenario('walletMe') },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // error_code: 연결 끊김·응답 도중 중단 같은 전송 오류를 상태 코드와 따로 남깁니다.
  systemTags: ['status', 'name', 'scenario', 'error_code'],
};

// 동시 VU가 회원 수보다 많을 수 있어 세션을 돌려 씁니다. 같은 세션의 동시 사용은 장애 판정에 영향이 없습니다.
// 본인 응답 여부는 성능 A·B가 검사하므로, 여기서는 상태 코드와 시각만 남깁니다(분석은 CSV의 status 태그로 함).
function userFor(iteration) {
  return users[iteration % users.length];
}

export function myInfo() {
  http.get(`${GATEWAY}/api/v1/users/me`, {
    headers: sessionHeaders(userFor(__ITER + __VU)), tags: { name: 'me' }, timeout: '30s',
  });
}

export function walletMe() {
  http.get(`${GATEWAY}/api/v1/wallets/me`, {
    headers: sessionHeaders(userFor(__ITER + __VU)), tags: { name: 'wallet' }, timeout: '30s',
  });
}

export function handleSummary(data) {
  const metric = (name) => data.metrics[name]?.values || {};
  const report = {
    rate,
    duration,
    httpRequests: metric('http_reqs').count || 0,
    droppedIterations: metric('dropped_iterations').count || 0,
    maxVus: metric('vus_max').max || 0,
  };
  return {
    [__ENV.SUMMARY_PATH || '/results/fault-summary.json']: JSON.stringify(report, null, 2),
    stdout: `${JSON.stringify(report)}\n`,
  };
}
