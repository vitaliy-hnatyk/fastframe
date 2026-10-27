#!/usr/bin/env python3
"""Offline SVG/HTML and CSV report for JSON response sizes."""
from __future__ import annotations

import csv
import html
import json
import math
import statistics
from collections import defaultdict
from pathlib import Path

from concurrency_report import FRAMEWORK_COLORS

SUMMARY_FIELDS=('size','bytes','framework','n','expected_repeats','valid',
                'median_rps','min_rps','max_rps','median_mib_s',
                'median_p95_ms','median_p99_ms','median_rss_peak_mb','median_cpu_cores_avg',
                'total_errors')


def aggregate(rows: list[dict], expected_repeats: int) -> list[dict]:
    if expected_repeats < 1:
        raise ValueError('expected repeats must be positive')
    groups=defaultdict(list)
    for row in rows:
        groups[(str(row['size']),int(row['bytes']),str(row['framework']))].append(row)
    result=[]
    def median(records,key):
        numbers=[float(v[key]) for v in records if v.get(key) not in (None,'')]
        return round(statistics.median(numbers),4) if numbers else None
    for (size,bytes_,framework),group in sorted(groups.items(),key=lambda e:(e[0][1],e[0][2])):
        rps=[float(row['rps']) for row in group]
        complete=(len(group)==expected_repeats and
                  len({int(r['repeat']) for r in group})==expected_repeats)
        valid=complete and all(int(r['errors'])==0 and int(r['ok_2xx'])>0 and
                               int(r['requests'])==int(r['errors'])+int(r['ok_2xx'])
                               for r in group)
        result.append({'size':size,'bytes':bytes_,'framework':framework,'n':len(group),
                       'expected_repeats':expected_repeats,'valid':bool(valid),
                       'median_rps':median(group,'rps'),
                       'min_rps':round(min(rps),3),'max_rps':round(max(rps),3),
                       'median_mib_s':median(group,'mib_s'),
                       'median_p95_ms':median(group,'p95_ms'),
                       'median_p99_ms':median(group,'p99_ms'),
                       'median_rss_peak_mb':median(group,'rss_peak_mb'),
                       'median_cpu_cores_avg':median(group,'cpu_cores_avg'),
                       'total_errors':sum(int(r['errors']) for r in group)})
    return result


def fmt(value, decimals=1):
    return f'{value:,.{decimals}f}' if value is not None else 'n/a'


def framework_labels(metadata):
    return [f'laravel-{metadata.get("laravel_server","octane")}' if f=='laravel' else f
            for f in metadata.get('frameworks',[])]


def markdown_report(summary,metadata):
    lookup={(r['size'],r['framework']):r for r in summary}
    lines=['# FastFrame v0.22 — JSON Size Benchmark','',
           f"Date: {metadata.get('timestamp_utc','unknown')}  ",
           f"Platform: {metadata.get('platform','unknown')}  ",
           f"Duration: {metadata.get('duration','30s')} per size, "
           f"concurrency: {metadata.get('concurrency',64)}, "
           f"repeats: {metadata.get('repeats',3)}.",'',
           'JSON responses are identical byte-for-byte across all three applications. '
           'Each includes an ASCII data field and is serialized per request.','',
           '| Size | Bytes | Framework | Runs | Valid | Median RPS | MiB/s | p95 ms | p99 ms | Errors |',
           '|---|---:|---|---:|---|---:|---:|---:|---:|---:|']
    for size in metadata.get('sizes',[]):
        for framework in framework_labels(metadata):
            row=lookup.get((size,framework))
            if row is None:
                lines.append(f'| {size} | {metadata.get("bytes",{}).get(size,"—")} | {framework} | 0 | incomplete | — | — | — | — | — |')
            else:
                lines.append(f"| {size} | {row['bytes']} | {framework} | {row['n']} | "
                             f"{'yes' if row['valid'] else 'NO'} | {fmt(row['median_rps'])} | "
                             f"{fmt(row['median_mib_s'])} | {fmt(row['median_p95_ms'],2)} | "
                             f"{fmt(row['median_p99_ms'],2)} | {row['total_errors']} |")
    lines.extend(['','## FastFrame throughput ratios (valid and complete groups only)','',
                  '| Size | Comparison | RPS ratio |','|---|---|---:|'])
    for size in metadata.get('sizes',[]):
        good={r['framework']:r for r in summary if r['size']==size and r['valid']}
        base=good.get('fastframe')
        if base:
            for name,opp in sorted(good.items()):
                if name!='fastframe' and opp['median_rps']>0:
                    lines.append(f"| {size} | FastFrame / {name} | "
                                 f"{base['median_rps']/opp['median_rps']:.3f}× |")
    lines.extend(['','## Interpreting the results','',
                  '- The output throughput (MiB/s) is *payload bytes × completed RPS / 2²⁰*, '
                  'not network wire throughput.',
                  '- Valid results require all repeats and zero non-2xx responses. Incomplete '
                  'and invalid results remain visible but are not plotted.',
                  '- The runner checks SHA-256 and exact response bytes before each size workload; '
                  'the load generator verifies HTTP statuses but not every response body.',
                  '- Data strings are cached; JSON serialization is performed per request. '
                  'Results also include IO, framework routing and response allocation.',
                  '- hey CSV collection costs client CPU. A shared host can bottleneck all servers.',
                  '- Laravel worker CPU/RSS are not measured (null), not zero.',
                  '- Check environment.json for payload hashes, Java/PHP versions and settings.', ''])
    return '\n'.join(lines)


