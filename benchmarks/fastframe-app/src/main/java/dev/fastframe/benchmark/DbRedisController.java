package dev.fastframe.benchmark;

import dev.fastframe.core.annotation.Controller;
import dev.fastframe.core.annotation.Get;
import dev.fastframe.core.annotation.Post;
import dev.fastframe.core.http.Response;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

@Controller("/bench")
public final class DbRedisController {
    private CompletionStage<Response> run(Supplier<?> work) {
        return DbRedisWorkload.get().submit(() -> Response.jsonValue(work.get()));
    }
    @Get("/db/select") public CompletionStage<Response> select() { return run(() -> DbRedisWorkload.get().select()); }
    @Post("/db/insert") public CompletionStage<Response> insert() { return run(() -> DbRedisWorkload.get().insert()); }
    @Post("/db/update") public CompletionStage<Response> update() { return run(() -> DbRedisWorkload.get().update()); }
    @Post("/db/transaction") public CompletionStage<Response> transaction() { return run(() -> DbRedisWorkload.get().transaction()); }
    @Get("/cache/get") public CompletionStage<Response> cacheGet() { return run(() -> DbRedisWorkload.get().cacheGet()); }
    @Post("/cache/set") public CompletionStage<Response> cacheSet() { return run(() -> DbRedisWorkload.get().cacheSet()); }
}
