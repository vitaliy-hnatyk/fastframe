#!/usr/bin/env python3
"""Executable integration smoke test of both compiled HTTP applications. No hey needed."""
import argparse
import os
import subprocess
from pathlib import Path
from run import CASES, JARS, assert_endpoint, java_args, wait_until_ready


def main():
    parser = argparse.ArgumentParser(description='Check FastFrame and Micronaut endpoints')
    parser.add_argument('--port', type=int, default=18080)
    args = parser.parse_args()
    if not (1024 <= args.port <= 65535):
        parser.error('--port must be 1024-65535')
    url = f'http://127.0.0.1:{args.port}'
    for framework, jar in JARS.items():
        if not jar.is_file():
            raise SystemExit(f'Missing {jar}. Run benchmarks/build.ps1 (Windows) or benchmarks/build.sh first')
        env = {**os.environ, 'PORT': str(args.port)}
        with (jar.parent / f'{framework}-preflight.log').open('w') as log:
            process = subprocess.Popen(java_args('256m', jar), stdout=log, stderr=subprocess.STDOUT, env=env)
            try:
                wait_until_ready(process, url)
                for case in CASES:
                    assert_endpoint(url, case)
                print(f'{framework}: all 4 endpoints passed')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == '__main__':
    main()
