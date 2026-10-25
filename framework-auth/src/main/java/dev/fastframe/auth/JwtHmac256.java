package dev.fastframe.auth;
import dev.fastframe.core.http.Json;
import dev.fastframe.core.http.JsonBody;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
/** Narrow JWS HS256 issuer/verifier; algorithm is fixed, never selected from untrusted header. */
public final class JwtHmac256 {
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private final SecretKeySpec key;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final Duration maxTtl;

    public JwtHmac256(byte[] secret, String issuer, String audience) {
        this(secret, issuer, audience, Clock.systemUTC(), Duration.ofHours(1));
    }
    public JwtHmac256(byte[] secret, String issuer, String audience, Clock clock, Duration maxTtl) {
        if (secret == null || secret.length < 32) throw new IllegalArgumentException("HS256 requires >=32 random secret bytes");
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank())
            throw new IllegalArgumentException("Issuer and audience are required");
        this.key = new SecretKeySpec(secret.clone(), "HmacSHA256");
        this.issuer = issuer; this.audience = audience;
        this.clock = Objects.requireNonNull(clock); this.maxTtl = Objects.requireNonNull(maxTtl);
        if (maxTtl.isNegative() || maxTtl.isZero() || maxTtl.compareTo(Duration.ofHours(24)) > 0)
            throw new IllegalArgumentException("Invalid max token lifetime");
    }
    public String issue(Identity principal, Duration ttl) {
        Objects.requireNonNull(principal);
        if (ttl == null || ttl.isNegative() || ttl.isZero() || ttl.compareTo(maxTtl) > 0)
            throw new IllegalArgumentException("Invalid TTL");
        long now = clock.instant().getEpochSecond();
        long expiry = Math.addExact(now, ttl.getSeconds());
        if (expiry <= now) throw new IllegalArgumentException("TTL too short");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", issuer); payload.put("aud", audience); payload.put("sub", principal.subject());
        payload.put("iat", now); payload.put("nbf", now); payload.put("exp", expiry);
        payload.put("roles", principal.roles().stream().sorted().toList());
        payload.put("permissions", principal.permissions().stream().sorted().toList());
        String unsigned = encode(HEADER) + "." + encode(Json.stringify(payload));
        return unsigned + "." + ENCODER.encodeToString(mac(unsigned));
    }
    public Identity verify(String token) {
        try {
            if (token == null || token.length() < 32 || token.length() > 4096) return reject();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !canonical(parts[0]) || !canonical(parts[1]) || !canonical(parts[2])) return reject();
            byte[] signature = DECODER.decode(parts[2]);
            if (signature.length != 32) return reject();
            String unsigned = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(mac(unsigned), signature)) return reject();
            Map<String, Object> header = JsonBody.parseObject(decode(parts[0]));
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))
                    || header.size() != 2) return reject();
            Map<String, Object> payload = JsonBody.parseObject(decode(parts[1]));
            if (!issuer.equals(payload.get("iss")) || !audience.equals(payload.get("aud"))) return reject();
            if (!(payload.get("sub") instanceof String sub)) return reject();
            long now = clock.instant().getEpochSecond();
            long iat = timestamp(payload.get("iat"));
            long nbf = timestamp(payload.get("nbf"));
            long exp = timestamp(payload.get("exp"));
            if (iat > now + 30 || nbf > now + 30 || exp <= now || exp <= iat
                    || exp - iat > maxTtl.getSeconds() || nbf < iat - 30) return reject();
            return new Identity(sub, strings(payload.get("roles")), strings(payload.get("permissions")));
        } catch (IllegalArgumentException | ArithmeticException ex) {
            throw new IllegalArgumentException("Invalid bearer token");
        }
    }
    private static Set<String> strings(Object input) {
        if (!(input instanceof List<?> list) || list.size() > 64) return reject();
        List<String> strings = new ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof String s) || s.isBlank() || s.length() > 128) return reject();
            strings.add(s);
        }
        return Set.copyOf(strings);
    }
    private static long timestamp(Object value) {
        if (!(value instanceof BigDecimal number)) return reject();
        return number.longValueExact();
    }
    private byte[] mac(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) { throw new IllegalStateException("HMAC unavailable", ex); }
    }
    private static boolean canonical(String value) {
        try { return !value.contains("=") && value.matches("[A-Za-z0-9_-]+")
                    && ENCODER.encodeToString(DECODER.decode(value)).equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }
    private static String encode(String raw) {
        return ENCODER.encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String data) {
        byte[] bytes = DECODER.decode(data);
        try { return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException ex) { throw new IllegalArgumentException("Invalid UTF-8"); }
    }
    private static <T> T reject() { throw new IllegalArgumentException("Invalid bearer token"); }
}
