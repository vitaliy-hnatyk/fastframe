package dev.fastframe.auth;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
/** Versioned password hashes. PBKDF2-HMAC-SHA256 (default 600k iterations). */
public final class PasswordHasher {
    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final SecureRandom RNG = new SecureRandom();
    private final int iterations;
    public PasswordHasher() { this(DEFAULT_ITERATIONS); }
    public PasswordHasher(int iterations) {
        if (iterations < 100_000 || iterations > 2_000_000) throw new IllegalArgumentException("Invalid PBKDF2 work factor");
        this.iterations = iterations;
    }
    public String hash(char[] password) {
        validatePassword(password);
        byte[] salt = new byte[16]; RNG.nextBytes(salt);
        byte[] derived = derive(password, salt, iterations);
        try {
            return "$ffpbkdf2-sha256$" + iterations + "$"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
        } finally { Arrays.fill(derived, (byte) 0); }
    }
    public boolean verify(char[] password, String encoded) {
        if (password == null || password.length == 0 || password.length > 4096 || encoded == null) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 5 || !parts[0].isEmpty() || !parts[1].equals("ffpbkdf2-sha256")) return false;
        try {
            int work = Integer.parseInt(parts[2]);
            if (work < 100_000 || work > 2_000_000) return false;
            byte[] salt = Base64.getUrlDecoder().decode(parts[3]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[4]);
            if (salt.length != 16 || expected.length != 32) return false;
            byte[] actual = derive(password, salt, work);
            try { return MessageDigest.isEqual(expected, actual); }
            finally { Arrays.fill(actual, (byte) 0); }
        } catch (IllegalArgumentException ex) { return false; }
    }
    public boolean needsRehash(String encoded) {
        if (encoded == null) return true;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 5 || !parts[1].equals("ffpbkdf2-sha256")) return true;
        try { return Integer.parseInt(parts[2]) < iterations; }
        catch (NumberFormatException ex) { return true; }
    }
    private static void validatePassword(char[] password) {
        Objects.requireNonNull(password, "password");
        if (password.length == 0 || password.length > 4096) throw new IllegalArgumentException("Password length out of bounds");
    }
    private static byte[] derive(char[] password, byte[] salt, int rounds) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, rounds, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (Exception ex) { throw new IllegalStateException("PBKDF2 unavailable", ex); }
        finally { spec.clearPassword(); }
    }
}
