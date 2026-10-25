package dev.fastframe.core.http;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Strict, deliberately small JSON object decoder for compile-time generated DTO binding. */
public final class JsonBody {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_VALUES = 2048;
    private static final int MAX_BODY_CHARS = 65536;
    private final Map<String, Object> fields;
    private final Map<String, List<String>> errors = new LinkedHashMap<>();

    private JsonBody(Map<String, Object> fields) { this.fields = fields; }

    public static JsonBody parse(Request request) {
        Objects.requireNonNull(request);
        String contentType = request.header("content-type");
        if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new HttpException(415, "Content-Type must be application/json");
        }
        if (contentType.contains(";")) {
            String[] parts = contentType.split(";");
            for (int i = 1; i < parts.length; i++) {
                String parameter = parts[i].trim().toLowerCase(Locale.ROOT);
                if (!parameter.equals("charset=utf-8") && !parameter.equals("charset=\"utf-8\"")) {
                    throw new HttpException(415, "Only UTF-8 JSON is supported");
                }
            }
        }
        String source = request.body();
        if (source.isBlank() || source.length() > MAX_BODY_CHARS) {
            throw new HttpException(400, "Invalid JSON body");
        }
        try {
            Object result = new Parser(source).parse();
            if (!(result instanceof Map<?, ?> raw)) throw new IllegalArgumentException("JSON root must be an object");
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) raw;
            return new JsonBody(map);
        } catch (IllegalArgumentException ex) {
            throw new HttpException(400, "Malformed JSON object");
        }
    }

    /** Strict, bounded JSON object decoding reused for signed JWT claims. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("Invalid JSON length");
        }
        Object value = new Parser(json).parse();
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    /** Test/support JSON parsing for objects, arrays and scalars using existing limits. */
    public static Object parseValue(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("Invalid JSON length");
        }
        return new Parser(json).parse();
    }

    /** Nullable string unless @Required; invalid values accumulate for HTTP 422. */
    public String string(String field, boolean required, int min, int max) {
        Object value = fields.get(field);
        if (value == null) { if (required) error(field, "is required"); return null; }
        if (!(value instanceof String s)) { error(field, "must be a string"); return null; }
        if (s.length() < min || s.length() > max) error(field, "length must be between " + min + " and " + max);
        return s;
    }

    public Integer integer(String field, boolean required, long min, long max) {
        Long number = numeric(field, required, Math.max(min, Integer.MIN_VALUE), Math.min(max, Integer.MAX_VALUE));
        return number == null ? null : number.intValue();
    }

    public Long longValue(String field, boolean required, long min, long max) {
        return numeric(field, required, min, max);
    }

    private Long numeric(String field, boolean required, long min, long max) {
        Object value = fields.get(field);
        if (value == null) { if (required) error(field, "is required"); return null; }
        if (!(value instanceof BigDecimal decimal)) { error(field, "must be an integer"); return null; }
        try {
            long result = decimal.longValueExact();
            if (result < min || result > max) error(field, "must be between " + min + " and " + max);
            return result;
        } catch (ArithmeticException ex) {
            error(field, "must be an integer within range");
            return null;
        }
    }

    public Boolean bool(String field, boolean required) {
        Object value = fields.get(field);
        if (value == null) { if (required) error(field, "is required"); return null; }
        if (!(value instanceof Boolean b)) { error(field, "must be a boolean"); return null; }
        return b;
    }

    public void finish(String... knownFields) {
        List<String> allowed = Arrays.asList(knownFields);
        for (String key : fields.keySet()) if (!allowed.contains(key)) error(key, "unknown field");
        if (!errors.isEmpty()) throw new ValidationException(errors);
    }

    private void error(String field, String reason) {
        errors.computeIfAbsent(field, k -> new ArrayList<>()).add(reason);
    }

    private static final class Parser {
        private final String s;
        private int pos;
        private int values;
        Parser(String source) { s = source; }

        Object parse() {
            whitespace();
            Object result = value(0);
            whitespace();
            if (pos != s.length()) fail();
            return result;
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH || ++values > MAX_VALUES) throw new IllegalArgumentException("JSON limits exceeded");
            whitespace();
            if (pos >= s.length()) return fail();
            char c = s.charAt(pos);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (c == 't') { word("true"); return Boolean.TRUE; }
            if (c == 'f') { word("false"); return Boolean.FALSE; }
            if (c == 'n') { word("null"); return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            return fail();
        }

        private Map<String, Object> object(int depth) {
            pos++;
            Map<String, Object> obj = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return obj;
            do {
                whitespace();
                if (pos >= s.length() || s.charAt(pos) != '"') fail();
                String key = string();
                whitespace();
                require(':');
                Object value = value(depth + 1);
                if (obj.containsKey(key)) fail(); // no ambiguous duplicate keys
                obj.put(key, value);
                whitespace();
                if (take('}')) return obj;
                require(',');
            } while (true);
        }

        private List<Object> array(int depth) {
            pos++;
            List<Object> array = new ArrayList<>();
            whitespace();
            if (take(']')) return array;
            do {
                array.add(value(depth + 1));
                whitespace();
                if (take(']')) return array;
                require(',');
            } while (true);
        }

        private String string() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    if (!isWellFormedUtf16(out)) fail();
                    return out.toString();
                }
                if (c < 0x20) fail();
                if (c != '\\') { out.append(c); continue; }
                if (pos >= s.length()) fail();
                char esc = s.charAt(pos++);
                switch (esc) {
                    case '"', '\\', '/' -> out.append(esc);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) fail();
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = s.charAt(pos++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) fail();
                            code = (code << 4) | digit;
                        }
                        out.append((char) code);
                    }
                    default -> fail();
                }
            }
            return fail();
        }

        private static boolean isWellFormedUtf16(CharSequence text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
                } else if (Character.isLowSurrogate(c)) return false;
            }
            return true;
        }

        private BigDecimal number() {
            int start = pos;
            take('-');
            if (take('0')) {
                if (pos < s.length() && Character.isDigit(s.charAt(pos))) fail();
            } else {
                if (pos >= s.length() || s.charAt(pos) < '1' || s.charAt(pos) > '9') fail();
                while (pos < s.length() && betweenDigit(s.charAt(pos))) pos++;
            }
            if (take('.')) {
                if (pos >= s.length() || !betweenDigit(s.charAt(pos))) fail();
                while (pos < s.length() && betweenDigit(s.charAt(pos))) pos++;
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                if (pos >= s.length() || !betweenDigit(s.charAt(pos))) fail();
                while (pos < s.length() && betweenDigit(s.charAt(pos))) pos++;
            }
            if (pos - start > 64) fail();
            try { return new BigDecimal(s.substring(start, pos)); }
            catch (NumberFormatException ex) { return fail(); }
        }

        private static boolean betweenDigit(char c) { return c >= '0' && c <= '9'; }
        private void word(String text) { if (!s.startsWith(text, pos)) fail(); pos += text.length(); }
        private void whitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
                pos++;
            }
        }
        private boolean take(char c) { if (pos < s.length() && s.charAt(pos) == c) { pos++; return true; } return false; }
        private void require(char c) { if (!take(c)) fail(); }
        private static <T> T fail() { throw new IllegalArgumentException("Invalid JSON"); }
    }
}
