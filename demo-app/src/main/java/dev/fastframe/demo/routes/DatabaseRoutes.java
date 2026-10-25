package dev.fastframe.demo.routes;

import dev.fastframe.core.http.Response;
import dev.fastframe.core.http.Router;
import dev.fastframe.database.Database;
import dev.fastframe.demo.models.UserFastFrameMapper;
import dev.fastframe.orm.Repository;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Optional blocking JDBC demo. Always offload from the Netty event loop. */
public final class DatabaseRoutes {
    private DatabaseRoutes() { }
    public static void register(Router router, Database db, Executor jdbcWorkers) {
        Repository<dev.fastframe.demo.models.User, Long> users = new Repository<>(db, new UserFastFrameMapper());
        router.getAsync("/api/db/users", request -> CompletableFuture.supplyAsync(
                () -> Response.jsonValue(users.get(20).stream()
                        .map(u -> Map.of("id", u.id(), "name", u.name())).toList()), jdbcWorkers));
    }
}
