"""Target RPS: parser, k6 invocation, lifecycle, reporting, failure accounting."""
import argparse
import csv
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
import target_rps
import target_rps_report


def summary(requests=5950, drops=0, bad=0, failure_rate=0., p99=9.5):
    return {'schema': 1, 'requests': requests, 'k6_reported_rps': requests / 60.2,
            'dropped_iterations': drops, 'invalid_responses': bad,
            'http_req_failed_rate': failure_rate, 'p50_ms': 2.0, 'p95_ms': 5.0,
            'p99_ms': p99, 'mean_ms': 2.7}


def raw(label='fastframe', case='plain', rate=100, repeat=1, drops=0, bad=0,
        requests=6000, target_met=True, p99=5):
    return {'framework':label,'case':case,'target_rps':rate,'repeat':repeat,
            'requests':requests, 'actual_rps':requests/60,
            'delivery_pct':100*requests/60/rate, 'p95_ms':p99/2,'p99_ms':p99,
            'dropped_iterations':drops,'errors':bad,'target_met':target_met}


class ParseTest(unittest.TestCase):
    def test_default_levels(self):
        self.assertEqual(target_rps.make_parser().parse_args([]).duration, '60s')
        self.assertEqual(target_rps.make_parser().parse_args([]).frameworks,
                         ['fastframe','micronaut','laravel'])
        self.assertEqual(target_rps.DEFAULT_RATES[-1], 30000)

    def test_invalid_rates(self):
        for values in ([1,1], [100,10], [0], [1000001], []):
            with self.subTest(values=values), self.assertRaises(ValueError):
                target_rps.rates_valid(values)
        self.assertEqual(target_rps.rates_valid([1,200,1000]), [1,200,1000])

    def parse(self, value, rate=100):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)/'k6.json'
            target.write_text(json.dumps(value), encoding='utf-8')
            return target_rps.parse_summary(target, rate, '60s')

    def test_success(self):
        result = self.parse(summary(requests=6000))
        self.assertEqual(result['actual_rps'], 100)
        self.assertEqual(result['delivery_pct'], 100)
        self.assertTrue(result['target_met'])
        self.assertEqual(result['p99_ms'], 9.5)

    def test_delivery_shortfall_missed(self):
        result = self.parse(summary(requests=5000))
        self.assertFalse(result['target_met'])
        self.assertEqual(result['delivery_pct'], 83.333)

    def test_dropped_iterations_missed(self):
        self.assertFalse(self.parse(summary(requests=6000, drops=4))['target_met'])

    def test_http_errors_missed(self):
        self.assertFalse(self.parse(summary(requests=6000, bad=1))['target_met'])
        result = self.parse(summary(requests=6000, failure_rate=.005))
        self.assertEqual(result['errors'], 30)

    def test_untrustworthy_data_rejected(self):
        for value in ({'schema':2}, summary(requests=0), summary(requests=-100),
                      summary(requests=1.5), summary(drops=-1),
                      summary(failure_rate=1.1), summary(p99=float('nan'))):
            with self.subTest(value=value), self.assertRaises((ValueError,TypeError,KeyError)):
                self.parse(value)

    def test_k6_env_and_command(self):
        args = argparse.Namespace(duration='60s', vus=128, max_vus=512)
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)/'out.json'
            env = target_rps.k6_env('http://127.0.0.1:18080','echo',3000,args,path)
            self.assertEqual(env['BENCH_CASE'],'echo')
            self.assertEqual(env['BENCH_TARGET_RPS'],'3000')
            self.assertEqual(env['BENCH_SUMMARY_PATH'],str(path.resolve()))
            self.assertEqual(target_rps.k6_command()[:3], ['k6','run','--quiet'])

    def test_csv_recovery(self):
        with tempfile.TemporaryDirectory() as folder:
            directory = Path(folder)
            values = [{'framework':'fastframe', 'case':'plain', 'repeat':1,
                       'port':18080, 'target_rps':100, **self.parse(summary(requests=6000))}]
            target_rps.save_rows(directory,values)
            with (directory/'target-rps-results.csv').open(newline='') as f:
                self.assertEqual(next(csv.DictReader(f))['target_rps'],'100')
            self.assertEqual(json.loads((directory/'target-rps-results.json').read_text())[0]['requests'],6000)


