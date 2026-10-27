#!/usr/bin/env python3
"""Self-contained, offline Markdown/CSV/HTML/SVG report for fixed-arrival benchmarks."""
from __future__ import annotations

import csv
import html
import json
import statistics
from collections import defaultdict
from pathlib import Path

from concurrency_report import FRAMEWORK_COLORS

SUMMARY_FIELDS = ('framework', 'case', 'target_rps', 'n', 'expected_repeats',
                  'complete', 'target_met', 'median_actual_rps', 'median_delivery_pct',
                  'median_p95_ms', 'median_p99_ms', 'median_dropped_iterations',
                  'median_errors')


def aggregate(rows: list[dict], repeats: int) -> list[dict]:
    if repeats < 1:
        raise ValueError('repeats must be positive')
    groups = defaultdict(list)
    for row in rows:
        groups[(str(row['framework']), str(row['case']), int(row['target_rps']))].append(row)
    summary = []
    for (framework, case, rate), group in sorted(groups.items(), key=lambda it: (it[0][1], it[0][2], it[0][0])):
        complete = (len(group) == repeats and
                    len({int(r['repeat']) for r in group}) == repeats and
                    all(int(r['requests']) > 0 for r in group))
        summary.append({
            'framework': framework, 'case': case, 'target_rps': rate, 'n': len(group),
            'expected_repeats': repeats, 'complete': complete,
            'target_met': complete and all(str(r['target_met']).lower() == 'true' for r in group),
            'median_actual_rps': round(statistics.median(float(r['actual_rps']) for r in group), 3),
            'median_delivery_pct': round(statistics.median(float(r['delivery_pct']) for r in group), 3),
            'median_p95_ms': round(statistics.median(float(r['p95_ms']) for r in group), 3),
            'median_p99_ms': round(statistics.median(float(r['p99_ms']) for r in group), 3),
            'median_dropped_iterations': round(statistics.median(int(r['dropped_iterations']) for r in group), 3),
            'median_errors': round(statistics.median(int(r['errors']) for r in group), 3),
        })
    return summary


def fmt(value: float | None, digits: int = 1) -> str:
    return f'{value:,.{digits}f}' if value is not None else '—'


def series_svg(summary: list[dict], case: str, field: str, frameworks: list[str],
               rates: list[int], title: str) -> str:
    width, height, left, right, top, bottom = 910, 340, 84, 26, 44, 72
    pw, ph = width-left-right, height-top-bottom
    displayed = [r for r in summary if r['case'] == case and r['complete'] and
                 r['framework'] in frameworks and r[field] is not None]
    max_value = max([float(r[field]) for r in displayed] + [1])*1.13
    x_min, x_max = min(rates), max(rates)
    def x(v):
        return left + (v-x_min)/max(1, x_max-x_min)*pw
    def y(v):
        return top + ph-(v/max_value)*ph
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" role="img" '
           f'aria-label="{html.escape(title, quote=True)}" viewBox="0 0 {width} {height}" width="100%">',
           f'<text x="{left}" y="25" class="chart-title">{html.escape(title)}</text>']
    for index in range(5):
        value = max_value * index/4
        yy = y(value)
        label = f'{value:,.0f}' if field == 'median_actual_rps' else f'{value:.1f}'
        out.append(f'<line x1="{left}" x2="{width-right}" y1="{yy:.1f}" y2="{yy:.1f}" class="gridline"/>')
        out.append(f'<text x="{left-10}" y="{yy+4:.1f}" text-anchor="end" class="tick">{label}</text>')
    # Distinct target levels on linear axis, no implied equally spaced numeric levels.
    for index, rate in enumerate(rates):
        out.append(f'<text x="{x(rate):.2f}" y="{height-bottom+23}" text-anchor="middle" '
                   f'class="tick">{rate:,}</text>')
    out.append(f'<text x="{left+pw/2:.2f}" y="{height-12}" text-anchor="middle" class="axis">Target RPS</text>')
    if field == 'median_actual_rps':
        out.append(f'<line x1="{x(x_min):.2f}" y1="{y(x_min):.2f}" '
                   f'x2="{x(x_max):.2f}" y2="{y(x_max):.2f}" '
                   'stroke="#94a3b8" stroke-width="1.4" stroke-dasharray="6,5"/>')
    for framework in frameworks:
        group = sorted((r for r in displayed if r['framework'] == framework), key=lambda r: r['target_rps'])
        color = FRAMEWORK_COLORS.get(framework, '#475569')
        # Never connect missing levels: missing data is not an interpolation.
        order = {rate:i for i,rate in enumerate(rates)}
        segments, current, last = [], [], -2
        for row in group:
            index = order[row['target_rps']]
            if index > last+1 and current:
                segments.append(current)
                current = []
            current.append(f'{x(row["target_rps"]):.2f},{y(row[field]):.2f}')
            last = index
        if current:
            segments.append(current)
        for segment in segments:
            if len(segment) > 1:
                out.append(f'<polyline points="{" ".join(segment)}" fill="none" '
                           f'stroke="{color}" stroke-width="2.6"/>')
        for row in group:
            cx, cy = x(row['target_rps']), y(row[field])
            shape = 'none' if not row['target_met'] else color
            tooltip = html.escape(f'{framework} {case} target={row["target_rps"]}; '
                                  f'{field}={row[field]}; target met={row["target_met"]}')
            out.append(f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="4.7" stroke="{color}" '
                       f'fill="{shape}" stroke-width="2"><title>{tooltip}</title></circle>')
    if not displayed:
        out.append(f'<text x="{width/2}" y="{height/2}" text-anchor="middle" '
                   'class="empty">No completed benchmark runs</text>')
    out.append('</svg>')
    return ''.join(out)


