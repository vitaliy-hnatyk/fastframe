package dev.fastframe.micronautbench;

import io.micronaut.runtime.Micronaut;
import java.util.Map;

public final class Application {
    private Application() { }
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "18080"));
        Micronaut.build(args)
                .mainClass(Application.class)
                .properties(Map.of("micronaut.server.port", port, "micronaut.server.host", "127.0.0.1",
                        "micronaut.server.netty.use-native-transport", false))
                .start();
    }
}
