package dev.fastframe.testing;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.core.http.HttpException;
import dev.fastframe.core.http.ValidationException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

final class FeatureClientTest {
    @Test void realHttpMiddlewareQueriesRoutesAndJson() {
        Router router = new Router();
        router.use((request, next) -> next.proceed(request).thenApply(r -> r.header("X-Feature", "yes")));
        router.get("/hello", req -> Response.jsonValue(Map.of("users", List.of(Map.of("id", 8, "name", "Ada")))));
        router.get("/user/{id}", req -> Response.jsonValue(Map.of("id", Long.parseLong(req.routeParam("id")),
                "sort", req.query("sort"))));
        router.post("/echo", req -> Response.json(req.body()).status(201));
        router.getAsync("/async", req -> CompletableFuture.completedFuture(Response.text("async ok")));
        try (TestClient app = TestClient.start(router)) {
            app.get("/hello").assertStatus(200).assertHeader("X-Feature", "yes")
                    .assertJson("{\"users\":[{\"name\":\"Ada\",\"id\":8}]}")
                    .assertJsonPath("$.users[0].id", 8);
            app.get("/user/42?sort=name").assertJsonPath("$.id", 42).assertJsonPath("$.sort", "name");
            app.postJson("/echo", Map.of("id", 7, "ok", true)).assertStatus(201)
                    .assertJsonPath("$.ok", true).assertJsonPath("$.id", 7);
            app.get("/async").assertStatus(200).assertBody("async ok");
            app.delete("/hello").assertStatus(405);
            app.get("/not-found").assertStatus(404);
        }
    }

    @Test void serverMapsExpectedExceptions() {
        Router router = new Router();
        router.get("/bad", req -> { throw new HttpException(400, "Invalid query"); });
        router.get("/invalid", req -> { throw new ValidationException(Map.of("email", List.of("is required"))); });
        router.get("/private", req -> { throw new IllegalStateException("secret-db-password"); });
        try (TestClient app = TestClient.start(router)) {
            app.get("/bad").assertStatus(400).assertJsonPath("$.error.code", "http_error");
            app.get("/invalid").assertStatus(422).assertJsonPath("$.error.fields.email[0]", "is required");
            TestResponse privateError = app.get("/private").assertStatus(500);
            assertFalse(privateError.body().contains("secret-db-password"));
        }
    }

    @Test void assertionsDetectWrongResponses() {
        Router router = new Router();
        router.get("/json", req -> Response.jsonValue(Map.of("values", List.of(1, 2))));
        try (TestClient app = TestClient.start(router)) {
            TestResponse response = app.get("/json");
            assertThrows(AssertionError.class, () -> response.assertStatus(404));
            assertThrows(AssertionError.class, () -> response.assertJsonPath("$.values[1]", 3));
            assertThrows(AssertionError.class, () -> response.assertJsonPath("$.nope", 1));
            assertThrows(AssertionError.class, () -> response.assertJson("{\"values\":[3]}"));
        }
    }

    @Test void rejectsExternalUrls() {
        Router router = new Router();
        try (TestClient app = TestClient.start(router)) {
            assertThrows(IllegalArgumentException.class, () -> app.get("//example.com/data"));
            assertThrows(IllegalArgumentException.class, () -> app.get("http://example.com/data"));
        }
    }
}
