"""Self-contained HTML/SVG, CSV, JSON and Markdown soak benchmark reporting."""
from __future__ import annotations
import csv
import html
import json
import math
from pathlib import Path

COLORS = {'fastframe':'#2563eb','micronaut':'#0891b2',
          'laravel-octane':'#d97706','laravel-artisan':'#f97316'}
SUMMARY_FIELDS = ('framework','case','repeat','status','requests','actual_rps','p99_ms',
                  'errors','dropped_iterations','rss_start_mb','rss_peak_mb','rss_end_mb',
                  'rss_slope_mb_hr','cpu_cores_avg','heap_peak_mb','gc_collections',
                  'gc_pause_s','probe_errors','sample_count','failure')


def status(row: dict) -> str:
    if not row.get('complete'): return 'INCOMPLETE'
    if row.get('errors',0) or row.get('dropped_iterations',0): return 'ERRORS_OR_DROPS'
    if row.get('probe_errors',0): return 'PROBE_FAILURES'
    if not row.get('target_met'): return 'TARGET_MISSED'
    if not row.get('sample_count') or row.get('rss_peak_mb') is None: return 'UNMONITORED'
    return 'COMPLETE'


def summarize(results: list[dict]) -> list[dict]:
    return [{key: status(row) if key == 'status' else row.get(key) for key in SUMMARY_FIELDS}
            for row in results]


def numeric(value, digits=2):
    if value is None:return 'N/A'
    if isinstance(value, float):
        if not math.isfinite(value):return 'N/A'
        return f'{value:,.{digits}f}'
    return str(value)


def chart(samples: list[dict], field: str, heading: str, unit: str, frameworks: list[str], expected_minutes: float = 60.0) -> str:
    w,h=1000,315
    left,right,top,bottom=85,30,45,54
    plot_w=w-left-right
    plot_h=h-top-bottom
    records={}
    for row in samples:
        v=row.get(field)
        if v is None or not isinstance(v,(float,int)) or not math.isfinite(float(v)):
            continue
        name=row['framework'] if row.get('repeat',1)==1 else f'{row["framework"]} #{row["repeat"]}'
        records.setdefault(name,[]).append((float(row['elapsed_s']) / 60, float(v)))
    x_max=max([x for series in records.values() for x,_ in series]+[max(0.05, expected_minutes)])
    y_max=max([y for series in records.values() for _,y in series]+[1])*1.12
    def xp(x): return left+x/x_max*plot_w
    def yp(y): return top+plot_h-y/y_max*plot_h
    out=[f'<svg viewBox="0 0 {w} {h}" role="img" aria-label="{html.escape(heading,quote=True)}" '
         'xmlns="http://www.w3.org/2000/svg" width="100%">',
         f'<text x="{left}" y="23" class="cht">{html.escape(heading)}</text>']
    for k in range(5):
        yy=y_max*k/4
        ypos=yp(yy)
        out.append(f'<line class="grid" x1="{left}" y1="{ypos:.2f}" x2="{w-right}" y2="{ypos:.2f}"/>')
        out.append(f'<text class="tick" x="{left-12}" y="{ypos+4:.2f}" text-anchor="end">{numeric(yy,1)}</text>')
    for k in range(7):
        t=x_max*k/6
        label = f'{t:.1f}' if x_max < 2 else f'{t:.0f}'
        out.append(f'<text class="tick" x="{xp(t):.2f}" y="{h-bottom+20}" text-anchor="middle">{label}</text>')
    out.append(f'<text class="tick" x="{left+plot_w/2}" y="{h-9}" text-anchor="middle">Elapsed minutes</text>')
    out.append(f'<text class="tick" x="{left+4}" y="{top+17}">{html.escape(unit)}</text>')
    for name, series in records.items():
        series.sort()
        base=name.split(' #',1)[0]
        color=COLORS.get(base,'#64748b')
        points=' '.join(f'{xp(x):.2f},{yp(y):.2f}' for x,y in series)
        if len(series)>1:
            out.append(f'<polyline fill="none" stroke="{color}" stroke-width="2.3" points="{points}"/>')
        else:
            x,y=series[0]
            out.append(f'<circle fill="{color}" cx="{xp(x):.2f}" cy="{yp(y):.2f}" r="4"/>')
    if not records:
        out.append(f'<text class="tick" x="{w/2}" y="{h/2}" text-anchor="middle">Not available for selected runtimes</text>')
    out.append('</svg>')
    return ''.join(out)


