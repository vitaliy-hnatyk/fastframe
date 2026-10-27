#!/usr/bin/env python3
"""Real PostgreSQL + Redis workload benchmark for FastFrame, Micronaut, Laravel Octane.

This tool NEVER silently substitutes H2, array cache or stub network handlers.
Use only local Docker services created from benchmarks/services/compose.yaml.
"""
from __future__ import annotations
import argparse
from collections import Counter
import csv
import gzip
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from run import (ROOT, AVAILABLE_FRAMEWORKS, project_version, laravel_metadata,
                 require_installed, result_framework, select_server_port,
                 server_command, start_server, stop_server, wait_until_ready,
                 run_load, process_metrics, metrics_backend)
from concurrency import validate_duration, is_zero_duration
from db_redis_report import make_reports

COMPOSE=ROOT/'benchmarks/services/compose.yaml'
CASES={
    'select':('GET','/bench/db/select',{'value':'ok'}),
    'insert':('POST','/bench/db/insert',{'ok':True}),
    'update':('POST','/bench/db/update',{'ok':True}),
    'transaction':('POST','/bench/db/transaction',{'ok':True}),
    'cache_get':('GET','/bench/cache/get',{'value':'ok'}),
    'cache_set':('POST','/bench/cache/set',{'ok':True}),
}
FIELDS=['framework','case','repeat','port','concurrency','requests','ok_2xx',
        'errors','status_counts','duration_s','rps','p50_ms','p95_ms','p99_ms','mean_ms',
        'rss_baseline_mb','rss_peak_mb','cpu_cores_avg','side_effect_ok','db_observed','redis_observed']


def compose_cmd(*tail):
    return ['docker','compose','-f',str(COMPOSE),*tail]


def docker_run(*tail, timeout=45):
    proc=subprocess.run(compose_cmd(*tail),capture_output=True,text=True,timeout=timeout)
    if proc.returncode:
        raise RuntimeError('Docker Compose failed: '+' '.join(tail)+'\n'+proc.stderr[-1200:])
    return proc.stdout.strip()


def compose_failure_diagnostics():
    """Collect bounded container status/logs so CI reports the underlying SQL error."""
    sections=[]
    for command in (('ps','-a'),('logs','--no-color','--tail','100','postgres','redis')):
        try:
            result=subprocess.run(compose_cmd(*command),capture_output=True,text=True,timeout=20)
            output=('\n'.join(part for part in (result.stdout,result.stderr) if part)).strip()
            sections.append('$ docker compose '+' '.join(command)+'\n'+(output[-12000:] or '(no output)'))
        except (OSError,subprocess.TimeoutExpired) as ex:
            sections.append('$ docker compose '+' '.join(command)+'\n(diagnostics unavailable: '+str(ex)+')')
    return '\n'.join(sections)


def start_services():
    # Benchmark-only Compose stack. Discard anonymous PostgreSQL data volumes left
    # by failed init scripts; restarting such a container may skip schema creation.
    try:
        docker_run('up','-d','--wait','--force-recreate','--renew-anon-volumes',timeout=180)
        # Compose checks health; verify PostgreSQL accepts the benchmark connection.
        readiness=docker_run('exec','-T','postgres','pg_isready','-U','ffbench','-d','ffbench')
        if not readiness.endswith('accepting connections'):
            raise RuntimeError('PostgreSQL not ready: '+readiness)
        counters=db_query('SELECT id,counter_value FROM bench_counter ORDER BY id;').splitlines()
        if counters!=['1|0','2|0']:
            raise RuntimeError('PostgreSQL seed data not initialized: '+repr(counters))
        if docker_run('exec','-T','redis','redis-cli','--raw','PING')!='PONG':
            raise RuntimeError('Redis not ready')
    except (RuntimeError,subprocess.TimeoutExpired) as ex:
        raise RuntimeError(str(ex)+'\nDocker Compose diagnostics:\n'+compose_failure_diagnostics()) from ex


