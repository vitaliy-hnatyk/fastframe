#!/usr/bin/env python3
"""CSV/JSON/Markdown + self-contained SVG/HTML for PostgreSQL/Redis workload.
Only complete zero-error groups with verified database side effects can be compared.
"""
from __future__ import annotations
import csv
import html
import json
import statistics
from collections import defaultdict
from pathlib import Path
from concurrency_report import FRAMEWORK_COLORS

FIELDS=('case','framework','n','expected_repeats','valid','median_rps','median_p95_ms',
        'median_p99_ms','total_errors','median_rss_peak_mb','median_cpu_cores_avg')


def aggregate(rows, repeats):
    if repeats < 1: raise ValueError('repeats must be positive')
    groups=defaultdict(list)
    for r in rows: groups[(r['case'],r['framework'])].append(r)
    def median(group,key):
        v=[float(r[key]) for r in group if r.get(key) not in (None,'')]
        return round(statistics.median(v),4) if v else None
    out=[]
    for (case,framework),group in sorted(groups.items()):
        complete=len(group)==repeats and len({int(r['repeat']) for r in group})==repeats
        valid=complete and all(int(r['errors'])==0 and int(r['ok_2xx'])>0
                               and int(r['requests'])==int(r['ok_2xx'])+int(r['errors'])
                               and r.get('side_effect_ok') is True for r in group)
        out.append({'case':case,'framework':framework,'n':len(group),
                    'expected_repeats':repeats,'valid':valid,
                    'median_rps':median(group,'rps'),
                    'median_p95_ms':median(group,'p95_ms'),
                    'median_p99_ms':median(group,'p99_ms'),
                    'median_rss_peak_mb':median(group,'rss_peak_mb'),
                    'median_cpu_cores_avg':median(group,'cpu_cores_avg'),
                    'total_errors':sum(int(r['errors']) for r in group)})
    return out


def label(framework, meta):
    return 'laravel-'+meta.get('laravel_server','octane') if framework=='laravel' else framework


def fmt(value, digits=1):
    return f'{value:,.{digits}f}' if value is not None else '—'


def markdown(summary,meta):
    index={(r['case'],r['framework']):r for r in summary}
    lines=['# FastFrame v0.22 — PostgreSQL & Redis', '',
           f"Tested: {meta.get('timestamp_utc','unknown')}  ",
           f"Duration: {meta.get('duration','30s')} · concurrency: {meta.get('concurrency',64)} · repeats: {meta.get('repeats',3)}",'',
           '| Case | Framework | Runs | Valid | Median RPS | p95 ms | p99 ms | Errors |',
           '|---|---|---:|---|---:|---:|---:|---:|']
    for case in meta.get('cases',[]):
        for f in meta.get('frameworks',[]):
            name=label(f,meta); row=index.get((case,name))
            if row is None: lines.append(f'| {case} | {name} | 0 | incomplete | — | — | — | — |')
            else:
                lines.append(f"| {case} | {name} | {row['n']} | {'yes' if row['valid'] else 'NO'} | "
                             f"{fmt(row['median_rps'])} | {fmt(row['median_p95_ms'],2)} | "
                             f"{fmt(row['median_p99_ms'],2)} | {row['total_errors']} |")
    lines += ['', '## FastFrame ratios — valid complete groups only','',
              '| Case | Comparison | RPS ratio |', '|---|---|---:|']
    for case in meta.get('cases',[]):
        rows_case={r['framework']:r for r in summary if r['case']==case and r['valid']}
        base=rows_case.get('fastframe')
        if base:
            for name,r in rows_case.items():
                if name!='fastframe' and r['median_rps'] and r['median_rps']>0:
                    lines.append(f"| {case} | FastFrame / {name} | {base['median_rps']/r['median_rps']:.3f}× |")
    lines += ['', '## Workload and validity','',
              '- PostgreSQL 16 and Redis 7; real network calls, not H2/in-memory mocks.',
              '- All three backends use identical PostgreSQL queries and Redis keys.',
              '- Every measured phase begins with reset of rows and keys. Post-run assertions verify persistent side effects.',
              '- UPDATE and transaction intentionally contend on rows 1/2; this is a hot-row workload.',
              '- INSERT appends rows; sequence and index growth may reduce throughput during long runs.',
              '- Benchmark servers, database, Redis and hey may compete for CPU on the same Mac.',
              '- Laravel Octane worker RSS/CPU are omitted (not zero).',
              '- Results with errors, missing repeats, or failed side-effect validation are excluded from ratios.',
              '- Inspect environment.json for run order, pool size, worker count, command and ports.', '']
    return '\n'.join(lines)


