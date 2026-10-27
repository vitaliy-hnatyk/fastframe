#!/usr/bin/env python3
"""60-minute fixed-rate soak: process-tree RSS/CPU, JVM heap/GC, errors and p99.

Comparisons share one k6 workload and request contract. Java GC/heap are read with
jstat (if installed); PHP worker memory is process-tree RSS, not a JVM heap.
Partial measurements survive interruptions; incomplete tests are never PASS.
Requires Python 3.10+, psutil, k6 and previously built benchmark servers.
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
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

from concurrency import is_zero_duration, validate_duration
from run import (AVAILABLE_FRAMEWORKS, CASES, ROOT, assert_endpoint, laravel_metadata,
                 project_version, require_installed, result_framework,
                 select_server_port, server_command, start_server, stop_server,
                 wait_until_ready)
from target_rps import k6_command, k6_env, parse_summary
from soak_report import make_reports

try:
    import psutil
except ImportError:
    psutil = None

SAMPLE_FIELDS = ('framework', 'case', 'repeat', 'elapsed_s', 'utc', 'rss_mb', 'cpu_cores',
                 'process_count', 'heap_used_mb', 'heap_capacity_mb', 'gc_count',
                 'gc_pause_s', 'probe_total', 'probe_errors')
RESULT_FIELDS = ('framework','case','repeat','port','rate','duration_s','requests',
                 'k6_reported_rps','http_req_failed_rate','actual_rps','delivery_pct','dropped_iterations','errors',
                 'p50_ms','p95_ms','p99_ms','mean_ms','rss_start_mb','rss_end_mb',
                 'rss_peak_mb','rss_growth_mb','rss_slope_mb_hr','cpu_cores_avg',
                 'heap_start_mb','heap_end_mb','heap_peak_mb',
                 'gc_collections','gc_pause_s','probe_errors','sample_count',
                 'target_met','complete','failure')


def seconds(text: str) -> float:
    text = validate_duration(text, allow_zero=True)
    if not text.endswith('s'):
        raise ValueError('Duration must use seconds, e.g. 3600s')
    value = float(text[:-1])
    if not math.isfinite(value) or value < 0:
        raise ValueError('Duration must be finite and nonnegative')
    return value


def jstat_parse(output: str) -> dict | None:
    """Parse JDK jstat -gc fields by header names, not brittle fixed indexes."""
    lines = [line.strip().split() for line in output.splitlines() if line.strip()]
    if len(lines) < 2:
        return None
    data = dict(zip(lines[0], lines[1]))
    used_keys = ('S0U','S1U','EU','OU')
    cap_keys = ('S0C','S1C','EC','OC')
    if any(k not in data for k in used_keys + cap_keys):
        return None
    try:
        numeric = {k: float(v) for k, v in data.items()}
        if any(not math.isfinite(v) or v < 0 for v in numeric.values()):
            return None
        return {
            'heap_used_mb': round(sum(numeric[k] for k in used_keys) / 1024, 3),
            'heap_capacity_mb': round(sum(numeric[k] for k in cap_keys) / 1024, 3),
            'gc_count': int(numeric.get('YGC', 0) + numeric.get('FGC', 0) + numeric.get('CGC', 0)),
            'gc_pause_s': round(numeric.get('GCT', numeric.get('YGCT', 0) + numeric.get('FGCT', 0) + numeric.get('CGCT', 0)), 4),
        }
    except (ValueError, OverflowError):
        return None


def read_jvm(pid: int) -> dict:
    if not shutil.which('jstat'):
        return {}
    try:
        result = subprocess.run(['jstat', '-gc', str(pid)], capture_output=True,
                                text=True, timeout=4, check=False)
        return jstat_parse(result.stdout) if result.returncode == 0 else {}
    except (OSError, subprocess.TimeoutExpired):
        return {}


class ProcessTreeSampler:
    """Includes Octane/FrankenPHP worker processes, avoiding parent-only accounting."""
    def __init__(self, pid: int):
        if psutil is None:
            raise RuntimeError('psutil missing: python3 -m pip install psutil')
        self.pid = pid
        self.previous_cpu: dict[tuple[int, float], float] = {}
        self.previous_time: float | None = None
        self.cpu_seconds = 0.0
        self.elapsed_observed = 0.0

    def sample(self, *, now: float | None = None) -> dict:
        now = time.monotonic() if now is None else now
        try:
            root = psutil.Process(self.pid)
            processes = [root] + root.children(recursive=True)
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            return {'rss_mb': None, 'cpu_cores': None, 'process_count': 0}
        rss = 0.0
        live_cpu = {}
        seen = 0
        for proc in processes:
            try:
                with proc.oneshot():
                    info = proc.memory_info()
                    cpu = proc.cpu_times()
                    ident = (proc.pid, proc.create_time())
                    live_cpu[ident] = cpu.user + cpu.system
                    rss += info.rss
                    seen += 1
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                continue
        increment = sum(max(0.0, total - self.previous_cpu.get(ident, total))
                        for ident, total in live_cpu.items())
        elapsed = now - self.previous_time if self.previous_time is not None else 0.0
        cores = increment / elapsed if elapsed > 0 else None
        if elapsed > 0:
            self.cpu_seconds += increment
            self.elapsed_observed += elapsed
        self.previous_time = now
        self.previous_cpu = live_cpu
        return {'rss_mb': round(rss / 1048576, 3) if seen else None,
                'cpu_cores': round(cores, 4) if cores is not None else None,
                'process_count': seen}


def probe(url: str) -> bool:
    try:
        with urllib.request.urlopen(url + '/bench/plain', timeout=2) as response:
            return response.status == 200 and response.read(64) == b'ok'
    except (OSError, urllib.error.URLError, TimeoutError):
        return False


def append_csv(path: Path, fieldnames, rows):
    """Write completed rows atomically to preserve partial data on exceptions."""
    tmp = path.with_suffix(path.suffix + '.tmp')
    with tmp.open('w', newline='', encoding='utf-8') as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader(); writer.writerows(rows)
    tmp.replace(path)


def save_state(directory: Path, results: list[dict], samples: list[dict]) -> None:
    append_csv(directory / 'soak-results.csv', RESULT_FIELDS, results)
    append_csv(directory / 'soak-timeseries.csv', SAMPLE_FIELDS, samples)
    for name, data in [('soak-results.json', results), ('soak-timeseries.json', samples)]:
        path = directory / name
        tmp = path.with_suffix('.json.tmp')
        tmp.write_text(json.dumps(data, indent=2, allow_nan=False), encoding='utf-8')
        tmp.replace(path)


def memory_slope(samples: list[dict]) -> float | None:
    """Least-squares RSS trend in MiB/hour after the first 10% of the run.

    A positive slope is a *signal to investigate*, not proof of a memory leak.
    """
    observed = [(float(s['elapsed_s']), float(s['rss_mb'])) for s in samples
                if s.get('rss_mb') is not None]
    if len(observed) < 4:
        return None
    cutoff = observed[-1][0] * 0.1
    observed = [(x, y) for x, y in observed if x >= cutoff]
    if len(observed) < 3:
        return None
    xx = sum(x for x, _ in observed) / len(observed)
    yy = sum(y for _, y in observed) / len(observed)
    den = sum((x - xx) ** 2 for x, _ in observed)
    if den <= 0:
        return None
    return round(3600 * sum((x - xx)*(y - yy) for x, y in observed) / den, 3)


def summarize_samples(framework: str, samples: list[dict], sampler: ProcessTreeSampler) -> dict:
    def valid(field): return [float(s[field]) for s in samples if s.get(field) is not None]
    rss, heap = valid('rss_mb'), valid('heap_used_mb')
    counts, gc_time = valid('gc_count'), valid('gc_pause_s')
    return {
        'rss_start_mb': rss[0] if rss else None,
        'rss_end_mb': rss[-1] if rss else None,
        'rss_peak_mb': max(rss) if rss else None,
        'rss_growth_mb': round(rss[-1] - rss[0], 3) if len(rss) > 1 else None,
        'rss_slope_mb_hr': memory_slope(samples),
        'cpu_cores_avg': (round(sampler.cpu_seconds / sampler.elapsed_observed, 4)
                          if sampler.elapsed_observed > 0 else None),
        'heap_start_mb': heap[0] if heap else None,
        'heap_end_mb': heap[-1] if heap else None,
        'heap_peak_mb': max(heap) if heap else None,
        'gc_collections': int(counts[-1] - counts[0]) if len(counts) > 1 else None,
        'gc_pause_s': round(gc_time[-1] - gc_time[0], 4) if len(gc_time) > 1 else None,
        'probe_errors': samples[-1]['probe_errors'] if samples else 0,
        'sample_count': len(samples),
    }


def soak_env(framework: str, settings, port: int) -> dict:
    env = {**os.environ, 'PORT': str(port),
           'MAX_IN_FLIGHT': str(max(4096, settings.max_vus * 4))}
    if framework == 'laravel':
        env.update({'APP_ENV': 'production', 'APP_DEBUG': 'false',
                    'SESSION_DRIVER': 'array', 'CACHE_STORE': 'array',
                    'QUEUE_CONNECTION': 'sync', 'LOG_CHANNEL': 'stderr'})
    return env


def run_one(framework: str, repeat: int, args, directory: Path,
            results: list[dict], timeseries: list[dict]) -> None:
    port = select_server_port(args.port)
    settings = argparse.Namespace(**vars(args)); settings.port = port
    label = result_framework(framework, args)
    url = f'http://127.0.0.1:{port}'
    server_log = directory / f'{label}-r{repeat}-server.log'
    with server_log.open('w', encoding='utf-8') as server_log_fp:
        server = start_server(server_command(framework, settings), framework,
                              server_log_fp, soak_env(framework, args, port))
        try:
            wait_until_ready(server, url, timeout_s=75 if framework == 'laravel' else 40)
            assert_endpoint(url, args.case)
            k6_settings = argparse.Namespace(**vars(args))
            k6_settings.duration = args.warmup
            if not is_zero_duration(args.warmup):
                warm_file = directory / f'{label}-r{repeat}-warmup.json'
                warm = subprocess.run(k6_command(), env=k6_env(url, args.case, args.rate, k6_settings, warm_file),
                                      stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
                if warm.returncode != 0:
                    raise RuntimeError('k6 warmup failed: ' + warm.stderr[-1200:])
                warm_file.unlink(missing_ok=True)
            sampler = ProcessTreeSampler(server.pid)
            sampler.sample()  # CPU baseline after warmup
            result_json = directory / f'{label}-r{repeat}-k6-summary.json'
            k6_log = directory / f'{label}-r{repeat}-k6.log'
            running_samples = []
            start = time.monotonic()
            sample_time = start
            gc_time = start
            next_probe = start
            probe_total = probe_errors = 0
            last_jvm = {}
            with k6_log.open('w', encoding='utf-8') as handle:
                child = subprocess.Popen(k6_command(),
                                         env=k6_env(url, args.case, args.rate, args, result_json),
                                         stdout=handle, stderr=subprocess.STDOUT)
                try:
                    while child.poll() is None:
                        now = time.monotonic()
                        if server.poll() is not None:
                            raise RuntimeError(f'{label} server exited during soak: see {server_log}')
                        if now >= sample_time:
                            metrics = sampler.sample(now=now)
                            if now >= gc_time:
                                jvm = read_jvm(server.pid) if framework != 'laravel' else {}
                                gc_time = now + args.gc_interval
                            else:
                                jvm = {}
                            # Carry forward last jstat result until the next GC poll.
                            if jvm: last_jvm = jvm
                            row = {'framework': label,'case': args.case,'repeat': repeat,
                                   'elapsed_s': round(now - start, 3),
                                   'utc': datetime.now(timezone.utc).isoformat(),
                                   **metrics,
                                   **{key: (last_jvm.get(key) if framework != 'laravel' else None)
                                      for key in ('heap_used_mb','heap_capacity_mb','gc_count','gc_pause_s')},
                                   'probe_total': probe_total, 'probe_errors': probe_errors}
                            running_samples.append(row)
                            timeseries.append(row)
                            save_state(directory, results, timeseries)
                            sample_time = now + args.sample_interval
                        if now >= next_probe:
                            probe_total += 1
                            if not probe(url): probe_errors += 1
                            next_probe = time.monotonic() + args.probe_interval
                        time.sleep(min(0.25, max(0.01, sample_time - time.monotonic())))
                    if child.returncode != 0:
                        raise RuntimeError(f'k6 failed with exit {child.returncode}; see {k6_log}')
                    if not result_json.exists():
                        raise RuntimeError('k6 summary missing after completion')
                    stats = parse_summary(result_json, args.rate, args.duration)
                    result = {'framework':label,'case':args.case,'repeat':repeat,
                              'port':port,'rate':args.rate, **stats,
                              **summarize_samples(framework, running_samples, sampler),
                              'complete': True, 'failure': ''}
                    results.append(result)
                    save_state(directory, results, timeseries)
                    print(f'{label:16} {args.case:5} #{repeat}: {stats["actual_rps"]:.1f} RPS; '
                          f'p99={stats["p99_ms"]:.2f}ms; RSS peak={result["rss_peak_mb"]} MiB; '
                          f'errors={stats["errors"]}, dropped={stats["dropped_iterations"]}', flush=True)
                except (Exception, KeyboardInterrupt) as exc:
                    error = exc
                    result = {'framework':label,'case':args.case,'repeat':repeat,
                              'port':port,'rate':args.rate,'duration_s':seconds(args.duration),
                              'requests':None,'k6_reported_rps':None,'http_req_failed_rate':None,
                              'actual_rps':None,'delivery_pct':None,
                              'dropped_iterations':None,'errors':None,
                              'p50_ms':None,'p95_ms':None,'p99_ms':None,'mean_ms':None,
                              'target_met':False,
                              **summarize_samples(framework, running_samples, sampler),
                              'complete':False,'failure':str(exc)}
                    results.append(result)
                    save_state(directory, results, timeseries)
                    raise
                finally:
                    if child.poll() is None:
                        child.terminate()
                        try:child.wait(timeout=5)
                        except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
                    if not args.keep_k6: result_json.unlink(missing_ok=True)
        finally:
            stop_server(server)


def configuration(args) -> dict:
    meta = {'benchmark':'soak','created_utc':datetime.now(timezone.utc).isoformat(),
            'platform':platform.platform(), 'cpu_count': os.cpu_count(),
            'python':sys.version.split()[0], 'psutil':psutil.__version__ if psutil else None,
            'frameworks':args.frameworks, 'case':args.case,'rate':args.rate,
            'duration':args.duration, 'warmup':args.warmup,
            'repeats':args.repeats, 'sample_interval_s':args.sample_interval,
            'gc_interval_s':args.gc_interval,'probe_interval_s':args.probe_interval,
            'heap':args.heap,'port':args.port,
            'laravel_server':args.laravel_server, 'laravel_workers':args.laravel_workers,
            'vus':args.vus,'max_vus':args.max_vus,
            'load_generator':'k6 constant-arrival-rate',
            'jvm_observer':'jstat -gc' if shutil.which('jstat') else None,
            'resource_method':'sum of RSS and CPU times across launcher + descendants via psutil; '
                              'RSS sums shared pages more than once; not USS/PSS',
            'probe_note':'probe errors are periodic independent /bench/plain health probes, '
                         'distinct from final k6 request errors',
            'k6_version': subprocess.run(['k6','version'],capture_output=True,text=True).stdout.strip(),
            'java_version':subprocess.run(['java','-version'],capture_output=True,text=True).stderr.splitlines()[0]
                            if shutil.which('java') else None,
            'fastframe_version':project_version(ROOT/'pom.xml'),
            'micronaut_version':project_version(ROOT/'benchmarks/micronaut-app/pom.xml')}
    if 'laravel' in args.frameworks:meta.update(laravel_metadata())
    return meta


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description='60-minute fixed-load soak: RSS, heap, CPU, GC, errors')
    p.add_argument('--frameworks',nargs='+',choices=AVAILABLE_FRAMEWORKS,
                   default=list(AVAILABLE_FRAMEWORKS))
    p.add_argument('--case', choices=sorted(CASES), default='json')
    p.add_argument('--rate',type=int,default=1000,help='Same open-loop target RPS for all frameworks')
    p.add_argument('--duration',default='3600s',help='60-minute soak per framework (default 3600s)')
    p.add_argument('--warmup',default='15s')
    p.add_argument('--repeats',type=int,default=1)
    p.add_argument('--vus',type=int,default=128)
    p.add_argument('--max-vus',type=int,default=512)
    p.add_argument('--sample-interval',type=float,default=10.0)
    p.add_argument('--gc-interval',type=float,default=20.0)
    p.add_argument('--probe-interval',type=float,default=15.0)
    p.add_argument('--heap',default='512m')
    p.add_argument('--port',type=int,default=18080)
    p.add_argument('--laravel-server',choices=('octane','artisan'),default='octane')
    p.add_argument('--laravel-workers',type=int,default=4)
    p.add_argument('--output',type=Path,default=ROOT/'benchmarks/results')
    p.add_argument('--keep-k6',action='store_true')
    return p


def main(argv=None) -> int:
    p = parser(); args = p.parse_args(argv)
    try:
        args.duration = validate_duration(args.duration)
        args.warmup = validate_duration(args.warmup, allow_zero=True)
        seconds(args.duration); seconds(args.warmup)
        if seconds(args.duration) < 1: raise ValueError('--duration must be >= 1 second')
        if any(not math.isfinite(v) or v < 0.1 for v in
               (args.sample_interval,args.gc_interval,args.probe_interval)):
            raise ValueError('sampling intervals must be >= 0.1s')
        if not 1 <= args.rate <= 100000 or not 1 <= args.port <= 65535:
            raise ValueError('rate or port out of allowed range')
        if min(args.repeats,args.vus,args.laravel_workers) < 1 or args.max_vus < args.vus:
            raise ValueError('repeats/VUs/workers must be positive and max-vus >= vus')
        if psutil is None: raise ValueError('psutil missing: python3 -m pip install psutil')
        if not shutil.which('k6'): raise ValueError('k6 missing: brew install k6')
        for binary in ('java' if any(x != 'laravel' for x in args.frameworks) else '',
                       'php' if 'laravel' in args.frameworks else ''):
            if binary and not shutil.which(binary):raise ValueError(f'Missing: {binary}')
        for framework in args.frameworks: require_installed(framework,args)
    except ValueError as exc:
        p.error(str(exc))
    directory = args.output / ('soak-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ'))
    directory.mkdir(parents=True,exist_ok=False)
    metadata = configuration(args)
    (directory/'environment.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    results: list[dict] = []; samples: list[dict] = []
    status=0
    try:
        for repeat in range(1,args.repeats+1):
            order=list(args.frameworks)
            if repeat%2==0:order.reverse()
            for framework in order: run_one(framework,repeat,args,directory,results,samples)
    except (Exception,KeyboardInterrupt) as exc:
        print(f'Soak benchmark stopped: {exc}',file=sys.stderr)
        status=1
    finally:
        save_state(directory,results,samples)
        try:
            make_reports(results,samples,metadata,directory)
            print(f'HTML report: {directory / "report.html"}',flush=True)
        except Exception as exc:
            print(f'Report generation failed: {exc}',file=sys.stderr)
            status=1
    if (len(results)!=len(args.frameworks)*args.repeats or
            any(not r['complete'] or r.get('rss_peak_mb') is None for r in results)):
        status=1
    return status


if __name__=='__main__':
    raise SystemExit(main())
