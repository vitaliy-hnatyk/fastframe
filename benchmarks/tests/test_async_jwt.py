"""Async external I/O and HS256 JWT benchmark regression tests."""
import argparse
import csv
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import async_jwt
import async_jwt_report


def _find_jdk21_tools():
    """Find a matching JDK 21+ compiler/runtime, preferring JAVA_HOME over PATH.

    Windows often has java.exe (21) and javac.exe (17) from different
    installations. Do not silently accept that mismatch or hide javac stderr.
    """
    suffix='.exe' if os.name=='nt' else ''
    candidates=[]
    if os.environ.get('JAVA_HOME'):
        candidates.append(Path(os.environ['JAVA_HOME'])/'bin'/('javac'+suffix))
    on_path=shutil.which('javac')
    if on_path:
        candidates.append(Path(on_path))
    examined=[]
    for javac in dict.fromkeys(candidates):
        if not javac.is_file():
            examined.append(f'{javac}: not found')
            continue
        try:
            completed=subprocess.run([str(javac),'-version'],capture_output=True,
                                     text=True,timeout=10)
        except (OSError,subprocess.TimeoutExpired) as error:
            examined.append(f'{javac}: {error}')
            continue
        version=(completed.stdout+' '+completed.stderr).strip()
        match=re.search(r'\bjavac\s+(\d+)(?:\.\d+)*',version)
        if completed.returncode or not match:
            examined.append(f'{javac}: {version or "version unavailable"}')
            continue
        if int(match.group(1))<21:
            examined.append(f'{javac}: {version} (requires JDK 21+)')
            continue
        java=javac.with_name('java'+suffix)
        if not java.is_file():
            examined.append(f'{javac}: matching java executable not found')
            continue
        return str(javac),str(java),version
    return None,None,'; '.join(examined) or 'javac not found; set JAVA_HOME to JDK 21+'


def metric_row(case='async_50', repeat=1, framework='fastframe', valid=True, errors=0):
    return {'case':case,'framework':framework,'repeat':repeat,'requests':100,
            'ok_2xx':100-errors,'errors':errors,'rps':500,
            'p95_ms':60,'p99_ms':65,'rss_peak_mb':None,'cpu_cores_avg':None,
            'side_effect_ok':valid}


class ContractTests(unittest.TestCase):
    def test_case_uris_and_header(self):
        for case, delay in async_jwt.DELAYS.items():
            self.assertEqual(async_jwt.case_uri(case),f'/bench/async/{delay}')
            self.assertNotIn('-H',async_jwt.hey_command('http://127.0.0.1',case,64,'30s','token'))
        self.assertEqual(async_jwt.case_uri('jwt'),'/bench/jwt')
        cmd=async_jwt.hey_command('http://localhost','jwt',16,'30s','signed-token')
        self.assertEqual(cmd[cmd.index('-H')+1],'Authorization: Bearer signed-token')
        with self.assertRaises(ValueError):async_jwt.case_uri('unknown')

    def test_jwt_canonical_hmac_and_expiration(self):
        secret='benchmark-test-only-secret-at-least-32-bytes'
        token=async_jwt.make_jwt(secret, now=2000000000)
        a,b,c=token.split('.')
        import base64,hashlib,hmac
        claim=json.loads(base64.urlsafe_b64decode(b+'==='))
        self.assertEqual(claim['sub'],'bench-user')
        self.assertEqual(claim['permissions'],['bench:read'])
        self.assertEqual(base64.urlsafe_b64encode(hmac.new(secret.encode(),(a+'.'+b).encode(),hashlib.sha256).digest()).decode().rstrip('='),c)
        expired=async_jwt.make_jwt(secret,now=2000000000,expired=True)
        exp=json.loads(base64.urlsafe_b64decode(expired.split('.')[1]+'==='))
        self.assertLessEqual(exp['exp'],exp['iat']+3600)
        self.assertGreater(claim['exp'],claim['iat'])

    def test_auth_preflight_negative_checks(self):
        observed=[]
        def reply(url,*,token=None,**kw):
            observed.append(token)
            return ((200,{'subject':'bench-user','authorized':True}) if token=='valid' else (401,{'error':'unauthorized'}))
        with mock.patch.object(async_jwt,'http_json',side_effect=reply):
            async_jwt.verify_response('http://127.0.0.1','jwt','valid','expired')
        self.assertEqual(len(observed),4)
        self.assertIn(None,observed)
        self.assertIn('expired',observed)
        with mock.patch.object(async_jwt,'http_json',return_value=(200,{})):
            with self.assertRaises(ValueError):async_jwt.verify_response('http://127.0.0.1','jwt','valid','expired')

    def test_async_preflight_verifies_real_json(self):
        with mock.patch.object(async_jwt,'http_json',return_value=(200,{'value':'ok','delay_ms':50})):
            async_jwt.verify_response('http://127.0.0.1','async_50','unused','unused')
        with mock.patch.object(async_jwt,'http_json',return_value=(200,{'value':'ok','delay_ms':100})):
            with self.assertRaises(ValueError):async_jwt.verify_response('http://127.0.0.1','async_50','unused','unused')

    def test_config_security_and_upstream(self):
        args=argparse.Namespace(concurrency=64)
        env=async_jwt.env_for('laravel',args,18080,19090,'sekret')
        self.assertEqual(env['BENCH_UPSTREAM_URL'],'http://127.0.0.1:19090')
        self.assertEqual(env['BENCH_JWT_SECRET'],'sekret')
        self.assertEqual(env['MAX_IN_FLIGHT'],'2048')
        self.assertEqual(env['APP_ENV'],'production')


