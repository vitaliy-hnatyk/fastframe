package dev.fastframe.core.http;

@FunctionalInterface
public interface Handler {
    // MVP is synchronous: never perform blocking I/O in Netty event-loop handlers.
    Response handle(Request request) throws Exception;
}
