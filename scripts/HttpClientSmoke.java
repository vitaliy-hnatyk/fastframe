import com.sun.net.httpserver.HttpServer;
import dev.fastframe.httpclient.AsyncHttpClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class HttpClientSmoke {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> {
            byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/echo", exchange -> {
            byte[] data = exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(201, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/large", exchange -> {
            byte[] data = new byte[4096];
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/hello");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try (var client = new AsyncHttpClient(Duration.ofSeconds(2), Duration.ofSeconds(3), 128)) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            if (!client.get(URI.create(base + "/hello")).get(5, TimeUnit.SECONDS).text().equals("hello")) throw new AssertionError("GET failed");
            var post = client.postJson(URI.create(base + "/echo"), "{\"ok\":true}").get(5, TimeUnit.SECONDS);
            if (post.status() != 201 || !post.text().equals("{\"ok\":true}")) throw new AssertionError("POST failed");
            if (client.get(URI.create(base + "/redirect")).get(5, TimeUnit.SECONDS).status() != 302) throw new AssertionError("Redirect followed");
            try { client.get(URI.create(base + "/large")).get(5, TimeUnit.SECONDS); throw new AssertionError("Accepted oversize response"); }
            catch (ExecutionException expected) { }
            System.out.println("FastFrame v0.14 HTTP Client local server smoke PASS");
        } finally { server.stop(0); }
    }
}
