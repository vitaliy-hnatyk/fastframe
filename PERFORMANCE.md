# FastFrame v0.21 — Performance and memory diagnostics

Java 21 · Windows 10/11 / Linux / macOS · Netty 4.2.x.

## Source changes

1. `Json.utf8(Object)` emits bounded UTF-8 bytes in one serialization pass instead of constructing an intermediate `String`, then encoding it again. The existing `Json.stringify(Object)` is preserved for compatibility.
2. `Response.jsonValue(Object)` uses `Json.utf8()`; JSON escaping of `<`, `>`, `&`, lone surrogates, controls, cyclic input and excessive nesting remains consistent with v0.20.
3. Netty's binary `Content-Type` check uses a character scan instead of `split()`, and CORS response-header processing avoids allocating temporary collections and regex/stream processing.
4. `benchmarks/run.py` now derives both benchmark JAR filenames from their Maven POM versions. Earlier versions incorrectly expected `benchmark-fastframe-0.9.0-SNAPSHOT.jar` even when building v0.20.
5. Added repeat-aware `compare_versions.py` and Windows `profile.ps1` for JFR evidence before more optimizations.

## Build and validate — Windows PowerShell

Open PowerShell at `C:\xampp\www\fastframe` after installing and verifying Java 21. The included `scripts/windows-env.ps1` selects JDK 21 for its PowerShell process only, leaving global Java 8 unaffected.

```powershell
Set-ExecutionPolicy -Scope Process RemoteSigned
.\scripts\json-allocation.ps1
mvn -B -ntp -pl framework-core,framework-netty,demo-app -am clean verify
.\benchmarks\build.ps1
.\benchmarks\preflight.ps1
```

If PowerShell blocks files marked as downloaded, review them and use `Unblock-File` selectively; do not weaken machine-wide policies. Run these scripts only from trusted source.

## Allocation diagnostic

```powershell
.\scripts\json-allocation.ps1
```

`JsonAllocationProbe.java` compares `Response.json(Json.stringify(map))` and `Response.jsonValue(map)` using `ThreadMXBean` allocated bytes per current thread after warmup. Results depend on JDK, payload and JIT. It is **not** a JMH benchmark, is **not** requests/second, and says nothing on its own about live HTTP throughput or memory RSS. A sample measurement made on Linux Java 21 with a fixed JSON object: old **1,248 B/call**, new **710.5 B/call**. Your Windows numbers may differ.

## End-to-end benchmark — same machine and configuration

```powershell
.\benchmarks\run.ps1 -SkipBuild -SkipPreflight -Concurrency 64 -Repeats 5 -Duration 30s -Warmup 10s
```

The runner requires `hey.exe`, Python 3.10+, the packaged FastFrame and Micronaut apps, and JDK 21. It samples throughput, latency, RSS, CPU, and error counts. It alternates server launch order each repeat. The Micronaut benchmark application is a separate Maven build with its own pinned version; do not infer app versions from the benchmark filename.

Compare separate measurements from v0.20 and v0.21, produced on the same PC (ideally with background load reduced):

```powershell
python benchmarks/compare_versions.py `
  --baseline 'C:\benchmarks\v020\results.csv' `
  --candidate 'C:\benchmarks\v021\results.csv' `
  --framework fastframe
```

The comparison prints medians from **observed** rows, not estimated RPS; it rejects non-2xx responses. Preserve `environment.json` from both runs and compare JDK, CPU, heap, concurrency, warmup, security configuration and cases before interpreting a change. The `hey` process can be the bottleneck when generating load on the same host as Java.

## JFR profiling on Windows

After `benchmarks/build.ps1` and installing `hey.exe`:

```powershell
.\benchmarks\profile.ps1 -Framework fastframe -Case json -Concurrency 64 -Duration 30s -Warmup 10s
.\benchmarks\profile.ps1 -Framework micronaut -Case json -Concurrency 64 -Duration 30s -Warmup 10s
```

The script starts one benchmark server on `localhost:18080`, enables JFR using JDK 21, warms up, generates load, dumps the recording to `benchmarks/jfr/`, and prints a JFR summary. The profiler itself adds overhead; don't compare profiled RPS to nonprofiled RPS. Use JDK Mission Control or `jfr print` to inspect allocation events, method samples, GC pauses, and thread activity. Do not commit `.jfr` dumps (they can contain sensitive runtime information).

## Security and correctness notes

* Java's existing JSON serializer behavior remains supported. Direct byte serialization does not authorize arbitrary POJO reflection or HTML-safe JSON inlining without proper context escaping.
* The Netty HTTP pipeline still allocates `Request`, `Pending`, headers and a request ID per request. No claim of "zero allocation" or "zero copy" is made.
* This is experimental code. Full Maven/Netty/JUnit integration and Windows PowerShell behavior require verification in CI and on a Windows host.
* If response bytes, HTTP headers or status change under load, treat it as a correctness regression, not a performance win.
