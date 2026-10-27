"""Unit / orchestration tests for the v0.22 concurrency benchmark."""
import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import concurrency
from concurrency_report import aggregate, make_reports, ratios, svg_plot


def sample(framework='fastframe', case='json', level=16, repeat=1,
           rps=8000, p99=3, errors=0):
    return {'framework': framework, 'case': case, 'concurrency': level,
            'repeat': repeat, 'port': 18080, 'requests': 100,
            'ok_2xx': 100 if not errors else 99, 'errors': errors,
            'duration_s': 0.2, 'rps': rps, 'p50_ms': 1,
            'p95_ms': 2, 'p99_ms': p99, 'mean_ms': 1.1,
            'rss_baseline_mb': 64 if 'laravel' not in framework else None,
            'rss_peak_mb': 80 if 'laravel' not in framework else None,
            'cpu_cores_avg': 0.5 if 'laravel' not in framework else None}


class ConcurrencySuiteTest(unittest.TestCase):
    def test_default_is_1_to_1024_30_seconds(self):
        args = concurrency.make_parser().parse_args([])
        self.assertEqual(args.frameworks, ['fastframe', 'micronaut', 'laravel'])
        self.assertEqual(args.duration, '30s')
        self.assertEqual(args.levels, list(concurrency.DEFAULT_LEVELS))
        self.assertEqual(len(args.cases), 4)

    def test_levels_and_duration_validation(self):
        for levels in ([2, 2], [16, 8], [0, 4], [5, 99999], []):
            with self.subTest(levels=levels), self.assertRaises(ValueError):
                concurrency.positive_levels(levels)
        self.assertEqual(concurrency.positive_levels([1, 1024]), [1, 1024])
        for value in ('-1s', '30', 'test', '0s', '10sec'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                concurrency.validate_duration(value)
        self.assertTrue(concurrency.is_zero_duration(concurrency.validate_duration('0s', True)))

    def test_aggregate_valid_median_and_comparison(self):
        entries = [sample(repeat=1, rps=10000), sample(repeat=2, rps=14000),
                   sample('micronaut', repeat=1, rps=6000),
                   sample('micronaut', repeat=2, rps=6000)]
        result = aggregate(entries, 2)
        ff = next(r for r in result if r['framework'] == 'fastframe')
        self.assertEqual(ff['median_rps'], 12000)
        self.assertEqual(ff['n'], 2)
        self.assertTrue(ff['valid'])
        self.assertEqual(ratios(result, 'json', 16), [('micronaut', 2)])

    def test_missing_repetition_and_http_error_excluded(self):
        entries = [sample(repeat=1), sample('micronaut', repeat=1),
                   sample('micronaut', repeat=2, errors=1),
                   sample('laravel-octane', repeat=1, rps=1000),
                   sample('laravel-octane', repeat=1, rps=1500)]
        result = aggregate(entries, 2)
        self.assertFalse(any(r['valid'] for r in result))
        self.assertEqual(ratios(result, 'json', 16), [])

    def test_reports_html_and_csv_use_real_input_only(self):
        metadata = {'timestamp_utc': '2026-10-27T00:00Z', 'platform': 'macOS',
                    'duration': '30s', 'warmup': '5s', 'repeats': 1,
                    'frameworks': ['fastframe','micronaut','laravel'],
                    'laravel_server': 'octane', 'cases': ['json'], 'levels': [16,32]}
        rows = [sample(), sample('micronaut', rps=9000),
                sample('laravel-octane', rps=3000)]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            summary = make_reports(rows, metadata, path)
            self.assertEqual(len(summary), 3)
            html = (path / 'report.html').read_text(encoding="utf-8")
            md = (path / 'report.md').read_text(encoding="utf-8")
            self.assertIn('<svg', html)
            self.assertIn('laravel-octane', html)
            self.assertIn('0 | incomplete', md)
            self.assertIn('2.667×', md)
            self.assertNotIn('NaN', html)
            with (path / 'concurrency-summary.csv').open() as infile:
                self.assertEqual(len(list(csv.DictReader(infile))), 3)
            self.assertTrue(json.loads((path / 'concurrency-summary.json').read_text(encoding="utf-8")))

    def test_plot_html_escapes_user_controlled_values(self):
        bad = sample(framework='<script>alert(1)</script>')
        agg = aggregate([bad], 1)
        svg = svg_plot(agg, 'json', 'median_rps', [bad['framework']], [16], 'Test')
        self.assertNotIn('<script>', svg)
        self.assertIn('&lt;script&gt;', svg)

    def test_empty_report_is_explicit(self):
        metadata = {'repeats': 1, 'frameworks': ['fastframe'], 'cases': ['plain'], 'levels': [1]}
        with tempfile.TemporaryDirectory() as temp:
            make_reports([], metadata, Path(temp))
            self.assertIn('No valid completed', (Path(temp)/'report.html').read_text(encoding="utf-8"))
            self.assertIn('incomplete', (Path(temp)/'report.md').read_text(encoding="utf-8"))

    def test_server_run_records_all_levels_and_always_stops(self):
        args = SimpleNamespace(port=18080, laravel_server='octane', laravel_workers=4,
                               levels=[1,16,1024], cases=['plain'], duration='1s',
                               warmup='0s', metrics_backend='proc', keep_raw=False,
                               heap='256m')
        rows = []
        fake_proc = Mock(pid=42)
        fake_proc.poll.return_value = None
        stats = dict(sample())
        for key in ('framework','case','repeat','concurrency','port'):
            stats.pop(key)
        with tempfile.TemporaryDirectory() as tmp, \
             patch.object(concurrency, 'select_server_port', return_value=18080), \
             patch.object(concurrency, 'start_server', return_value=fake_proc), \
             patch.object(concurrency, 'stop_server') as stop, \
             patch.object(concurrency, 'wait_until_ready'), \
             patch.object(concurrency, 'assert_endpoint'), \
             patch.object(concurrency, 'process_metrics', return_value=(25, 1)), \
             patch.object(concurrency, 'run_load', return_value=stats) as load:
            concurrency.run_framework('fastframe', 1, args, Path(tmp), rows)
            self.assertEqual([r['concurrency'] for r in rows], [1, 16, 1024])
            self.assertEqual(load.call_count, 3)
            self.assertEqual(stop.call_count, 1)
            self.assertTrue((Path(tmp)/'concurrency-results.csv').exists())
            with (Path(tmp)/'concurrency-results.csv').open() as saved:
                self.assertEqual(len(list(csv.DictReader(saved))), 3)

    def test_cli_full_orchestration_checkpoints_and_report(self):
        launches = []
        def fake_run(framework, repeat, args, output, rows):
            launches.append((framework, repeat))
            for level in args.levels:
                data = sample(framework=('laravel-octane' if framework == 'laravel' else framework),
                              case='plain', level=level, repeat=repeat, rps=1200+level)
                rows.append(data)
                concurrency.write_rows(output, rows)
        with tempfile.TemporaryDirectory() as temp, \
             patch.object(concurrency, 'metrics_backend', return_value='proc'), \
             patch.object(concurrency.shutil, 'which', return_value='/usr/bin/fake'), \
             patch.object(concurrency, 'require_installed'), \
             patch.object(concurrency, 'run_framework', side_effect=fake_run), \
             patch.object(concurrency, 'configuration', return_value={
                 'repeats': 2, 'cases': ['plain'], 'frameworks': ['fastframe','micronaut','laravel'],
                 'levels': [1,16], 'duration': '1s', 'warmup': '0s',
                 'laravel_server': 'octane'}):
            status = concurrency.main(['--frameworks','fastframe','micronaut','laravel',
                                       '--cases','plain','--levels','1','16',
                                       '--duration','1s','--warmup','0s','--repeats','2',
                                       '--output',temp])
            self.assertEqual(status, 0)
            self.assertEqual(launches, [('fastframe',1),('micronaut',1),('laravel',1),
                                        ('laravel',2),('micronaut',2),('fastframe',2)])
            directory = next(Path(temp).iterdir())
            with (directory/'concurrency-results.csv').open() as fp:
                self.assertEqual(len(list(csv.DictReader(fp))), 12)
            self.assertTrue((directory/'report.html').exists())
            with (directory/'concurrency-summary.csv').open() as fp:
                group_rows = list(csv.DictReader(fp))
            self.assertEqual(len(group_rows), 6)
            self.assertTrue(all(row['valid'] == 'True' for row in group_rows))

    def test_failure_still_stops_server(self):
        args = SimpleNamespace(port=18080, laravel_server='octane', laravel_workers=4,
                               levels=[1], cases=['plain'], duration='1s', warmup='0s',
                               metrics_backend='proc', keep_raw=False, heap='256m')
        with tempfile.TemporaryDirectory() as tmp, \
             patch.object(concurrency, 'select_server_port', return_value=18080), \
             patch.object(concurrency, 'start_server', return_value=Mock(pid=8)) as start, \
             patch.object(concurrency, 'stop_server') as stop, \
             patch.object(concurrency, 'wait_until_ready', side_effect=RuntimeError('fail')):
            with self.assertRaisesRegex(RuntimeError, 'fail'):
                concurrency.run_framework('fastframe', 1, args, Path(tmp), [])
            self.assertEqual(start.call_count, 1)
            self.assertEqual(stop.call_count, 1)


if __name__ == '__main__':
    unittest.main()
