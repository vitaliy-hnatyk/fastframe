"""Exact payload parity, JSON size reporting, runner lifecycle and failure handling."""
import argparse
import csv
import io
import json
import shutil
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

HERE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(HERE))
import json_sizes
import json_sizes_report


def row(framework='fastframe',size='1kb',repeat=1,errors=0,rps=1000):
    byte_len=json_sizes.SIZES[size]
    return {'framework':framework,'size':size,'repeat':repeat,'bytes':byte_len,
            'requests':100,'ok_2xx':100-errors,'errors':errors,'rps':rps,
            'mib_s':rps*byte_len/(1024**2),'p95_ms':4,'p99_ms':7,
            'rss_peak_mb':None,'cpu_cores_avg':None}


class FixtureTests(unittest.TestCase):
    def test_exact_binary_sizes_and_valid_json(self):
        for name, length in json_sizes.SIZES.items():
            with self.subTest(name=name):
                data=json_sizes.payload(name)
                self.assertEqual(len(data),length)
                self.assertEqual(json.loads(data),{'data':'x'*(length-11)})
                self.assertEqual(data[:9],b'{"data":"')
                self.assertEqual(data[-2:],b'"}')
    def test_unknown_size(self):
        with self.assertRaises(ValueError): json_sizes.endpoint('../../admin')
    def test_hey_configuration(self):
        self.assertEqual(json_sizes.hey_for_json_size('http://127.0.0.1:18080','100kb',8,'30s')[-1],
                         'http://127.0.0.1:18080/bench/json-size/100kb')
        self.assertEqual(json_sizes.make_parser().parse_args([]).duration,'30s')
        self.assertEqual(json_sizes.make_parser().parse_args([]).sizes,list(json_sizes.SIZES))

    def test_preflight_exact_bytes_and_type(self):
        size='1kb'; data=json_sizes.payload(size)
        class Response:
            status=200
            headers={'Content-Type':'application/json; charset=utf-8','Content-Length':str(len(data))}
            def __enter__(self):return self
            def __exit__(self,*_):pass
            def read(self,limit):return data[:limit]
        with mock.patch.object(json_sizes.urllib.request,'urlopen',return_value=Response()):
            result=json_sizes.verify_payload('http://127.0.0.1:18080',size)
            self.assertEqual(result['bytes'],1024)
        class BadResponse(Response):
            def read(self,limit):return data[:-1]
        with mock.patch.object(json_sizes.urllib.request,'urlopen',return_value=BadResponse()):
            with self.assertRaisesRegex(ValueError,'expected 1024'):
                json_sizes.verify_payload('http://127.0.0.1:18080',size)


class ReportTests(unittest.TestCase):
    def test_validity_and_median(self):
        group=[row(repeat=1,rps=1200),row(repeat=2,rps=900),
               row('micronaut',repeat=1,rps=500),row('micronaut',repeat=2,errors=1,rps=700)]
        agg=json_sizes_report.aggregate(group,2)
        byname={r['framework']:r for r in agg}
        self.assertEqual(byname['fastframe']['median_rps'],1050)
        self.assertTrue(byname['fastframe']['valid'])
        self.assertFalse(byname['micronaut']['valid'])
        self.assertEqual(byname['micronaut']['total_errors'],1)
        self.assertFalse(json_sizes_report.aggregate([row()],2)[0]['valid'])
    def test_duplicate_repeat_invalid(self):
        data=[row(repeat=1),row(repeat=1)]
        self.assertFalse(json_sizes_report.aggregate(data,2)[0]['valid'])
    def test_reports_offline_and_empty(self):
        meta={'platform':'test','duration':'30s','concurrency':64,'repeats':2,
              'sizes':['1kb','10kb'],'frameworks':['fastframe','micronaut','laravel'],
              'laravel_server':'octane','bytes':json_sizes.SIZES}
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)
            json_sizes_report.make_reports([row(repeat=1),row(repeat=2)],meta,target)
            for file in ('report.html','report.md','json-sizes-summary.csv','json-sizes-summary.json'):
                self.assertTrue((target/file).exists(),file)
            html=(target/'report.html').read_text()
            self.assertIn('MiB/s',html)
            self.assertIn('svg',html)
            self.assertIn('micronaut',html)
            self.assertIn('FastFrame v0.22',html)
            self.assertIn('incomplete',(target/'report.md').read_text())
            json_sizes_report.make_reports([],meta,target)
            self.assertIn('No valid complete measurements', (target/'report.html').read_text())

    def test_atomic_results(self):
        with tempfile.TemporaryDirectory() as folder:
            dest=Path(folder)
            json_sizes.save_rows(dest,[{name:row().get(name) for name in json_sizes.FIELDS}])
            with (dest/'json-sizes-results.csv').open(newline='',encoding='utf-8') as fp:
                data=list(csv.DictReader(fp))
            self.assertEqual(data[0]['size'],'1kb')
            self.assertEqual(len(json.loads((dest/'json-sizes-results.json').read_text())),1)


