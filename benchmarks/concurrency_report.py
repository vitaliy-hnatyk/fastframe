#!/usr/bin/env python3
"""Dependency-free CSV/Markdown/HTML/SVG reports for concurrency scaling."""
from __future__ import annotations

import argparse
import csv
import html
import json
import statistics
from collections import defaultdict
from pathlib import Path

SUMMARY_FIELDS = ('framework', 'case', 'concurrency', 'n', 'expected_repeats',
                  'valid', 'median_rps', 'min_rps', 'max_rps',
                  'median_p95_ms', 'median_p99_ms', 'median_errors',
                  'median_rss_peak_mb', 'median_cpu_cores_avg')
FRAMEWORK_COLORS = {'fastframe': '#2563eb', 'micronaut': '#059669',
                    'laravel-octane': '#d97706', 'laravel-artisan': '#9c27b0'}


def aggregate(rows: list[dict], expected_repeats: int) -> list[dict]:
    """Medians; reject mixed errors and incomplete repetitions in comparisons."""
    if expected_repeats < 1:
        raise ValueError('expected_repeats must be positive')
    groups = defaultdict(list)
    for row in rows:
        groups[(str(row['framework']), str(row['case']), int(row['concurrency']))].append(row)
    summary = []
    def median(rows, key):
        values = [float(row[key]) for row in rows if row.get(key) not in ('', None)]
        return round(statistics.median(values), 4) if values else None
    for (framework, case, level), group in sorted(groups.items(), key=lambda i: (i[0][1], i[0][2], i[0][0])):
        rps = [float(r['rps']) for r in group]
        valid = (len(group) == expected_repeats and
                 len({int(row['repeat']) for row in group}) == expected_repeats and
                 all(int(row['errors']) == 0 and int(row['ok_2xx']) > 0 for row in group))
        summary.append({'framework': framework, 'case': case, 'concurrency': level,
                        'n': len(group), 'expected_repeats': expected_repeats,
                        'valid': valid, 'median_rps': round(statistics.median(rps), 4),
                        'min_rps': round(min(rps), 4), 'max_rps': round(max(rps), 4),
                        'median_p95_ms': median(group, 'p95_ms'),
                        'median_p99_ms': median(group, 'p99_ms'),
                        'median_errors': median(group, 'errors'),
                        'median_rss_peak_mb': median(group, 'rss_peak_mb'),
                        'median_cpu_cores_avg': median(group, 'cpu_cores_avg')})
    return summary


def fmt(value: float | None, digits: int = 1) -> str:
    return f'{value:,.{digits}f}' if value is not None else 'n/a'


def ratios(summary: list[dict], case: str, level: int) -> list[tuple[str, float]]:
    values = {r['framework']: r['median_rps'] for r in summary
              if r['case'] == case and r['concurrency'] == level and r['valid']}
    base = values.get('fastframe')
    if base is None:
        return []
    return [(name, base / val) for name, val in values.items()
            if name != 'fastframe' and val > 0]


def markdown_report(summary: list[dict], metadata: dict) -> str:
    cases = metadata.get('cases', sorted({r['case'] for r in summary}))
    levels = metadata.get('levels', sorted({r['concurrency'] for r in summary}))
    lines = ['# FastFrame v0.22 — Concurrency Scaling', '',
             f"Run: `{metadata.get('timestamp_utc', 'unknown')}`  ",
             f"Platform: `{metadata.get('platform', 'unknown')}`  ",
             f"Workload: `{metadata.get('duration', '30s')}` measured per level, "
             f"`{metadata.get('warmup', '5s')}` warmup, "
             f"`{metadata.get('repeats', 1)}` repetition(s).", '',
             '**Valid** requires every requested repetition with zero non-2xx responses; '
             'incomplete or error-bearing rows remain visible but are excluded from comparisons.', '',
             '| Case | Clients | Framework | Runs | Valid | Median RPS | p95 ms | p99 ms | Peak RSS MiB |',
             '|---|---:|---|---:|---|---:|---:|---:|---:|']
    index = {(r['case'], r['concurrency'], r['framework']): r for r in summary}
    for case in cases:
        for level in levels:
            for framework in metadata.get('frameworks', []):
                label = (f'laravel-{metadata.get("laravel_server", "octane")}'
                         if framework == 'laravel' else framework)
                r = index.get((case, level, label))
                if not r:
                    lines.append(f'| {case} | {level} | {label} | 0 | incomplete | — | — | — | — |')
                    continue
                lines.append(f"| {case} | {level} | {label} | {r['n']} | "
                             f"{'yes' if r['valid'] else 'NO'} | {fmt(r['median_rps'])} | "
                             f"{fmt(r['median_p95_ms'], 2)} | {fmt(r['median_p99_ms'], 2)} | "
                             f"{fmt(r['median_rss_peak_mb'])} |")
    lines.extend(['', '## FastFrame RPS ratios (only valid, complete groups)', '',
                  '| Case | Clients | Opponent | FastFrame / opponent |',
                  '|---|---:|---|---:|'])
    for case in cases:
        for level in levels:
            for opponent, ratio in ratios(summary, case, level):
                lines.append(f'| {case} | {level} | {opponent} | {ratio:.3f}× |')
    lines.extend(['', '## Interpretation and limitations', '',
                  '- The measurements cover HTTP microbenchmarks, not full business transactions.',
                  '- RPS is completed HTTP requests divided by observed wall time; p99 is per-request latency.',
                  '- Results are not statistically significant with a single repetition; use 3–5 repeats.',
                  '- `hey -o csv` consumes client resources. Running the generator and server on '
                  'the same host can cap throughput or distort high-concurrency curves.',
                  '- Laravel multi-process RSS/CPU values are blank (not zero).',
                  '- Compare RPS and p99 together; throughput with HTTP errors is invalid.',
                  '- Review environment.json for exact host, JVM/PHP versions and worker settings.', ''])
    return '\n'.join(lines)


