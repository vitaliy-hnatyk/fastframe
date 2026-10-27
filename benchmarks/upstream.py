#!/usr/bin/env python3
"""Asyncio loopback delayed HTTP upstream; *actual* network waits, not framework sleep stubs.

This benchmark-only server is not a public reverse proxy or production application.
"""
from __future__ import annotations
import argparse
import asyncio
import json

DELAYS = (10, 50, 200)
COUNTS = {str(d): 0 for d in DELAYS}


def http_reply(code: int, body: bytes, keepalive: bool = True) -> bytes:
    reason = {200: 'OK', 400: 'Bad Request', 404: 'Not Found', 405: 'Method Not Allowed'}.get(code, 'Error')
    return (f'HTTP/1.1 {code} {reason}\r\nContent-Type: application/json\r\n'
            f'Content-Length: {len(body)}\r\nCache-Control: no-store\r\n'
            f'Connection: {"keep-alive" if keepalive else "close"}\r\n\r\n').encode('ascii') + body


async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            try:
                head = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), timeout=12)
            except (asyncio.IncompleteReadError, asyncio.TimeoutError):
                return
            except asyncio.LimitOverrunError:
                writer.write(http_reply(400, b'{}', False)); await writer.drain(); return
            if len(head) > 8192:
                writer.write(http_reply(400, b'{}', False)); await writer.drain(); return
            start = head.split(b'\r\n', 1)[0].split()
            if len(start) != 3:
                writer.write(http_reply(400, b'{}', False)); await writer.drain(); return
            method, raw_path, _ = start
            path = raw_path.decode('ascii', errors='replace')
            if method == b'GET' and path in ('/health', '/_stats'):
                payload = ({'ok': True} if path == '/health' else dict(COUNTS))
                body = json.dumps(payload, separators=(',', ':')).encode('ascii')
                writer.write(http_reply(200, body))
            elif method == b'POST' and path == '/_reset':
                for key in COUNTS: COUNTS[key] = 0
                writer.write(http_reply(200, b'{"ok":true}'))
            elif method == b'GET' and path.startswith('/wait/') and path[6:] in COUNTS:
                delay = int(path[6:])
                await asyncio.sleep(delay / 1000)
                COUNTS[str(delay)] += 1
                body = f'{{"value":"ok","delay_ms":{delay}}}'.encode('ascii')
                writer.write(http_reply(200, body))
            else:
                writer.write(http_reply(404, b'{"error":"not found"}'))
            await writer.drain()
            # urllib and other clients may ask for close; honor it for clean shutdown.
            if b'connection: close' in head.lower():
                return
    except (BrokenPipeError, ConnectionResetError, OSError, asyncio.IncompleteReadError):
        pass
    finally:
        writer.close()
        try: await writer.wait_closed()
        except (OSError, ConnectionError): pass


async def main(port: int) -> None:
    server = await asyncio.start_server(handle, '127.0.0.1', port, limit=16384, backlog=2048)
    print(f'Benchmark upstream started on 127.0.0.1:{port}', flush=True)
    async with server: await server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=19090)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error('port must be 1024..65535')
    asyncio.run(main(args.port))
