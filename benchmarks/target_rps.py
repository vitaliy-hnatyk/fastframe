#!/usr/bin/env python3
"""Open-loop, fixed-arrival-rate HTTP benchmark: FastFrame / Micronaut / Laravel.

Uses Grafana k6 constant-arrival-rate and a versioned JSON summary from the
companion JS workload. No guessed results. Requires Python 3.10+ and k6.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from concurrency import validate_duration, is_zero_duration
from run import (AVAILABLE_FRAMEWORKS, CASES, ROOT, assert_endpoint,
                 laravel_metadata, metrics_backend, project_version,
                 require_installed, result_framework, select_server_port,
                 server_command, start_server, stop_server, wait_until_ready)
from target_rps_report import make_reports

DEFAULT_RATES = (1000, 2000, 4000, 8000, 12000, 16000, 20000, 24000, 30000)
FIELDS = ('framework', 'case', 'repeat', 'port', 'target_rps', 'duration_s',
          'requests', 'actual_rps', 'k6_reported_rps', 'delivery_pct',
          'dropped_iterations', 'errors', 'http_req_failed_rate',
          'p50_ms', 'p95_ms', 'p99_ms', 'mean_ms', 'target_met')
WORKLOAD = Path(__file__).with_name('target_rps.js')


def rates_valid(values: list[int]) -> list[int]:
    if not values or values != sorted(set(values)) or any(n <= 0 or n > 1_000_000 for n in values):
        raise ValueError('Rates must be strictly increasing unique integers in 1..1000000')
    return values


def seconds(value: str) -> float:
    if not value.endswith('s'):
        raise ValueError('Expected seconds duration, e.g., 60s')
    return float(value[:-1])


def k6_command(workload: Path = WORKLOAD) -> list[str]:
    return ['k6', 'run', '--quiet', str(workload)]


def parse_summary(path: Path, target_rps: int, duration: str) -> dict:
    """Metrics from k6 summary. The target duration excludes graceful stop."""
    info = json.loads(path.read_text(encoding='utf-8'))
    if info.get('schema') != 1:
        raise ValueError(f'Unexpected k6 benchmark schema in {path}')
    for key in ('requests', 'k6_reported_rps', 'p50_ms', 'p95_ms', 'p99_ms', 'mean_ms'):
        if info.get(key) is None or not math.isfinite(float(info[key])) or float(info[key]) < 0:
            raise ValueError(f'Missing/invalid k6 metric {key} in {path}')
    count = int(info['requests'])
    if count <= 0 or count != info['requests']:
        raise ValueError('k6 returned no requests or a nonintegral request count')
    drops = int(info['dropped_iterations'])
    bad = int(info['invalid_responses'])
    if min(drops, bad) < 0 or drops != info['dropped_iterations'] or bad != info['invalid_responses']:
        raise ValueError('Invalid dropped/invalid response count')
    failed_rate = float(info['http_req_failed_rate'])
    if not 0 <= failed_rate <= 1:
        raise ValueError('Invalid HTTP failure rate')
    elapsed = seconds(duration)
    actual = count / elapsed
    delivered = count / (target_rps * elapsed) * 100
    # `http_req_failed` can include transport failures that have no response status.
    # Exclude them from valid target achievement, even if the custom 200-status counter is 0.
    errors = max(bad, int(math.ceil(failed_rate * count - 1e-6)))
    target_met = bool(errors == 0 and drops == 0 and 95 <= delivered <= 105)
    return {'duration_s': elapsed, 'requests': count,
            'actual_rps': round(actual, 3),
            'k6_reported_rps': round(float(info['k6_reported_rps']), 3),
            'delivery_pct': round(delivered, 3),
            'dropped_iterations': drops, 'errors': errors,
            'http_req_failed_rate': round(failed_rate, 6),
            'p50_ms': round(float(info['p50_ms']), 4),
            'p95_ms': round(float(info['p95_ms']), 4),
            'p99_ms': round(float(info['p99_ms']), 4),
            'mean_ms': round(float(info['mean_ms']), 4),
            'target_met': target_met}


def k6_env(base: str, case: str, rate: int, args: argparse.Namespace, output: Path) -> dict:
    return {**os.environ,
            'BENCH_BASE_URL': base,
            'BENCH_CASE': case,
            'BENCH_TARGET_RPS': str(rate),
            'BENCH_DURATION': args.duration,
            'BENCH_VUS': str(args.vus),
            'BENCH_MAX_VUS': str(args.max_vus),
            'BENCH_SUMMARY_PATH': str(output.resolve())}


def save_rows(directory: Path, rows: list[dict]) -> None:
    dest = directory / 'target-rps-results.csv'
    tmp = dest.with_suffix('.csv.tmp')
    with tmp.open('w', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    tmp.replace(dest)
    dest_json = directory / 'target-rps-results.json'
    tmp_json = dest_json.with_suffix('.json.tmp')
    tmp_json.write_text(json.dumps(rows, indent=2), encoding='utf-8')
    tmp_json.replace(dest_json)


def run_framework(framework: str, repeat: int, args: argparse.Namespace,
                  destination: Path, rows: list[dict]) -> None:
    port = select_server_port(args.port)
    label = result_framework(framework, args)
    url = f'http://127.0.0.1:{port}'
    config = argparse.Namespace(**vars(args))
    config.port = port
    server_env = {**os.environ, 'PORT': str(port),
                  'MAX_IN_FLIGHT': str(max(4096, args.max_vus * 4))}
    if framework == 'laravel':
        server_env.update({'APP_ENV': 'production', 'APP_DEBUG': 'false',
                           'SESSION_DRIVER': 'array', 'CACHE_STORE': 'array',
                           'QUEUE_CONNECTION': 'sync', 'LOG_CHANNEL': 'stderr'})
    server_log = destination / f'{label}-run-{repeat}.log'
    with server_log.open('w', encoding='utf-8') as f:
        server = start_server(server_command(framework, config), framework, f, server_env)
        try:
            wait_until_ready(server, url, timeout_s=60 if framework == 'laravel' else 35)
            for case in args.cases:
                assert_endpoint(url, case)
                # Real warmup, outside the measured fixed-arrival window.
                if not is_zero_duration(args.warmup):
                    # Warm once per endpoint at the lowest target rate.
                    warm_rate = args.rates[0]
                    warm_path = destination / f'warmup-{label}-{case}-{warm_rate}-{repeat}.json'
                    completed = subprocess.run(k6_command(),
                                               env=k6_env(url, case, warm_rate, args, warm_path) |
                                               {'BENCH_DURATION': args.warmup},
                                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
                    if completed.returncode != 0:
                        raise RuntimeError(f'k6 warmup failed for {label}/{case}/{warm_rate}: '
                                           f'{completed.stderr.strip()}')
                    warm_path.unlink(missing_ok=True)
                for rate in args.rates:
                    if server.poll() is not None:
                        raise RuntimeError(f'{label} exited before rate={rate}; see {server_log}')
                    summary_path = destination / f'k6-{label}-{case}-{rate}-r{repeat}.json'
                    log_path = destination / f'k6-{label}-{case}-{rate}-r{repeat}.log'
                    with log_path.open('w', encoding='utf-8') as logfile:
                        completed = subprocess.run(k6_command(), env=k6_env(url, case, rate, args, summary_path),
                                                   stdout=logfile, stderr=subprocess.STDOUT, check=False)
                    if completed.returncode != 0:
                        raise RuntimeError(f'k6 failed ({completed.returncode}) for {label}/{case}/{rate}; '
                                           f'see {log_path}')
                    stats = parse_summary(summary_path, rate, args.duration)
                    row = {'framework': label, 'case': case, 'repeat': repeat,
                           'port': port, 'target_rps': rate, **stats}
                    rows.append(row)
                    save_rows(destination, rows)
                    print(f'{label:16} {case:5} target={rate:6} RPS #{repeat}: '
                          f'actual={row["actual_rps"]:9.1f} p99={row["p99_ms"]:7.2f} ms '
                          f'dropped={row["dropped_iterations"]} errors={row["errors"]} '
                          f'[{"MET" if row["target_met"] else "MISSED"}]', flush=True)
                    if not args.keep_k6:
                        summary_path.unlink(missing_ok=True)
                        log_path.unlink(missing_ok=True)
        finally:
            stop_server(server)


def configuration(args: argparse.Namespace) -> dict:
    info = {'benchmark': 'target-rps', 'timestamp_utc': datetime.now(timezone.utc).isoformat(),
            'platform': platform.platform(), 'cpu_count': os.cpu_count(),
            'processor': platform.processor(), 'python': sys.version.split()[0],
            'frameworks': args.frameworks, 'cases': args.cases, 'rates': args.rates,
            'duration': args.duration, 'warmup': args.warmup, 'repeats': args.repeats,
            'vus': args.vus, 'max_vus': args.max_vus, 'heap': args.heap,
            'preferred_port': args.port,
            'laravel_server': args.laravel_server, 'laravel_workers': args.laravel_workers,
            'k6_version': subprocess.run(['k6', 'version'], capture_output=True, text=True).stdout.strip(),
            'open_loop': True, 'load_generator': 'k6 constant-arrival-rate',
            'measurement_note': 'Actual RPS = completed requests / target duration; '
                                'k6_reported_rps is a separate wall-time statistic.',
            'drops_note': 'Dropped iterations can result from insufficient load-generator VUs '
                          'or from server-induced latency; not automatically a server error.',
            'java_version': (subprocess.run(['java', '-version'], capture_output=True,
                                            text=True).stderr if shutil.which('java') else None),
            'php_version': (subprocess.run(['php', '-v'], capture_output=True,
                                           text=True).stdout.splitlines()[0]
                            if 'laravel' in args.frameworks and shutil.which('php') else None),
            'fastframe_maven_version': project_version(ROOT / 'pom.xml'),
            'micronaut_maven_version': project_version(ROOT / 'benchmarks/micronaut-app/pom.xml'),
            'command': sys.argv}
    if 'laravel' in args.frameworks:
        info.update(laravel_metadata())
    return info


def make_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description='Open-loop Target RPS / p99: FastFrame, Micronaut, Laravel Octane')
    p.add_argument('--frameworks', nargs='+', choices=AVAILABLE_FRAMEWORKS,
                   default=list(AVAILABLE_FRAMEWORKS))
    p.add_argument('--laravel-server', choices=['octane', 'artisan'], default='octane')
    p.add_argument('--laravel-workers', type=int, default=4)
    p.add_argument('--rates', nargs='+', type=int, default=list(DEFAULT_RATES), metavar='RPS')
    p.add_argument('--duration', default='60s', help='Measured period per target rate (default 60s)')
    p.add_argument('--warmup', default='5s', help='Warmup per endpoint, 0s to disable')
    p.add_argument('--repeats', type=int, default=1)
    p.add_argument('--cases', nargs='+', choices=sorted(CASES), default=list(CASES))
    p.add_argument('--vus', type=int, default=128, help='Pre-allocated k6 virtual users')
    p.add_argument('--max-vus', type=int, default=512)
    p.add_argument('--heap', default='256m')
    p.add_argument('--port', type=int, default=18080)
    p.add_argument('--output', type=Path, default=ROOT / 'benchmarks/results')
    p.add_argument('--keep-k6', action='store_true', help='Keep k6 JSON summaries and execution logs')
    return p


def main(argv: list[str] | None = None) -> int:
    parser = make_parser()
    args = parser.parse_args(argv)
    try:
        args.rates = rates_valid(args.rates)
        args.duration = validate_duration(args.duration)
        args.warmup = validate_duration(args.warmup, allow_zero=True)
        if not args.duration.endswith('s') or not args.warmup.endswith('s'):
            raise ValueError('Target RPS durations must use seconds (e.g. 60s)')
    except ValueError as exc:
        parser.error(str(exc))
    if args.repeats < 1 or args.laravel_workers < 1 or args.vus < 1 or args.max_vus < args.vus:
        parser.error('repeats/workers/VUs must be positive; max-vus >= vus')
    if not (1024 <= args.port <= 65535):
        parser.error('port must be 1024..65535')
    if not (sys.platform.startswith('linux') or sys.platform in ('darwin', 'win32')):
        parser.error('Supported platforms: macOS, Linux, Windows')
    for binary in (['k6'] + (['java'] if any(f != 'laravel' for f in args.frameworks) else [])
                   + (['php'] if 'laravel' in args.frameworks else [])):
        if not shutil.which(binary):
            parser.error(f'Missing executable: {binary}')
    for framework in args.frameworks:
        try:
            require_installed(framework, args)
        except ValueError as exc:
            parser.error(str(exc))
    args.metrics_backend = metrics_backend()
    destination = args.output / ('target-rps-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    destination.mkdir(parents=True, exist_ok=False)
    metadata = configuration(args)
    (destination / 'environment.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
    rows: list[dict] = []
    status = 0
    print(f'Destination: {destination}; {len(args.frameworks)} frameworks, '
          f'{len(args.cases)} cases, {len(args.rates)} fixed rates, {args.repeats} repeat(s)', flush=True)
    try:
        for repeat in range(1, args.repeats + 1):
            order = list(args.frameworks)
            if repeat % 2 == 0:
                order.reverse()
            for framework in order:
                run_framework(framework, repeat, args, destination, rows)
    except (Exception, KeyboardInterrupt) as exc:
        print(f'Target RPS benchmark interrupted: {exc}', file=sys.stderr)
        status = 1
    finally:
        save_rows(destination, rows)
        try:
            make_reports(rows, metadata, destination)
            print(f'Reports: {destination / "report.html"} | {destination / "report.md"}', flush=True)
        except Exception as exc:
            print(f'Failed to generate reports: {exc}', file=sys.stderr)
            status = 1
    if not rows or len(rows) != len(args.frameworks)*len(args.cases)*len(args.rates)*args.repeats:
        status = 1
    # A missed target is a *measurement*, not a harness failure. See target_met column.
    return status


if __name__ == '__main__':
    raise SystemExit(main())
