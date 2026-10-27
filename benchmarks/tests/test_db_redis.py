"""Real service workload contract, runner lifecycle, reporting and failure tests."""
import argparse
import csv
import json
import sqlite3
from contextlib import closing
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import db_redis
import db_redis_report


def row(case='select',framework='fastframe',repeat=1,errors=0,ok=True):
    return {'case':case,'framework':framework,'repeat':repeat,'requests':100,
            'ok_2xx':100-errors,'errors':errors,'side_effect_ok':ok,
            'rps':700,'p95_ms':4,'p99_ms':6,'rss_peak_mb':None,'cpu_cores_avg':None}


class ContractTests(unittest.TestCase):
    def test_cases_and_methods(self):
        expected={'select':'GET','insert':'POST','update':'POST','transaction':'POST',
                  'cache_get':'GET','cache_set':'POST'}
        self.assertEqual({k:v[0] for k,v in db_redis.CASES.items()},expected)
        for name,(method,path,body) in db_redis.CASES.items():
            self.assertEqual(db_redis.request_spec(name,'http://127.0.0.1:18080'),
                             (method,'http://127.0.0.1:18080'+path))
            self.assertEqual(db_redis.hey_command('http://127.0.0.1:18080',name,64,'30s')[-1],
                             'http://127.0.0.1:18080'+path)
            self.assertEqual(body,{'value':'ok'} if name in ('select','cache_get') else {'ok':True})

    def test_invalid_case(self):
        with self.assertRaisesRegex(ValueError,'Unknown'):db_redis.request_spec('hack','http://localhost')


    def test_postgres_seed_sql_initializes_counter_columns(self):
        # Small common SQL subset runs in SQLite to catch misspelled seed
        # columns without needing Docker in the fast unit suite. Real PostgreSQL
        # validation is covered by the separate integration smoke job.
        schema=db_redis.ROOT/'benchmarks/services/schema.sql'
        with closing(sqlite3.connect(':memory:')) as connection:
            connection.executescript(schema.read_text(encoding='utf-8'))
            self.assertEqual(connection.execute(
                'SELECT id,counter_value FROM bench_counter ORDER BY id').fetchall(),
                [(1,0),(2,0)])
            self.assertEqual(connection.execute(
                'SELECT payload FROM bench_lookup WHERE id=1').fetchone(), ('ok',))
            self.assertEqual(connection.execute(
                'SELECT count(*) FROM bench_events').fetchone(), (0,))

    def test_compose_postgres_health_waits_for_seed_data(self):
        compose=(db_redis.ROOT/'benchmarks/services/compose.yaml').read_text(encoding='utf-8')
        self.assertIn('pg_isready -h 127.0.0.1',compose)
        self.assertIn('SELECT count(*) FROM bench_counter',compose)
        self.assertIn('schema.sql:/docker-entrypoint-initdb.d/',compose)

    def test_service_start_requires_fresh_volume_and_initialized_schema(self):
        calls=[]
        def fake_docker(*command,**options):
            calls.append(command)
            if command[0]=='exec' and 'pg_isready' in command:
                return '/var/run/postgresql:5432 - accepting connections'
            if command[0]=='exec' and 'psql' in command:
                return '1|0\n2|0'
            if command[0]=='exec' and 'redis-cli' in command:
                return 'PONG'
            return ''
        with mock.patch.object(db_redis,'docker_run',side_effect=fake_docker):
            db_redis.start_services()
        self.assertIn('--force-recreate',calls[0])
        self.assertIn('--renew-anon-volumes',calls[0])
        self.assertTrue(any(any('SELECT id,counter_value FROM bench_counter' in part for part in c) for c in calls))

    def test_start_failure_includes_postgres_logs(self):
        proc=mock.Mock(returncode=0,stdout='postgres | ERROR: column "value" does not exist',stderr='')
        with mock.patch.object(db_redis,'docker_run',side_effect=RuntimeError('up failed')) as run, \
             mock.patch.object(db_redis.subprocess,'run',return_value=proc) as subprocess_run:
            with self.assertRaisesRegex(RuntimeError,'column "value" does not exist'):
                db_redis.start_services()
        self.assertEqual(subprocess_run.call_count,2)
        self.assertIn('logs',subprocess_run.call_args.args[0])
        run.assert_called_once()

    def test_failure_with_stop_services_cleans_benchmark_volumes(self):
        # A failed initial SQL script should not leave stale anonymous volumes.
        source=(db_redis.ROOT/'benchmarks/db_redis.py').read_text(encoding='utf-8')
        self.assertIn("if args.stop_services:", source)
        self.assertIn("docker_run('down','--volumes','--remove-orphans'",source)

    def test_docker_only_and_reset_sql(self):
        calls=[]
        with mock.patch.object(db_redis,'docker_run',side_effect=lambda *x,**kw:calls.append(x) or 'OK'):
            db_redis.reset_state()
        self.assertEqual(len(calls),3)
        self.assertIn('TRUNCATE bench_events RESTART IDENTITY',calls[0][-1])
        self.assertIn('UPDATE bench_counter SET counter_value=0',calls[0][-1])
        self.assertIn('ffbench:hot',calls[-1])

    def test_side_effect_counts(self):
        for case,result,want in [('select','ok',True),('select','wrong',False),
                                 ('insert','4',True),('insert','0',False),
                                 ('update','4',True),('update','0',False),
                                 ('transaction','4\n4',True),('transaction','12\n11',False),
                                 ('transaction','0\n0',False)]:
            with self.subTest(case=case,result=result),mock.patch.object(db_redis,'db_query',return_value=result):
                self.assertEqual(db_redis.side_effect(case,4)[0],want)
        for case,result,want in [('cache_get','ok',True),('cache_get','',False),
                                 ('cache_set','ok',True),('cache_set','',False)]:
            with mock.patch.object(db_redis,'redis_get',return_value=result):
                self.assertEqual(db_redis.side_effect(case,4)[0],want)

    def test_laravel_postgres_redis_settings(self):
        args=argparse.Namespace(concurrency=64,db_pool=16,io_workers=32)
        env=db_redis.env_for('laravel',args,18080)
        self.assertEqual(env['DB_CONNECTION'],'pgsql')
        self.assertEqual(env['REDIS_CLIENT'],'predis')
        self.assertEqual(env['REDIS_PREFIX'],'')
        self.assertEqual(env['REDIS_PORT'],'56379')
        self.assertEqual(env['CACHE_STORE'],'array')  # Redis routes use Redis facade, not local cache store

    def test_preflight_rejects_wrong_json_or_http(self):
        class Reply:
            status=200
            headers={'Content-Type':'application/json'}
            def __enter__(self):return self
            def __exit__(self,*_):pass
            def read(self,n):return b'{"value":"wrong"}'
        with mock.patch.object(db_redis.urllib.request,'urlopen',return_value=Reply()):
            with self.assertRaisesRegex(ValueError,'expected'):db_redis.verify_response('http://localhost','select')
        Reply.read=lambda self,n:b'{"value":"ok"}'
        with mock.patch.object(db_redis.urllib.request,'urlopen',return_value=Reply()):
            db_redis.verify_response('http://localhost','select')


