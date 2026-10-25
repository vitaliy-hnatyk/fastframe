package dev.fastframe.testing;

import org.junit.jupiter.api.Test;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

final class MockHttpServerTest {
    @Test void mockedOutboundHttpAndCalls() throws Exception {
        try (MockHttpServer mock = MockHttpServer.start(); HttpClient client = HttpClient.newHttpClient()) {
            mock.when("GET", "/users", 200, "application/json", "{\"users\":[]}");
            var response = client.send(HttpRequest.newBuilder(mock.uri("/users")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("{\"users\":[]}", response.body());
            assertEquals(1, mock.calls("GET", "/users"));
            assertEquals(404, client.send(HttpRequest.newBuilder(mock.uri("/none")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
}
