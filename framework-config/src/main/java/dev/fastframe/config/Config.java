package dev.fastframe.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable env-backed config: process environment overrides .env. No interpolation or shell execution. */
public final class Config {
    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final long MAX_FILE_BYTES = 1_048_576;
    private final Map<String, String> values;

    private Config(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Reads optional .env in current directory; process environment wins. */
    public static Config load() throws IOException {
        // Put FF_ENV_FILE outside the web document root when serving from XAMPP/www.
        return load(Path.of(System.getenv().getOrDefault("FF_ENV_FILE", ".env")), System.getenv());
    }

    public static Config load(Path file) throws IOException {
        return load(file, System.getenv());
    }

    /** Explicit process overrides allow deterministic tests without changing process environment. */
    public static Config load(Path file, Map<String, String> environment) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(environment, "environment");
        Map<String, String> values = new LinkedHashMap<>();
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) throw new IOException("Not a regular env file: " + file);
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("Env file too large: " + file);
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            String[] lines = content.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();
                int eq = line.indexOf('=');
                if (eq < 1) throw invalid(i + 1, "expected KEY=VALUE");
                String key = line.substring(0, eq).trim();
                if (!KEY.matcher(key).matches()) throw invalid(i + 1, "invalid key");
                if (values.containsKey(key)) throw invalid(i + 1, "duplicate key " + key);
                values.put(key, parseValue(line.substring(eq + 1).trim(), i + 1));
            }
        }
        // Explicitly supplied environment is authoritative, including empty values.
        for (var entry : environment.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) values.put(entry.getKey(), entry.getValue());
        }
        return new Config(values);
    }

    public String get(String key) { return values.get(Objects.requireNonNull(key)); }

    public String get(String key, String fallback) {
        return values.getOrDefault(Objects.requireNonNull(key), fallback);
    }

    public String require(String key) {
        String value = get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing required configuration: " + key);
        return value;
    }

    public boolean booleanValue(String key, boolean fallback) {
        String value = get(key);
        if (value == null) return fallback;
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("Invalid boolean configuration: " + key);
        };
    }

    public int intValue(String key, int fallback, int min, int max) {
        if (min > max) throw new IllegalArgumentException("Invalid bounds");
        String text = get(key);
        int value;
        try { value = text == null ? fallback : Integer.parseInt(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid integer configuration: " + key, e); }
        if (value < min || value > max) throw new IllegalArgumentException("Out-of-range integer configuration: " + key);
        return value;
    }

    public Duration millis(String key, long fallback, long min, long max) {
        if (min > max) throw new IllegalArgumentException("Invalid bounds");
        String text = get(key);
        long value;
        try { value = text == null ? fallback : Long.parseLong(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid duration configuration: " + key, e); }
        if (value < min || value > max) throw new IllegalArgumentException("Out-of-range duration configuration: " + key);
        return Duration.ofMillis(value);
    }

    private static IllegalArgumentException invalid(int line, String reason) {
        return new IllegalArgumentException("Invalid .env at line " + line + ": " + reason);
    }

    private static String parseValue(String s, int line) {
        if (s.isEmpty()) return "";
        char quote = s.charAt(0);
        if (quote == '\'' || quote == '"') {
            StringBuilder value = new StringBuilder();
            int i = 1;
            boolean closed = false;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == quote) { closed = true; break; }
                if (quote == '"' && c == '\\') {
                    if (i >= s.length()) throw invalid(line, "incomplete escape");
                    char escaped = s.charAt(i++);
                    value.append(switch (escaped) {
                        case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        case '"' -> '"'; case '\\' -> '\\';
                        default -> throw invalid(line, "unsupported escape");
                    });
                } else value.append(c);
            }
            if (!closed) throw invalid(line, "unclosed quote");
            String suffix = s.substring(i).trim();
            if (!suffix.isEmpty() && !suffix.startsWith("#")) throw invalid(line, "trailing characters");
            return value.toString();
        }
        // A comment starts after whitespace; "#" inside a URL/password remains literal.
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '#' && Character.isWhitespace(s.charAt(i - 1))) {
                return s.substring(0, i).stripTrailing();
            }
        }
        return s;
    }
}