class ReportTests(unittest.TestCase):
    def test_only_completed_verified_zero_error_runs_appear(self):
        r=[row(repeat=1),row(repeat=2),row(case='insert',repeat=1),
           row(case='update',repeat=1,errors=1),row(case='update',repeat=2),
           row(case='cache_get',repeat=1,ok=False),row(case='cache_get',repeat=2)]
        agg=db_redis_report.aggregate(r,2)
        lookup={x['case']:x for x in agg}
        self.assertTrue(lookup['select']['valid'])
        self.assertFalse(lookup['insert']['valid'])
        self.assertFalse(lookup['update']['valid'])
        self.assertFalse(lookup['cache_get']['valid'])
        self.assertEqual(lookup['select']['median_rps'],700)

    def test_duplicate_repeat_invalid(self):
        self.assertFalse(db_redis_report.aggregate([row(),row()],2)[0]['valid'])

    def test_offline_reports_and_incomplete_comparisons(self):
        meta={'frameworks':['fastframe','micronaut','laravel'],'laravel_server':'octane',
              'cases':['select','insert'],'repeats':2,'duration':'30s','concurrency':64}
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            summary=db_redis_report.make_reports([row(repeat=1),row(repeat=2)],meta,folder)
            self.assertTrue(summary[0]['valid'])
            for file in ('db-redis-summary.csv','db-redis-summary.json','report.html','report.md'):
                self.assertTrue((folder/file).is_file(),file)
            content=(folder/'report.html').read_text(encoding="utf-8")
            self.assertIn('<svg',content)
            self.assertIn('p99',content)
            self.assertIn('laravel-octane',content)
            self.assertIn('incomplete',(folder/'report.md').read_text(encoding="utf-8"))
            db_redis_report.make_reports([],meta,folder)
            self.assertIn('No complete validated measurements yet',(folder/'report.html').read_text(encoding="utf-8"))

    def test_atomic_csv_json_snapshots(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            data={key:row().get(key) for key in db_redis.FIELDS}
            db_redis.save_rows(folder,[data])
            with (folder/'db-redis-results.csv').open() as fp:
                self.assertEqual(list(csv.DictReader(fp))[0]['framework'],'fastframe')
            self.assertEqual(len(json.loads((folder/'db-redis-results.json').read_text(encoding="utf-8"))),1)


class LifecycleTests(unittest.TestCase):
    def test_reset_preflight_warmup_reset_measure_assert_and_cleanup(self):
        class FakeProcess:
            pid=900
            def poll(self):return None
        args=argparse.Namespace(port=18080,concurrency=8,repeat=1,
                warmup='0s',duration='1s',laravel_server='octane',laravel_workers=4,
                db_pool=16,io_workers=32,heap='512m',keep_raw=False,metrics_backend='proc',
                cases=['insert','update'])
        timeline=[]
        def reset():timeline.append('reset')
        def validate(*_):timeline.append('verify')
        def measured(pid,command,path,baseline,backend,**kw):
            timeline.append('load');path.write_text('hey CSV')
            return {'requests':20,'ok_2xx':20,'errors':0,'rps':20,
                    'duration_s':1,'p50_ms':1,'p95_ms':2,'p99_ms':3,'mean_ms':2,
                    'rss_baseline_mb':None,'rss_peak_mb':None,'cpu_cores_avg':None}
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.object(db_redis,'select_server_port',return_value=18080), \
             mock.patch.object(db_redis,'server_command',return_value=['fake']), \
             mock.patch.object(db_redis,'start_server',return_value=FakeProcess()), \
             mock.patch.object(db_redis,'stop_server') as stop, \
             mock.patch.object(db_redis,'wait_until_ready'), \
             mock.patch.object(db_redis,'reset_state',side_effect=reset), \
             mock.patch.object(db_redis,'verify_response',side_effect=validate), \
             mock.patch.object(db_redis,'side_effect',return_value=(True,'20',None)), \
             mock.patch.object(db_redis,'process_metrics',return_value=(None,None)), \
             mock.patch.object(db_redis,'run_load',side_effect=measured):
            rows=[]
            db_redis.run_framework('fastframe',1,args,Path(directory),rows)
            self.assertEqual(len(rows),2)
            self.assertEqual(timeline,['reset','verify','reset','load','reset','verify','reset','load'])
            stop.assert_called_once()
            self.assertFalse(list(Path(directory).glob('raw-*.csv')))

    def test_cleanup_if_preflight_raises(self):
        class FakeProcess:
            pid=901
            def poll(self):return None
        args=argparse.Namespace(port=18080,concurrency=8,warmup='0s',duration='1s',
                laravel_server='octane',laravel_workers=4,db_pool=16,io_workers=32,
                heap='512m',keep_raw=False,metrics_backend='proc',cases=['select'])
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.object(db_redis,'select_server_port',return_value=18080), \
             mock.patch.object(db_redis,'server_command',return_value=['fake']), \
             mock.patch.object(db_redis,'start_server',return_value=FakeProcess()), \
             mock.patch.object(db_redis,'stop_server') as stop, \
             mock.patch.object(db_redis,'wait_until_ready'), \
             mock.patch.object(db_redis,'reset_state'), \
             mock.patch.object(db_redis,'verify_response',side_effect=RuntimeError('wrong response')):
            with self.assertRaisesRegex(RuntimeError,'wrong response'):
                db_redis.run_framework('fastframe',1,args,Path(directory),[])
            stop.assert_called_once()

if __name__=='__main__':unittest.main()