def svg_plot(rows: list[dict], case: str, field: str, frameworks: list[str],
             levels: list[int], title: str) -> str:
    width, height, left, right, top, bottom = 860, 350, 78, 24, 44, 68
    plot_w, plot_h = width-left-right, height-top-bottom
    visible = [r for r in rows if r['case'] == case and r['valid']
               and r['framework'] in frameworks and r[field] is not None]
    maxima = max([float(r[field]) for r in visible] + [1]) * 1.12
    # Equal columns represent measurement levels, including 1 and 1024.
    order = {lv: i for i, lv in enumerate(levels)}
    def x(level):
        return left + (order[level] / max(1, len(levels)-1))*plot_w
    def y(value):
        return top + plot_h - float(value)/maxima*plot_h
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" role="img" '
           f'aria-label="{html.escape(title, quote=True)}" viewBox="0 0 {width} {height}" '
           f'width="100%" height="auto">',
           f'<text x="{left}" y="24" class="chart-title">{html.escape(title)}</text>']
    for i in range(5):
        val = maxima*i/4
        ypos = y(val)
        out.append(f'<line x1="{left}" y1="{ypos:.1f}" x2="{width-right}" y2="{ypos:.1f}" class="gridline"/>')
        tick = f'{val:.1f}' if field == 'median_p99_ms' else f'{val:,.0f}'
        out.append(f'<text x="{left-12}" y="{ypos+4:.1f}" text-anchor="end" class="tick">{tick}</text>')
    for lv in levels:
        out.append(f'<text x="{x(lv):.1f}" y="{height-bottom+22}" text-anchor="middle" '
                   f'class="tick">{lv}</text>')
    out.append(f'<text x="{left+plot_w/2:.1f}" y="{height-12}" text-anchor="middle" '
               f'class="axis">Concurrent clients</text>')
    for framework in frameworks:
        points = sorted((r for r in visible if r['framework'] == framework),
                        key=lambda r: r['concurrency'])
        if not points:
            continue
        color = FRAMEWORK_COLORS.get(framework, '#475569')
        segments = []
        current = []
        previous = None
        for row in points:
            level_index = order[row['concurrency']]
            if previous is not None and level_index != previous + 1 and current:
                segments.append(current)
                current = []
            current.append(f'{x(row["concurrency"]):.2f},{y(row[field]):.2f}')
            previous = level_index
        if current:
            segments.append(current)
        for segment in segments:
            if len(segment) > 1:
                out.append(f'<polyline points="{" ".join(segment)}" fill="none" '
                           f'stroke="{color}" stroke-width="2.6"/>')
        for row in points:
            cx, cy = x(row['concurrency']), y(row[field])
            tip = html.escape(f'{framework} / {case}: {row["concurrency"]} clients; '
                              f'{field}={row[field]:.3f}; n={row["n"]}')
            out.append(f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="4.1" fill="{color}">'
                       f'<title>{tip}</title></circle>')
    if not visible:
        out.append(f'<text x="{width/2}" y="{height/2}" text-anchor="middle" '
                   f'class="empty">No valid completed measurements yet</text>')
    out.append('</svg>')
    return ''.join(out)