def chart(summary,meta,field,title):
    cases=list(meta.get('cases',[])); names=[label(f,meta) for f in meta.get('frameworks',[])]
    w=1050; h=340; left=80; right=26; top=48; bottom=88
    ph=h-top-bottom; pw=w-left-right
    eligible=[r for r in summary if r['valid'] and r['case'] in cases and r['framework'] in names and r.get(field) is not None]
    vmax=max([r[field] for r in eligible]+[1])*1.15
    y=lambda v:top+ph*(1-v/vmax)
    out=[f'<svg viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="{html.escape(title,quote=True)}">',
         f'<text x="{left}" y="25" class="heading">{html.escape(title)}</text>']
    for k in range(5):
        val=vmax*k/4; yy=y(val)
        out.append(f'<line class="grid" x1="{left}" x2="{w-right}" y1="{yy:.2f}" y2="{yy:.2f}"/>')
        out.append(f'<text class="tick" x="{left-10}" y="{yy+4:.2f}" text-anchor="end">{val:,.1f}</text>')
    lookup={(r['case'],r['framework']):r for r in eligible}
    step=pw/max(1,len(cases)); bw=min(25,step/(len(names)+1))
    for i,case in enumerate(cases):
        x=left+step*(i+.5)
        out.append(f'<text class="tick" x="{x:.1f}" y="{h-bottom+25}" text-anchor="middle">{html.escape(case)}</text>')
        for j,name in enumerate(names):
            row=lookup.get((case,name))
            if row is None:continue
            bx=x+(j-(len(names)-1)/2)*bw*1.2-bw/2
            val=row[field]; yy=y(val)
            color=FRAMEWORK_COLORS.get(name,'#64748b')
            out.append(f'<rect x="{bx:.1f}" y="{yy:.2f}" width="{bw:.2f}" height="{max(0,top+ph-yy):.2f}" fill="{color}"><title>{html.escape(name)} {html.escape(case)}: {val:.2f}</title></rect>')
    if not eligible:
        out.append('<text class="tick" x="525" y="170" text-anchor="middle">No complete validated measurements yet</text>')
    return ''.join(out)+'</svg>'


def make_reports(rows,metadata,directory:Path):
    directory.mkdir(parents=True,exist_ok=True)
    summary=aggregate(rows,int(metadata.get('repeats',1)))
    with (directory/'db-redis-summary.csv').open('w',newline='',encoding='utf-8') as fp:
        writer=csv.DictWriter(fp,fieldnames=FIELDS)
        writer.writeheader();writer.writerows(summary)
    (directory/'db-redis-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
    doc=markdown(summary,metadata)
    (directory/'report.md').write_text(doc,encoding='utf-8')
    names=[label(f,metadata) for f in metadata.get('frameworks',[])]
    css='''body{background:#f8fafc;color:#0f172a;font:15px system-ui,sans-serif;margin:0}
    main{max-width:1100px;margin:30px auto;padding:0 24px 50px}h1{font-size:28px}
    .card{background:#fff;border:1px solid #e2e8f0;border-radius:12px;padding:18px;margin:20px 0}
    .grid{stroke:#e2e8f0}.tick{fill:#64748b;font-size:11px}.heading{fill:#0f172a;font-size:17px;font-weight:650}
    pre{white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.5}
    .legend span{margin-right:23px}.legend i{display:inline-block;width:12px;height:12px;margin-right:5px}'''
    legend=''.join(f'<span><i style="background:{FRAMEWORK_COLORS.get(n,"#64748b")}"></i>{html.escape(n)}</span>' for n in names)
    body=f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>FastFrame Database & Redis Benchmark</title><style>{css}</style><main><h1>FastFrame v0.22 — Database & Redis</h1><p>Measurements only, no estimated scores. Invalid runs excluded from charts.</p><p class="legend">{legend}</p><section class="card">{chart(summary,metadata,'median_rps','Median completed requests/sec')}</section><section class="card">{chart(summary,metadata,'median_p99_ms','HTTP p99 latency (ms)')}</section><section class="card"><h2>Results and methodology</h2><pre>{html.escape(doc)}</pre></section></main></html>'''
    (directory/'report.html').write_text(body,encoding='utf-8')
    return summary
