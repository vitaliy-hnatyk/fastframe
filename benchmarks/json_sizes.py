#!/usr/bin/env python3
"""FastFrame v0.22 fixed-size JSON serialization benchmark.

Three identical JSON documents (per size) across FastFrame/Micronaut/Laravel.
The strings are cached but serialized afresh by each server on each HTTP call.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from concurrency import is_zero_duration, validate_duration
from run import (AVAILABLE_FRAMEWORKS, ROOT, laravel_metadata, metrics_backend,
                 process_metrics, project_version, require_installed,
                 result_framework, run_load, select_server_port,
                 server_command, start_server, stop_server, wait_until_ready)
from json_sizes_report import make_reports

SIZES = {'1kb': 1024, '10kb': 10 * 1024, '100kb': 100 * 1024,
         '1mb': 1024 * 1024}
FIELDS = ('framework', 'size', 'bytes', 'repeat', 'port', 'concurrency', 'requests',
          'ok_2xx', 'errors', 'duration_s', 'rps', 'mib_s', 'p50_ms', 'p95_ms',
          'p99_ms', 'mean_ms', 'rss_baseline_mb', 'rss_peak_mb', 'cpu_cores_avg')


def payload(size: str) -> bytes:
    """Exact, canonical UTF-8 bytes: {\"data\":\"xxx...\"}."""
    return b'{"data":"' + b'x' * (SIZES[size] - 11) + b'"}'


def endpoint(size: str) -> str:
    if size not in SIZES:
        raise ValueError(f'Unknown JSON size: {size}')
    return '/bench/json-size/' + size


def verify_payload(base_url: str, size: str, timeout: float = 15) -> dict:
    """Reject wrong HTTP status, JSON key, UTF-8 byte count or content."""
    expected = payload(size)
    with urllib.request.urlopen(base_url + endpoint(size), timeout=timeout) as response:
        actual = response.read(SIZES[size] + 1)
        content_type = response.headers.get('Content-Type', '')
        if response.status != 200 or not content_type.lower().startswith('application/json'):
            raise ValueError(f'Invalid status/type for {size}: {response.status} {content_type}')
        if len(actual) != len(expected):
            raise ValueError(f'{size}: expected {len(expected)} bytes, got {len(actual)}')
        if actual != expected:
            raise ValueError(f'{size}: JSON mismatch / different body across frameworks')
        if json.loads(actual)['data'] != 'x' * (SIZES[size] - 11):
            raise ValueError(f'{size}: invalid JSON fixture')
        if response.headers.get('Content-Length') not in (None, str(len(expected))):
            raise ValueError(f'{size}: incorrect Content-Length header')
        return {'size': size, 'bytes': len(expected),
                'sha256': hashlib.sha256(actual).hexdigest()}


def hey_for_json_size(base: str, size: str, concurrency: int, duration: str) -> list[str]:
    return ['hey', '-o', 'csv', '-c', str(concurrency), '-z', duration,
            '-m', 'GET', '-t', '20', base + endpoint(size)]


def save_rows(directory: Path, rows: list[dict]) -> None:
    # Atomic snapshots survive Ctrl-C / interrupted benchmarks.
    dest = directory / 'json-sizes-results.csv'
    tmp = dest.with_suffix('.csv.tmp')
    with tmp.open('w', newline='', encoding='utf-8') as out:
        writer = csv.DictWriter(out, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    tmp.replace(dest)
    raw_json = directory / 'json-sizes-results.json'
    tmp_json = raw_json.with_suffix('.json.tmp')
    tmp_json.write_text(json.dumps(rows, indent=2), encoding='utf-8')
    tmp_json.replace(raw_json)


def run_framework(framework: str, repeat: int, args: argparse.Namespace,
                  directory: Path, rows: list[dict]) -> None:
    port = select_server_port(args.port)
    url = f'http://127.0.0.1:{port}'
    label = result_framework(framework, args)
    server_args = argparse.Namespace(**vars(args))
    server_args.port = port
    env = {**os.environ, 'PORT': str(port),
           'MAX_IN_FLIGHT': str(max(2048, args.concurrency * 2))}
    if framework == 'laravel':
        env.update({'APP_ENV':'production', 'APP_DEBUG':'false',
                    'SESSION_DRIVER':'array', 'CACHE_STORE':'array',
                    'QUEUE_CONNECTION':'sync', 'LOG_CHANNEL':'stderr'})
    log = directory / f'{label}-run-{repeat}.log'
    with log.open('w', encoding='utf-8') as logfile:
        proc = start_server(server_command(framework, server_args), framework, logfile, env)
        try:
            wait_until_ready(proc, url, timeout_s=60 if framework == 'laravel' else 35)
            for size in args.sizes:
                # Required before *each* size run; same expected bytes for all servers.
                verified = verify_payload(url, size)
                print(f'Validated {label} / {size}: {verified["bytes"]} bytes, '
                      f'SHA256={verified["sha256"][:12]}...', flush=True)
                if proc.poll() is not None:
                    raise RuntimeError(f'{label} exited; inspect {log}')
                if not is_zero_duration(args.warmup):
                    warm = subprocess.run(hey_for_json_size(url,size,min(args.concurrency,16),args.warmup),
                                          stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
                    if warm.returncode != 0:
                        raise RuntimeError(f'Warmup failed for {label}/{size}: {warm.stderr}')
                baseline, _ = (process_metrics(proc.pid, args.metrics_backend)
                               if framework != 'laravel' else (None,None))
                raw = directory / f'raw-{label}-{size}-r{repeat}.csv'
                try:
                    stats = run_load(proc.pid, hey_for_json_size(url,size,args.concurrency,args.duration),
                                     raw, baseline, args.metrics_backend,
                                     collect_process_metrics=(framework != 'laravel'))
                finally:
                    if not args.keep_raw:
                        raw.unlink(missing_ok=True)
                stats['mib_s'] = round(stats['rps'] * SIZES[size] / (1024 * 1024), 3)
                row = {'framework':label,'size':size,'bytes':SIZES[size], 'repeat':repeat,
                       'port':port,'concurrency':args.concurrency,**stats}
                rows.append(row)
                save_rows(directory,rows)
                print(f'{label:17} {size:5} #{repeat}: {row["rps"]:9.1f} RPS; '
                      f'{row["mib_s"]:8.1f} MiB/s; p99={row["p99_ms"]:7.2f}ms; '
                      f'errors={row["errors"]}',flush=True)
        finally:
            stop_server(proc)


def configuration(args: argparse.Namespace) -> dict:
    doc = {'benchmark':'json-sizes','schema':1,
           'timestamp_utc':datetime.now(timezone.utc).isoformat(),
           'platform':platform.platform(), 'processor':platform.processor(),
           'cpu_count':os.cpu_count(),'python':sys.version.split()[0],
           'frameworks':args.frameworks,'sizes':args.sizes,
           'bytes':{k:SIZES[k] for k in args.sizes},
           'payload_sha256':{k:hashlib.sha256(payload(k)).hexdigest() for k in args.sizes},
           'duration':args.duration,'warmup':args.warmup,
           'concurrency':args.concurrency,'repeats':args.repeats,
           'port':args.port, 'laravel_server':args.laravel_server,
           'laravel_workers':args.laravel_workers,'heap':args.heap,
           'metrics_backend':args.metrics_backend,
           'measurement_note':'Bytes are exact UTF-8 JSON lengths, not application data length. '
                              'MiB/s = measured HTTP RPS * response payload bytes / 2^20, '
                              'excluding HTTP headers/TCP framing.',
           'validity_note':'Preflight validates exact payload content per server and size. '
                           'The hey load phase counts HTTP statuses but does not inspect '
                           'every response body; validate checksum before each run.',
           'load_note':'hey -o csv and server run on the same host by default. '
                       'Large responses can saturate the loopback, CPU or load generator.',
           'rss_note':'Laravel workers are subprocesses, so RSS/CPU omitted.',
           'fastframe_version':project_version(ROOT/'pom.xml'),
           'micronaut_version':project_version(ROOT/'benchmarks/micronaut-app/pom.xml'),
           'command':sys.argv}
    if 'laravel' in args.frameworks:
        doc.update(laravel_metadata())
    return doc


def make_parser():
    p=argparse.ArgumentParser(description='JSON sizes (1 KiB..1 MiB) FastFrame/Micronaut/Laravel benchmark')
    p.add_argument('--frameworks',nargs='+',choices=AVAILABLE_FRAMEWORKS,default=list(AVAILABLE_FRAMEWORKS))
    p.add_argument('--laravel-server',choices=['octane','artisan'],default='octane')
    p.add_argument('--laravel-workers',type=int,default=4)
    p.add_argument('--sizes',nargs='+',choices=list(SIZES),default=list(SIZES))
    p.add_argument('--duration',default='30s')
    p.add_argument('--warmup',default='5s')
    p.add_argument('--concurrency',type=int,default=64)
    p.add_argument('--repeats',type=int,default=3)
    p.add_argument('--heap',default='512m',help='Same Java max heap across both JVMs')
    p.add_argument('--port',type=int,default=18080)
    p.add_argument('--output',type=Path,default=ROOT/'benchmarks/results')
    p.add_argument('--keep-raw',action='store_true')
    return p


def main(argv=None):
    p=make_parser()
    args=p.parse_args(argv)
    try:
        args.duration=validate_duration(args.duration)
        args.warmup=validate_duration(args.warmup,allow_zero=True)
    except ValueError as exc:
        p.error(str(exc))
    if args.repeats<1 or args.laravel_workers<1 or not 1<=args.concurrency<=4096:
        p.error('repeats/workers/concurrency must be positive; concurrency <= 4096')
    if not 1024<=args.port<=65535:
        p.error('port must be in 1024..65535')
    if not (sys.platform.startswith('linux') or sys.platform in ('darwin','win32')):
        p.error('Supported platforms: macOS, Linux, Windows')
    for binary in (['hey'] + (['java'] if any(f!='laravel' for f in args.frameworks) else [])
                   + (['php'] if 'laravel' in args.frameworks else [])):
        if not shutil.which(binary):
            p.error(f'Missing executable: {binary}')
    for framework in args.frameworks:
        try:
            require_installed(framework,args)
        except ValueError as exc:
            p.error(str(exc))
    args.metrics_backend=metrics_backend()
    destination=args.output/('json-sizes-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    destination.mkdir(parents=True,exist_ok=False)
    metadata=configuration(args)
    (destination/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    rows=[]
    status=0
    try:
        for repeat in range(1,args.repeats+1):
            order=list(args.frameworks)
            if repeat%2==0:
                order.reverse()
            for framework in order:
                run_framework(framework,repeat,args,destination,rows)
    except (Exception,KeyboardInterrupt) as exc:
        print(f'JSON sizes benchmark interrupted: {exc}',file=sys.stderr,flush=True)
        status=1
    finally:
        save_rows(destination,rows)
        try:
            make_reports(rows,metadata,destination)
            print(f'Reports: {destination/"report.html"} | {destination/"report.md"}',flush=True)
        except Exception as exc:
            print(f'Report generation failed: {exc}',file=sys.stderr)
            status=1
    if not rows or any(int(r['errors'])>0 for r in rows):
        status=max(status,2)
    return status


if __name__=='__main__':
    raise SystemExit(main())