def reset_state():
    docker_run('exec','-T','postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1',
               '-U','ffbench','-d','ffbench','-c',
               'TRUNCATE bench_events RESTART IDENTITY; UPDATE bench_counter SET counter_value=0;')
    docker_run('exec','-T','redis','redis-cli','--raw','DEL','ffbench:set','ffbench:hot')
    docker_run('exec','-T','redis','redis-cli','--raw','SET','ffbench:hot','ok')


def db_query(sql):
    return docker_run('exec','-T','postgres','psql','-X','-qAt','-v','ON_ERROR_STOP=1',
                      '-U','ffbench','-d','ffbench','-c',sql)


def redis_get(key):
    return docker_run('exec','-T','redis','redis-cli','--raw','GET',key)


def side_effect(case, observed_requests):
    """Verify the underlying backends changed, not merely HTTP status codes.
    Not equality to hey requests: a 5xx can result after commit but before response.
    Require >=1 write for nonempty successful runs.
    """
    if case=='select':
        value=db_query('SELECT payload FROM bench_lookup WHERE id=1;')
        return value=='ok',value,None
    if case=='insert':
        count=int(db_query('SELECT count(*) FROM bench_events;'))
        return count==observed_requests and count>=1,str(count),None
    if case=='update':
        count=int(db_query('SELECT counter_value FROM bench_counter WHERE id=1;'))
        return count==observed_requests and count>=1,str(count),None
    if case=='transaction':
        counts=db_query('SELECT counter_value FROM bench_counter ORDER BY id;').splitlines()
        return len(counts)==2 and counts[0]==counts[1] and int(counts[0])==observed_requests and int(counts[0])>=1, ','.join(counts),None
    if case=='cache_get':
        value=redis_get('ffbench:hot')
        return value=='ok',None,value
    if case=='cache_set':
        value=redis_get('ffbench:set')
        return value=='ok',None,value
    raise ValueError(f'Unknown case {case}')


def request_spec(case,base_url):
    if case not in CASES: raise ValueError('Unknown benchmark case: '+case)
    method,uri,_=CASES[case]
    return method,base_url+uri


def verify_response(base_url, case, timeout=8):
    method,url=request_spec(case,base_url)
    # Use the exact POST media type configured for the measured hey requests.
    # An empty POST without Content-Type may pass a preflight even though hey's
    # default (text/html) is rejected by Micronaut with HTTP 415.
    headers={'Accept':'application/json'}
    if method=='POST':
        headers['Content-Type']='application/json'
    request=urllib.request.Request(url, method=method, headers=headers)
    with urllib.request.urlopen(request,timeout=timeout) as reply:
        body=reply.read(4096)
        if reply.status!=200 or not reply.headers.get('Content-Type','').lower().startswith('application/json'):
            raise ValueError(f'{case}: incorrect HTTP {reply.status} / content type')
    expected=CASES[case][2]
    if json.loads(body)!=expected:
        raise ValueError(f'{case}: expected {expected!r}; got {body!r}')


def hey_command(base_url,case,concurrency,duration):
    method,url=request_spec(case,base_url)
    command=['hey','-o','csv','-c',str(concurrency),'-z',duration,
             '-m',method,'-t','20']
    # hey defaults to Content-Type: text/html; Micronaut @Post consumes JSON.
    # Preflight, warmup and measured requests must share the same contract.
    if method=='POST':
        command.extend(['-T','application/json'])
    command.append(url)
    return command


def save_rows(directory,rows):
    file=directory/'db-redis-results.csv'
    tmp=file.with_suffix('.csv.tmp')
    with tmp.open('w',newline='',encoding='utf-8') as out:
        wr=csv.DictWriter(out,fieldnames=FIELDS);wr.writeheader();wr.writerows(rows)
    tmp.replace(file)
    raw=directory/'db-redis-results.json';temp=raw.with_suffix('.json.tmp')
    temp.write_text(json.dumps(rows,indent=2),encoding='utf-8');temp.replace(raw)


