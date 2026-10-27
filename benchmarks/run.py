#!/usr/bin/env python3
"""Sequential FastFrame, Micronaut, Laravel HTTP benchmark (Python 3.10+).

All results are measured, never extrapolated. Laravel multi-process metrics are
not reported to avoid misleading parent-only RSS/CPU comparisons.
"""
from __future__ import annotations

import argparse
import csv
import ctypes
import errno
import hashlib
import ctypes.wintypes
import json
import math
import os
import platform
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LARAVEL_ROOT = ROOT / 'benchmarks/laravel-app/runtime'
AVAILABLE_FRAMEWORKS = ('fastframe', 'micronaut', 'laravel')
def project_version(path: Path) -> str:
    """Use Maven coordinates, never a stale version hard-coded in benchmark scripts."""
    import xml.etree.ElementTree as ET
    document = ET.parse(path).getroot()
    ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
    version = document.findtext('m:version', namespaces=ns)
    if version is None:
        version = document.findtext('m:parent/m:version', namespaces=ns)
    if not version:
        raise ValueError(f"Cannot determine Maven version from {path}")
    return version.strip()


def laravel_metadata() -> dict:
    """Record exact installed Composer package versions and lock hash when selected."""
    lock = LARAVEL_ROOT / 'composer.lock'
    if not lock.is_file():
        return {'composer_lock_sha256': None, 'laravel_framework_version': None,
                'laravel_octane_version': None}
    payload = json.loads(lock.read_text(encoding='utf-8'))
    versions = {item['name']: item['version'] for item in payload.get('packages', [])}
    return {'composer_lock_sha256': hashlib.sha256(lock.read_bytes()).hexdigest(),
            'laravel_framework_version': versions.get('laravel/framework'),
            'laravel_octane_version': versions.get('laravel/octane')}


def benchmark_jars(root: Path = ROOT) -> dict[str, Path]:
    ff_version = project_version(root / 'benchmarks/fastframe-app/pom.xml')
    mn_version = project_version(root / 'benchmarks/micronaut-app/pom.xml')
    return {
        'fastframe': root / f'benchmarks/fastframe-app/target/benchmark-fastframe-{ff_version}.jar',
        'micronaut': root / f'benchmarks/micronaut-app/target/benchmark-micronaut-{mn_version}.jar',
    }


JARS = benchmark_jars()

CASES = {
    "plain": ("GET", "/bench/plain", None, "text/plain"),
    "json": ("GET", "/bench/json", None, "application/json"),
    "route": ("GET", "/bench/route/123", None, "text/plain"),
    "echo": ("POST", "/bench/echo", '{"message":"hello"}', "application/json"),
}
FIELDS = ["framework", "case", "repeat", "port", "concurrency", "requests", "ok_2xx", "errors",
          "duration_s", "rps", "p50_ms", "p95_ms", "p99_ms", "mean_ms",
          "rss_baseline_mb", "rss_peak_mb", "cpu_cores_avg"]


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise ValueError("Cannot calculate percentile for an empty sample")
    xs = sorted(values)
    p = (len(xs) - 1) * quantile
    lo = math.floor(p)
    hi = math.ceil(p)
    return xs[lo] * (hi - p) + xs[hi] * (p - lo) if lo != hi else xs[lo]


def csv_statistics(path: Path) -> dict:
    """hey -o csv response-time and offset are in seconds; status-code is HTTP status."""
    latencies = []
    ok = errors = 0
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(source)
        if reader.fieldnames is None or not {"response-time", "status-code"}.issubset(reader.fieldnames):
            raise ValueError("Unexpected hey CSV format; need response-time and status-code columns")
        for entry in reader:
            if not entry.get("response-time"):
                continue
            latencies.append(float(entry["response-time"]) * 1000.0)
            status = int(entry["status-code"])
            if 200 <= status < 300:
                ok += 1
            else:
                errors += 1
    if not latencies:
        raise ValueError(f"No samples in {path}")
    return {"requests": len(latencies), "ok_2xx": ok, "errors": errors,
            "p50_ms": round(percentile(latencies, .50), 4),
            "p95_ms": round(percentile(latencies, .95), 4),
            "p99_ms": round(percentile(latencies, .99), 4),
            "mean_ms": round(statistics.fmean(latencies), 4)}


