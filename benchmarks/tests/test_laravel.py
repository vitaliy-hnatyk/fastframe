import csv
import socket
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run import (CASES, LARAVEL_ROOT, check_port_available, require_installed,
                 result_framework, server_command)
from summarize import summarize
from setup_laravel import TEMPLATE


class LaravelBenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.settings = SimpleNamespace(laravel_server='octane', laravel_workers=4,
                                        port=18080, heap='256m')

    def test_octane_and_artisan_commands_are_explicit(self):
        self.assertEqual(server_command('laravel', self.settings),
                         ['php', 'artisan', 'octane:start', '--server=frankenphp',
                          '--host=127.0.0.1', '--port=18080', '--workers=4'])
        self.assertEqual(result_framework('laravel', self.settings), 'laravel-octane')
        self.settings.laravel_server = 'artisan'
        self.assertEqual(server_command('laravel', self.settings),
                         ['php', 'artisan', 'serve', '--host=127.0.0.1',
                          '--port=18080', '--no-reload'])
        self.assertEqual(result_framework('laravel', self.settings), 'laravel-artisan')

    def test_php_source_matches_all_endpoints(self):
        source = (TEMPLATE / 'routes/bench.php').read_text()
        self.assertIn("$request->input('message')", source)
        for _, path, _, _ in CASES.values():
            if '/bench/route/' in path:
                continue
            self.assertIn(path, source)
        self.assertIn('/bench/route/{id}', source)
        bootstrap = (TEMPLATE / 'bootstrap/app.php').read_text()
        self.assertIn("base_path('routes/bench.php')", bootstrap)
        self.assertIn('withRouting(', bootstrap)

    def test_laravel_requires_real_installed_framework(self):
        self.settings.laravel_server = 'octane'
        with patch('run.LARAVEL_ROOT', Path('/nonexistent/laravel-benchmark-runtime')):
            with self.assertRaisesRegex(ValueError, 'not installed'):
                require_installed('laravel', self.settings)

    def test_busy_port_is_rejected(self):
        with socket.socket() as connection:
            connection.bind(('127.0.0.1', 0))
            connection.listen(1)
            with self.assertRaises(OSError):
                check_port_available(connection.getsockname()[1])

    def test_three_framework_summary_and_missing_php_metrics(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'results.csv'
            fields = ['case', 'framework', 'rps', 'p95_ms', 'p99_ms', 'rss_peak_mb', 'errors']
            with path.open('w', newline='') as out:
                writer = csv.DictWriter(out, fieldnames=fields)
                writer.writeheader()
                for framework, rps, rss in [('fastframe', 40000, '90'),
                                              ('micronaut', 39500, '105'),
                                              ('laravel-octane', 32000, '')]:
                    writer.writerow(dict(case='json', framework=framework, rps=rps,
                                         p95_ms=2, p99_ms=3, rss_peak_mb=rss, errors=0))
            report = '\n'.join(summarize(path))
            self.assertIn('laravel-octane', report)
            self.assertIn('RPS FastFrame / micronaut: 1.013x', report)
            self.assertIn('RPS FastFrame / laravel-octane: 1.250x', report)
            self.assertIn('n/a', report)


if __name__ == '__main__':
    unittest.main()
