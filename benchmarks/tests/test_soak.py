"""Soak metrics, reporting and runner lifecycle regression tests."""
import argparse
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import soak
import soak_report


FAKE_PYTHON = ('/usr/bin/python3' if sys.platform.startswith('linux') and
               Path('/usr/bin/python3').is_file() else sys.executable)


class SoakTest(unittest.TestCase):
    def test_defaults_and_duration(self):
        args = soak.parser().parse_args([])
        self.assertEqual(args.duration,'3600s')
        self.assertEqual(args.frameworks,['fastframe','micronaut','laravel'])
        self.assertEqual(args.rate,1000)
        self.assertEqual(soak.seconds('3600s'),3600)
        for invalid in ['3m','bad','-1s','0.2ms']:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                soak.seconds(invalid)

    def test_jstat_parses_header_order_and_gc(self):
        data = ('S0C S1C S0U S1U EC EU OC OU MC MU YGC YGCT FGC FGCT CGC CGCT GCT\n'
                '100 100 25 25 1000 200 2000 300 500 350 7 0.1 2 0.2 1 0.05 0.35\n')
        result = soak.jstat_parse(data)
        self.assertAlmostEqual(result['heap_used_mb'],550/1024,3)
        self.assertAlmostEqual(result['heap_capacity_mb'],3200/1024,3)
        self.assertEqual(result['gc_count'],10)
        self.assertEqual(result['gc_pause_s'],.35)
        self.assertIsNone(soak.jstat_parse('S0C S0U\n100 20'))
        self.assertIsNone(soak.jstat_parse(''))

    def test_jstat_missing_binary_is_unavailable(self):
        with mock.patch.object(soak.shutil,'which',return_value=None):
            self.assertEqual(soak.read_jvm(1),{})

    def test_process_tree_cpu_rss_is_real(self):
        if soak.psutil is None: self.skipTest('psutil missing')
        sampler=soak.ProcessTreeSampler(os.getpid())
        first=sampler.sample(now=100)
        second=sampler.sample(now=102)
        self.assertGreater(first['rss_mb'],0)
        self.assertGreaterEqual(first['process_count'],1)
        self.assertIsNone(first['cpu_cores'])
        self.assertGreaterEqual(second['cpu_cores'],0)
        self.assertGreaterEqual(sampler.elapsed_observed,2)

    def test_slope_and_heap_unavailable(self):
        samples=[{'elapsed_s':t,'rss_mb':50 + t/60, 'heap_used_mb':None,
                  'gc_count':None,'gc_pause_s':None,'probe_errors':0}
                 for t in (0,60,120,180,240)]
        self.assertAlmostEqual(soak.memory_slope(samples),60)
        sampler=mock.Mock(cpu_seconds=60, elapsed_observed=120)
        s=soak.summarize_samples('laravel',samples,sampler)
        self.assertIsNone(s['heap_start_mb'])
        self.assertIsNone(s['gc_collections'])
        self.assertAlmostEqual(s['cpu_cores_avg'],.5)
        self.assertEqual(s['rss_growth_mb'],4)
        self.assertIsNone(soak.memory_slope(samples[:2]))

    def test_sample_snapshots_atomic(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)
            result={'framework':'fastframe','case':'json','repeat':1,'complete':False}
            sample={'framework':'fastframe','case':'json','repeat':1,'elapsed_s':0}
            soak.save_state(path,[result],[sample])
            with (path/'soak-results.csv').open() as handle:
                self.assertEqual(len(list(__import__('csv').DictReader(handle))),1)
            self.assertEqual(json.loads((path/'soak-timeseries.json').read_text())[0]['elapsed_s'],0)
            self.assertFalse(list(path.glob('*.tmp')))

    def test_report_incomplete_is_not_pass_and_php_na(self):
        base={'framework':'laravel-octane','case':'json','repeat':1,'complete':True,
              'target_met':True,'sample_count':3,'requests':500,'errors':0,
              'dropped_iterations':0,'rss_peak_mb':200,'heap_peak_mb':None}
        self.assertEqual(soak_report.status(base),'COMPLETE')
        self.assertEqual(soak_report.status({**base,'errors':1}),'ERRORS_OR_DROPS')
        self.assertEqual(soak_report.status({**base,'target_met':False}),'TARGET_MISSED')
        self.assertEqual(soak_report.status({**base,'probe_errors':1}),'PROBE_FAILURES')
        self.assertEqual(soak_report.status({**base,'complete':False}),'INCOMPLETE')
        self.assertEqual(soak_report.status({**base,'sample_count':0}),'UNMONITORED')
        rows=[{**base,'complete':False,'failure':'k6 exited 3'}]
        series=[{'framework':'laravel-octane','repeat':1,'elapsed_s':0,'rss_mb':50,
                 'cpu_cores':None,'gc_count':None,'heap_used_mb':None,'gc_pause_s':None,'probe_errors':0}]
        meta={'frameworks':['fastframe','micronaut','laravel'],'laravel_server':'octane',
              'created_utc':'now','platform':'macOS','rate':1000,'duration':'3600s'}
        with tempfile.TemporaryDirectory() as folder:
            result=soak_report.make_reports(rows,series,meta,Path(folder))
            self.assertEqual(result[0]['status'],'INCOMPLETE')
            page=(Path(folder)/'report.html').read_text()
            self.assertIn('INCOMPLETE',page)
            self.assertIn('N/A',page)
            self.assertIn('<svg',page)
            self.assertIn('Cumulative independent HTTP probe failures',page)
            self.assertIn('does not prove a leak',page)
            self.assertTrue((Path(folder)/'soak-summary.csv').exists())

    @unittest.skipIf(soak.psutil is None, 'psutil required')
    def test_runner_server_cleanup_and_real_subprocess(self):
        # One-second subprocess instead of a real 60-minute k6 run. Tests runner lifecycle.
        with tempfile.TemporaryDirectory() as td:
            directory=Path(td)
            fake_k6=directory/'fake_k6.py'
            fake_k6.write_text('''import os,json,time,sys
 time.sleep(0.55)
 if os.environ.get('SIM_FAIL') == '1': sys.exit(3)
 result={'schema':1,'requests':10,'k6_reported_rps':10,'dropped_iterations':0,
 'invalid_responses':0,'http_req_failed_rate':0,'p50_ms':1,'p95_ms':2,
 'p99_ms':3,'mean_ms':1.5}
 with open(os.environ['BENCH_SUMMARY_PATH'],'w') as f:json.dump(result,f)
 '''.replace('\n ', '\n'))
            args=argparse.Namespace(**vars(soak.parser().parse_args([
                '--frameworks','fastframe','--duration','1s','--warmup','0s',
                '--rate','10','--sample-interval','0.15','--gc-interval','0.2',
                '--probe-interval','0.1','--keep-k6'])))
            results=[]; samples=[]
            runner=mock.Mock(pid=os.getpid(),poll=mock.Mock(return_value=None))
            common=[mock.patch.object(soak,'select_server_port',return_value=18080),
                    mock.patch.object(soak,'server_command',return_value=['fake-server']),
                    mock.patch.object(soak,'start_server',return_value=runner),
                    mock.patch.object(soak,'stop_server'),
                    mock.patch.object(soak,'wait_until_ready'),
                    mock.patch.object(soak,'assert_endpoint'),
                    mock.patch.object(soak,'read_jvm',return_value={}),
                    mock.patch.object(soak,'probe',return_value=True),
                    mock.patch.object(soak,'k6_command',return_value=[FAKE_PYTHON,str(fake_k6)])]
            with common[0],common[1],common[2],common[3] as stop,common[4],common[5],common[6],common[7],common[8]:
                soak.run_one('fastframe',1,args,directory,results,samples)
            self.assertEqual(len(results),1)
            self.assertTrue(results[0]['complete'])
            self.assertEqual(results[0]['requests'],10)
            self.assertGreaterEqual(results[0]['sample_count'],2)
            self.assertTrue(stop.called)
            self.assertGreater((directory/'soak-timeseries.csv').stat().st_size,100)

    @unittest.skipIf(soak.psutil is None, 'psutil required')
    def test_runner_failed_k6_marks_incomplete_and_stops_server(self):
        with tempfile.TemporaryDirectory() as td:
            directory=Path(td)
            fake=directory/'fail.py'
            fake.write_text('import sys,time\ntime.sleep(0.2)\nsys.exit(4)\n')
            args=soak.parser().parse_args(['--frameworks','fastframe','--duration','1s',
                                          '--warmup','0s','--sample-interval','0.1',
                                          '--probe-interval','0.1'])
            rows=[]; series=[]
            server=mock.Mock(pid=os.getpid(),poll=mock.Mock(return_value=None))
            with (mock.patch.object(soak,'select_server_port',return_value=18080),
                  mock.patch.object(soak,'server_command',return_value=['dummy']),
                  mock.patch.object(soak,'start_server',return_value=server),
                  mock.patch.object(soak,'stop_server') as stopped,
                  mock.patch.object(soak,'wait_until_ready'),
                  mock.patch.object(soak,'assert_endpoint'),
                  mock.patch.object(soak,'read_jvm',return_value={}),
                  mock.patch.object(soak,'probe',return_value=True),
                  mock.patch.object(soak,'k6_command',return_value=[FAKE_PYTHON,str(fake)])):
                with self.assertRaisesRegex(RuntimeError,'k6 failed'):
                    soak.run_one('fastframe',1,args,directory,rows,series)
            self.assertEqual(len(rows),1)
            self.assertFalse(rows[0]['complete'])
            self.assertEqual(soak_report.status(rows[0]),'INCOMPLETE')
            self.assertTrue(stopped.called)


if __name__=='__main__':unittest.main()