def response_status_counts(path: Path) -> dict[str, int]:
    """Count actual hey response statuses, including status 0 (transport errors).

    Only rows accepted by run.csv_statistics() are counted so the histogram
    sums to the same number of measured requests. Do not infer 500/429 from
    aggregated error counts: the raw statuses must support that diagnosis.
    """
    counts: Counter[str] = Counter()
    if not path.is_file():
        return {}
    with path.open(newline='', encoding='utf-8') as source:
        rows = csv.DictReader(source)
        if not rows.fieldnames or not {'response-time', 'status-code'}.issubset(rows.fieldnames):
            return {}
        for row in rows:
            if not row.get('response-time'):
                continue
            try:
                status = str(int(row['status-code']))
            except (ValueError, TypeError, KeyError):
                status = 'unparseable'
            counts[status] += 1
    return dict(sorted(counts.items(), key=lambda item: item[0]))


def save_failure_diagnostics(directory: Path, label: str, case: str, repeat: int,
                             raw: Path, logfile: Path, stats: dict | None,
                             db_observed: str | None = None,
                             redis_observed: str | None = None,
                             cause: str | None = None) -> Path:
    """Preserve evidence before stopping the benchmark process.

    Do not retry write endpoints here: that would mutate PostgreSQL after the
    measured phase and falsify the side-effect count.
    """
    counts = response_status_counts(raw)
    try:
        # 120 lines max; never emit entire potentially multi-hour server logs.
        log_tail = '\n'.join(logfile.read_text(encoding='utf-8', errors='replace').splitlines()[-120:])[-18000:]
    except OSError:
        log_tail = '(server log unavailable)'
    details = {'framework': label, 'case': case, 'repeat': repeat,
               'http_status_counts': counts,
               'stats': stats, 'db_observed': db_observed,
               'redis_observed': redis_observed,
               'error': cause, 'server_log_file': logfile.name,
               'server_log_tail': log_tail}
    destination = directory / f'diagnostics-{label}-{case}-r{repeat}.json'
    tmp = destination.with_suffix('.json.tmp')
    tmp.write_text(json.dumps(details, indent=2, ensure_ascii=False), encoding='utf-8')
    tmp.replace(destination)
    print(f'\n{label}/{case}: FAILED; hey HTTP status counts: {counts}; '
          f'db_observed={db_observed}; redis_observed={redis_observed}\n'
          f'Diagnostics: {destination}\n'
          f'Last server log lines:\n{log_tail[-8000:] or "(no server log output)"}',
          file=sys.stderr, flush=True)
    return destination


def preserve_failed_load(raw: Path, keep_raw: bool) -> Path | None:
    """Retain failed hey CSV; compress by default for CI artifact storage."""
    if not raw.is_file():
        return None
    if keep_raw:
        return raw
    compressed = raw.with_suffix(raw.suffix + '.gz')
    with raw.open('rb') as source, gzip.open(compressed, 'wb', compresslevel=6) as dest:
        shutil.copyfileobj(source, dest)
    raw.unlink()
    return compressed


def env_for(framework,args,port):
    env={**os.environ,'PORT':str(port),'MAX_IN_FLIGHT':str(max(2048,args.concurrency*2)),
         'BENCH_DB_POOL':str(args.db_pool),'BENCH_IO_WORKERS':str(args.io_workers),
         'BENCH_JDBC_URL':'jdbc:postgresql://127.0.0.1:55432/ffbench',
         'BENCH_DB_USER':'ffbench','BENCH_DB_PASSWORD':'ffbench',
         'BENCH_REDIS_URL':'redis://127.0.0.1:56379',
         'APP_ENV':'production','APP_DEBUG':'false','SESSION_DRIVER':'array',
         'QUEUE_CONNECTION':'sync','LOG_CHANNEL':'stderr'}
    if framework=='laravel':
        env.update({'DB_CONNECTION':'pgsql','DB_HOST':'127.0.0.1','DB_PORT':'55432',
                    'DB_DATABASE':'ffbench','DB_USERNAME':'ffbench','DB_PASSWORD':'ffbench',
                    'REDIS_CLIENT':'predis','REDIS_HOST':'127.0.0.1','REDIS_PORT':'56379',
                    'REDIS_DB':'0','REDIS_CACHE_DB':'0','REDIS_PREFIX':'',
                    'REDIS_URL':'redis://127.0.0.1:56379/0',
                    'CACHE_STORE':'array'})
    return env


