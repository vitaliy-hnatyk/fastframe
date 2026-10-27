"""Do not discard HTTP status breakdown or Micronaut error evidence on a failed DB run."""
import argparse
import csv
import gzip
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import db_redis


class DatabaseRedisDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.raw = self.folder / 'raw-micronaut-insert-r1.csv'
        self.log = self.folder / 'micronaut-repeat-1.log'
        self.log.write_text('Micronaut started\nERROR: sample database exception\n', encoding='utf-8')

    def write_rows(self, entries):
        with self.raw.open('w', newline='', encoding='utf-8') as out:
            writer = csv.DictWriter(out, fieldnames=['response-time', 'status-code'])
            writer.writeheader()
            for latency, status in entries:
                writer.writerow({'response-time': latency, 'status-code': status})

    def test_http_status_counts_not_just_total_errors(self):
        self.write_rows([('0.001', 200), ('0.002', 500), ('0.005', 429),
                         ('0.008', 0), ('0.010', 200), ('', 500)])
        self.assertEqual(db_redis.response_status_counts(self.raw),
                         {'0': 1, '200': 2, '429': 1, '500': 1})

    def test_missing_raw_csv_does_not_hide_server_log(self):
        self.assertEqual(db_redis.response_status_counts(self.raw), {})
        file = db_redis.save_failure_diagnostics(
            self.folder, 'micronaut', 'insert', 1, self.raw, self.log,
            {'requests': 10, 'ok_2xx': 5, 'errors': 5}, db_observed='8',
            cause='Side effect mismatch')
        details = json.loads(file.read_text(encoding='utf-8'))
        self.assertEqual(details['http_status_counts'], {})
        self.assertEqual(details['db_observed'], '8')
        self.assertIn('sample database exception', details['server_log_tail'])

    def test_failed_raw_is_compressed_without_losing_statuses(self):
        self.write_rows([('0.001', 200), ('0.001', 503)])
        destination = db_redis.preserve_failed_load(self.raw, keep_raw=False)
        self.assertTrue(destination.name.endswith('.csv.gz'))
        self.assertFalse(self.raw.exists())
        with gzip.open(destination, 'rt', encoding='utf-8') as source:
            self.assertIn('503', source.read())

    def test_keep_raw_preserves_exact_csv(self):
        self.write_rows([('0.001', 429)])
        self.assertEqual(db_redis.preserve_failed_load(self.raw, keep_raw=True), self.raw)
        self.assertTrue(self.raw.exists())

    def test_invalid_write_run_persists_csv_logs_and_stops_process(self):
        class FakeProcess:
            pid = 888
            def poll(self): return None
        args = argparse.Namespace(port=18080, concurrency=8, warmup='0s', duration='2s',
                                  laravel_server='octane', laravel_workers=4,
                                  db_pool=16, io_workers=32, heap='512m', keep_raw=False,
                                  metrics_backend='proc', cases=['insert'])
        rows = []
        def load(_pid, _command, path, *_args, **_kwargs):
            self.raw = path
            self.write_rows([('0.001', 200), ('0.002', 500), ('0.003', 0)])
            return {'requests': 3, 'ok_2xx': 1, 'errors': 2, 'rps': 15.0,
                    'duration_s': .2, 'p50_ms': 1, 'p95_ms': 2, 'p99_ms': 3,
                    'mean_ms': 1.3, 'rss_baseline_mb': None,
                    'rss_peak_mb': None, 'cpu_cores_avg': None}
        with (mock.patch.object(db_redis, 'select_server_port', return_value=18080),
              mock.patch.object(db_redis, 'server_command', return_value=['fake']),
              mock.patch.object(db_redis, 'start_server', return_value=FakeProcess()),
              mock.patch.object(db_redis, 'stop_server') as stop,
              mock.patch.object(db_redis, 'wait_until_ready'),
              mock.patch.object(db_redis, 'reset_state'),
              mock.patch.object(db_redis, 'verify_response'),
              mock.patch.object(db_redis, 'side_effect', side_effect=[(True, '1', None), (False, '3', None)]),
              mock.patch.object(db_redis, 'process_metrics', return_value=(None, None)),
              mock.patch.object(db_redis, 'run_load', side_effect=load)):
            with self.assertRaisesRegex(RuntimeError, 'HTTP errors=2'):
                db_redis.run_framework('micronaut', 1, args, self.folder, rows)
        stop.assert_called_once()
        self.assertEqual(rows[0]['errors'], 2)
        self.assertEqual(json.loads(rows[0]['status_counts']), {'0': 1, '200': 1, '500': 1})
        self.assertEqual(json.loads((self.folder / 'diagnostics-micronaut-insert-r1.json')
                                    .read_text(encoding='utf-8'))['db_observed'], '3')
        self.assertTrue((self.folder / 'raw-micronaut-insert-r1.csv.gz').exists())
        self.assertFalse((self.folder / 'raw-micronaut-insert-r1.csv').exists())

    def test_ci_artifacts_are_uploaded_on_failed_smoke(self):
        workflow = (db_redis.ROOT / '.github/workflows/ci.yml').read_text(encoding='utf-8')
        self.assertIn('Upload Database/Redis benchmark diagnostics (also on failure)', workflow)
        self.assertIn('if: always()', workflow)
        self.assertIn('diagnostics-*.json', workflow)
        self.assertIn('raw-*.csv.gz', workflow)


if __name__ == '__main__':
    unittest.main()
