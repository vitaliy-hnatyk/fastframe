#!/usr/bin/env python3
"""FastFrame/Micronaut/Laravel Octane: real outbound HTTP I/O and signed HS256 JWT tests.

A local asyncio HTTP upstream is launched separately and monitored. Each application
performs outbound I/O; JWT is HMAC-verified and time/claim-checked per request.
No plaintext fake tokens, Thread.sleep-only endpoints, or successful stub responses.
"""
from __future__ import annotations
import argparse
import base64
import csv
import hashlib
import hmac
import json
import os
import platform
import secrets
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from run import (ROOT, AVAILABLE_FRAMEWORKS, laravel_metadata, metrics_backend,
                 process_metrics, project_version, require_installed,
                 result_framework, run_load, select_server_port, server_command,
                 start_server, stop_server, wait_until_ready)
from concurrency import validate_duration, is_zero_duration
from async_jwt_report import make_reports

DELAYS = {'async_10': 10, 'async_50': 50, 'async_200': 200}
CASES = tuple(DELAYS) + ('jwt',)
FIELDS = ('framework','case','repeat','port','upstream_port','concurrency','requests',
          'ok_2xx','errors','duration_s','rps','p50_ms','p95_ms','p99_ms','mean_ms',
          'rss_baseline_mb','rss_peak_mb','cpu_cores_avg','side_effect_ok',
          'upstream_completed','auth_validation_ok')


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode('ascii').rstrip('=')