class LifecycleTests(unittest.TestCase):
    def test_end_to_end_fake_process_and_http_client(self):
        class Proc:
            pid=6789
            def poll(self):return None
        fake=Proc()
        args=argparse.Namespace(port=18080,laravel_server='octane',laravel_workers=4,heap='512m',
                                concurrency=8,sizes=['1kb','10kb'],warmup='0s',duration='30s',
                                keep_raw=False,metrics_backend='proc')
        def measured(pid,command,path,baseline,backend,collect_process_metrics=True):
            path.write_text('placeholder')
            return {'requests':150,'ok_2xx':150,'errors':0,'duration_s':30,'rps':5,
                    'p50_ms':1,'p95_ms':2,'p99_ms':3,'mean_ms':1.5,
                    'rss_baseline_mb':None,'rss_peak_mb':None,'cpu_cores_avg':None}
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(json_sizes,'select_server_port',return_value=18080), \
             mock.patch.object(json_sizes,'server_command',return_value=['fake']), \
             mock.patch.object(json_sizes,'start_server',return_value=fake), \
             mock.patch.object(json_sizes,'stop_server') as stop, \
             mock.patch.object(json_sizes,'wait_until_ready'), \
             mock.patch.object(json_sizes,'verify_payload',side_effect=lambda url,size:{'bytes':json_sizes.SIZES[size],'sha256':'a'*64}), \
             mock.patch.object(json_sizes,'process_metrics',return_value=(None,None)), \
             mock.patch.object(json_sizes,'run_load',side_effect=measured):
            rows=[]
            json_sizes.run_framework('fastframe',1,args,Path(folder),rows)
            self.assertEqual([r['size'] for r in rows],['1kb','10kb'])
            self.assertEqual(rows[0]['mib_s'],round(5/1024,3))
            self.assertEqual(rows[1]['mib_s'],round(50/1024,3))
            stop.assert_called_once_with(fake)
            self.assertEqual(len(list(Path(folder).glob('raw-*.csv'))),0)

    def test_cleanup_on_preflight_failure(self):
        class Proc:
            def poll(self):return None
        fake=Proc()
        args=argparse.Namespace(port=18080,laravel_server='octane',laravel_workers=4,
                                heap='512m',concurrency=8,sizes=['1kb'],warmup='0s',
                                duration='30s',keep_raw=False,metrics_backend='proc')
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(json_sizes,'select_server_port',return_value=18080), \
             mock.patch.object(json_sizes,'server_command',return_value=['fake']), \
             mock.patch.object(json_sizes,'start_server',return_value=fake), \
             mock.patch.object(json_sizes,'stop_server') as stop, \
             mock.patch.object(json_sizes,'wait_until_ready'), \
             mock.patch.object(json_sizes,'verify_payload',side_effect=ValueError('bad payload')):
            with self.assertRaisesRegex(ValueError,'bad payload'):
                json_sizes.run_framework('fastframe',1,args,Path(folder),[])
            stop.assert_called_once_with(fake)

if __name__=='__main__': unittest.main()
