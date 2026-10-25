package dev.fastframe.demo;
import dev.fastframe.auth.*;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
/** Local testing only: prints a short-lived development bearer token. */
public final class DevToken {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Usage: DevToken <subject> <permission>");
        String value = System.getenv("FF_JWT_SECRET_B64");
        if (value == null || value.isBlank()) throw new IllegalStateException("Set FF_JWT_SECRET_B64 first");
        JwtHmac256 jwt = new JwtHmac256(Base64.getDecoder().decode(value), "fastframe-demo", "fastframe-api");
        System.out.println(jwt.issue(new Identity(args[0], Set.of(), Set.of(args[1])), Duration.ofMinutes(5)));
    }
}
