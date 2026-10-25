package dev.fastframe.demo.routes;

import dev.fastframe.core.http.Request;
import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.storage.Storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Explicitly opt-in development demo. Never expose without HTTPS + real auth. */
public final class StorageRoutes {
    private StorageRoutes() {}

    public static void register(Router router, Storage storage, Executor ioPool, String token) {
        if (token == null || token.length() < 24) throw new IllegalArgumentException("Demo token must be >= 24 characters");
        router.putAsync("/api/storage/files/{key}", req -> execute(req, ioPool, token, () -> {
            if (!"application/octet-stream".equalsIgnoreCase(mediaType(req.header("content-type"))))
                return Response.jsonValue(Map.of("error", "Use application/octet-stream")).status(415);
            byte[] bytes = req.bodyBytes();
            storage.put(req.routeParam("key"), bytes);
            return Response.jsonValue(Map.of("stored", true, "bytes", bytes.length)).status(201);
        }));
        router.getAsync("/api/storage/files/{key}", req -> execute(req, ioPool, token, () ->
                storage.get(req.routeParam("key"))
                        .map(b -> Response.bytes(b, "application/octet-stream"))
                        .orElseGet(() -> Response.jsonValue(Map.of("error", "Not found")).status(404))));
        router.deleteAsync("/api/storage/files/{key}", req -> execute(req, ioPool, token, () ->
                Response.jsonValue(Map.of("deleted", storage.delete(req.routeParam("key"))))));
    }

    private static String mediaType(String header) {
        return header == null ? "" : header.split(";", 2)[0].trim();
    }

    private interface IoAction { Response run() throws IOException; }
    private static CompletableFuture<Response> execute(Request req, Executor pool, String expected, IoAction action) {
        String received = req.header("x-storage-demo-token");
        if (received == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8))) {
            return CompletableFuture.completedFuture(Response.jsonValue(Map.of("error", "Unauthorized")).status(401));
        }
        return CompletableFuture.supplyAsync(() -> {
            try { return action.run(); }
            catch (IllegalArgumentException invalid) {
                return Response.jsonValue(Map.of("error", "Invalid file key")).status(400);
            } catch (IOException io) {
                return Response.jsonValue(Map.of("error", "Storage operation failed")).status(500);
            }
        }, pool);
    }
}