def markdown_report(summary: list[dict], metadata: dict) -> str:
    lines = ['# FastFrame v0.22 — Target RPS / p99', '',
             f"Run: `{metadata.get('timestamp_utc','unknown')}`  ",
             f"Host: `{metadata.get('platform', 'unknown')}`  ",
             f"Measured: `{metadata.get('duration', '60s')}` per level; "
             f"`{metadata.get('repeats',1)}` repeat(s); k6 open-loop constant-arrival-rate.", '',
             '| Case | Target RPS | Framework | Repeats | Complete | Target met | Actual RPS | Delivery % | p95 ms | p99 ms | Drops | Errors |',
             '|---|---:|---|---:|---|---|---:|---:|---:|---:|---:|---:|']
    index = {(r['case'], r['target_rps'], r['framework']):r for r in summary}
    for case in metadata['cases']:
        for rate in metadata['rates']:
            for f in metadata['frameworks']:
                label = ('laravel-'+metadata.get('laravel_server','octane')) if f == 'laravel' else f
                row = index.get((case, rate, label))
                if row is None:
                    lines.append(f'| {case} | {rate} | {label} | 0 | incomplete | no | — | — | — | — | — | — |')
                else:
                    lines.append(f"| {case} | {rate} | {label} | {row['n']} | "
                                 f"{'yes' if row['complete'] else 'no'} | "
                                 f"{'yes' if row['target_met'] else 'no'} | "
                                 f"{fmt(row['median_actual_rps'])} | {fmt(row['median_delivery_pct'])} | "
                                 f"{fmt(row['median_p95_ms'],2)} | {fmt(row['median_p99_ms'],2)} | "
                                 f"{fmt(row['median_dropped_iterations'],0)} | {fmt(row['median_errors'],0)} |")
    lines.extend(['', '## Interpretation', '',
                  '- Actual RPS = requests completed / configured measured duration. '
                  'k6-reported wall-time RPS is also preserved in raw CSV.',
                  '- "Target met" requires every repetition to have 95–105% delivery, '
                  'zero HTTP failures, and zero dropped iterations. This is a declared '
                  'comparison criterion, not proof that drops originate at the server.',
                  '- Hollow points on charts indicate missing the specified target. '
                  'They are intentionally visible to show overload/saturation.',
                  '- p99 is of *executed* requests, not dropped iterations; inspect both.',
                  '- HTTP microbenchmark results do not predict application/DB workloads.',
                  '- The generator can saturate and insufficient preallocated VUs can produce drops. '
                  'Run k6 on another host for stronger evidence.',
                  '- FastFrame and Micronaut Java-process RSS are not gathered by this k6 script; '
                  'Laravel process-tree CPU/RSS is likewise not claimed.', ''])
    return '\n'.join(lines)