class ReportTest(unittest.TestCase):
    def test_medians_and_target_missed_not_hidden(self):
        rows = [raw(repeat=1, requests=6000, p99=3), raw(repeat=2, requests=5900, p99=5),
                raw(label='micronaut',repeat=1,requests=4000,p99=12,
                    target_met=False,drops=20),
                raw(label='micronaut',repeat=2,requests=4600,p99=16,
                    target_met=False,drops=10)]
        records = target_rps_report.aggregate(rows,2)
        f = next(r for r in records if r['framework']=='fastframe')
        m = next(r for r in records if r['framework']=='micronaut')
        self.assertTrue(f['complete'])
        self.assertTrue(f['target_met'])
        self.assertEqual(f['median_p99_ms'],4)
        self.assertTrue(m['complete'])
        self.assertFalse(m['target_met'])
        self.assertEqual(m['median_dropped_iterations'],15)
        html = target_rps_report.html_report(records,
            {'cases':['plain'],'rates':[100], 'frameworks':['fastframe','micronaut'],
             'repeats':2,'platform':'macOS','duration':'60s'})
        self.assertIn('Hollow points',html)
        self.assertIn('missed',html)
        self.assertIn('micronaut',html)

    def test_missing_repeat_is_incomplete(self):
        records = target_rps_report.aggregate([raw(repeat=1)],2)
        self.assertFalse(records[0]['complete'])
        self.assertFalse(records[0]['target_met'])

    def test_generate_files_without_network(self):
        with tempfile.TemporaryDirectory() as folder:
            metadata = {'cases':['plain'], 'frameworks':['fastframe','laravel'],
                        'rates':[100,200], 'repeats':1, 'laravel_server':'octane',
                        'duration':'60s','platform':'test'}
            out = Path(folder)
            target_rps_report.make_reports([raw()],metadata,out)
            for name in ['report.html','report.md','target-rps-summary.csv','target-rps-summary.json']:
                self.assertTrue((out/name).exists(),name)
            self.assertIn('incomplete', (out/'report.md').read_text())


class LifecycleTest(unittest.TestCase):
    def test_framework_cleanup_and_output_fake_k6(self):
        """Fake server + injected k6 verifies full sequential control flow, no network."""
        class FakeServer:
            pid=12345
            def poll(self):return None
        fake=FakeServer()
        args=argparse.Namespace(port=18080,laravel_server='octane',laravel_workers=4,
                                heap='256m',max_vus=64,vus=32,cases=['plain'],rates=[100,300],
                                warmup='0s',duration='60s',keep_k6=False)
        def simulate_k6(cmd, env=None, **kwargs):
            path=Path(env['BENCH_SUMMARY_PATH'])
            path.write_text(json.dumps(summary(requests=int(env['BENCH_TARGET_RPS'])*60)),encoding='utf-8')
            return subprocess_result(0)
        def subprocess_result(code):
            return argparse.Namespace(returncode=code)
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(target_rps,'select_server_port',return_value=18080), \
             mock.patch.object(target_rps,'server_command',return_value=['fake']), \
             mock.patch.object(target_rps,'start_server',return_value=fake), \
             mock.patch.object(target_rps,'stop_server') as stop, \
             mock.patch.object(target_rps,'wait_until_ready'), \
             mock.patch.object(target_rps,'assert_endpoint'), \
             mock.patch.object(target_rps.subprocess,'run',side_effect=simulate_k6):
            rows=[]
            target_rps.run_framework('fastframe',1,args,Path(folder),rows)
            self.assertEqual([r['target_rps'] for r in rows],[100,300])
            self.assertTrue(all(r['target_met'] for r in rows))
            stop.assert_called_once_with(fake)
            self.assertEqual(len(list(Path(folder).glob('k6-*.json'))),0)

    def test_cleanup_on_k6_failure(self):
        class FakeServer:
            def poll(self):return None
        fake=FakeServer()
        args=argparse.Namespace(port=18080, laravel_server='octane',laravel_workers=4,heap='256m',
                                max_vus=64,vus=32,cases=['plain'],rates=[100],warmup='0s',
                                duration='60s',keep_k6=False)
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(target_rps,'select_server_port',return_value=18080), \
             mock.patch.object(target_rps,'server_command',return_value=['fake']), \
             mock.patch.object(target_rps,'start_server',return_value=fake), \
             mock.patch.object(target_rps,'stop_server') as stop, \
             mock.patch.object(target_rps,'wait_until_ready'), \
             mock.patch.object(target_rps,'assert_endpoint'), \
             mock.patch.object(target_rps.subprocess,'run',return_value=argparse.Namespace(returncode=9)):
            with self.assertRaisesRegex(RuntimeError,'k6 failed'):
                target_rps.run_framework('fastframe',1,args,Path(folder),[])
            stop.assert_called_once_with(fake)

if __name__=='__main__':
    unittest.main()
