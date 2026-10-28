package dev.fastframe.core.http;
@FunctionalInterface public interface SseSource { void subscribe(SseEmitter emitter); }
