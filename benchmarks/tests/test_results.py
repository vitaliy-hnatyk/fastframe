import csv
import os
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run import csv_statistics, percentile, parse_ps_cpu_time, ps_metrics, windows_metrics, metrics_backend, process_metrics, run_load, benchmark_jars


class ResultsTest(unittest.TestCase):
    def test_benchmark_jars_track_pom_versions(self):
        jars = benchmark_jars()
        self.assertEqual(jars['fastframe'].name, 'benchmark-fastframe-0.21.0-SNAPSHOT.jar')
        self.assertEqual(jars['micronaut'].name, 'benchmark-micronaut-0.9.0.jar')

    def test_percentile_interpolation(self):
        self.assertEqual(percentile([1.0, 2.0, 3.0, 4.0, 5.0], .95), 4.8)
        self.assertEqual(percentile([2.0], .99), 2.0)

    def test_csv_parser_and_error_detection(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'hey.csv'
            with path.open('w', newline='') as output:
                writer = csv.writer(output)
                writer.writerow(['response-time', 'dns+dialup', 'dns', 'request-write', 'response-delay',
                                 'response-read', 'status-code', 'offset'])
                writer.writerow(['0.001', 0, 0, 0, 0, 0, 200, 0])
                writer.writerow(['0.004', 0, 0, 0, 0, 0, 503, 1])
            result = csv_statistics(path)
            self.assertEqual(result['requests'], 2)
            self.assertEqual(result['ok_2xx'], 1)
            self.assertEqual(result['errors'], 1)
            self.assertAlmostEqual(result['p50_ms'], 2.5)

    def test_ps_cpu_time_formats(self):
        self.assertAlmostEqual(parse_ps_cpu_time("00:01.25"), 1.25)
        self.assertAlmostEqual(parse_ps_cpu_time("01:02"), 62)
        self.assertAlmostEqual(parse_ps_cpu_time("01:02:03.45"), 3723.45)
        self.assertAlmostEqual(parse_ps_cpu_time("2-01:02:03.45"), 176523.45)
        with self.assertRaises(ValueError):
            parse_ps_cpu_time("not-a-time")

    @patch('run.subprocess.run')
    def test_ps_metrics_mac_output(self, run):
        run.return_value = SimpleNamespace(returncode=0, stdout="  65536  0:12.34\n")
        self.assertEqual(ps_metrics(123), (64.0, 12.34))
        self.assertEqual(run.call_args.args[0],
                         ['ps', '-p', '123', '-o', 'rss=', '-o', 'time='])
        run.return_value = SimpleNamespace(returncode=1, stdout="")
        self.assertEqual(ps_metrics(123), (None, None))

    @patch('run.sys.platform', 'darwin')
    @patch('run.shutil.which', return_value='/bin/ps')
    def test_macos_uses_ps(self, which):
        self.assertEqual(metrics_backend(), 'ps')

    @patch('run.sys.platform', 'win32')
    def test_windows_backend_selected(self):
        self.assertEqual(metrics_backend(), 'windows')

    @patch('run.windows_metrics', return_value=(32.5, 7.25))
    def test_windows_backend_dispatch(self, sampler):
        self.assertEqual(process_metrics(991, 'windows'), (32.5, 7.25))
        sampler.assert_called_once_with(991)

    @unittest.skipUnless(sys.platform == 'win32', 'Native Windows API only')
    def test_native_windows_current_process_metrics(self):
        rss, cpu = windows_metrics(os.getpid())
        self.assertIsNotNone(rss, 'Cannot read working set from WinAPI')
        self.assertGreater(rss, 0)
        self.assertIsNotNone(cpu, 'Cannot read process CPU time from WinAPI')
        self.assertGreaterEqual(cpu, 0)

    @unittest.skipUnless(sys.platform == 'win32', 'Native Windows API only')
    def test_native_windows_metrics_on_child_process(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(5)'])
        try:
            rss, cpu = windows_metrics(child.pid)
            self.assertIsNotNone(rss)
            self.assertGreater(rss, 0)
            self.assertIsNotNone(cpu)
        finally:
            child.terminate()
            child.wait(timeout=5)

    def test_ps_real_current_process(self):
        import os
        import shutil
        if not shutil.which('ps'):
            self.skipTest('ps is unavailable')
        rss, cpu = process_metrics(os.getpid(), 'ps')
        self.assertIsNotNone(rss)
        self.assertGreater(rss, 0)
        self.assertIsNotNone(cpu)
        self.assertGreaterEqual(cpu, 0)

    def test_ps_full_metrics_sampling_with_local_process(self):
        """Exercise the ps backend while a load-like command emits hey CSV."""
        import shutil
        if not shutil.which('ps'):
            self.skipTest('ps is unavailable')
        with tempfile.TemporaryDirectory() as tempdir:
            server = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(4)'])
            try:
                baseline, _ = ps_metrics(server.pid)
                self.assertIsNotNone(baseline)
                code = (
                    'import time; time.sleep(0.35); '
                    'print("response-time,status-code"); print("0.002,200"); print("0.005,200")'
                )
                result = run_load(
                    server.pid, [sys.executable, '-c', code],
                    Path(tempdir) / 'load.csv', baseline, 'ps'
                )
                self.assertEqual(result['requests'], 2)
                self.assertEqual(result['errors'], 0)
                self.assertGreater(result['rss_peak_mb'], 0)
                self.assertIsNotNone(result['cpu_cores_avg'])
                self.assertGreaterEqual(result['cpu_cores_avg'], 0)
            finally:
                server.terminate()
                server.wait(timeout=5)

    def test_empty_sample_fails(self):
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / 'hey.csv'
            path.write_text('response-time,status-code\n')
            with self.assertRaises(ValueError):
                csv_statistics(path)


if __name__ == '__main__':
    unittest.main()