def svg_plot(rows, field, sizes, frameworks, title, unit):
    width,height=950,335
    left,right,top,bottom=86,28,42,63
    pw=width-left-right; ph=height-top-bottom
    indexes={size:i for i,size in enumerate(sizes)}
    good=[r for r in rows if r['valid'] and r['size'] in indexes and
          r['framework'] in frameworks and r.get(field) is not None]
    maxval=max([float(r[field]) for r in good]+[1])*1.13
    def xx(size):return left+pw*(indexes[size]/max(1,len(sizes)-1))
    def yy(val):return top+ph*(1-float(val)/maxval)
    out=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" '
         f'role="img" aria-label="{html.escape(title,quote=True)}">',
         f'<text x="{left}" y="25" class="ct">{html.escape(title)}</text>']
    for i in range(5):
        value=maxval*i/4
        out.append(f'<line class="grid" x1="{left}" y1="{yy(value):.1f}" '
                   f'x2="{width-right}" y2="{yy(value):.1f}"/>')
        out.append(f'<text class="tick" text-anchor="end" x="{left-12}" '
                   f'y="{yy(value)+4:.1f}">{value:,.1f}</text>')
    for size in sizes:
        out.append(f'<text class="tick" x="{xx(size):.1f}" y="{height-bottom+23}" '
                   f'text-anchor="middle">{html.escape(size)}</text>')
    out.append(f'<text class="tick" x="{left+pw/2}" y="{height-13}" '
               f'text-anchor="middle">JSON response size (bytes / KiB / MiB)</text>')
    for framework in frameworks:
        color=FRAMEWORK_COLORS.get(framework,'#64748b')
        valid=sorted([r for r in good if r['framework']==framework],key=lambda r:indexes[r['size']])
        for a,b in zip(valid,valid[1:]):
            if indexes[b['size']]==indexes[a['size']]+1:
                out.append(f'<line x1="{xx(a["size"]):.2f}" y1="{yy(a[field]):.2f}" '
                           f'x2="{xx(b["size"]):.2f}" y2="{yy(b[field]):.2f}" '
                           f'stroke="{color}" stroke-width="2.6"/>')
        for r in valid:
            tool=html.escape(f'{framework} {r["size"]}: {r[field]:.2f} {unit}; n={r["n"]}')
            out.append(f'<circle cx="{xx(r["size"]):.2f}" cy="{yy(r[field]):.2f}" '
                       f'r="5" fill="{color}"><title>{tool}</title></circle>')
    if not good:
        out.append('<text x="475" y="160" text-anchor="middle" '
                   'class="tick">No valid complete measurements yet</text>')
    return ''.join(out)+'</svg>'


