"""Regression: the k6 fixed-rate workload must initialize with real BENCH_* values.

An ordinary JS syntax check cannot detect wrong-case unbound identifiers such as
`maxVUs` versus `maxVus`. Execute the script's top-level code without the k6 runtime
using a minimal Node mock, and assert options + summary behavior.
"""
from __future__ import annotations

import re
import shutil
import subprocess
import unittest
from pathlib import Path

WORKLOAD = Path(__file__).resolve().parents[1] / 'target_rps.js'


class K6WorkloadTest(unittest.TestCase):
    def test_max_vus_matches_defined_configuration(self):
        text = WORKLOAD.read_text(encoding='utf-8')
        self.assertRegex(text, r'const\s+maxVus\s*=\s*Number\(__ENV\.BENCH_MAX_VUS\)')
        self.assertRegex(text, r'maxVUs\s*:\s*maxVus\s*,')

    @unittest.skipUnless(shutil.which('node'), 'Node.js is needed for runtime k6 JS validation')
    def test_js_initializes_and_runs_with_mock_http(self):
        code = WORKLOAD.read_text(encoding='utf-8')
        code, count = re.subn(r'^import\s+.*?;\s*$', '', code, flags=re.MULTILINE)
        self.assertEqual(count, 2, 'Unexpected import layout: update the test')
        self.assertIn('export const options =', code)
        code = code.replace('export const options =', 'const options =')
        code = code.replace('export default function ()', 'function doRequest ()')
        code = code.replace('export function handleSummary(', 'function handleSummary(')
        stub = r'''
'use strict';
const assert = require('node:assert/strict');
const __ENV = {
  BENCH_BASE_URL: 'http://127.0.0.1:18080',
  BENCH_CASE: 'echo',
  BENCH_SUMMARY_PATH: '/tmp/k6-summary.json',
  BENCH_TARGET_RPS: '500',
  BENCH_VUS: '8',
  BENCH_MAX_VUS: '16',
  BENCH_DURATION: '5s'
};
let requestCount = 0;
const http = {
  get: () => { requestCount++; return {status: 200}; },
  post: () => { requestCount++; return {status: 200}; }
};
function Counter() { return {add: () => {}}; }
'''
        assertions = r'''
assert.equal(options.scenarios.arrival.executor, 'constant-arrival-rate');
assert.equal(options.scenarios.arrival.rate, 500);
assert.equal(options.scenarios.arrival.preAllocatedVUs, 8);
assert.equal(options.scenarios.arrival.maxVUs, 16);
assert.equal(options.scenarios.arrival.duration, '5s');
doRequest();
assert.equal(requestCount, 1);
const output = handleSummary({metrics: {
  http_reqs: {values: {count: 2500, rate: 500}},
  http_req_duration: {values: {avg: 2, med: 1, 'p(95)': 3, 'p(99)': 4}},
  invalid_responses: {values: {count: 0}}
}});
const summary = JSON.parse(output['/tmp/k6-summary.json']);
assert.equal(summary.requests, 2500);
assert.equal(summary.p99_ms, 4);
assert.equal(summary.dropped_iterations, 0);
'''
        result = subprocess.run(['node', '-e', stub + code + assertions],
                                capture_output=True, text=True, timeout=15, check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
