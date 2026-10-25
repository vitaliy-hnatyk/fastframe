package dev.fastframe.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class FastFrameCliTest {
    @TempDir Path root;

    private int run(String... args) {
        try (var out = new PrintStream(new ByteArrayOutputStream());
             var err = new PrintStream(new ByteArrayOutputStream())) {
            String[] all = new String[args.length + 2];
            System.arraycopy(args, 0, all, 0, args.length);
            all[args.length] = "--root";
            all[args.length + 1] = root.toString();
            return FastFrameCli.run(all, out, err);
        }
    }

    @Test void controllerUsesSupportedAnnotationsAndDoesNotOverwrite() throws Exception {
        assertEquals(0, run("make:controller", "PostController"));
        Path file = root.resolve("demo-app/src/main/java/dev/fastframe/demo/controllers/PostController.java");
        String text = Files.readString(file);
        assertTrue(text.contains("@Controller(\"/api/posts\")"));
        assertTrue(text.contains("@Get(\"/{id}\")"));
        assertEquals(1, run("make:controller", "PostController"));
        assertEquals(text, Files.readString(file));
    }

    @Test void modelUsesOrmAnnotations() throws Exception {
        assertEquals(0, run("make:model", "Category"));
        String text = Files.readString(root.resolve("demo-app/src/main/java/dev/fastframe/demo/models/Category.java"));
        assertTrue(text.contains("@Table(\"categories\")"));
        assertTrue(text.contains("record Category(@Id Long id, String name)"));
    }

    @Test void allNewGeneratorsUseCurrentFastFrameApis() throws Exception {
        assertEquals(0, run("make:service", "Payment"));
        assertEquals(0, run("make:request", "CreatePayment"));
        assertEquals(0, run("make:middleware", "Audit"));
        assertEquals(0, run("make:job", "SendEmail"));
        assertTrue(Files.readString(root.resolve("demo-app/src/main/java/dev/fastframe/demo/services/PaymentService.java"))
                .contains("@Singleton"));
        assertTrue(Files.readString(root.resolve("demo-app/src/main/java/dev/fastframe/demo/dto/CreatePaymentRequest.java"))
                .contains("@Required @Size"));
        assertTrue(Files.readString(root.resolve("demo-app/src/main/java/dev/fastframe/demo/middleware/AuditMiddleware.java"))
                .contains("implements Middleware"));
        assertTrue(Files.readString(root.resolve("demo-app/src/main/java/dev/fastframe/demo/jobs/SendEmailJob.java"))
                .contains("implements JobHandler"));
        assertEquals(1, run("make:service", "Payment"));
        assertEquals(1, run("make:job", "../Bad"));
    }

    @Test void migrationsPickNextVersionAndNeverReplace() throws Exception {
        Path dir = Files.createDirectories(root.resolve("demo-app/migrations"));
        Files.writeString(dir.resolve("V001__create_users.sql"), "SELECT 1;");
        assertEquals(0, run("make:migration", "create_posts"));
        Path migration = dir.resolve("V002__create_posts.sql");
        assertTrue(Files.readString(migration).contains("CREATE TABLE \"posts\""));
        assertEquals(0, run("make:migration", "add_email_to_users"));
        assertTrue(Files.exists(dir.resolve("V003__add_email_to_users.sql")));
        assertEquals(1, run("make:migration", "../../evil"));
    }

    @Test void rejectTraversalAndReservedPackages() throws Exception {
        assertEquals(1, run("make:model", "../Escape"));
        assertEquals(1, run("make:controller", "Hello", "--package", "bad.class"));
        assertEquals(1, run("make:controller", "Hello", "--package", "../bad"));
        assertEquals(1, run("make:model", "Test", "--force"));
    }

    @Test void rejectSymlinkedOutputPath() throws Exception {
        Path real = Files.createDirectory(root.resolve("real"));
        Files.createDirectories(root.resolve("demo-app/src/main"));
        Path source = root.resolve("demo-app/src/main/java");
        try {
            Files.createSymbolicLink(source, real);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException | SecurityException ex) {
            return; // symlink privileges vary on Windows
        }
        assertEquals(1, run("make:model", "NewRecord"));
        assertFalse(Files.exists(real.resolve("dev/fastframe/demo/models/NewRecord.java")));
    }

    @Test void routeListDisplaysCompiledManifest() throws Exception {
        Path dir = Files.createDirectories(root.resolve("demo-app/target/classes/META-INF/fastframe"));
        Files.writeString(dir.resolve("routes.tsv"),
                "# method\tpath\tcontroller#method\nGET\t/api/posts/{id}\tdev.app.PostController#show\n",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, FastFrameCli.run(new String[] {"route:list", "--root", root.toString()},
                new PrintStream(out), System.err));
        assertTrue(out.toString().contains("GET"));
        assertTrue(out.toString().contains("/api/posts/{id}"));
        assertTrue(out.toString().contains("manual Router routes are not included"));
    }
}
