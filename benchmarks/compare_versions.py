#!/usr/bin/env python3
"""Compare two benchmark results.csv files; report measured medians only."""
import argparse
import csv
import statistics
from pathlib import Path


def load(path: Path, framework: str) -> dict[str, list[dict]]:
    cases = {}
    with path.open(newline='', encoding='utf-8') as source:
        reader = csv.DictReader(source)
        needed = {'framework', 'case', 'rps', 'p99_ms', 'errors'}
        if not needed.issubset(reader.fieldnames or []):
            raise ValueError(f'Missing benchmark columns in {path}')
        for row in reader:
            if row['framework'] != framework:
                continue
            if int(row['errors']) != 0:
                raise ValueError(f'HTTP errors in {path} for {row["case"]}: {row["errors"]}')
            cases.setdefault(row['case'], []).append(row)
    if not cases:
        raise ValueError(f'No rows for {framework} in {path}')
    return cases


def summarize(base: dict, candidate: dict) -> list[dict]:
    results = []
    for case in sorted(set(base) & set(candidate)):
        def median(rows, key):
            return statistics.median(float(row[key]) for row in rows)
        before, after = median(base[case], 'rps'), median(candidate[case], 'rps')
        before_p99, after_p99 = median(base[case], 'p99_ms'), median(candidate[case], 'p99_ms')
        results.append({'case':case, 'baseline_rps':before, 'candidate_rps':after,
                        'rps_change_pct':((after/before - 1)*100 if before else None),
                        'baseline_p99_ms':before_p99, 'candidate_p99_ms':after_p99,
                        'baseline_repeats':len(base[case]), 'candidate_repeats':len(candidate[case])})
    return results


def main():
    p = argparse.ArgumentParser(description='Median benchmark comparison; no invented results')
    p.add_argument('--baseline', required=True, type=Path, help='v0.20 results.csv')
    p.add_argument('--candidate', required=True, type=Path, help='v0.21 results.csv')
    p.add_argument('--framework', choices=['fastframe','micronaut'], default='fastframe')
    args = p.parse_args()
    results = summarize(load(args.baseline,args.framework), load(args.candidate,args.framework))
    print(f'Framework: {args.framework}; compare runs with same JDK, machine and settings')
    print(f'{"case":<9} {"baseline":>12} {"candidate":>12} {"RPS delta":>12} {"p99 before":>12} {"p99 after":>12} {"repeats":>9}')
    for r in results:
        change = f'{r["rps_change_pct"]:+.2f}%' if r['rps_change_pct'] is not None else 'n/a'
        print(f'{r["case"]:<9} {r["baseline_rps"]:>12.1f} {r["candidate_rps"]:>12.1f} {change:>12} '
              f'{r["baseline_p99_ms"]:>12.2f} {r["candidate_p99_ms"]:>12.2f} '
              f'{r["baseline_repeats"]}/{r["candidate_repeats"]:>7}')
    if not results: raise SystemExit('No scenarios present in both files')

if __name__ == '__main__': main()