def html_report(rows, metadata):
    frameworks=framework_labels(metadata)
    sizes=metadata.get('sizes',[])
    esc=html.escape
    css='''body{margin:0;background:#f8fafc;color:#0f172a;font:15px system-ui,sans-serif}
    main{max-width:1090px;padding:28px 24px 70px;margin:auto}h1{font-size:28px}h2{margin-top:34px}
    .panel{background:white;border:1px solid #e2e8f0;border-radius:12px;padding:18px;margin:16px 0}
    .legend{display:flex;gap:20px;flex-wrap:wrap}.sq{display:inline-block;width:12px;height:12px;margin-right:7px}
    .ct{font-size:16px;font-weight:650;fill:#0f172a}.grid{stroke:#e2e8f0}.tick{fill:#64748b;font-size:12px}
    table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}
    th,td{padding:9px 8px;border-bottom:1px solid #e2e8f0;text-align:right}
    th:first-child,td:first-child{text-align:left}th{background:#f1f5f9}
    .scroll{overflow:auto}.bad{color:#b45309}.good{color:#047857}
    .sub{color:#475569}.info{line-height:1.55}svg{width:100%;height:auto}'''
    lines=['<!doctype html><html lang="en"><head><meta charset="utf-8">',
           '<meta name="viewport" content="width=device-width,initial-scale=1">',
           '<title>FastFrame v0.22 — JSON sizes</title>',f'<style>{css}</style></head><body><main>',
           '<h1>FastFrame v0.22 — JSON Sizes</h1>',
           f'<p class="sub">{esc(str(metadata.get("platform","")))} · '
           f'{esc(str(metadata.get("duration","")))} per size · '
           f'{int(metadata.get("repeats",1))} repeats · '
           f'concurrency {int(metadata.get("concurrency",64))}</p>',
           '<div class="legend">']
    for f in frameworks:
        color=FRAMEWORK_COLORS.get(f,'#64748b')
        lines.append(f'<span><i class="sq" style="background:{color}"></i>{esc(f)}</span>')
    lines.append('</div>')
    for field,title,unit in [('median_rps','HTTP throughput (RPS)','RPS'),
                             ('median_mib_s','JSON payload throughput (MiB/s)','MiB/s'),
                             ('median_p99_ms','HTTP p99 latency (ms)','ms')]:
        lines.append('<div class="panel">'+svg_plot(rows,field,sizes,frameworks,title,unit)+'</div>')
    lines.append('<h2>Measured data</h2><div class="panel scroll"><table><thead><tr>'
                 '<th>Framework</th><th>Size</th><th>Runs</th><th>Status</th><th>RPS</th>'
                 '<th>MiB/s</th><th>p95 ms</th><th>p99 ms</th><th>Errors</th>'
                 '</tr></thead><tbody>')
    for r in rows:
        lines.append(f'<tr><td>{esc(str(r["framework"]))}</td><td>{esc(str(r["size"]))}</td>'
                     f'<td>{r["n"]}</td><td class="{"good" if r["valid"] else "bad"}">'
                     f'{"valid" if r["valid"] else "invalid / incomplete"}</td>'
                     f'<td>{fmt(r["median_rps"])}</td><td>{fmt(r["median_mib_s"])}</td>'
                     f'<td>{fmt(r["median_p95_ms"],2)}</td>'
                     f'<td>{fmt(r["median_p99_ms"],2)}</td><td>{r["total_errors"]}</td></tr>')
    lines.append('</tbody></table></div><div class="panel info"><h2>Methodology</h2>'
                 '<p>Each size is an exact byte length of UTF-8 JSON, not raw data length. '
                 'The server caches the source strings but serializes JSON per request. '
                 'All server payloads are byte-for-byte verified before measurement.</p>'
                 '<p>Only complete zero-error series are charted. Missing data are not zero. '
                 'hey response CSV collection and shared host CPU may cap throughput. '
                 'MiB/s excludes HTTP protocol overhead. Memory/CPU for Laravel workers '
                 'is not measured.</p><p>See environment.json and json-sizes-summary.csv.</p>'
                 '</div></main></body></html>')
    return ''.join(lines)


def make_reports(rows,metadata,destination:Path):
    destination.mkdir(parents=True,exist_ok=True)
    summary=aggregate(rows,int(metadata.get('repeats',1)))
    with (destination/'json-sizes-summary.csv').open('w',newline='',encoding='utf-8') as out:
        writer=csv.DictWriter(out,fieldnames=SUMMARY_FIELDS)
        writer.writeheader(); writer.writerows(summary)
    (destination/'json-sizes-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
    (destination/'report.md').write_text(markdown_report(summary,metadata),encoding='utf-8')
    (destination/'report.html').write_text(html_report(summary,metadata),encoding='utf-8')
    return summary