def proc_rss_mb(pid: int) -> float | None:
    try:
        for line in Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    except (OSError, ValueError):
        pass
    return None


def proc_cpu_seconds(pid: int) -> float | None:
    try:
        text = Path(f"/proc/{pid}/stat").read_text()
        fields = text.rsplit(") ", 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf("SC_CLK_TCK")
    except (OSError, ValueError, IndexError):
        return None


def parse_ps_cpu_time(value: str) -> float:
    """Parse ps time= on macOS/Linux: MM:SS, HH:MM:SS, or D-HH:MM:SS."""
    days = 0
    if "-" in value:
        day_part, value = value.split("-", 1)
        days = int(day_part)
    units = value.split(":")
    if len(units) not in (2, 3):
        raise ValueError(f"Unexpected process CPU time: {value!r}")
    parts = [float(u) for u in units]
    if len(parts) == 2:
        minutes, seconds = parts
        return days * 86400 + minutes * 60 + seconds
    hours, minutes, seconds = parts
    return days * 86400 + hours * 3600 + minutes * 60 + seconds


def ps_metrics(pid: int) -> tuple[float | None, float | None]:
    """RSS (MiB) and CPU time (seconds) via portable ps; needed on macOS.

    ps is a separate process, so sample less often than the Linux /proc reader.
    """
    try:
        result = subprocess.run(
            ["ps", "-p", str(pid), "-o", "rss=", "-o", "time="],
            capture_output=True, text=True, timeout=2, check=False)
        if result.returncode != 0 or not result.stdout.strip():
            return None, None
        fields = result.stdout.strip().split()
        if len(fields) != 2:
            return None, None
        return int(fields[0]) / 1024, parse_ps_cpu_time(fields[1])
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return None, None


class ProcessMemoryCounters(ctypes.Structure):
    """PROCESS_MEMORY_COUNTERS structure for GetProcessMemoryInfo."""
    _fields_ = [
        ("cb", ctypes.wintypes.DWORD),
        ("PageFaultCount", ctypes.wintypes.DWORD),
        ("PeakWorkingSetSize", ctypes.c_size_t),
        ("WorkingSetSize", ctypes.c_size_t),
        ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPagedPoolUsage", ctypes.c_size_t),
        ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
        ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
        ("PagefileUsage", ctypes.c_size_t),
        ("PeakPagefileUsage", ctypes.c_size_t),
    ]


def windows_metrics(pid: int) -> tuple[float | None, float | None]:
    """Sample process working set (MiB) and cumulative CPU seconds via WinAPI.

    Read-only process handle. No psutil, PowerShell process spawning or admin rights.
    A process that has already exited yields (None, None).
    """
    if sys.platform != "win32":
        return None, None
    try:
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
        open_process = kernel32.OpenProcess
        open_process.argtypes = [ctypes.wintypes.DWORD, ctypes.wintypes.BOOL,
                                 ctypes.wintypes.DWORD]
        open_process.restype = ctypes.wintypes.HANDLE
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = [ctypes.wintypes.HANDLE]
        close_handle.restype = ctypes.wintypes.BOOL
        read_memory = psapi.GetProcessMemoryInfo
        read_memory.argtypes = [ctypes.wintypes.HANDLE,
                                ctypes.POINTER(ProcessMemoryCounters), ctypes.wintypes.DWORD]
        read_memory.restype = ctypes.wintypes.BOOL
        read_times = kernel32.GetProcessTimes
        filetime_ptr = ctypes.POINTER(ctypes.wintypes.FILETIME)
        read_times.argtypes = [ctypes.wintypes.HANDLE, filetime_ptr,
                               filetime_ptr, filetime_ptr, filetime_ptr]
        read_times.restype = ctypes.wintypes.BOOL

        # PROCESS_QUERY_INFORMATION | PROCESS_VM_READ (GetProcessMemoryInfo)
        handle = open_process(0x0400 | 0x0010, False, pid)
        if not handle:
            return None, None
        try:
            memory = ProcessMemoryCounters()
            memory.cb = ctypes.sizeof(memory)
            rss = (memory.WorkingSetSize / (1024 * 1024)
                   if read_memory(handle, ctypes.byref(memory), memory.cb) else None)
            created = ctypes.wintypes.FILETIME()
            exited = ctypes.wintypes.FILETIME()
            kernel = ctypes.wintypes.FILETIME()
            user = ctypes.wintypes.FILETIME()
            cpu = None
            if read_times(handle, ctypes.byref(created), ctypes.byref(exited),
                          ctypes.byref(kernel), ctypes.byref(user)):
                def ticks(value: ctypes.wintypes.FILETIME) -> int:
                    return (value.dwHighDateTime << 32) | value.dwLowDateTime
                cpu = (ticks(kernel) + ticks(user)) / 10_000_000
            return rss, cpu
        finally:
            close_handle(handle)
    except (OSError, ValueError, AttributeError):
        return None, None


def metrics_backend() -> str:
    """Choose Linux /proc, macOS ps or native Windows APIs."""
    if sys.platform == "win32":
        return "windows"
    if sys.platform.startswith("linux") and Path("/proc/self/stat").exists():
        return "proc"
    if sys.platform == "darwin" and shutil.which("ps"):
        return "ps"
    raise RuntimeError("Cannot measure RSS/CPU: require Windows APIs, /proc (Linux) or ps (macOS)")


def process_metrics(pid: int, backend: str) -> tuple[float | None, float | None]:
    if backend == "proc":
        return proc_rss_mb(pid), proc_cpu_seconds(pid)
    if backend == "ps":
        return ps_metrics(pid)
    if backend == "windows":
        return windows_metrics(pid)
    raise ValueError(f"Unknown metrics backend: {backend}")


def java_args(heap: str, jar: Path) -> list[str]:
    return ["java", f"-Xms{heap}", f"-Xmx{heap}", "-XX:+UseG1GC", "-jar", str(jar)]


def server_command(framework: str, settings: argparse.Namespace) -> list[str]:
    if framework == 'laravel':
        if settings.laravel_server == 'artisan':
            return ['php', 'artisan', 'serve', '--host=127.0.0.1',
                    f'--port={settings.port}', '--no-reload']
        return ['php', 'artisan', 'octane:start', '--server=frankenphp',
                '--host=127.0.0.1', f'--port={settings.port}',
                f'--workers={settings.laravel_workers}']
    return java_args(settings.heap, JARS[framework])


def result_framework(framework: str, settings: argparse.Namespace) -> str:
    return f'laravel-{settings.laravel_server}' if framework == 'laravel' else framework


def check_port_available(port: int) -> None:
    """Probe whether a TCP listener can bind, ignoring harmless TIME_WAIT where allowed.

    SO_REUSEADDR must be set *before* bind. A bare bind() without it can
    falsely report EADDRINUSE immediately after the previous server exits.
    listen() also checks that a second listener cannot claim the port.
    """
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        # Windows SO_REUSEADDR can allow another process to bind a live port.
        # On POSIX it permits re-binding a port left in TIME_WAIT.
        if os.name != 'nt':
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        probe.bind(('127.0.0.1', port))
        probe.listen(1)


def _macos_listener_from_lsof(port: int) -> bool | None:
    """Inspect LISTEN sockets without connecting to (and filling) their backlog.

    macOS can refuse a new connect() once the accept backlog is full. Repeated
    connect_ex() probes could therefore mistake a *live* listener for TIME_WAIT
    and incorrectly switch to a different benchmark port. lsof is supplied by
    macOS and checks the kernel's socket table without making connections.
    None means lsof was unavailable or inconclusive; fall back to TCP probing.
    """
    executable = shutil.which('lsof')
    if not executable:
        system_lsof = Path('/usr/sbin/lsof')
        executable = str(system_lsof) if system_lsof.is_file() else None
    if not executable:
        return None
    try:
        result = subprocess.run(
            [executable, '-nP', '-t', f'-iTCP:{port}', '-sTCP:LISTEN'],
            capture_output=True, text=True, timeout=3, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode == 0 and result.stdout.strip():
        return True
    if result.returncode == 1 and not result.stdout.strip():
        return False
    return None



def _windows_listener_from_netstat(port: int) -> bool | None:
    """Inspect Windows TCP LISTEN sockets without filling the accept backlog.

    Repeated connect_ex() can return WSAECONNREFUSED for a live listener whose
    accept backlog is full. netstat -ano reads the socket table without
    connecting. None means the table cannot be trusted: do not classify an
    unbindable Windows port as harmless TIME_WAIT on that basis.
    """
    executable = shutil.which('netstat')
    if not executable:
        return None
    try:
        result = subprocess.run([executable, '-ano'], capture_output=True,
                                text=True, errors='replace', timeout=5,
                                check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    unknown_state = False
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) < 4 or parts[0].upper() != 'TCP':
            continue
        _, separator, local_port = parts[1].rpartition(':')
        if not separator or not local_port.isdigit() or int(local_port) != port:
            continue
        state = parts[3].upper()
        if state == 'LISTENING':
            return True
        # Localized/unrecognized states must not be mistaken for TIME_WAIT.
        if state not in {'TIME_WAIT', 'CLOSE_WAIT', 'FIN_WAIT_1', 'FIN_WAIT_2',
                         'LAST_ACK', 'CLOSED', 'CLOSING', 'SYN_SENT',
                         'SYN_RECEIVED', 'ESTABLISHED'}:
            unknown_state = True
    return None if unknown_state else False


def port_has_listener(port: int) -> bool:
    """Check for a real TCP listener, not a TIME_WAIT-only local port.

    Do not create probe connections on macOS when lsof can determine the state:
    on a small/saturated accept backlog, connect_ex() is not a reliable test.
    """
    if sys.platform == 'darwin':
        found = _macos_listener_from_lsof(port)
        if found is not None:
            return found
    if sys.platform == 'win32':
        found = _windows_listener_from_netstat(port)
        if found is not None:
            return found
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.25)
        try:
            result = probe.connect_ex(('127.0.0.1', port))
        except OSError as exc:
            # Winsock WSAEACCES (10013) is sometimes reported for a port
            # already owned by another socket or blocked by local policy.
            # Treat it as unavailable, not as an empty listener slot.
            return sys.platform == 'win32' and (getattr(exc, 'winerror', None) == 10013
                                                 or exc.errno == errno.EACCES)
        return result == 0 or (sys.platform == 'win32' and result in (10013, errno.EACCES))


def select_server_port(preferred: int, wait_s: float = 2.0) -> int:
    """Preserve a user-selected port when possible, avoid stale TCP TIME_WAIT.

    Refuse to start if another server is still *listening* on the preferred
    port (so that old benchmark processes cannot falsify the preflight).
    If no listener exists but the port cannot be rebound due to TIME_WAIT,
    select a fresh ephemeral loopback port. The actual port is logged and
    saved with every benchmark row. There is an unavoidable small race
    between releasing the probe socket and starting the server.
    """
    deadline = time.monotonic() + wait_s
    while port_has_listener(preferred):
        if time.monotonic() >= deadline:
            raise OSError(errno.EADDRINUSE,
                          f'Active listener on 127.0.0.1:{preferred}; '
                          f'run: lsof -nP -iTCP:{preferred} -sTCP:LISTEN')
        time.sleep(0.1)
    try:
        check_port_available(preferred)
        return preferred
    except OSError as exc:
        # Access denied is NOT harmless TIME_WAIT: Windows returns 10013
        # when a port is reserved or restricted. Never silently fall back,
        # which could conceal an active server and invalidate a benchmark.
        if exc.errno in (errno.EACCES, errno.EPERM) or getattr(exc, 'winerror', None) == 10013:
            raise OSError(errno.EACCES,
                          f'Active listener or restricted port on 127.0.0.1:{preferred}; '
                          f'check the owning process and Windows TCP exclusions') from exc
        if exc.errno != errno.EADDRINUSE and getattr(exc, 'winerror', None) != 10048:
            raise
        # The socket could have started listening after the initial probe.
        if port_has_listener(preferred):
            raise OSError(errno.EADDRINUSE,
                          f'Active listener on 127.0.0.1:{preferred}; '
                          f'stop the existing server before benchmarking') from exc
        if sys.platform == 'win32' and _windows_listener_from_netstat(preferred) is not False:
            # A full backlog can fool connect_ex(), and an unavailable or
            # localized socket listing is not proof of harmless TIME_WAIT.
            raise OSError(errno.EADDRINUSE,
                          f'Active listener or unverified TCP port on 127.0.0.1:{preferred}; '
                          f'run: netstat -ano | findstr :{preferred}') from exc
    # A port with no listener may still be unavailable due to TIME_WAIT.
    # A new ephemeral port avoids waiting for the TCP lifetime to expire.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(('127.0.0.1', 0))
        alternative = probe.getsockname()[1]
    print(f'Port {preferred} is in use by stale TCP state; '
          f'using available port {alternative}', file=sys.stderr, flush=True)
    return alternative


def start_server(command: list[str], framework: str, log, env: dict) -> subprocess.Popen:
    opts = {'stdout': log, 'stderr': subprocess.STDOUT, 'env': env}
    if framework == 'laravel':
        opts['cwd'] = str(LARAVEL_ROOT)
    if os.name == 'nt':
        opts['creationflags'] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        opts['start_new_session'] = True
    return subprocess.Popen(command, **opts)


def stop_server(server: subprocess.Popen) -> None:
    # Kill spawned web servers too: artisan serve launches php -S, Octane launches workers.
    if os.name == 'nt':
        subprocess.run(['taskkill', '/PID', str(server.pid), '/T', '/F'],
                       capture_output=True, check=False)
    else:
        try:
            os.killpg(server.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    try:
        server.wait(timeout=6)
    except subprocess.TimeoutExpired:
        if os.name != 'nt':
            try:
                os.killpg(server.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        else:
            server.kill()
        server.wait(timeout=6)


def require_installed(framework: str, settings: argparse.Namespace) -> None:
    if framework == 'laravel':
        if not (LARAVEL_ROOT / 'artisan').is_file() or not (LARAVEL_ROOT / 'vendor/autoload.php').is_file():
            raise ValueError('Laravel is not installed. Run python3 benchmarks/setup_laravel.py'
                             + (' --with-octane' if settings.laravel_server == 'octane' else ''))
        if settings.laravel_server == 'octane' and not (LARAVEL_ROOT / 'vendor/laravel/octane').exists():
            raise ValueError('Laravel Octane missing. Run python3 benchmarks/setup_laravel.py --with-octane')
    elif not JARS[framework].is_file():
        raise ValueError(f'Missing {JARS[framework]}. Run benchmarks/build.sh first')


def assert_endpoint(url: str, case: str) -> None:
    method, uri, body, content_type = CASES[case]
    headers = {"Content-Type": "application/json"} if body else {}
    request = urllib.request.Request(url + uri, data=body.encode() if body else None,
                                     headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=4) as response:
        raw = response.read().decode("utf-8")
        if response.status != 200:
            raise RuntimeError(f"{uri}: status {response.status}")
        if not response.headers.get("Content-Type", "").startswith(content_type):
            raise RuntimeError(f"{uri}: wrong Content-Type: {response.headers}")
        if case == "plain" and raw != "ok":
            raise RuntimeError(f"{uri}: unexpected body {raw!r}")
        if case == "route" and raw != "123":
            raise RuntimeError(f"{uri}: unexpected body {raw!r}")
        if case in ("json", "echo"):
            expected = {"message": "ok", "value": 42} if case == "json" else {"message": "hello"}
            if json.loads(raw) != expected:
                raise RuntimeError(f"{uri}: expected {expected!r}, got {raw!r}")


def wait_until_ready(proc: subprocess.Popen, url: str, timeout_s: float = 35) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            raise RuntimeError(f"Server exited early ({proc.returncode}); check server log")
        try:
            assert_endpoint(url, "plain")
            return
        except (OSError, RuntimeError):
            time.sleep(.2)
    raise TimeoutError(f"Server not ready at {url} within {timeout_s} s")


def hey_command(url: str, case: str, concurrency: int, duration: str) -> list[str]:
    method, uri, body, _ = CASES[case]
    command = ["hey", "-o", "csv", "-c", str(concurrency), "-z", duration,
               "-m", method, "-t", "15"]
    if body is not None:
        command.extend(["-T", "application/json", "-d", body])
    command.append(url + uri)
    return command


def run_load(pid: int, command: list[str], path: Path, baseline_rss: float | None, backend: str,
             collect_process_metrics: bool = True) -> dict:
    samples = []
    stop = threading.Event()
    def sample() -> None:
        while not stop.is_set():
            rss, _ = process_metrics(pid, backend)
            if rss is not None:
                samples.append(rss)
            stop.wait(0.1 if backend == "proc" else 0.5)
    monitor = threading.Thread(target=sample, daemon=True)
    _, cpu_start = process_metrics(pid, backend) if collect_process_metrics else (None, None)
    if collect_process_metrics:
        monitor.start()
    start = time.monotonic()
    try:
        with path.open("w", encoding="utf-8", newline="") as output:
            proc = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, text=True)
    finally:
        elapsed = time.monotonic() - start
        stop.set()
        if collect_process_metrics:
            monitor.join(timeout=2)
    final_rss, cpu_end = process_metrics(pid, backend) if collect_process_metrics else (None, None)
    if final_rss is not None:
        samples.append(final_rss)
    if baseline_rss is not None:
        samples.append(baseline_rss)
    if proc.returncode != 0:
        raise RuntimeError(f"hey failed: {proc.stderr.strip()}")
    stats = csv_statistics(path)
    stats["duration_s"] = round(elapsed, 3)
    stats["rps"] = round(stats["requests"] / elapsed, 2)
    stats["rss_baseline_mb"] = round(baseline_rss, 2) if baseline_rss is not None else None
    stats["rss_peak_mb"] = round(max(samples), 2) if samples else None
    stats["cpu_cores_avg"] = (round((cpu_end - cpu_start) / elapsed, 3)
                               if cpu_start is not None and cpu_end is not None else None)
    return stats


def run_one(framework: str, settings: argparse.Namespace, repeat: int, output: Path) -> list[dict]:
    selected_port = select_server_port(settings.port)
    server_settings = argparse.Namespace(**vars(settings))
    server_settings.port = selected_port
    url = f"http://127.0.0.1:{selected_port}"
    label = result_framework(framework, settings)
    env = {**os.environ, "PORT": str(selected_port),
           "MAX_IN_FLIGHT": str(max(256, settings.concurrency * 2))}
    if framework == 'laravel':
        env.update({'APP_ENV': 'production', 'APP_DEBUG': 'false',
                    'SESSION_DRIVER': 'array', 'CACHE_STORE': 'array',
                    'QUEUE_CONNECTION': 'sync', 'LOG_CHANNEL': 'stderr'})
    logfile = output / f"{label}-run-{repeat}.log"
    rows = []
    with logfile.open("w", encoding="utf-8") as log:
        server = start_server(server_command(framework, server_settings), framework, log, env)
        try:
            wait_until_ready(server, url, timeout_s=60 if framework == 'laravel' else 35)
            for case in settings.cases:
                assert_endpoint(url, case)
            for case in settings.cases:
                with (output / f"warmup-{label}-{case}-{repeat}.csv").open("w", encoding="utf-8", newline="") as dest:
                    subprocess.run(hey_command(url, case, min(settings.concurrency, 16), settings.warmup),
                                   stdout=dest, stderr=subprocess.PIPE, check=True)
            for case in settings.cases:
                # The Laravel parent is not necessarily the process serving requests.
                # NULL measurements are more truthful than pretending its RSS/CPU are total.
                baseline, _ = ((None, None) if framework == 'laravel' else
                               process_metrics(server.pid, settings.metrics_backend))
                destination = output / f"raw-{label}-{case}-{repeat}.csv"
                stats = run_load(server.pid, hey_command(url, case, settings.concurrency, settings.duration),
                                 destination, baseline, settings.metrics_backend,
                                 collect_process_metrics=(framework != 'laravel'))
                row = {"framework": label, "case": case, "repeat": repeat,
                       "port": selected_port, "concurrency": settings.concurrency, **stats}
                rows.append(row)
                print(f"{label:16} {case:6} #{repeat}: {row['rps']} RPS; "
                      f"p99={row['p99_ms']}ms; errors={row['errors']}", flush=True)
        finally:
            stop_server(server)
    return rows


def main() -> None:
    parser = argparse.ArgumentParser(description="Sequential FastFrame, Micronaut and Laravel benchmarking")
    parser.add_argument('--frameworks', nargs='+', choices=AVAILABLE_FRAMEWORKS,
                        default=['fastframe', 'micronaut'], help='opt in to Laravel explicitly')
    parser.add_argument('--laravel-server', choices=['octane', 'artisan'], default='octane')
    parser.add_argument('--laravel-workers', type=int, default=4)
    parser.add_argument("--duration", default="20s", help="hey duration per measured scenario")
    parser.add_argument("--warmup", default="5s", help="hey duration per warmup scenario")
    parser.add_argument("--concurrency", type=int, default=64)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--heap", default="256m")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--cases", nargs="+", choices=sorted(CASES), default=list(CASES))
    parser.add_argument("--output", type=Path, default=ROOT / "benchmarks/results")
    settings = parser.parse_args()
    if not (sys.platform.startswith("linux") or sys.platform in ("darwin", "win32")):
        parser.error("Supported benchmark platforms: Windows, Linux and macOS")
    try:
        settings.metrics_backend = metrics_backend()
    except RuntimeError as exc:
        parser.error(str(exc))
    if settings.concurrency < 1 or settings.repeats < 1 or settings.laravel_workers < 1:
        parser.error("--concurrency and --repeats must be positive")
    for binary in (("hey",) + (("java",) if any(f != "laravel" for f in settings.frameworks) else ())
                   + (("php",) if "laravel" in settings.frameworks else ())):
        if not shutil.which(binary):
            parser.error(f"Missing executable: {binary}")
    for framework in settings.frameworks:
        try:
            require_installed(framework, settings)
        except ValueError as exc:
            parser.error(str(exc))
    destination = settings.output / datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    destination.mkdir(parents=True, exist_ok=False)
    environment = {"timestamp_utc": datetime.now(timezone.utc).isoformat(),
                   "platform": platform.platform(), "python": sys.version.split()[0],
                   "metrics_backend": settings.metrics_backend,
                   "rss_units": "MiB", "cpu_units": "average cores",
                   "processor": platform.processor(), "cpu_count": os.cpu_count(),
                   "command": sys.argv, "heap": settings.heap, "duration": settings.duration,
                   "warmup": settings.warmup, "concurrency": settings.concurrency,
                   "repeats": settings.repeats, "preferred_port": settings.port, "cases": settings.cases,
                   "frameworks": settings.frameworks, "laravel_server": settings.laravel_server,
                   "laravel_workers": settings.laravel_workers,
                   "laravel_metrics_note": "Laravel RSS/CPU null: parent process omits worker processes",
                   "java_version": (subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
                                    if shutil.which('java') else None),
                   "php_version": (subprocess.run(['php', '-v'], capture_output=True, text=True).stdout.splitlines()[0]
                                   if shutil.which('php') and 'laravel' in settings.frameworks else None),
                   "hey_help": subprocess.run(["hey", "-h"], capture_output=True, text=True).stderr[:4000]}
    if 'laravel' in settings.frameworks:
        environment.update(laravel_metadata())
    (destination / "environment.json").write_text(json.dumps(environment, indent=2), encoding="utf-8")
    measurements = []
    try:
        for repeat in range(1, settings.repeats + 1):
            order = (list(settings.frameworks) if repeat % 2 else list(reversed(settings.frameworks)))
            for framework in order:
                measurements.extend(run_one(framework, settings, repeat, destination))
    finally:
        if measurements:
            with (destination / "results.csv").open("w", newline="", encoding="utf-8") as result:
                writer = csv.DictWriter(result, fieldnames=FIELDS)
                writer.writeheader()
                writer.writerows(measurements)
            (destination / "results.json").write_text(json.dumps(measurements, indent=2), encoding="utf-8")
    if any(row["errors"] != 0 for row in measurements):
        print("WARNING: non-2xx responses detected; results are not a successful throughput comparison", file=sys.stderr)
        raise SystemExit(2)
    print(f"Results: {destination / 'results.csv'}")


if __name__ == "__main__":
    main()
