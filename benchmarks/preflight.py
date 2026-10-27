#!/usr/bin/env python3
"""Executable preflight for selected HTTP benchmarks; no hey needed."""
import argparse
import os
import subprocess
from pathlib import Path
from run import (CASES, AVAILABLE_FRAMEWORKS, assert_endpoint, wait_until_ready,
                 server_command, start_server, stop_server, select_server_port,
                 require_installed, result_framework)


def main():
    parser = argparse.ArgumentParser(description='Check selected benchmark HTTP endpoints')
    parser.add_argument('--port', type=int, default=18080)
    parser.add_argument('--frameworks', nargs='+', choices=AVAILABLE_FRAMEWORKS,
                        default=['fastframe', 'micronaut'])
    parser.add_argument('--laravel-server', choices=['octane', 'artisan'], default='octane')
    parser.add_argument('--laravel-workers', type=int, default=4)
    parser.add_argument('--heap', default='256m')
    args = parser.parse_args()
    if not (1024 <= args.port <= 65535):
        parser.error('--port must be 1024-65535')
    for framework in args.frameworks:
        try:
            require_installed(framework, args)
        except ValueError as exc:
            parser.error(str(exc))
        label = result_framework(framework, args)
        port = select_server_port(args.port)
        settings = argparse.Namespace(**vars(args))
        settings.port = port
        url = f'http://127.0.0.1:{port}'
        env = {**os.environ, 'PORT': str(port), 'APP_ENV': 'production',
               'APP_DEBUG': 'false', 'SESSION_DRIVER': 'array', 'CACHE_STORE': 'array'}
        log_path = Path(__file__).resolve().parent / f'{label}-preflight.log'
        with log_path.open('w') as log:
            process = start_server(server_command(framework, settings), framework, log, env)
            try:
                wait_until_ready(process, url, timeout_s=60 if framework == 'laravel' else 35)
                for case in CASES:
                    assert_endpoint(url, case)
                print(f'{label}: all 4 endpoints passed (port {port})')
            finally:
                stop_server(process)


if __name__ == '__main__':
    main()
