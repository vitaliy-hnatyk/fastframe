"""Cross-platform guards for Windows CI output and metrics behavior."""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from concurrency_report import make_reports
import run


class WindowsCICompatibilityTest(unittest.TestCase):
    def test_markdown_generated_as_utf8_with_multiplication_sign(self):
        meta = {'repeats': 1, 'frameworks': ['fastframe', 'laravel'],
                'cases': ['json'], 'levels': [16], 'duration': '30s'}
        def entry(framework, rps):
            return {'framework': framework, 'case': 'json', 'concurrency': 16,
                    'repeat': 1, 'requests': 10, 'ok_2xx': 10, 'errors': 0,
                    'rps': rps, 'p95_ms': 1.0, 'p99_ms': 2.0,
                    'rss_peak_mb': None}
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            make_reports([entry('fastframe', 8000), entry('laravel-octane', 3000)], meta, output)
            raw = (output / 'report.md').read_bytes()
            self.assertIn('2.667×'.encode('utf-8'), raw)
            self.assertIn('2.667×', raw.decode('utf-8'))

    def test_windows_native_backend_selected(self):
        with patch.object(run.sys, 'platform', 'win32'):
            self.assertEqual(run.metrics_backend(), 'windows')


if __name__ == '__main__':
    unittest.main()
