package dev.fastframe.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal dependency-free Artisan-style CLI for a FastFrame Maven application. */
public final class FastFrameCli {
    private static final Pattern CLASS_NAME = Pattern.compile("[A-Z][A-Za-z0-9_]*");
    private static final Pattern PACKAGE_SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private static final Pattern MIGRATION_NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern MIGRATION_FILE = Pattern.compile("V([0-9]+)__[a-z][a-z0-9_]*\\.sql");
    private static final Set<String> RESERVED = Set.of(
            "abstract","assert","boolean","break","byte","case","catch","char","class","const",
            "continue","default","do","double","else","enum","extends","final","finally","float",
            "for","goto","if","implements","import","instanceof","int","interface","long","native",
            "new","package","private","protected","public","return","short","static","strictfp",
            "super","switch","synchronized","this","throw","throws","transient","try","void",
            "volatile","while","true","false","null","record","sealed","permits","non-sealed",
            "var","yield","module","open","opens","requires","transitive","exports","to","uses",
            "provides","with");

    private FastFrameCli() {}

    public static void main(String[] args) {
        int result = run(args, System.out, System.err);
        if (result != 0) System.exit(result);
    }

    /** Entry point that can also be exercised by JUnit and the javac smoke suite. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args);
        try {
            return execute(args, out);
        } catch (IllegalArgumentException | IOException ex) {
            err.println("FastFrame CLI: " + ex.getMessage());
            return 1;
        }
    }

    private static int execute(String[] args, PrintStream out) throws IOException {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            help(out); return 0;
        }
        String command = args[0];
        String name = null;
        String packageName = null;
        Path root = Path.of(".").toAbsolutePath().normalize();
        Path classes = null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--root") || arg.equals("--package") || arg.equals("--classes")) {
                if (++i >= args.length) throw new IllegalArgumentException("Missing value for " + arg);
                switch (arg) {
                    case "--root" -> root = Path.of(args[i]).toAbsolutePath().normalize();
                    case "--package" -> packageName = args[i];
                    case "--classes" -> classes = Path.of(args[i]);
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            } else if (arg.equals("--force")) {
                force = true;
            } else if (arg.startsWith("--")) {
                throw new IllegalArgumentException("Unknown option: " + arg);
            } else if (name == null) {
                name = arg;
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + arg);
            }
        }
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Project root does not exist: " + root);
        if (Files.isSymbolicLink(root)) throw new IllegalArgumentException("Project root must not be a symlink");
        if (force) throw new IllegalArgumentException("--force is not supported: CLI never overwrites existing files");
        switch (command) {
            case "make:controller" -> {
                requireName(name, command);
                validateClassName(name);
                if (!name.endsWith("Controller")) name += "Controller";
                if (packageName == null) packageName = "dev.fastframe.demo.controllers";
                validatePackage(packageName);
                String resource = name.substring(0, name.length()-"Controller".length());
                String route = "/api/" + pluralSnake(resource);
                Path target = javaPath(root, packageName, name);
                create(target, root, controller(packageName, name, route));
                out.println("Created controller: " + root.relativize(target));
            }
            case "make:service", "make:request", "make:middleware", "make:job", "make:provider", "make:listener" -> {
                requireName(name, command);
                validateClassName(name);
                String type = command.substring("make:".length());
                String suffix = switch (type) {
                    case "service" -> "Service";
                    case "request" -> "Request";
                    case "middleware" -> "Middleware";
                    case "job" -> "Job";
                    case "provider" -> "Provider";
                    case "listener" -> "Listener";
                    default -> throw new IllegalStateException();
                };
                if (!name.endsWith(suffix)) name += suffix;
                if (packageName == null) packageName = "dev.fastframe.demo." + switch (type) {
                    case "service" -> "services";
                    case "request" -> "dto";
                    case "middleware" -> "middleware";
                    case "job" -> "jobs";
                    case "provider" -> "providers";
                    case "listener" -> "listeners";
                    default -> throw new IllegalStateException();
                };
                validatePackage(packageName);
                if (classes != null) throw new IllegalArgumentException("--classes is only supported for route:list");
                String source = switch (type) {
                    case "service" -> service(packageName, name);
                    case "request" -> request(packageName, name);
                    case "middleware" -> middleware(packageName, name);
                    case "job" -> job(packageName, name);
                    case "provider" -> provider(packageName, name);
                    case "listener" -> listener(packageName, name);
                    default -> throw new IllegalStateException();
                };
                Path target = javaPath(root, packageName, name);
                create(target, root, source);
                out.println("Created " + type + ": " + root.relativize(target));
            }
            case "make:model" -> {
                requireName(name, command);
                validateClassName(name);
                if (packageName == null) packageName = "dev.fastframe.demo.models";
                validatePackage(packageName);
                Path target = javaPath(root, packageName, name);
                create(target, root, model(packageName, name, pluralSnake(name)));
                out.println("Created model: " + root.relativize(target));
            }
            case "make:migration" -> {
                requireName(name, command);
                if (!MIGRATION_NAME.matcher(name).matches())
                    throw new IllegalArgumentException("Migration names must be lowercase snake_case (a-z, 0-9, _) ");
                if (packageName != null || classes != null) throw new IllegalArgumentException("Unexpected option for make:migration");
                Path directory = root.resolve("demo-app/migrations");
                checkSafeDirectory(root, directory);
                long next = 1;
                if (Files.isDirectory(directory)) {
                    try (var files = Files.list(directory)) {
                        for (Path p : files.toList()) {
                            Matcher m = MIGRATION_FILE.matcher(p.getFileName().toString());
                            if (m.matches()) next = Math.max(next, Long.parseLong(m.group(1)) + 1);
                        }
                    }
                }
                if (next > 999_999) throw new IllegalArgumentException("Too many migrations");
                String file = String.format(Locale.ROOT, "V%03d__%s.sql", next, name);
                Path target = directory.resolve(file);
                create(target, root, migration(name));
                out.println("Created migration: " + root.relativize(target));
            }
            case "route:list" -> {
                if (name != null || packageName != null) throw new IllegalArgumentException("route:list accepts only --classes/--root");
                if (classes == null) classes = root.resolve("demo-app/target/classes");
                if (!classes.isAbsolute()) classes = root.resolve(classes).normalize();
                Path manifest = classes.resolve("META-INF/fastframe/routes.tsv");
                if (!Files.isRegularFile(manifest)) {
                    throw new IllegalArgumentException("Compiled route manifest not found: " + manifest
                            + ". Run: mvn -pl demo-app -am compile");
                }
                out.printf("%-8s %-36s %s%n", "METHOD", "PATH", "CONTROLLER#METHOD");
                for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    String[] fields = line.split("\\t", -1);
                    if (fields.length != 3) throw new IllegalArgumentException("Invalid route manifest: " + manifest);
                    out.printf("%-8s %-36s %s%n", fields[0], fields[1], fields[2]);
                }
                out.println("Note: only @Controller routes compiled in this module are listed; manual Router routes are not included.");
            }
            default -> throw new IllegalArgumentException("Unknown command: " + command + ". Run 'help'.");
        }
        return 0;
    }

    private static void help(PrintStream out) {
        out.println("FastFrame CLI v0.17 (Java 21)");
        out.println("  make:controller User [--package dev.example.controllers] [--root PATH]");
        out.println("  make:model Post [--package dev.example.models] [--root PATH]");
        out.println("  make:service User [--package dev.example.services] [--root PATH]");
        out.println("  make:request CreateUser [--package dev.example.dto] [--root PATH]");
        out.println("  make:middleware Audit [--package dev.example.middleware] [--root PATH]");
        out.println("  make:job SendEmail [--package dev.example.jobs] [--root PATH]");
        out.println("  make:provider Cache [--package dev.example.providers] [--root PATH]");
        out.println("  make:listener UserCreated [--package dev.example.listeners] [--root PATH]");
        out.println("  make:migration create_posts [--root PATH]");
        out.println("  route:list [--classes demo-app/target/classes] [--root PATH]");
        out.println("Files are created only when absent; generators never overwrite existing code.");
    }

    private static void requireName(String name, String command) {
        if (name == null) throw new IllegalArgumentException(command + " requires a name");
    }

    private static void validateClassName(String name) {
        if (!CLASS_NAME.matcher(name).matches() || RESERVED.contains(name))
            throw new IllegalArgumentException("Invalid Java class name: " + name + " (use PascalCase)");
    }

    private static void validatePackage(String pkg) {
        for (String s : pkg.split("\\.", -1)) {
            if (!PACKAGE_SEGMENT.matcher(s).matches() || RESERVED.contains(s))
                throw new IllegalArgumentException("Invalid Java package: " + pkg);
        }
    }

    private static Path javaPath(Path root, String pkg, String name) {
        return root.resolve("demo-app/src/main/java")
                .resolve(pkg.replace('.', '/')).resolve(name + ".java");
    }

    /** Require no symbolic links in the project subtree and create files atomically without overwriting. */
    private static void checkSafeDirectory(Path root, Path directory) throws IOException {
        if (!directory.normalize().startsWith(root)) throw new IllegalArgumentException("Path escapes project root");
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symlink in output path: " + current);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("Not a directory: " + current);
        }
    }

    private static void create(Path file, Path root, String content) throws IOException {
        checkSafeDirectory(root, file.getParent());
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ex) {
            throw new IllegalArgumentException("Refusing to overwrite: " + root.relativize(file));
        }
    }

    private static String pluralSnake(String className) {
        String snake = className.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        if (snake.endsWith("y") && snake.length() > 1 && !"aeiou".contains(snake.substring(snake.length()-2,snake.length()-1)))
            return snake.substring(0,snake.length()-1) + "ies";
        return snake.endsWith("s") ? snake : snake + "s";
    }

    private static String controller(String pkg, String name, String route) {
        return """
            package %s;

            import dev.fastframe.core.annotation.Controller;
            import dev.fastframe.core.annotation.Get;
            import dev.fastframe.core.annotation.PathParam;
            import dev.fastframe.core.http.Response;
            import java.util.Map;

            @Controller("%s")
            public final class %s {
                @Get("/{id}")
                public Response show(@PathParam("id") long id) {
                    // Replace this sample response with a service call.
                    return Response.jsonValue(Map.of("id", id));
                }
            }
            """.formatted(pkg, route, name);
    }

    private static String model(String pkg, String name, String table) {
        return """
            package %s;

            import dev.fastframe.orm.annotation.Table;
            import dev.fastframe.orm.annotation.Id;

            @Table("%s")
            public record %s(@Id Long id, String name) {}
            """.formatted(pkg, table, name);
    }

    private static String service(String pkg, String name) {
        return """
            package %s;

            import dev.fastframe.core.annotation.Singleton;

            @Singleton
            public final class %s {
                public String execute() {
                    return "ready";
                }
            }
            """.formatted(pkg, name);
    }

    private static String request(String pkg, String name) {
        return """
            package %s;

            import dev.fastframe.core.annotation.Required;
            import dev.fastframe.core.annotation.Size;

            /** JSON DTO: bind in a controller using @Body. */
            public record %s(@Required @Size(min = 1, max = 255) String name) { }
            """.formatted(pkg, name);
    }

    private static String middleware(String pkg, String name) {
        return """
            package %s;

            import dev.fastframe.core.http.Middleware;
            import dev.fastframe.core.http.Request;
            import dev.fastframe.core.http.Response;
            import java.util.concurrent.CompletionStage;

            /** Register via router.use(new %s()) or a route group. */
            public final class %s implements Middleware {
                @Override
                public CompletionStage<Response> handle(Request request, Next next) throws Exception {
                    return next.proceed(request);
                }
            }
            """.formatted(pkg, name, name);
    }

    private static String job(String pkg, String name) {
        return """
            package %s;

            import dev.fastframe.queue.JobHandler;

            /** Register on a JobWorker with a stable job type, then enqueue through Jobs. */
            public final class %s implements JobHandler {
                @Override
                public void handle(String payload) throws Exception {
                    // Implement an idempotent background task (jobs can be delivered more than once).
                }
            }
            """.formatted(pkg, name);
    }

    private static String provider(String pkg, String name) {
        return """
            package %s;

            import dev.fastframe.application.AppContext;
            import dev.fastframe.application.ServiceProvider;

            /** Register in FastFrameApp.boot(config, new %s()). */
            public final class %s implements ServiceProvider {
                @Override public void register(AppContext context) {
                    // Bind services here: context.bind(MyService.class, new MyService());
                }
                @Override public void boot(AppContext context) {
                    // Register listeners and routes after all providers have registered.
                }
            }
            """.formatted(pkg, name, name);
    }

    private static String listener(String pkg, String name) {
        return """
            package %s;

            import java.util.function.Consumer;

            /** Subscribe with context.events().on(%s.Event.class, new %s()); */
            public final class %s implements Consumer<%s.Event> {
                public record Event(String payload) { }
                @Override public void accept(Event event) {
                    // Handle event; avoid blocking a Netty event loop.
                }
            }
            """.formatted(pkg, name, name, name, name);
    }

    private static String migration(String name) {
        if (name.startsWith("create_") && name.length() > 7) {
            String table = name.substring(7);
            return "-- FastFrame SQL migration: " + name + "\n" +
                    "CREATE TABLE \"" + table + "\" (\n" +
                    "    \"id\" BIGINT PRIMARY KEY,\n" +
                    "    \"name\" VARCHAR(255) NOT NULL\n" +
                    ");\n";
        }
        // The user must write the actual SQL. This is intentionally not a no-op migration.
        return "-- FastFrame SQL migration: " + name + "\n" +
                "-- TODO: Write SQL before applying this migration.\n";
    }
}
