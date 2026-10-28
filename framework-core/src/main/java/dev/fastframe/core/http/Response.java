package dev.fastframe.core.http;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable HTTP response. json(String) expects already serialized JSON. */
public final class Response {
    private final int status;
    private final String contentType;
    private final byte[] body;
    private final Map<String, String> headers;
    private final SseSource sse;
    private final WebSocketHandler ws;

    private Response(int status, String contentType, byte[] body, Map<String, String> headers) {
        if (status < 100 || status > 599) throw new IllegalArgumentException("Invalid HTTP status");
        this.status = status;
        this.contentType = contentType;
        this.body = body;
        this.headers = headers;
        this.sse = null;
        this.ws = null;
    }

    private Response(int status,String contentType,byte[] body,Map<String,String> headers,SseSource sse,WebSocketHandler ws){
        if(status<100||status>599)throw new IllegalArgumentException("Invalid HTTP status");
        this.status=status; this.contentType=contentType; this.body=body; this.headers=headers; this.sse=sse; this.ws=ws;
    }
    public static Response sse(SseSource source){return new Response(200,"text/event-stream; charset=utf-8",new byte[0],Map.of(),Objects.requireNonNull(source),null);}
    public static Response websocket(WebSocketHandler handler){return new Response(101,"application/octet-stream",new byte[0],Map.of(),null,Objects.requireNonNull(handler));}
    public SseSource sseSource(){return sse;}
    public WebSocketHandler webSocketHandler(){return ws;}


    public static Response text(String value) {
        return new Response(200, "text/plain; charset=utf-8",
                Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** Binary response. Defensive copy prevents subsequent mutation. */
    public static Response bytes(byte[] data, String contentType) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(contentType, "contentType");
        if (!contentType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))
            throw new IllegalArgumentException("Invalid media type");
        return new Response(200, contentType, data.clone(), Map.of());
    }

    public static Response json(String alreadySerializedJson) {
        return new Response(200, "application/json; charset=utf-8",
                Objects.requireNonNull(alreadySerializedJson).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** Serialize supported Java values safely. The original json(String) accepts raw JSON. */
    public static Response jsonValue(Object value) {
        return new Response(200, "application/json; charset=utf-8", Json.utf8(value), Map.of());
    }

    public Response status(int code) { return new Response(code, contentType, body, headers, sse, ws); }

    public Response header(String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid header name");
        }
        Objects.requireNonNull(value, "value");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                throw new IllegalArgumentException("Invalid header value");
            }
        }
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new Response(status, contentType, body, Map.copyOf(copy), sse, ws);
    }

    public int status() { return status; }
    public String contentType() { return contentType; }
    public byte[] body() { return body; }
    public Map<String, String> headers() { return headers; }
}
