#!/usr/bin/env python3
"""Summarize comparable valid benchmark runs (median across repetitions)."""
import argparse
import csv
import statistics
from collections import defaultdict
from pathlib import Path


def summarize(path: Path) -> list[str]:
    grouped = defaultdict(list)
    with path.open(newline='') as infile:
        for row in csv.DictReader(infile):
            grouped[(row['case'], row['framework'])].append(row)
    lines = ['case       framework        n   median RPS    median p95 ms   median p99 ms   peak RSS MB']
    cases = sorted({key[0] for key in grouped})
    ordering = ('fastframe', 'micronaut', 'laravel-octane', 'laravel-artisan')
    frameworks = [f for f in ordering if any(key[1] == f for key in grouped)]
    frameworks.extend(sorted({k[1] for k in grouped} - set(frameworks)))
    for case in cases:
        for framework in frameworks:
            rows = grouped.get((case, framework), [])
            if not rows:
                continue
            if any(int(r['errors']) for r in rows):
                lines.append(f'{case:10} {framework:16} INVALID (non-2xx responses)')
                continue
            median = lambda field: statistics.median(float(r[field]) for r in rows)
            rss = [float(r['rss_peak_mb']) for r in rows if r['rss_peak_mb']]
            rss_text = f'{statistics.median(rss):9.1f}' if rss else '       n/a'
            lines.append(f'{case:10} {framework:16} {len(rows):2} {median("rps"):12.1f}'
                         f' {median("p95_ms"):15.3f} {median("p99_ms"):15.3f} {rss_text}')
        fa = grouped.get((case, 'fastframe'), [])
        for other in ('micronaut', 'laravel-octane', 'laravel-artisan'):
            competing = grouped.get((case, other), [])
            if fa and competing and all(int(r['errors']) == 0 for r in fa + competing):
                a = statistics.median(float(r['rps']) for r in fa)
                b = statistics.median(float(r['rps']) for r in competing)
                if b > 0:
                    lines.append(f'  RPS FastFrame / {other}: {a / b:.3f}x')
    return lines


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('csv', type=Path, help='Path to results.csv')
    args = p.parse_args()
    print('\n'.join(summarize(args.csv)))
