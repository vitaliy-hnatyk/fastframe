<?php
/**
 * Laravel 13 benchmark workload. No DB, session, auth or view rendering.
 * Routes are loaded directly by bootstrap/app.php without the web middleware stack.
 */

use Illuminate\Http\Request;
use Illuminate\Support\Facades\Route;

Route::get('/bench/plain', static fn () => response('ok', 200)
    ->header('Content-Type', 'text/plain; charset=utf-8'));

Route::get('/bench/json', static fn () => response()->json([
    'message' => 'ok',
    'value' => 42,
]));

Route::get('/bench/route/{id}', static fn (string $id) => response($id, 200)
    ->header('Content-Type', 'text/plain; charset=utf-8'));

Route::post('/bench/echo', static function (Request $request) {
    // Exercise Laravel's JSON body parsing; do not simply return hardcoded JSON.
    return response()->json(['message' => $request->input('message')]);
});

// Fixed-size JSON responses (exact UTF-8 lengths: 1 KiB, 10 KiB, 100 KiB, 1 MiB).
// Octane workers retain the fixture strings; response()->json serializes on each request.
Route::get('/bench/json-size/{size}', static function (string $size) {
    static $documents = null;
    if ($documents === null) {
        $documents = [];
        foreach (['1kb' => 1024, '10kb' => 10240, '100kb' => 102400, '1mb' => 1048576] as $key => $bytes) {
            $documents[$key] = str_repeat('x', $bytes - 11);
        }
    }
    if (!array_key_exists($size, $documents)) {
        return response()->json(['message' => 'Not Found'], 404);
    }
    return response()->json(['data' => $documents[$size]]);
});

// PostgreSQL and Redis benchmarks. Raw SQL is parameterized to match the Java fixtures.
// These routes are restricted to 127.0.0.1 by the benchmark server launcher.
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Redis;

Route::get('/bench/db/select', static function () {
    $rows = DB::select('SELECT payload FROM bench_lookup WHERE id=?', [1]);
    if (count($rows) !== 1) {
        abort(500, 'benchmark lookup missing');
    }
    return response()->json(['value' => $rows[0]->payload]);
});
Route::post('/bench/db/insert', static function () {
    DB::insert('INSERT INTO bench_events (payload) VALUES (?)', ['ok']);
    return response()->json(['ok' => true]);
});
Route::post('/bench/db/update', static function () {
    DB::update('UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?', [1]);
    return response()->json(['ok' => true]);
});
Route::post('/bench/db/transaction', static function () {
    DB::transaction(static function () {
        DB::update('UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?', [1]);
        DB::update('UPDATE bench_counter SET counter_value=counter_value+1 WHERE id=?', [2]);
    });
    return response()->json(['ok' => true]);
});
Route::get('/bench/cache/get', static function () {
    $value = Redis::get('ffbench:hot');
    if ($value === null) {
        abort(500, 'benchmark cache key missing');
    }
    return response()->json(['value' => $value]);
});
Route::post('/bench/cache/set', static function () {
    Redis::set('ffbench:set', 'ok');
    return response()->json(['ok' => true]);
});

// Async/HTTP I/O benchmark: REAL outbound HTTP call to a delayed local upstream.
// Octane is configured with multiple workers; PHP's HTTP client call is synchronous
// within each worker, in contrast to Java's CompletionStage implementation.
Route::get('/bench/async/{delay}', static function (string $delay) {
    if (!in_array($delay, ['10', '50', '200'], true)) {
        return response()->json(['error' => 'not found'], 404);
    }
    $upstream = rtrim(getenv('BENCH_UPSTREAM_URL') ?: 'http://127.0.0.1:19090', '/');
    $response = \Illuminate\Support\Facades\Http::timeout(5)->get($upstream . '/wait/' . $delay);
    $expected = ['value' => 'ok', 'delay_ms' => (int) $delay];
    if (!$response->successful() || $response->json() !== $expected) {
        abort(502, 'unexpected upstream response');
    }
    return response()->json($expected);
});

// HS256 signature validation *on every request*, plus strict issuer/audience,
// subject and timestamp checks. Static fixture credentials never grant real access.
Route::get('/bench/jwt', static function (Request $request) {
    $authorization = $request->header('Authorization', '');
    $valid = false;
    if (is_string($authorization) && strlen($authorization) <= 4096
        && str_starts_with($authorization, 'Bearer ')) {
        $parts = explode('.', substr($authorization, 7));
        if (count($parts) === 3) {
            $decode = static function (string $input): string|false {
                if ($input === '' || !preg_match('/^[A-Za-z0-9_-]+$/D', $input)) return false;
                $decoded = base64_decode(strtr($input, '-_', '+/'), true);
                return $decoded !== false && rtrim(strtr(base64_encode($decoded), '+/', '-_'), '=') === $input
                    ? $decoded : false;
            };
            [$h, $p, $s] = $parts;
            $header = $decode($h);
            $payload = $decode($p);
            $signature = $decode($s);
            $secret = getenv('BENCH_JWT_SECRET') ?: 'no-production-secret-benchmark-only';
            if ($header !== false && $payload !== false && $signature !== false
                && strlen($signature) === 32
                && hash_equals(hash_hmac('sha256', $h . '.' . $p, $secret, true), $signature)) {
                $head = json_decode($header, true);
                $claim = json_decode($payload, true);
                $now = time();
                $valid = is_array($head) && count($head) === 2
                    && ($head['alg'] ?? null) === 'HS256' && ($head['typ'] ?? null) === 'JWT'
                    && is_array($claim) && count($claim) === 8
                    && ($claim['iss'] ?? null) === 'ff-bench'
                    && ($claim['aud'] ?? null) === 'ff-bench'
                    && ($claim['sub'] ?? null) === 'bench-user'
                    && ($claim['roles'] ?? null) === ['reader']
                    && ($claim['permissions'] ?? null) === ['bench:read']
                    && is_int($claim['iat'] ?? null) && is_int($claim['nbf'] ?? null)
                    && is_int($claim['exp'] ?? null)
                    && $claim['iat'] <= $now + 30 && $claim['nbf'] <= $now + 30
                    && $claim['exp'] > $now && $claim['exp'] > $claim['iat']
                    && $claim['exp'] - $claim['iat'] <= 3600
                    && $claim['nbf'] >= $claim['iat'] - 30;
            }
        }
    }
    return $valid ? response()->json(['subject' => 'bench-user', 'authorized' => true])
        : response()->json(['error' => 'unauthorized'], 401);
});