class JavaToolchainTests(unittest.TestCase):
    def test_jdk_selection_uses_compiler_and_runtime_from_same_directory(self):
        javac,java,details=_find_jdk21_tools()
        if not javac:
            self.assertTrue('JDK 21' in details or 'javac' in details)
        else:
            self.assertEqual(Path(javac).parent,Path(java).parent)
            self.assertTrue(Path(javac).is_file())
            self.assertTrue(Path(java).is_file())

    def test_older_javac_is_reported_not_run(self):
        with tempfile.TemporaryDirectory() as directory:
            home=Path(directory)
            binary=home/'bin'/('javac'+('.exe' if os.name=='nt' else ''))
            # On Windows an absolute mock path is still accepted if is_file().
            binary.parent.mkdir()
            binary.touch()
            class Fake:
                returncode=0
                stdout='javac 17.0.12'
                stderr=''
            with mock.patch.dict(os.environ,{'JAVA_HOME':str(home)}), \
                 mock.patch.object(shutil,'which',return_value=None), \
                 mock.patch.object(subprocess,'run',return_value=Fake()):
                javac,java,details=_find_jdk21_tools()
            self.assertIsNone(javac)
            self.assertIsNone(java)
            self.assertIn('requires JDK 21+',details)


class CrossRuntimeSignatureTests(unittest.TestCase):
    def test_php_route_validates_real_signature_without_composer(self):
        import shutil
        if not shutil.which('php'): self.skipTest('PHP unavailable')
        path=Path(async_jwt.ROOT)/'benchmarks/tests/php-jwt-smoke.php'
        secret='for-local-fixture-testing-only-1234567890'
        env={**os.environ,'BENCH_JWT_SECRET':secret}
        for expected,header in [(200,'Bearer '+async_jwt.make_jwt(secret)),
                                (401,'Bearer '+async_jwt.make_jwt(secret,expired=True)),
                                (401,'Bearer '+async_jwt.make_jwt('different-secret-1234')),
                                (401,'')]:
            with self.subTest(expected=expected, header=header[:13]):
                proc=subprocess.run(['php',str(path),header],env=env,text=True,
                                    capture_output=True,timeout=10,check=True)
                self.assertEqual(json.loads(proc.stdout)['status'],expected)

    def test_java_verifiers_agree_on_signed_token(self):
        javac,java,jdk_info=_find_jdk21_tools()
        if not javac:
            self.skipTest('JDK 21+ unavailable: '+jdk_info)
        root=Path(async_jwt.ROOT)/'benchmarks'
        one=root/'fastframe-app/src/main/java/dev/fastframe/benchmark/BenchJwt.java'
        two=root/'micronaut-app/src/main/java/dev/fastframe/micronautbench/BenchJwt.java'
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            probe=folder/'JwtProbe.java'
            probe.write_text("""public class JwtProbe { public static void main(String[] a) {
                System.out.println(dev.fastframe.benchmark.BenchJwt.valid("Bearer " + a[0]));
                System.out.println(dev.fastframe.micronautbench.BenchJwt.valid("Bearer " + a[0]));
                System.out.println(dev.fastframe.benchmark.BenchJwt.valid("Bearer " + a[1]));
                System.out.println(dev.fastframe.micronautbench.BenchJwt.valid("Bearer " + a[1]));
            }}""")
            command=[javac,'--release','21','-d',str(folder),str(one),str(two),str(probe)]
            compiled=subprocess.run(command,capture_output=True,text=True,timeout=20)
            if compiled.returncode:
                self.fail('JWT javac failed ('+jdk_info+') using '+javac+'\n'
                          +'Command: '+' '.join(command)+'\n'
                          +'stdout:\n'+compiled.stdout+'\nstderr:\n'+compiled.stderr)
            secret='local-cross-runtime-test-signing-secret-12345'
            proc=subprocess.run([java,'-cp',str(folder),'JwtProbe',
                       async_jwt.make_jwt(secret),async_jwt.make_jwt(secret,expired=True)],
                       env={**os.environ,'BENCH_JWT_SECRET':secret},
                       capture_output=True,text=True,timeout=10,check=True)
            self.assertEqual(proc.stdout.splitlines(),['true','true','false','false'])


