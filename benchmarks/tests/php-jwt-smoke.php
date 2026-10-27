<?php
// No Composer dependencies: test the exact Laravel benchmark closure via facade stubs.
namespace Illuminate\Http {
    class Request {
        public function __construct(private string $bearer) {}
        public function header($name, $fallback = '') { return $this->bearer; }
    }
}
namespace Illuminate\Support\Facades {
    class Route {
        public static array $routes = [];
        public static function get($path, $handler) { self::$routes[$path] = $handler; }
        public static function post($path, $handler) { self::$routes[$path] = $handler; }
    }
}
namespace {
    class FakeReply {
        public function __construct(public mixed $body, public int $status) {}
    }
    class FakeResponse {
        public function json($data, $status = 200) { return new FakeReply($data, $status); }
    }
    function response(...$args) { return new FakeResponse(); }
    require __DIR__ . '/../laravel-app/routes/bench.php';
    $path = '/bench/jwt';
    $handler = \Illuminate\Support\Facades\Route::$routes[$path];
    if (!$handler) { fwrite(STDERR, "Missing route\n"); exit(2); }
    $request = new \Illuminate\Http\Request($argv[1] ?? '');
    $reply = $handler($request);
    echo json_encode(['status' => $reply->status, 'body' => $reply->body], JSON_THROW_ON_ERROR) . PHP_EOL;
}
