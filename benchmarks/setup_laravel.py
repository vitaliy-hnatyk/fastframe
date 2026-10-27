#!/usr/bin/env python3
"""Install a reproducible Laravel 13 benchmark app and apply committed route fixtures.

Requires external Composer/Packagist access. This script never writes outside the
benchmarks/laravel-app/runtime directory except for Composer's own cache.
"""
from __future__ import annotations
import argparse
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
TEMPLATE = HERE / 'laravel-app'
RUNTIME = TEMPLATE / 'runtime'


def run(*args: str, cwd: Path | None = None) -> None:
    print('+', ' '.join(args), flush=True)
    subprocess.run(list(args), cwd=cwd, check=True)


def install(with_octane: bool = False, server: str = 'frankenphp') -> None:
    if shutil.which('php') is None or shutil.which('composer') is None:
        raise RuntimeError('PHP 8.3+ and Composer 2 are required; see benchmarks/README.md')
    if not (RUNTIME / 'artisan').is_file():
        if RUNTIME.exists() and any(RUNTIME.iterdir()):
            raise RuntimeError(f'{RUNTIME} exists but no artisan found; inspect/remove it first')
        RUNTIME.parent.mkdir(parents=True, exist_ok=True)
        run('composer', 'create-project', 'laravel/laravel', str(RUNTIME), '^13.0',
            '--prefer-dist', '--no-interaction')
    # Source overlays remain the authoritative definition for test endpoints.
    for relative in (Path('routes/bench.php'), Path('bootstrap/app.php')):
        target = RUNTIME / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(TEMPLATE / relative, target)
    # Install step may be repeated without downloading an entire Laravel skeleton.
    if with_octane:
        installed = subprocess.run(['composer', 'show', 'laravel/octane', '--no-ansi'],
                                   cwd=RUNTIME, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.DEVNULL).returncode == 0
        if not installed:
            run('composer', 'require', 'laravel/octane:^2.0', '--no-interaction', cwd=RUNTIME)
        if server == 'frankenphp' and not ((RUNTIME / 'frankenphp').exists() or shutil.which('frankenphp')):
            run('php', 'artisan', 'octane:install', '--server=frankenphp', '--no-interaction', cwd=RUNTIME)
    # Predis enables a Redis backend on macOS without the phpredis PHP extension.
    installed = subprocess.run(['composer', 'show', 'predis/predis', '--no-ansi'],
                               cwd=RUNTIME, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL).returncode == 0
    if not installed:
        run('composer', 'require', 'predis/predis:^2.4', '--no-interaction', cwd=RUNTIME)
    # Laravel config caching is intentionally disabled to keep environment overrides effective.
    run('php', 'artisan', 'config:clear', '--no-interaction', cwd=RUNTIME)
    run('php', 'artisan', 'route:clear', '--no-interaction', cwd=RUNTIME)
    print(f'Laravel fixture ready: {RUNTIME}')


def main():
    parser = argparse.ArgumentParser(description='Install Laravel 13 HTTP benchmark fixture')
    parser.add_argument('--with-octane', action='store_true', help='Install Octane + FrankenPHP')
    args = parser.parse_args()
    install(args.with_octane)


if __name__ == '__main__':
    main()