def html_report(results, samples, metadata):
    frameworks=[('laravel-'+metadata.get('laravel_server','octane')) if x=='laravel' else x
                for x in metadata.get('frameworks',[])]
    css='''body{margin:0;background:#f8fafc;color:#0f172a;font:15px system-ui, sans-serif}
    main{max-width:1120px;margin:30px auto;padding:0 24px 60px}h1{font-size:28px}
    h2{font-size:21px;margin-top:30px}.lead{color:#475569;line-height:1.6}
    .card{padding:16px;margin:16px 0;border-radius:12px;border:1px solid #e2e8f0;background:white}
    .grid{stroke:#e2e8f0;stroke-width:1}.tick{fill:#64748b;font-size:12px}.cht{fill:#0f172a;font-size:17px;font-weight:650}
    .legend{display:flex;flex-wrap:wrap;gap:18px;margin:20px 0}.swatch{display:inline-block;width:11px;height:11px;border-radius:3px;margin-right:6px}
    table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}td,th{padding:10px;text-align:right;border-bottom:1px solid #e2e8f0}
    td:first-child,th:first-child{text-align:left}th{background:#f1f5f9}.scroll{overflow-x:auto}.warn{color:#9a3412}.ok{color:#047857}
    .note{line-height:1.55;color:#475569}'''
    out=['<!doctype html><html lang="en"><head><meta charset="utf-8">',
         '<meta name="viewport" content="width=device-width, initial-scale=1">',
         '<title>FastFrame v0.22 — 60-minute Soak</title>',
         f'<style>{css}</style></head><body><main><h1>60-minute Soak Benchmark</h1>',
         f'<p class="lead">FastFrame / Micronaut / Laravel Octane · '
         f'{html.escape(metadata.get("created_utc",""))} · {html.escape(metadata.get("platform",""))} · '
         f'fixed offered rate {metadata.get("rate",0):,} RPS · '
         f'duration {html.escape(metadata.get("duration",""))} per framework</p>',
         '<div class="legend">']
    for name in frameworks:
        out.append(f'<span><i class="swatch" style="background:{COLORS.get(name,"#64748b")}"></i>{html.escape(name)}</span>')
    out.append('</div>')
    graphs=[('rss_mb','RSS — complete process tree','MiB'),
            ('heap_used_mb','Java heap used (jstat -gc)','MiB'),
            ('cpu_cores','CPU consumption (process tree)','CPU cores'),
            ('gc_count','JVM cumulative GC collections','count'),
            ('gc_pause_s','JVM cumulative GC time','seconds'),
            ('probe_errors','Cumulative independent HTTP probe failures','count')]
    try: expected_minutes = float(metadata.get('duration','3600s').removesuffix('s')) / 60
    except (ValueError, AttributeError): expected_minutes = 60.0
    for field,title,unit in graphs:
        out.append('<section class="card">'+chart(samples,field,title,unit,frameworks,expected_minutes)+'</section>')
    out.append('<h2>Summary</h2><section class="card scroll"><table><thead><tr>')
    columns=['Framework','Status','RPS','p99 (ms)','HTTP errors','k6 dropped',
             'Peak RSS (MiB)','RSS slope (MiB/h)','CPU (cores)','Peak heap (MiB)',
             'GC count','GC time (s)','Probe failures']
    out.extend('<th>'+html.escape(x)+'</th>' for x in columns)
    out.append('</tr></thead><tbody>')
    for row in results:
        state=status(row)
        cells=[str(row.get('framework','')),state,numeric(row.get('actual_rps'),1),numeric(row.get('p99_ms')),
               numeric(row.get('errors'),0), numeric(row.get('dropped_iterations'),0),
               numeric(row.get('rss_peak_mb')),numeric(row.get('rss_slope_mb_hr')),
               numeric(row.get('cpu_cores_avg')),numeric(row.get('heap_peak_mb')),
               numeric(row.get('gc_collections'),0),numeric(row.get('gc_pause_s')),
               numeric(row.get('probe_errors'),0)]
        out.append('<tr>'+''.join(f'<td>{html.escape(v)}</td>' for v in cells)+'</tr>')
    out.append('</tbody></table></section>')
    if not results: out.append('<p class="lead">No completed measurements yet.</p>')
    out.append('''<section class="note"><h2>Interpretation / limitations</h2><p>
    One hour per framework is a separate sequential run; a three-framework comparison is at least three hours.
    Measurements compare the same requested RPS and endpoint. A failed target, dropped k6 iterations,
    or HTTP error is not a successful load level. Probe failures are independent diagnostics, not load-test HTTP errors.
    RSS sums each process's resident set, including shared pages counted for multiple workers. It is NOT unique physical RAM.
    Java heap and GC are from jstat and include application startup baseline; PHP heap/GC are unavailable, not zero.
    The heap values exclude metaspace and native buffers. GC time means cumulative JVM collection time (not p99 GC pause).
    Positive RSS growth or slope alone does not prove a leak. Short test runs yield unreliable trends.
    Sampling and k6 on the same host can distort measurements. For production-quality results, run k6 remotely
    and compare repetitions in a controlled environment. Incomplete runs remain labeled INCOMPLETE.
    </p></section></main></body></html>''')
    return ''.join(out)


