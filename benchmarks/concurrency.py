#!/usr/bin/env python3
"""Concurrency scaling: FastFrame, Micronaut, Laravel Octane; Python 3.10+.

30 seconds measured per (framework, case, level, repeat) by default.
Runs servers one at a time; refuses to reuse a live listener. All results are
observed HTTP samples (hey -o csv), not model estimates.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from run import (AVAILABLE_FRAMEWORKS, CASES, FIELDS, ROOT, assert_endpoint,
                 hey_command, laravel_metadata, metrics_backend,
                 process_metrics, project_version, require_installed,
                 result_framework, run_load, select_server_port, server_command,
                 start_server, stop_server, wait_until_ready)
from concurrency_report import make_reports

DEFAULT_LEVELS = (1, 8, 16, 32, 64, 128, 256, 512, 1024)


def positive_levels(values: list[int]) -> list[int]:
    if not values or any(n < 1 or n > 4096 for n in values):
        raise ValueError('Levels must be between 1 and 4096')
    if sorted(set(values)) != values:
        raise ValueError('Levels must be unique and strictly increasing')
    return values


def validate_duration(value: str, allow_zero: bool = False) -> str:
    if not re.fullmatch(r'\d+(?:\.\d+)?(?:ms|s|m)', value):
        raise ValueError('Duration must be a Go duration such as 30s, 500ms, or 1m')
    v = float(re.match(r'[\d.]+', value).group())
    if v == 0 and allow_zero:
        return value
    if v <= 0:
        raise ValueError('Duration must be greater than zero')
    return value


def is_zero_duration(value: str) -> bool:
    return float(re.match(r'[\d.]+', value).group()) == 0


def write_rows(directory: Path, rows: list[dict]) -> None:
    """Rewrite atomic CSV/JSON snapshots after each level (recoverable on SIGINT)."""
    temp = directory / 'concurrency-results.csv.tmp'
    with temp.open('w', newline='', encoding='utf-8') as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    temp.replace(directory / 'concurrency-results.csv')
    tmp_json = directory / 'concurrency-results.json.tmp'
    tmp_json.write_text(json.dumps(rows, indent=2), encoding='utf-8')
    tmp_json.replace(directory / 'concurrency-results.json')


def run_framework(framework: str, repeat: int, args: argparse.Namespace,
                  destination: Path, rows: list[dict]) -> None:
    port = select_server_port(args.port)
    label = result_framework(framework, args)
    server_args = argparse.Namespace(**vars(args))
    server_args.port = port
    url = f'http://127.0.0.1:{port}'
    env = {**os.environ, 'PORT': str(port),
           'MAX_IN_FLIGHT': str(max(2048, max(args.levels) * 2))}
    if framework == 'laravel':
        env.update({'APP_ENV': 'production', 'APP_DEBUG': 'false',
                    'SESSION_DRIVER': 'array', 'CACHE_STORE': 'array',
                    'QUEUE_CONNECTION': 'sync', 'LOG_CHANNEL': 'stderr'})
    log_path = destination / f'{label}-run-{repeat}.log'
    with log_path.open('w', encoding='utf-8') as logfile:
        process = start_server(server_command(framework, server_args), framework, logfile, env)
        try:
            wait_until_ready(process, url, timeout_s=60 if framework == 'laravel' else 35)
            for case in args.cases:
                assert_endpoint(url, case)
                for concurrency in args.levels:
                    if process.poll() is not None:
                        raise RuntimeError(f'{label} server died before c={concurrency}; see {log_path}')
                    if not is_zero_duration(args.warmup):
                        warm_command = hey_command(url, case, concurrency, args.warmup)
                        warm = subprocess.run(warm_command, stdout=subprocess.DEVNULL,
                                              stderr=subprocess.PIPE, text=True)
                        if warm.returncode != 0:
                            raise RuntimeError(f'Warmup failed for {label}/{case}/c={concurrency}: '
                                               f'{warm.stderr.strip()}')
                    # Keep Laravel process-tree resource metrics null, not misleading.
                    track = framework != 'laravel'
                    baseline, _ = process_metrics(process.pid, args.metrics_backend) if track else (None, None)
                    raw = destination / f'raw-{label}-{case}-c{concurrency}-r{repeat}.csv'
                    try:
                        stats = run_load(process.pid, hey_command(url, case, concurrency, args.duration),
                                         raw, baseline, args.metrics_backend,
                                         collect_process_metrics=track)
                    finally:
                        if not args.keep_raw:
                            raw.unlink(missing_ok=True)
                    row = {'framework': label, 'case': case, 'repeat': repeat,
                           'port': port, 'concurrency': concurrency, **stats}
                    rows.append(row)
                    write_rows(destination, rows)
                    print(f'{label:16} {case:5} c={concurrency:4} #{repeat}: '
                          f'{row["rps"]:10.2f} RPS  p99={row["p99_ms"]:7.2f}ms  '
                          f'errors={row["errors"]}', flush=True)
        finally:
            stop_server(process)


def configuration(args: argparse.Namespace) -> dict:
    data = {'benchmark': 'concurrency-scaling',
            'timestamp_utc': datetime.now(timezone.utc).isoformat(),
            'platform': platform.platform(), 'processor': platform.processor(),
            'cpu_count': os.cpu_count(), 'python': sys.version.split()[0],
            'frameworks': args.frameworks, 'cases': args.cases, 'levels': args.levels,
            'duration': args.duration, 'warmup': args.warmup, 'repeats': args.repeats,
            'preferred_port': args.port, 'heap': args.heap,
            'metrics_backend': args.metrics_backend,
            'rss_units': 'MiB', 'cpu_units': 'average cores',
            'laravel_server': args.laravel_server, 'laravel_workers': args.laravel_workers,
            'resource_metrics_note': 'Laravel RSS/CPU omitted: parent PID excludes workers',
            'load_generator_note': 'hey -o csv overhead; generator and server on same host can cap RPS',
            'raw_samples_retained': args.keep_raw, 'command': sys.argv,
            'fastframe_maven_version': project_version(ROOT / 'pom.xml'),
            'micronaut_maven_version': project_version(ROOT / 'benchmarks/micronaut-app/pom.xml'),
            'java_version': (subprocess.run(['java', '-version'], capture_output=True,
                                            text=True).stderr if shutil.which('java') else None),
            'php_version': (subprocess.run(['php', '-v'], capture_output=True, text=True).stdout.splitlines()[0]
                            if 'laravel' in args.frameworks and shutil.which('php') else None),
            'hey_help': subprocess.run(['hey', '-h'], capture_output=True, text=True).stderr[:4000]}
    if 'laravel' in args.frameworks:
        data.update(laravel_metadata())
    return data


def make_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description='HTTP concurrency scaling: FastFrame, Micronaut, Laravel Octane')
    parser.add_argument('--frameworks', nargs='+', choices=AVAILABLE_FRAMEWORKS,
                        default=list(AVAILABLE_FRAMEWORKS))
    parser.add_argument('--laravel-server', choices=['octane', 'artisan'], default='octane')
    parser.add_argument('--laravel-workers', type=int, default=4)
    parser.add_argument('--levels', nargs='+', type=int, default=list(DEFAULT_LEVELS), metavar='N')
    parser.add_argument('--duration', default='30s', help='Measured time per level (default: 30s)')
    parser.add_argument('--warmup', default='5s', help='Warmup before every level (0s to disable)')
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--cases', nargs='+', choices=sorted(CASES), default=list(CASES))
    parser.add_argument('--heap', default='256m')
    parser.add_argument('--port', type=int, default=18080)
    parser.add_argument('--output', type=Path, default=ROOT / 'benchmarks/results')
    parser.add_argument('--keep-raw', action='store_true', help='Keep large per-request hey CSV files')
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = make_parser()
    args = parser.parse_args(argv)
    try:
        args.levels = positive_levels(args.levels)
        args.duration = validate_duration(args.duration)
        args.warmup = validate_duration(args.warmup, allow_zero=True)
    except ValueError as exc:
        parser.error(str(exc))
    if args.repeats < 1 or args.laravel_workers < 1 or not 1024 <= args.port <= 65535:
        parser.error('repeats/workers must be positive; port must be 1024..65535')
    if not (sys.platform.startswith('linux') or sys.platform in ('darwin', 'win32')):
        parser.error('Supported platforms: Windows, Linux, macOS')
    try:
        args.metrics_backend = metrics_backend()
    except RuntimeError as exc:
        parser.error(str(exc))
    for binary in (['hey'] + (['java'] if any(f != 'laravel' for f in args.frameworks) else [])
                   + (['php'] if 'laravel' in args.frameworks else [])):
        if not shutil.which(binary):
            parser.error(f'Missing executable: {binary}')
    for framework in args.frameworks:
        try:
            require_installed(framework, args)
        except ValueError as exc:
            parser.error(str(exc))
    # High client counts may exceed the per-process FD limit (especially on macOS).
    if os.name != 'nt':
        try:
            import resource
            soft, _ = resource.getrlimit(resource.RLIMIT_NOFILE)
            if soft < max(args.levels) + 128:
                print(f'WARNING: file descriptor soft limit is {soft}, while max concurrency is '
                      f'{max(args.levels)}. Consider increasing `ulimit -n` before this run.',
                      file=sys.stderr)
        except (ImportError, OSError, ValueError):
            pass
    destination = args.output / ('concurrency-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    destination.mkdir(parents=True, exist_ok=False)
    metadata = configuration(args)
    (destination / 'environment.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    rows: list[dict] = []
    status = 0
    print(f'Destination: {destination}; {len(args.frameworks)} frameworks, '
          f'{len(args.cases)} cases, {len(args.levels)} levels, {args.repeats} repeat(s)', flush=True)
    try:
        for repeat in range(1, args.repeats + 1):
            order = list(args.frameworks)
            if repeat % 2 == 0:
                order.reverse()
            for framework in order:
                run_framework(framework, repeat, args, destination, rows)
    except (Exception, KeyboardInterrupt) as exc:
        print(f'Benchmark interrupted: {exc}', file=sys.stderr, flush=True)
        status = 1
    finally:
        write_rows(destination, rows)
        try:
            make_reports(rows, metadata, destination)
            print(f'Reports: {destination / "report.html"} | {destination / "report.md"}', flush=True)
        except Exception as exc:
            print(f'Failed to create reports: {exc}', file=sys.stderr)
            status = 1
    if any(int(r['errors']) for r in rows):
        print('WARNING: non-2xx responses; affected data invalid for throughput comparison', file=sys.stderr)
        status = 2
    if not rows:
        status = 1
    return status


if __name__ == '__main__':
    raise SystemExit(main())
