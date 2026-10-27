package dev.fastframe.benchmark;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Benchmark-only canonical HS256 JWT verifier: verifies HMAC and fixed fixture claims.
 * Not a general JWT implementation and not intended for production authentication.
 */
public final class BenchJwt {
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Pattern CLAIMS = Pattern.compile(
            Pattern.quote("{\"iss\":\"ff-bench\",\"aud\":\"ff-bench\",\"sub\":\"bench-user\",\"iat\":")
            + "([0-9]+)" + Pattern.quote(",\"nbf\":")
            + "([0-9]+)" + Pattern.quote(",\"exp\":")
            + "([0-9]+)" + Pattern.quote(",\"roles\":[\"reader\"],\"permissions\":[\"bench:read\"]}"));
    private static final byte[] KEY = System.getenv().getOrDefault(
            "BENCH_JWT_SECRET", "no-production-secret-benchmark-only").getBytes(StandardCharsets.UTF_8);
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private BenchJwt() { }
    public static boolean valid(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 4096) return false;
        String[] part = authorization.substring(7).split("\\.", -1);
        if (part.length != 3) return false;
        try {
            for (String p : part) if (p.isEmpty() || p.contains("=") ||
                    !ENCODER.encodeToString(DECODER.decode(p)).equals(p)) return false;
            byte[] signature = DECODER.decode(part[2]);
            if (signature.length != 32) return false;
            String input = part[0] + "." + part[1];
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(KEY, "HmacSHA256"));
            if (!MessageDigest.isEqual(signature, hmac.doFinal(input.getBytes(StandardCharsets.US_ASCII)))) return false;
            if (!HEADER.equals(new String(DECODER.decode(part[0]), StandardCharsets.UTF_8))) return false;
            Matcher fields = CLAIMS.matcher(new String(DECODER.decode(part[1]), StandardCharsets.UTF_8));
            if (!fields.matches()) return false;
            long issued = Long.parseLong(fields.group(1));
            long notBefore = Long.parseLong(fields.group(2));
            long expires = Long.parseLong(fields.group(3));
            long now = Instant.now().getEpochSecond();
            return issued <= now + 30 && notBefore <= now + 30 && expires > now
                    && expires > issued && expires - issued <= 3600 && notBefore >= issued - 30;
        } catch (Exception invalid) { return false; }
    }
}