def md_report(results, metadata):
    lines=['# FastFrame v0.22 — 60-minute soak','',
           f'Offered rate: {metadata.get("rate")} RPS · Duration: {metadata.get("duration")} per framework', '',
           '| Framework | Status | RPS | p99 ms | HTTP errors | Drops | Peak RSS MiB | RSS trend MiB/hr | CPU cores | Heap MiB | GC count | GC seconds |',
           '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for r in results:
        fields=['framework', 'status','actual_rps','p99_ms','errors','dropped_iterations',
                'rss_peak_mb','rss_slope_mb_hr','cpu_cores_avg','heap_peak_mb','gc_collections','gc_pause_s']
        row={**r,'status':status(r)}
        lines.append('| '+' | '.join(numeric(row.get(k)) for k in fields)+' |')
    lines+=['','## Reporting notes','',
            '- HTTP errors/dropped iterations come from k6; separate HTTP probe errors are in the time series.',
            '- Java JVM heap/GC via jstat; PHP is N/A for JVM-only metrics.',
            '- All RSS values sum the process tree, potentially double-counting shared memory.',
            '- RSS slope is a descriptive trend, **not proof of a memory leak**.',
            '- One 60-minute soak per framework; for three frameworks total measurement is 180 minutes.',
            '- Incomplete runs remain INCOMPLETE; see raw logs and CSV/JSON.','']
    return '\n'.join(lines)


def make_reports(results, samples, metadata, folder: Path):
    folder.mkdir(parents=True,exist_ok=True)
    summary=summarize(results)
    with (folder/'soak-summary.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=SUMMARY_FIELDS)
        writer.writeheader(); writer.writerows(summary)
    (folder/'soak-summary.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
    (folder/'report.html').write_text(html_report(results,samples,metadata),encoding='utf-8')
    (folder/'report.md').write_text(md_report(results,metadata),encoding='utf-8')
    return summary
