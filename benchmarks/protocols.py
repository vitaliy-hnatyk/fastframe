#!/usr/bin/env python3
"""Small curl-based protocol-negotiation comparison (not maximum server capacity)."""
import argparse, csv, html, json, math, subprocess, time
from datetime import datetime,timezone
from pathlib import Path
PROTOCOLS={'h1':('--http1.1','1.1'),'h2':('--http2','2'),'h3':('--http3-only','3')}
def parse(text,requested):
    values=[]
    for line in text.splitlines():
        parts=line.strip().split(',')
        if len(parts)!=3:continue
        try:
            version,status,elapsed=parts[0],int(parts[1]),float(parts[2])
            values.append((version,status,elapsed,version==PROTOCOLS[requested][1] and 200<=status<400))
        except ValueError:pass
    return values
def benchmark(curl,name,url,proto,count,concurrency,insecure):
    cmd=[curl,'--silent','--show-error','--parallel','--parallel-immediate','--parallel-max',str(concurrency),PROTOCOLS[proto][0],'-w','%{http_version},%{http_code},%{time_total}\n']
    if insecure:cmd.append('--insecure')
    for _ in range(count):cmd.extend(['--url',url,'--output','NUL' if __import__('os').name=='nt' else '/dev/null'])
    start=time.monotonic();result=subprocess.run(cmd,capture_output=True,text=True,encoding='utf-8',errors='replace');duration=time.monotonic()-start
    values=parse(result.stdout,proto)
    complete=result.returncode==0 and len(values)==count and all(v[3] for v in values)
    latencies=sorted(v[2]*1000 for v in values if v[3])
    return {'framework':name,'requested':proto,'observed':','.join(sorted(set(v[0] for v in values))),'status':'COMPLETE' if complete else 'INVALID_OR_FALLBACK',
        'requests':count,'received':len(values),'rps':round(count/duration,2) if complete and duration else None,
        'p99_ms':round(latencies[max(0,math.ceil(len(latencies)*.99)-1)],3) if latencies else None,'stderr':result.stderr[-1000:]}
def report(path,rows):
    path.mkdir(parents=True,exist_ok=True)
    (path/'results.json').write_text(json.dumps(rows,indent=2)+'\n',encoding='utf-8')
    with (path/'results.csv').open('w',newline='',encoding='utf-8') as f:
        writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader();writer.writerows(rows)
    tr=''.join('<tr>'+''.join('<td>'+html.escape(str(row[k]))+'</td>' for k in ('framework','requested','observed','status','rps','p99_ms'))+'</tr>' for row in rows)
    (path/'report.html').write_text('<!doctype html><meta charset="utf-8"><title>FastFrame protocols</title><style>body{font:16px system-ui;max-width:1000px;margin:48px auto}td,th{padding:10px;border-bottom:1px solid #aaa}</style><h1>HTTP Protocol Comparison</h1><p>Includes curl client overhead/handshakes. Not maximum-RPS.</p><table><tr><th>Framework</th><th>Requested</th><th>Negotiated</th><th>Status</th><th>RPS</th><th>p99 ms</th></tr>'+tr+'</table>',encoding='utf-8')
def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--target',action='append',required=True,metavar='NAME=https://URL');p.add_argument('--protocols',nargs='+',choices=list(PROTOCOLS),default=list(PROTOCOLS));p.add_argument('--requests',type=int,default=100);p.add_argument('--concurrency',type=int,default=16);p.add_argument('--insecure',action='store_true');p.add_argument('--curl',default='curl');p.add_argument('--output',type=Path)
    a=p.parse_args(argv)
    if a.requests<=0 or a.concurrency<=0:p.error('positive requests and concurrency required')
    targets=[]
    for item in a.target:
        if '=' not in item:p.error('--target NAME=https://URL required')
        name,url=item.split('=',1)
        if not name or not url.startswith('https://'):p.error('targets require name and HTTPS URL')
        targets.append((name,url))
    path=a.output or Path('benchmarks/results')/('protocols-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
    rows=[]
    for name,url in targets:
        for proto in a.protocols:
            result=benchmark(a.curl,name,url,proto,a.requests,a.concurrency,a.insecure);rows.append(result);report(path,rows)
            print(f'{name}/{proto}: {result["status"]} RPS={result["rps"]} p99={result["p99_ms"]}')
    print('Report:',path/'report.html')
    return 0 if all(row['status']=='COMPLETE' for row in rows) else 2
if __name__=='__main__':raise SystemExit(main())
