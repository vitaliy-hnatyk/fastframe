package dev.fastframe.demo;

import dev.fastframe.application.AppContext;
import dev.fastframe.application.ServiceProvider;
import dev.fastframe.auth.BearerAuthMiddleware;
import dev.fastframe.auth.JwtHmac256;
import dev.fastframe.cache.Cache;
import dev.fastframe.cache.InMemoryCacheStore;
import dev.fastframe.database.Database;
import dev.fastframe.database.DriverManagerDataSource;
import dev.fastframe.demo.models.User;
import dev.fastframe.demo.models.UserFastFrameMapper;
import dev.fastframe.demo.routes.ApiRoutes;
import dev.fastframe.demo.routes.CacheRoutes;
import dev.fastframe.demo.routes.DatabaseRoutes;
import dev.fastframe.demo.routes.QueueRoutes;
import dev.fastframe.demo.routes.StorageRoutes;
import dev.fastframe.migrations.MigrationRunner;
import dev.fastframe.migrations.SqlMigrations;
import dev.fastframe.orm.Repository;
import dev.fastframe.queue.InMemoryQueueStore;
import dev.fastframe.queue.JobWorker;
import dev.fastframe.queue.Jobs;
import dev.fastframe.storage.LocalDiskStorage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Modular Laravel-style bootstrap for FastFrame's demo app. */
final class DemoProviders {
    private DemoProviders() { }

    static final class Infrastructure implements ServiceProvider {
        @Override public void register(AppContext app) {
            var pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(128), new ThreadPoolExecutor.AbortPolicy());
            app.onShutdown(pool::shutdown);
            app.bind(ThreadPoolExecutor.class, pool);
            app.bind(Cache.class, new Cache(new InMemoryCacheStore(10_000)));
            var store = new InMemoryQueueStore(2048);
            app.bind(InMemoryQueueStore.class, store);
            app.bind(Jobs.class, new Jobs(store));
        }
    }

    static final class HttpRoutes implements ServiceProvider {
        @Override public void register(AppContext app) { }
        @Override public void boot(AppContext app) {
            var config = app.config();
            var router = app.router();
            String secretB64 = config.get("FF_JWT_SECRET_B64");
            if (secretB64 != null && !secretB64.isBlank()) {
                JwtHmac256 jwt = new JwtHmac256(Base64.getDecoder().decode(secretB64),
                        "fastframe-demo", "fastframe-api");
                router.use(new BearerAuthMiddleware(jwt));
            } else {
                System.out.println("No FF_JWT_SECRET_B64 configured: protected routes fail closed (401).");
            }
            ApiRoutes.register(router, app.require(ThreadPoolExecutor.class));
        }
    }

    static final class LocalQueue implements ServiceProvider {
        @Override public void register(AppContext app) { }
        @Override public void boot(AppContext app) {
            var worker = new JobWorker(app.require(InMemoryQueueStore.class))
                    .handle("demo.greeting", payload -> System.out.println("Background job: " + payload));
            app.onShutdown(worker);
            worker.start(Duration.ofMillis(100));
            QueueRoutes.register(app.router(), app.require(Jobs.class));
        }
    }

    static final class CacheRoutesProvider implements ServiceProvider {
        @Override public void register(AppContext app) { }
        @Override public void boot(AppContext app) {
            CacheRoutes.register(app.router(), app.require(Cache.class));
        }
    }

    static final class DatabaseRoutesProvider implements ServiceProvider {
        @Override public void register(AppContext app) { }
        @Override public void boot(AppContext app) throws Exception {
            var config = app.config();
            String jdbcUrl = config.get("FF_DB_URL");
            if (jdbcUrl == null || jdbcUrl.isBlank()) return;
            Database db = new Database(new DriverManagerDataSource(jdbcUrl,
                    config.get("FF_DB_USER"), config.get("FF_DB_PASSWORD")));
            String migrateDefault = jdbcUrl.startsWith("jdbc:h2:mem:") ? "true" : "false";
            if (config.booleanValue("FF_RUN_MIGRATIONS", Boolean.parseBoolean(migrateDefault))) {
                Path dir = Path.of(config.get("FF_MIGRATIONS_DIR", "demo-app/migrations"));
                var result = new MigrationRunner(db).migrate(SqlMigrations.load(dir));
                System.out.printf("Migrations: %d applied, %d skipped%n", result.applied(), result.skipped());
            }
            if (jdbcUrl.startsWith("jdbc:h2:mem:")) {
                var users = new Repository<User, Long>(db, new UserFastFrameMapper());
                if (users.find(1L).isEmpty()) users.insert(new User(1L, "Ada"));
            }
            // JDBC blocks a worker, not a Netty event loop.
            DatabaseRoutes.register(app.router(), db, app.require(ThreadPoolExecutor.class));
        }
    }

    static final class StorageRoutesProvider implements ServiceProvider {
        @Override public void register(AppContext app) { }
        @Override public void boot(AppContext app) throws Exception {
            var config = app.config();
            if (!config.booleanValue("FF_ENABLE_STORAGE_DEMO", false)) return;
            String token = config.get("FF_STORAGE_DEMO_TOKEN");
            String root = config.get("FF_STORAGE_ROOT",
                    Path.of(System.getProperty("user.home"), ".fastframe-private-files").toString());
            StorageRoutes.register(app.router(), new LocalDiskStorage(Path.of(root), 64 * 1024),
                    app.require(ThreadPoolExecutor.class), token);
        }
    }
}