def run_framework(framework,repeat,args,directory,rows):
    port=select_server_port(args.port)
    base_url=f'http://127.0.0.1:{port}'
    label=result_framework(framework,args)
    namespace=argparse.Namespace(**vars(args));namespace.port=port
    server_log=directory/f'{label}-repeat-{repeat}.log'
    with server_log.open('w',encoding='utf-8') as logfile:
        proc=start_server(server_command(framework,namespace),framework,logfile,env_for(framework,args,port))
        try:
            wait_until_ready(proc,base_url,timeout_s=75 if framework=='laravel' else 40)
            for case in args.cases:
                reset_state()
                verify_response(base_url,case)
                ok,_,_=side_effect(case,1)
                if not ok:raise RuntimeError(f'{label}/{case}: preflight side effect failed')
                if not is_zero_duration(args.warmup):
                    warm=subprocess.run(hey_command(base_url,case,min(args.concurrency,16),args.warmup),
                                        stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
                    if warm.returncode:raise RuntimeError(f'{label}/{case} warmup: {warm.stderr}')
                # Preflight and warmup should never affect measured DB state.
                reset_state()
                baseline,_=(process_metrics(proc.pid,args.metrics_backend)
                            if framework!='laravel' else (None,None))
                raw=directory/f'raw-{label}-{case}-r{repeat}.csv'
                try:
                    stats=run_load(proc.pid,hey_command(base_url,case,args.concurrency,args.duration),
                                   raw,baseline,args.metrics_backend,
                                   collect_process_metrics=framework!='laravel')
                except Exception as ex:
                    logfile.flush()
                    save_failure_diagnostics(directory,label,case,repeat,raw,server_log,
                                             stats=None,cause=f'hey/runner: {ex}')
                    preserve_failed_load(raw,args.keep_raw)
                    raise
                counts=response_status_counts(raw)
                try:
                    ok,db_value,redis_value=side_effect(case,stats['ok_2xx'])
                except Exception as ex:
                    logfile.flush()
                    save_failure_diagnostics(directory,label,case,repeat,raw,server_log,
                                             stats=stats,cause=f'post-run verification: {ex}')
                    preserve_failed_load(raw,args.keep_raw)
                    raise
                row={'framework':label,'case':case,'repeat':repeat,
                     'port':port,'concurrency':args.concurrency,**stats,
                     'status_counts':json.dumps(counts,sort_keys=True),
                     'side_effect_ok':ok,'db_observed':db_value,'redis_observed':redis_value}
                rows.append(row);save_rows(directory,rows)
                print(f'{label:17} {case:11} #{repeat}: {row["rps"]:9.1f} RPS; '
                      f'p99={row["p99_ms"]:.2f}ms; errors={row["errors"]}; persisted={ok}',flush=True)
                if stats['errors'] or not ok:
                    logfile.flush()
                    save_failure_diagnostics(directory,label,case,repeat,raw,server_log,
                                             stats=stats,db_observed=db_value,
                                             redis_observed=redis_value,
                                             cause=f'HTTP errors={stats["errors"]}, side_effect_ok={ok}')
                    preserve_failed_load(raw,args.keep_raw)
                    raise RuntimeError(f'{label}/{case}: HTTP errors={stats["errors"]}; '
                                       f'side-effect verified={ok}; statuses={counts}; '
                                       f'db_observed={db_value}; inspect diagnostics and server log')
                if not args.keep_raw:
                    raw.unlink(missing_ok=True)
        finally:
            stop_server(proc)


def make_parser():
    p=argparse.ArgumentParser(description='Real PostgreSQL/Redis comparison (not mocks)')
    p.add_argument('--frameworks',nargs='+',choices=AVAILABLE_FRAMEWORKS,default=list(AVAILABLE_FRAMEWORKS))
    p.add_argument('--cases',nargs='+',choices=list(CASES),default=list(CASES))
    p.add_argument('--laravel-server',choices=['octane','artisan'],default='octane')
    p.add_argument('--laravel-workers',type=int,default=4)
    p.add_argument('--duration',default='30s');p.add_argument('--warmup',default='5s')
    p.add_argument('--concurrency',type=int,default=64);p.add_argument('--repeats',type=int,default=3)
    p.add_argument('--db-pool',type=int,default=16);p.add_argument('--io-workers',type=int,default=32)
    p.add_argument('--port',type=int,default=18080);p.add_argument('--heap',default='512m')
    p.add_argument('--output',type=Path,default=ROOT/'benchmarks/results')
    p.add_argument('--keep-raw',action='store_true');p.add_argument('--stop-services',action='store_true')
    return p


def main(argv=None):
    parser=make_parser();args=parser.parse_args(argv)
    try:
        args.duration=validate_duration(args.duration)
        args.warmup=validate_duration(args.warmup,allow_zero=True)
    except ValueError as ex:parser.error(str(ex))
    if any(v<1 for v in (args.concurrency,args.repeats,args.db_pool,args.io_workers,args.laravel_workers)):
        parser.error('concurrency/repeats/workers/pool must all be positive')
    if args.concurrency>4096 or args.db_pool>512 or args.io_workers>512:
        parser.error('concurrency<=4096, db-pool/io-workers<=512 required')
    if not 1024<=args.port<=65535:parser.error('port must be between 1024 and 65535')
    for name in ['docker','hey']+(['java'] if any(f!='laravel' for f in args.frameworks) else [])+(['php'] if 'laravel' in args.frameworks else []):
        if not shutil.which(name):parser.error(f'Missing executable: {name}')
    for f in args.frameworks:
        try:require_installed(f,args)
        except ValueError as err:parser.error(str(err))
    args.metrics_backend=metrics_backend()
    dest=args.output/('db-redis-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    dest.mkdir(parents=True,exist_ok=False)
    meta={'benchmark':'db-redis','timestamp_utc':datetime.now(timezone.utc).isoformat(),
          'platform':platform.platform(),'python':sys.version.split()[0],
          'frameworks':args.frameworks,'cases':args.cases,'duration':args.duration,
          'warmup':args.warmup,'concurrency':args.concurrency,'repeats':args.repeats,
          'laravel_server':args.laravel_server,'laravel_workers':args.laravel_workers,
          'db_pool':args.db_pool,'io_workers':args.io_workers,'heap':args.heap,
          'postgres_image':'postgres:16.6-alpine','redis_image':'redis:7.4.2-alpine',
          'host_db_port':55432,'host_redis_port':56379,'metrics_backend':args.metrics_backend,
          'fastframe_version':project_version(ROOT/'pom.xml'),
          'micronaut_version':project_version(ROOT/'benchmarks/micronaut-app/pom.xml'),
          'note':'Shared host may bottleneck generator/server/PostgreSQL/Redis. '
                 'Rows 1,2 are deliberately contended by writers. '
                 'Preflight and warmup are reset before measurement. '
                 'Laravel process-tree memory metrics unavailable.',
          'command':sys.argv}
    if 'laravel' in args.frameworks:meta.update(laravel_metadata())
    (dest/'environment.json').write_text(json.dumps(meta,indent=2),encoding='utf-8')
    rows=[];status=0
    try:
        start_services()
        for repeat in range(1,args.repeats+1):
            order=list(args.frameworks)
            if repeat%2==0:order.reverse()
            for framework in order:run_framework(framework,repeat,args,dest,rows)
    except (Exception,KeyboardInterrupt) as ex:
        print(f'Database/Redis benchmark stopped: {ex}',file=sys.stderr,flush=True)
        status=1
    finally:
        if args.stop_services:
            try:docker_run('down','--volumes','--remove-orphans',timeout=90)
            except Exception as ex:print(f'Compose cleanup warning: {ex}',file=sys.stderr)
        save_rows(dest,rows)
        try:make_reports(rows,meta,dest)
        except Exception as ex:
            print(f'Report generation failed: {ex}',file=sys.stderr)
            status=1
        print(f'Reports: {dest / "report.html"} | {dest / "report.md"}',flush=True)
    if not rows or any(not row['side_effect_ok'] or int(row['errors'])>0 for row in rows):
        status=max(status,2)
    return status

if __name__=='__main__':raise SystemExit(main())