def html_report(summary: list[dict], metadata: dict) -> str:
    rates = metadata['rates']
    cases = metadata['cases']
    frameworks = [('laravel-'+metadata.get('laravel_server','octane')) if f == 'laravel' else f
                  for f in metadata['frameworks']]
    css = '''body{background:#f8fafc;color:#0f172a;margin:0;font:14px system-ui, sans-serif}
    main{max-width:1200px;margin:30px auto;padding:0 24px 70px}h1{font-size:28px}
    .subtitle{color:#64748b}.panel{background:white;border:1px solid #e2e8f0;border-radius:12px;
    margin:20px 0;padding:18px;box-shadow:0 2px 6px #e2e8f066}.charts{display:grid;grid-template-columns:1fr;
    gap:12px}.chart-title{font-weight:650;font-size:18px;fill:#0f172a}.gridline{stroke:#e2e8f0}
    .tick,.axis{font-size:12px;fill:#64748b}.empty{fill:#64748b}table{border-collapse:collapse;
    width:100%;font-size:12px}td,th{border-bottom:1px solid #e2e8f0;text-align:right;padding:9px 6px}
    td:first-child,th:first-child,td:nth-child(2),th:nth-child(2){text-align:left}
    .scroll{overflow:auto}.muted{color:#64748b}.bad{color:#9a3412;font-weight:bold}
    .good{color:#166534;font-weight:bold}.legend span{display:inline-block;margin:8px 22px 0 0}
    .legend b{display:inline-block;width:12px;height:12px;border-radius:50%;vertical-align:middle}
    @media print{body{background:white}.panel{box-shadow:none;break-inside:avoid}}'''
    blocks = []
    for case in cases:
        blocks.append(f'<section class="panel"><h2>{html.escape(case.upper())}</h2>'
                      + '<div class="charts">'
                      + series_svg(summary, case, 'median_actual_rps', frameworks, rates,
                                   'Achieved RPS vs target (dashed: ideal)')
                      + series_svg(summary, case, 'median_p99_ms', frameworks, rates,
                                   'p99 latency (ms) vs target')
                      + '</div></section>')
    entries = []
    for row in summary:
        status = ('yes' if row['target_met'] else 'missed') if row['complete'] else 'incomplete'
        entries.append('<tr>' + ''.join(
            f'<td>{html.escape(str(cell))}</td>' for cell in (
                row['case'], row['framework'], row['target_rps'], row['n'],
                status, fmt(row['median_actual_rps']), fmt(row['median_p99_ms'],2),
                fmt(row['median_dropped_iterations'],0), fmt(row['median_errors'],0))) + '</tr>')
    legend = ''.join(f'<span><b style="background:{FRAMEWORK_COLORS.get(f,"#475569")}"></b> '
                     f'{html.escape(f)}</span>' for f in frameworks)
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>FastFrame v0.22 — Target RPS / p99</title><style>'+css+'</style></head><body><main>'
            '<h1>FastFrame v0.22 — Target RPS / p99</h1>'
            f'<p class="subtitle">{html.escape(metadata.get("platform","unknown"))} · '
            f'{html.escape(metadata.get("duration","60s"))} per rate · '
            'constant-arrival-rate (k6)</p><p class="legend">'+legend+'</p>'
            '<p class="muted">Filled points meet the requested rate. Hollow points missed target '
            'or encountered errors/dropped iterations. Missing runs are not interpolated. '
            'p99 describes only executed requests.</p>'
            + ''.join(blocks)
            + '<section class="panel"><h2>Measured results</h2><div class="scroll">'
            '<table><thead><tr><th>Case</th><th>Framework</th><th>Target</th><th>Runs</th>'
            '<th>Target met</th><th>Actual RPS</th><th>p99 (ms)</th><th>Dropped</th><th>Errors</th>'
            '</tr></thead><tbody>'+''.join(entries)+'</tbody></table></div></section>'
            '<p class="muted">Benchmark conditions and tool versions are in environment.json. '
            'If the generator is saturated, the outcome is inconclusive for server capacity.</p>'
            '</main></body></html>')


def make_reports(rows: list[dict], metadata: dict, out: Path) -> list[dict]:
    out.mkdir(parents=True, exist_ok=True)
    summary = aggregate(rows, int(metadata['repeats']))
    destination = out/'target-rps-summary.csv'
    with destination.open('w', encoding='utf-8', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=SUMMARY_FIELDS)
        writer.writeheader()
        writer.writerows(summary)
    (out/'target-rps-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    (out/'report.md').write_text(markdown_report(summary, metadata), encoding='utf-8')
    (out/'report.html').write_text(html_report(summary, metadata), encoding='utf-8')
    return summary


if __name__ == '__main__':
    import argparse
    p = argparse.ArgumentParser(description='Regenerate target RPS report from results CSV')
    p.add_argument('directory', type=Path)
    opts = p.parse_args()
    with (opts.directory/'target-rps-results.csv').open(newline='', encoding='utf-8') as f:
        rows = list(csv.DictReader(f))
    meta = json.loads((opts.directory/'environment.json').read_text(encoding='utf-8'))
    make_reports(rows, meta, opts.directory)
    print(opts.directory/'report.html')