class UpstreamIntegrationTests(unittest.TestCase):
    def test_real_upstream_timing_and_counter_reset(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0))
            port=sock.getsockname()[1]
        with tempfile.TemporaryFile() as log:
            upstream=subprocess.Popen([sys.executable,str(Path(async_jwt.ROOT)/'benchmarks/upstream.py'),'--port',str(port)],
                                      stdout=log,stderr=subprocess.STDOUT)
            try:
                async_jwt.await_upstream(upstream,port)
                async_jwt.reset_upstream(port)
                start=time.monotonic()
                code,body=async_jwt.http_json(async_jwt.upstream_url(port,'/wait/50'))
                duration=time.monotonic()-start
                self.assertEqual(code,200)
                self.assertEqual(body,{'value':'ok','delay_ms':50})
                self.assertGreaterEqual(duration,0.038)
                self.assertLess(duration,1.5)
                self.assertEqual(async_jwt.upstream_stats(port)['50'],1)
                async_jwt.reset_upstream(port)
                self.assertEqual(async_jwt.upstream_stats(port)['50'],0)
                code,_=async_jwt.http_json(async_jwt.upstream_url(port,'/wait/51'))
                self.assertEqual(code,404)
            finally:
                upstream.terminate()
                try:upstream.wait(timeout=3)
                except subprocess.TimeoutExpired:upstream.kill();upstream.wait()


class ReportTests(unittest.TestCase):
    def test_incomplete_bad_error_runs_excluded(self):
        values=[metric_row(repeat=1),metric_row(repeat=2),metric_row(case='jwt'),
                metric_row(case='async_200',errors=1),metric_row(case='async_200',repeat=2),
                metric_row(case='async_10',valid=False),metric_row(case='async_10',repeat=2)]
        result=async_jwt_report.aggregate(values,2)
        valid={x['case']:x['valid'] for x in result}
        self.assertTrue(valid['async_50'])
        self.assertFalse(valid['jwt'])
        self.assertFalse(valid['async_10'])
        self.assertFalse(valid['async_200'])

    def test_report_files_chart_incomplete_status(self):
        meta={'frameworks':['fastframe','micronaut','laravel'],'laravel_server':'octane',
              'cases':['async_50','jwt'],'repeats':2,'duration':'30s','concurrency':64}
        with tempfile.TemporaryDirectory() as path:
            directory=Path(path)
            result=async_jwt_report.make_reports([metric_row(repeat=1),metric_row(repeat=2)],meta,directory)
            self.assertTrue(result[0]['valid'])
            for name in ('report.html','report.md','async-jwt-summary.csv','async-jwt-summary.json'):
                self.assertTrue((directory/name).is_file())
            self.assertIn('<svg',(directory/'report.html').read_text(encoding="utf-8"))
            self.assertIn('incomplete',(directory/'report.md').read_text(encoding="utf-8"))
            self.assertIn('HS256',(directory/'report.md').read_text(encoding="utf-8"))
            async_jwt_report.make_reports([],meta,directory)
            self.assertIn('No complete validated measurements yet',(directory/'report.html').read_text(encoding="utf-8"))

    def test_snapshot_roundtrip(self):
        with tempfile.TemporaryDirectory() as path:
            folder=Path(path)
            row={k:metric_row().get(k) for k in async_jwt.FIELDS}
            async_jwt.save_rows(folder,[row])
            with (folder/'async-jwt-results.csv').open() as fp:
                self.assertEqual(list(csv.DictReader(fp))[0]['framework'],'fastframe')
            self.assertEqual(len(json.loads((folder/'async-jwt-results.json').read_text(encoding="utf-8"))),1)


