<?php
/**
 * Minimal Laravel 13 application bootstrap used only for benchmark comparison.
 * Avoid routing all benchmark endpoints through the web middleware group
 * (sessions, cookies and CSRF) while keeping real Laravel routing and HTTP responses.
 */

use Illuminate\Foundation\Application;
use Illuminate\Foundation\Configuration\Exceptions;
use Illuminate\Foundation\Configuration\Middleware;
use Illuminate\Support\Facades\Route;

return Application::configure(basePath: dirname(__DIR__))
    ->withRouting(
        using: static function (): void {
            Route::group([], base_path('routes/bench.php'));
        },
    )
    ->withMiddleware(static function (Middleware $middleware): void {
        // No additional benchmark-only middleware.
    })
    ->withExceptions(static function (Exceptions $exceptions): void {
    })
    ->create();
