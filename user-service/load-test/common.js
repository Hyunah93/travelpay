import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// 공개 주소를 입력받지 않습니다. 측정 요청은 이 Compose의 Gateway만 호출합니다.
export const GATEWAY = 'http://auth-gateway:8080';
export const STATUS_CODES = ['200', '401', '403', '409', '429', '500', '502', '503', '504', 'other'];

const users = JSON.parse(open('/scripts/data/sessions.json')).users;

/** VU마다 다른 회원·세션을 씁니다. 지갑이 필요한 시나리오는 prepare --wallets로 만든 지갑 ID까지 요구합니다. */
export function requireUsers(vus, needsWallet) {
  if (!Number.isInteger(vus) || vus < 1 || vus > users.length) {
    throw new Error('VU마다 다른 로그인 세션이 필요합니다. prepare를 먼저 실행하세요.');
  }
  if (needsWallet && users.some((user) => !user.walletId)) {
    throw new Error('지갑이 없는 시험 회원이 있습니다. 업무 구성을 띄운 뒤 prepare --wallets를 실행하세요.');
  }
  return users;
}

export function sessionHeaders(user) {
  return { Cookie: `SESSION=${user.session}`, Accept: 'application/json' };
}

export function parseBody(response) {
  try {
    return response.json();
  } catch (_) {
    return undefined; // 잘못된 응답은 호출한 쪽의 판정에서 실패로 셉니다.
  }
}

/** 한 시나리오의 요청·성공·지연·상태 코드 카운터. 빠른 오류 응답이나 타인의 응답을 정상 성능으로 세지 않습니다. */
export function measurement(prefix) {
  const statuses = {};
  for (const code of STATUS_CODES) {
    statuses[code] = new Counter(`${prefix}_status_${code}`);
  }
  const metrics = {
    requests: new Counter(`${prefix}_requests`),
    successes: new Counter(`${prefix}_successes`),
    failures: new Rate(`${prefix}_failures`),
    latency: new Trend(`${prefix}_latency`, true),
  };
  return {
    record(response, valid) {
      metrics.requests.add(1);
      metrics.successes.add(valid ? 1 : 0);
      metrics.failures.add(!valid);
      metrics.latency.add(response.timings.duration);
      const code = String(response.status);
      statuses[STATUS_CODES.includes(code) ? code : 'other'].add(1);
    },
  };
}

export function currentUser(list) {
  return list[exec.vu.idInTest - 1];
}

/** 측정기가 읽는 요약 JSON. 형식은 시나리오와 관계없이 같습니다. */
export function summaryFor(prefix, vus, data) {
  const metric = (name) => data.metrics[name]?.values || {};
  const report = {
    vus,
    duration: __ENV.DURATION || '60s',
    requests: metric(`${prefix}_requests`).count || 0,
    successes: metric(`${prefix}_successes`).count || 0,
    failures: metric(`${prefix}_failures`).passes || 0,
    failureRate: metric(`${prefix}_failures`).rate || 0,
    successfulRps: metric(`${prefix}_successes`).rate || 0,
    latencyMs: metric(`${prefix}_latency`),
    statusCounts: Object.fromEntries(STATUS_CODES.map(
      (code) => [code, metric(`${prefix}_status_${code}`).count || 0])),
    // 셋업 요청은 없습니다. 로그인·CSRF·지갑 개설은 별도 prepare 명령에서 끝냅니다.
    checks: metric('checks'),
    httpFailures: metric('http_req_failed'),
  };
  return {
    [__ENV.SUMMARY_PATH || '/results/summary.json']: JSON.stringify(report, null, 2),
    stdout: `${JSON.stringify(report)}\n`,
  };
}

export const SUMMARY_TREND_STATS = ['avg', 'med', 'p(95)', 'p(99)', 'max'];
export const SYSTEM_TAGS = ['status', 'method', 'name', 'scenario', 'expected_response'];