def make_jwt(secret: str, *, now: int | None = None, expired: bool = False) -> str:
    now = int(time.time()) if now is None else now
    issue = now - 7200 if expired else now
    head = {'alg': 'HS256', 'typ': 'JWT'}
    claims = {'iss':'ff-bench','aud':'ff-bench','sub':'bench-user',
              'iat':issue,'nbf':issue,'exp':issue+3600,
              'roles':['reader'],'permissions':['bench:read']}
    compact = lambda item: json.dumps(item, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
    unsigned = b64url(compact(head)) + '.' + b64url(compact(claims))
    return unsigned + '.' + b64url(hmac.new(secret.encode('utf-8'), unsigned.encode('ascii'), hashlib.sha256).digest())


def case_uri(case: str) -> str:
    if case in DELAYS: return '/bench/async/' + str(DELAYS[case])
    if case == 'jwt': return '/bench/jwt'
    raise ValueError('Unknown case: ' + case)


def http_json(url: str, *, token: str | None = None, method='GET', timeout=7):
    headers = {'Authorization': 'Bearer ' + token} if token is not None else {}
    request = urllib.request.Request(url, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read(2048))
    except urllib.error.HTTPError as ex:
        return ex.code, json.loads(ex.read(2048)) if ex.headers.get('content-type', '').startswith('application/json') else None


def verify_response(base: str, case: str, token: str, expired_token: str) -> None:
    if case == 'jwt':
        url = base + '/bench/jwt'
        status, body = http_json(url, token=token)
        if status != 200 or body != {'subject':'bench-user','authorized':True}:
            raise ValueError('JWT positive request failed')
        for invalid in (None, expired_token, token[:-1] + ('A' if token[-1] != 'A' else 'B')):
            status, _ = http_json(url, token=invalid)
            if status != 401: raise ValueError('JWT rejected-token contract failed')
    elif case in DELAYS:
        delay = DELAYS[case]
        status, body = http_json(base + case_uri(case))
        if status != 200 or body != {'value':'ok','delay_ms':delay}:
            raise ValueError(f'{case}: unexpected upstream-backed reply {status} {body}')
    else: raise ValueError('Unknown case: ' + case)


def hey_command(base: str, case: str, concurrency: int, duration: str, token: str) -> list[str]:
    args = ['hey','-o','csv','-c',str(concurrency),'-z',duration,'-m','GET','-t','15']
    if case == 'jwt': args += ['-H','Authorization: Bearer ' + token]
    return args + [base + case_uri(case)]


def upstream_url(port: int, path: str) -> str:
    return f'http://127.0.0.1:{port}{path}'


def upstream_stats(port: int) -> dict:
    status, body = http_json(upstream_url(port, '/_stats'))
    if status != 200 or set(body) != {'10','50','200'}: raise ValueError('Unexpected upstream stats')
    return body


def reset_upstream(port: int) -> None:
    status, body = http_json(upstream_url(port, '/_reset'), method='POST')
    if status != 200 or body != {'ok': True}: raise RuntimeError('Upstream reset failed')


def await_upstream(proc, port: int, timeout=12):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if proc.poll() is not None: raise RuntimeError('Upstream exited before readiness')
        try:
            code, data = http_json(upstream_url(port,'/health'), timeout=1)
            if code == 200 and data == {'ok': True}: return
        except (OSError, ValueError): pass
        time.sleep(.1)
    raise TimeoutError('Timed out waiting for delayed upstream')


def save_rows(directory: Path, rows: list[dict]) -> None:
    raw = directory / 'async-jwt-results.csv'
    tmp = raw.with_suffix('.csv.tmp')
    with tmp.open('w', newline='', encoding='utf-8') as fp:
        wr = csv.DictWriter(fp, fieldnames=FIELDS)
        wr.writeheader(); wr.writerows(rows)
    tmp.replace(raw)
    raw = directory / 'async-jwt-results.json'; tmp = raw.with_suffix('.json.tmp')
    tmp.write_text(json.dumps(rows, indent=2), encoding='utf-8');tmp.replace(raw)


def env_for(framework: str, args, port: int, upstream_port: int, secret: str) -> dict:
    env = {**os.environ, 'PORT':str(port), 'BENCH_UPSTREAM_URL':upstream_url(upstream_port,''),
           'BENCH_JWT_SECRET':secret,
           'MAX_IN_FLIGHT':str(max(2048, args.concurrency * 2)),
           'APP_ENV':'production','APP_DEBUG':'false', 'SESSION_DRIVER':'array',
           'CACHE_STORE':'array','QUEUE_CONNECTION':'sync','LOG_CHANNEL':'stderr'}
    return env


def run_framework(framework, repeat, args, directory: Path, rows: list, upstream_port: int, token: str, expired_token: str, secret: str):
    port=select_server_port(args.port)
    base=f'http://127.0.0.1:{port}'
    ns=argparse.Namespace(**vars(args));ns.port=port
    label=result_framework(framework,args)
    with (directory/f'{label}-repeat-{repeat}.log').open('w',encoding='utf-8') as log:
        proc=start_server(server_command(framework,ns),framework,log,env_for(framework,args,port,upstream_port,secret))
        try:
            wait_until_ready(proc,base,timeout_s=75 if framework=='laravel' else 40)
            for case in args.cases:
                verify_response(base,case,token,expired_token)
                if not is_zero_duration(args.warmup):
                    warm=subprocess.run(hey_command(base,case,min(args.concurrency,16),args.warmup,token),
                                        stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
                    if warm.returncode: raise RuntimeError(f'{label}/{case} warmup failed: {warm.stderr[-500:]}')
                reset_upstream(upstream_port)
                baseline, _ = (process_metrics(proc.pid,args.metrics_backend) if framework!='laravel' else (None,None))
                csv_path=directory/f'raw-{label}-{case}-r{repeat}.csv'
                try:
                    stats=run_load(proc.pid,hey_command(base,case,args.concurrency,args.duration,token),
                                   csv_path,baseline,args.metrics_backend,
                                   collect_process_metrics=framework!='laravel')
                finally:
                    if not args.keep_raw: csv_path.unlink(missing_ok=True)
                counts=upstream_stats(upstream_port)
                observed=counts[str(DELAYS[case])] if case in DELAYS else None
                side_effect_ok = (observed >= stats['ok_2xx'] > 0) if case in DELAYS else stats['ok_2xx'] > 0
                row={'framework':label,'case':case,'repeat':repeat,'port':port,
                     'upstream_port':upstream_port,'concurrency':args.concurrency,**stats,
                     'side_effect_ok':side_effect_ok,'upstream_completed':observed,
                     'auth_validation_ok':True if case=='jwt' else None}
                rows.append(row);save_rows(directory,rows)
                print(f'{label:17} {case:9} #{repeat}: {row["rps"]:.1f} RPS; '
                      f'p99={row["p99_ms"]:.2f}ms; errors={row["errors"]}; verified={side_effect_ok}',flush=True)
                if not side_effect_ok: raise RuntimeError(f'{label}/{case}: missing upstream calls or successful JWT checks')
                if stats['errors']:
                    raise RuntimeError(f'{label}/{case}: HTTP errors={stats["errors"]} (invalid test run)')
        finally: stop_server(proc)


def make_parser():
    p=argparse.ArgumentParser(description='Real external async HTTP and HMAC-verified JWT benchmarks')
    p.add_argument('--frameworks',nargs='+',choices=AVAILABLE_FRAMEWORKS,default=list(AVAILABLE_FRAMEWORKS))
    p.add_argument('--cases',nargs='+',choices=CASES,default=list(CASES))
    p.add_argument('--laravel-server',choices=['octane','artisan'],default='octane')
    p.add_argument('--laravel-workers',type=int,default=4)
    p.add_argument('--duration',default='30s');p.add_argument('--warmup',default='5s')
    p.add_argument('--concurrency',type=int,default=64);p.add_argument('--repeats',type=int,default=3)
    p.add_argument('--port',type=int,default=18080)
    p.add_argument('--upstream-port',type=int,default=19090)
    p.add_argument('--heap',default='512m')
    p.add_argument('--output',type=Path,default=ROOT/'benchmarks/results')
    p.add_argument('--keep-raw',action='store_true')
    return p


def main(argv=None):
    parser=make_parser();args=parser.parse_args(argv)
    try:
        args.duration=validate_duration(args.duration)
        args.warmup=validate_duration(args.warmup,allow_zero=True)
    except ValueError as ex:parser.error(str(ex))
    if min(args.concurrency,args.repeats,args.laravel_workers) < 1 or args.concurrency > 4096:
        parser.error('concurrency 1..4096, repeats/workers positive required')
    if any(not 1024 <= port <= 65535 for port in (args.port,args.upstream_port)):
        parser.error('port/upstream-port must be between 1024 and 65535')
    if args.port == args.upstream_port:parser.error('Application and upstream ports must differ')
    for name in ['hey'] + (['java'] if any(f!='laravel' for f in args.frameworks) else []) + (['php'] if 'laravel' in args.frameworks else []):
        if not shutil.which(name):parser.error(f'Missing executable: {name}')
    for framework in args.frameworks:
        try:require_installed(framework,args)
        except ValueError as err:parser.error(str(err))
    args.metrics_backend=metrics_backend()
    upstream_port=select_server_port(args.upstream_port)
    if upstream_port == args.port:parser.error('Selected upstream port collides with server port')
    secret=secrets.token_urlsafe(48)
    token=make_jwt(secret);expired_token=make_jwt(secret, expired=True)
    output=args.output/('async-jwt-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    output.mkdir(parents=True,exist_ok=False)
    meta={'benchmark':'async-jwt','timestamp_utc':datetime.now(timezone.utc).isoformat(),
          'platform':platform.platform(),'python':sys.version.split()[0],
          'frameworks':args.frameworks,'cases':args.cases,'duration':args.duration,
          'warmup':args.warmup,'concurrency':args.concurrency,'repeats':args.repeats,
          'laravel_server':args.laravel_server,'laravel_workers':args.laravel_workers,
          'heap':args.heap,'upstream_port':upstream_port,'jwt_algorithm':'HS256',
          'jwt_secret_sha256':hashlib.sha256(secret.encode()).hexdigest(),
          'jwt_subject':'bench-user','metrics_backend':args.metrics_backend,
          'fastframe_version':project_version(ROOT/'pom.xml'),
          'micronaut_version':project_version(ROOT/'benchmarks/micronaut-app/pom.xml'),
          'note':'Loopback asyncio server handles real outbound HTTP and delayed responses. '
                 'Java uses shared HttpClient.sendAsync; PHP Laravel Http client occupies workers while waiting. '
                 'JWT benchmark strictly validates HMAC and fixture claims; it is not full middleware auth. '
                 'RSS/CPU of Laravel workers not measured. hey may limit local throughput. '
                 'Input tokens and signing secrets are never saved in reports.',
          'command':[x for x in sys.argv if 'Bearer ' not in x]}
    if 'laravel' in args.frameworks: meta.update(laravel_metadata())
    (output/'environment.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
    rows=[];status=0
    with (output/'upstream.log').open('w',encoding='utf-8') as log:
        upstream=start_server([sys.executable,str(ROOT/'benchmarks/upstream.py'),'--port',str(upstream_port)],
                              'upstream',log,{**os.environ})
        try:
            await_upstream(upstream,upstream_port)
            for repeat in range(1,args.repeats+1):
                order=list(args.frameworks)
                if repeat%2==0:order.reverse()
                for framework in order:
                    run_framework(framework,repeat,args,output,rows,upstream_port,token,expired_token,secret)
        except (Exception,KeyboardInterrupt) as ex:
            print(f'Async/JWT benchmark stopped: {ex}',file=sys.stderr,flush=True)
            status=1
        finally:
            stop_server(upstream)
            save_rows(output,rows)
            try:make_reports(rows,meta,output)
            except Exception as ex:
                print(f'Report generation failed: {ex}',file=sys.stderr)
                status=1
            print(f'Reports: {output / "report.html"} | {output / "report.md"}',flush=True)
    if not rows or any(not row['side_effect_ok'] or int(row['errors']) > 0 for row in rows):
        status=max(status,2)
    return status


if __name__ == '__main__':raise SystemExit(main())
