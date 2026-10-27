// Open-loop fixed-arrival workload for FastFrame / Micronaut / Laravel Octane.
// All configuration is supplied by target_rps.py, not hardcoded per framework.
import http from 'k6/http';
import { Counter } from 'k6/metrics';

const base = __ENV.BENCH_BASE_URL;
const testCase = __ENV.BENCH_CASE;
const summaryPath = __ENV.BENCH_SUMMARY_PATH;
const target = Number(__ENV.BENCH_TARGET_RPS);
const vus = Number(__ENV.BENCH_VUS);
const maxVus = Number(__ENV.BENCH_MAX_VUS);
const duration = __ENV.BENCH_DURATION;

if (!base || !summaryPath || !['plain', 'json', 'route', 'echo'].includes(testCase) ||
    !Number.isInteger(target) || target < 1 || !Number.isInteger(vus) || vus < 1 ||
    !Number.isInteger(maxVus) || maxVus < vus || !/^\d+(?:\.\d+)?s$/.test(duration)) {
  throw new Error('Invalid BENCH_* environment configuration');
}

const endpoints = {
  plain: '/bench/plain',
  json: '/bench/json',
  route: '/bench/route/123',
  echo: '/bench/echo',
};
const invalidResponses = new Counter('invalid_responses');

export const options = {
  scenarios: {
    arrival: {
      executor: 'constant-arrival-rate',
      rate: target,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: vus,
      maxVUs: maxVus,
      gracefulStop: '5s',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)'],
  discardResponseBodies: true,
  noConnectionReuse: false,
};

export default function () {
  const url = base + endpoints[testCase];
  let result;
  if (testCase === 'echo') {
    result = http.post(url, '{"message":"hello"}', {
      headers: { 'Content-Type': 'application/json' }, timeout: '15s',
    });
  } else {
    result = http.get(url, { timeout: '15s' });
  }
  if (result.status !== 200) invalidResponses.add(1);
}

export function handleSummary(data) {
  // Emit our own stable, versioned schema instead of scraping terminal output.
  const values = (key) => (data.metrics && data.metrics[key] && data.metrics[key].values) || {};
  const requests = values('http_reqs');
  const latency = values('http_req_duration');
  const drops = values('dropped_iterations');
  const invalid = values('invalid_responses');
  const failed = values('http_req_failed');
  const result = {
    schema: 1,
    requests: requests.count ?? null,
    k6_reported_rps: requests.rate ?? null,
    dropped_iterations: drops.count ?? 0,
    invalid_responses: invalid.count ?? 0,
    http_req_failed_rate: failed.rate ?? 0,
    p50_ms: latency.med ?? null,
    p95_ms: latency['p(95)'] ?? null,
    p99_ms: latency['p(99)'] ?? null,
    mean_ms: latency.avg ?? null,
  };
  return { [summaryPath]: JSON.stringify(result, null, 2), stdout: '' };
}
