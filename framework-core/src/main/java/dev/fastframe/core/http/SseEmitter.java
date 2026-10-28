package dev.fastframe.core.http;
public interface SseEmitter { boolean send(String event,String data); void onClose(Runnable cleanup); void close(); }