class LifecycleTests(unittest.TestCase):
    def args(self):
        return argparse.Namespace(port=18080,concurrency=8,warmup='0s',duration='1s',
               laravel_server='octane',laravel_workers=4,heap='512m',keep_raw=False,
               metrics_backend='proc',cases=['async_50','jwt'])

    def test_positive_run_counts_upstream_and_closes_process(self):
        class Proc:
            pid=1234
        timeline=[]
        def reply(case,*_):timeline.append('verify')
        def reset(port):timeline.append('reset')
        def load(pid,command,path,*args,**kwargs):
            timeline.append('load');path.write_text('raw')
            return {'requests':8,'ok_2xx':8,'errors':0,'rps':8,
                    'duration_s':1,'p50_ms':1,'p95_ms':2,'p99_ms':3,'mean_ms':2,
                    'rss_baseline_mb':None,'rss_peak_mb':None,'cpu_cores_avg':None}
        with tempfile.TemporaryDirectory() as d, \
             mock.patch.object(async_jwt,'select_server_port',return_value=18080), \
             mock.patch.object(async_jwt,'server_command',return_value=['fakeserver']), \
             mock.patch.object(async_jwt,'start_server',return_value=Proc()), \
             mock.patch.object(async_jwt,'stop_server') as stop, \
             mock.patch.object(async_jwt,'wait_until_ready'), \
             mock.patch.object(async_jwt,'verify_response',side_effect=lambda *args:timeline.append('verify')), \
             mock.patch.object(async_jwt,'reset_upstream',side_effect=reset), \
             mock.patch.object(async_jwt,'upstream_stats',return_value={'10':0,'50':8,'200':0}), \
             mock.patch.object(async_jwt,'process_metrics',return_value=(None,None)), \
             mock.patch.object(async_jwt,'run_load',side_effect=load):
            rows=[]
            async_jwt.run_framework('fastframe',1,self.args(),Path(d),rows,19090,'tok','exp','key')
            self.assertEqual(timeline,['verify','reset','load','verify','reset','load'])
            self.assertEqual(rows[0]['upstream_completed'],8)
            self.assertIsNone(rows[1]['upstream_completed'])
            self.assertTrue(all(r['side_effect_ok'] for r in rows))
            stop.assert_called_once()

    def test_bad_upstream_rejected_and_server_cleaned(self):
        class Proc:pid=1234
        with tempfile.TemporaryDirectory() as d, \
             mock.patch.object(async_jwt,'select_server_port',return_value=18080), \
             mock.patch.object(async_jwt,'server_command',return_value=['fake']), \
             mock.patch.object(async_jwt,'start_server',return_value=Proc()), \
             mock.patch.object(async_jwt,'stop_server') as stop, \
             mock.patch.object(async_jwt,'wait_until_ready'), \
             mock.patch.object(async_jwt,'verify_response',side_effect=ValueError('fake response')):
            with self.assertRaisesRegex(ValueError,'fake response'):
                async_jwt.run_framework('fastframe',1,self.args(),Path(d),[],19090,'tok','exp','secret')
            stop.assert_called_once()


if __name__=='__main__':unittest.main()
