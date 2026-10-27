#!/usr/bin/env python3
"""Offline median RPS/p99 graphs for async external I/O and verified JWT workloads."""
from __future__ import annotations
import csv
import html
import json
from pathlib import Path
from db_redis_report import aggregate, chart, label, fmt

FIELDS=('case','framework','n','expected_repeats','valid','median_rps','median_p95_ms',
        'median_p99_ms','total_errors','median_rss_peak_mb','median_cpu_cores_avg')


def markdown(summary,meta):
    lookup={(r['case'],r['framework']):r for r in summary}
    lines=['# FastFrame v0.22 — Async I/O & HS256 JWT', '',
           f"Date: {meta.get('timestamp_utc','unknown')}",
           f"Concurrency: {meta.get('concurrency',64)} · Duration: {meta.get('duration','30s')} · Repeats: {meta.get('repeats',3)}",'',
           '| Case | Framework | Runs | Valid | Median RPS | p95 ms | p99 ms | Errors |',
           '|---|---|---:|---|---:|---:|---:|---:|']
    for case in meta.get('cases',[]):
        for framework in meta.get('frameworks',[]):
            name=label(framework,meta); r=lookup.get((case,name))
            if r is None: lines.append(f'| {case} | {name} | 0 | incomplete | — | — | — | — |')
            else:
                lines.append(f"| {case} | {name} | {r['n']} | {'yes' if r['valid'] else 'NO'} | "
                             f"{fmt(r['median_rps'])} | {fmt(r['median_p95_ms'],2)} | "
                             f"{fmt(r['median_p99_ms'],2)} | {r['total_errors']} |")
    lines += ['', '## Comparable throughput ratios (complete error-free runs only)','',
              '| Case | Comparison | RPS ratio |','|---|---|---:|']
    for case in meta.get('cases',[]):
        eligible={r['framework']:r for r in summary if r['case']==case and r['valid']}
        base=eligible.get('fastframe')
        if base:
            for name,r in eligible.items():
                if name!='fastframe' and r['median_rps'] and r['median_rps']>0:
                    lines.append(f"| {case} | FastFrame / {name} | {base['median_rps']/r['median_rps']:.3f}× |")
    lines += ['', '## Methodology','',
              '- Async endpoints perform real HTTP calls to the separate asyncio loopback upstream (10/50/200 ms wait).',
              '- Successful completed upstream calls are counted after measurement; a count lower than successful client responses invalidates the run.',
              '- Java uses java.net.http.HttpClient.sendAsync; PHP Laravel HTTP client blocks one worker for the external wait.',
              '- JWT endpoints verify a signed HS256 token on every request and reject missing, invalid and expired tokens.',
              '- The benchmark JWT verifier accepts only a canonical fixture; this is not a full framework security middleware benchmark.',
              '- All three applications use same JWT signing key and claim values during a run; the key and token are never stored in reports.',
              '- Invalid, incomplete and HTTP-error runs are excluded from RPS-ratio calculations and bar charts.',
              '- The upstream and load generator share host CPU/network with the tested server unless externally isolated.',
              '- Laravel worker-process RAM and CPU are intentionally not presented (null, not zero).','']
    return '\n'.join(lines)


def make_reports(rows,metadata,directory:Path):
    directory.mkdir(parents=True,exist_ok=True)
    summary=aggregate(rows,int(metadata.get('repeats',1)))
    with (directory/'async-jwt-summary.csv').open('w',newline='',encoding='utf-8') as fp:
        writer=csv.DictWriter(fp,fieldnames=FIELDS)
        writer.writeheader();writer.writerows(summary)
    (directory/'async-jwt-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
    doc=markdown(summary,metadata)
    (directory/'report.md').write_text(doc,encoding='utf-8')
    style='''body{font:15px system-ui;background:#f8fafc;color:#0f172a;margin:0}
    main{max-width:1140px;margin:36px auto;padding:0 22px}
    .card{padding:18px;border:1px solid #e2e8f0;border-radius:12px;background:white;margin:20px 0}
    .grid{stroke:#e2e8f0}.tick{fill:#64748b;font-size:11px}
    .heading{font:600 17px system-ui;fill:#0f172a}pre{white-space:pre-wrap;font-size:12px}
    '''
    body=f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
    <title>FastFrame Async I/O and JWT comparison</title><style>{style}</style>
    <main><h1>FastFrame v0.22 — Async I/O &amp; HS256 JWT</h1>
    <p>Real local upstream HTTP delays; live HMAC verification. Only complete error-free measurements are charted.</p>
    <section class="card">{chart(summary,metadata,'median_rps','Median HTTP throughput (RPS)')}</section>
    <section class="card">{chart(summary,metadata,'median_p99_ms','Median HTTP p99 latency (ms)')}</section>
    <section class="card"><h2>Full results and methodology</h2><pre>{html.escape(doc)}</pre></section></main></html>'''
    (directory/'report.html').write_text(body,encoding='utf-8')
    return summary