def html_report(summary: list[dict], metadata: dict) -> str:
    cases = metadata.get('cases', sorted({r['case'] for r in summary}))
    levels = metadata.get('levels', sorted({r['concurrency'] for r in summary}))
    frameworks = [(f'laravel-{metadata.get("laravel_server", "octane")}' if f == 'laravel' else f)
                  for f in metadata.get('frameworks', [])]
    css = '''body{background:#f8fafc;color:#0f172a;margin:0;font:15px system-ui,sans-serif}
    main{max-width:1080px;margin:28px auto;padding:0 24px 60px}h1{font-size:27px}
    h2{font-size:20px;margin-top:32px}.subtitle{color:#475569}
    .panel{background:white;border:1px solid #e2e8f0;border-radius:12px;padding:18px 14px;margin:16px 0}
    .charts{display:grid;grid-template-columns:1fr;gap:8px}
    table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}th,td{padding:9px 10px;
    border-bottom:1px solid #e2e8f0;text-align:right}th:first-child,td:first-child{text-align:left}
    th{background:#f1f5f9}.scroll{overflow-x:auto}.warn{color:#9a3412}.ok{color:#047857}
    .chart-title{font-size:16px;font-weight:650;fill:#0f172a}.tick{font-size:12px;fill:#64748b}
    .gridline{stroke:#e2e8f0;stroke-width:1}.axis{font-size:13px;fill:#334155}
    .empty{font-size:15px;fill:#64748b}.legend{display:flex;gap:22px;flex-wrap:wrap}
    .swatch{display:inline-block;width:12px;height:12px;border-radius:3px;margin-right:6px}
    .foot{line-height:1.6;color:#475569}'''
    parts = ['<!doctype html><html lang="en"><head><meta charset="utf-8">',
             '<meta name="viewport" content="width=device-width, initial-scale=1">',
             '<title>FastFrame v0.22 | Concurrency Scaling</title>',
             f'<style>{css}</style></head><body><main>',
             '<h1>FastFrame v0.22 — Concurrency Scaling</h1>',
             f'<p class="subtitle">{html.escape(str(metadata.get("timestamp_utc", "")))} · '
             f'{html.escape(str(metadata.get("platform", "")))} · '
             f'{html.escape(str(metadata.get("duration", "")))} / level · '
             f'{metadata.get("repeats",1)} repeat(s)</p>', '<div class="legend">']
    for f in frameworks:
        color = FRAMEWORK_COLORS.get(f, '#475569')
        parts.append(f'<span><i class="swatch" style="background:{color}"></i>{html.escape(f)}</span>')
    parts.append('</div>')
    for case in cases:
        parts.extend([f'<h2>{html.escape(case.title())}</h2>', '<div class="charts">',
                      '<section class="panel">',
                      svg_plot(summary, case, 'median_rps', frameworks, levels, 'Throughput (requests/sec)'),
                      '</section><section class="panel">',
                      svg_plot(summary, case, 'median_p99_ms', frameworks, levels, 'p99 latency (ms)'),
                      '</section></div>'])
    parts.append('<h2>Measured data (median of completed repetitions)</h2><div class="panel scroll"><table>'
                 '<thead><tr><th>Framework</th><th>Case</th><th>Clients</th><th>n</th>'
                 '<th>Status</th><th>RPS</th><th>p95 ms</th><th>p99 ms</th></tr></thead><tbody>')
    for r in summary:
        valid = bool(r['valid'])
        parts.append('<tr>' + f'<td>{html.escape(r["framework"])}</td>'
                     f'<td>{html.escape(r["case"])}</td><td>{r["concurrency"]}</td><td>{r["n"]}</td>'
                     f'<td class="{"ok" if valid else "warn"}">{"valid" if valid else "invalid / incomplete"}</td>'
                     f'<td>{fmt(r["median_rps"])}</td><td>{fmt(r["median_p95_ms"],2)}</td>'
                     f'<td>{fmt(r["median_p99_ms"],2)}</td></tr>')
    parts.append('</tbody></table></div><section class="foot"><h2>Reading the report</h2>'
                 '<p>Only complete groups without HTTP errors are plotted. '
                 'RPS alone is not proof of superiority: consider p99, variance, CPU, memory and load-generator capacity. '
                 'Missing points are not zero throughput. Tests use hey on the same host unless moved separately. '
                 'Laravels worker-process memory/CPU is not reported by this runner.</p>'
                 '<p>Open <code>concurrency-summary.csv</code> for medians and '
                 '<code>environment.json</code> for reproducibility settings. '
                 'For repeatable results, run 3–5 passes and observe host utilization.</p></section>'
                 '</main></body></html>')
    return ''.join(parts)


def make_reports(rows: list[dict], metadata: dict, destination: Path) -> list[dict]:
    destination.mkdir(parents=True, exist_ok=True)
    summary = aggregate(rows, int(metadata.get('repeats', 1)))
    with (destination / 'concurrency-summary.csv').open('w', newline='', encoding='utf-8') as fp:
        writer = csv.DictWriter(fp, fieldnames=SUMMARY_FIELDS)
        writer.writeheader()
        writer.writerows(summary)
    (destination / 'concurrency-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    (destination / 'report.md').write_text(markdown_report(summary, metadata), encoding='utf-8')
    (destination / 'report.html').write_text(html_report(summary, metadata), encoding='utf-8')
    return summary


if __name__ == '__main__':
    p = argparse.ArgumentParser(description='Recreate concurrency summary and charts from measured CSV')
    p.add_argument('directory', type=Path, help='Directory with concurrency-results.csv and environment.json')
    a = p.parse_args()
    with (a.directory / 'concurrency-results.csv').open(newline='', encoding='utf-8') as fp:
        rows = list(csv.DictReader(fp))
    metadata = json.loads((a.directory / 'environment.json').read_text(encoding='utf-8'))
    make_reports(rows, metadata, a.directory)
    print(f'Rebuilt {a.directory / "report.html"}')
