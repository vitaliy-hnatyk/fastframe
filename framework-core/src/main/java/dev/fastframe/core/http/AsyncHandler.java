package dev.fastframe.core.http;

import java.util.concurrent.CompletionStage;

/** Async controller action. Its result may complete on any thread. */
@FunctionalInterface
public interface AsyncHandler {
    CompletionStage<Response> handle(Request request) throws Exception;
}
