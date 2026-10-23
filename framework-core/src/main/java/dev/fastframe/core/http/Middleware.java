package dev.fastframe.core.http;

import java.util.concurrent.CompletionStage;

/** Laravel-inspired middleware; return a response to short-circuit the request. */
@FunctionalInterface
public interface Middleware {
    CompletionStage<Response> handle(Request request, Next next) throws Exception;

    @FunctionalInterface
    interface Next {
        CompletionStage<Response> proceed(Request request) throws Exception;
    }
}
