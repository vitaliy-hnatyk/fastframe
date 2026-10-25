package dev.fastframe.core.http;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, reflection-free JSON encoding for maps, iterables, Object[], strings and scalars. */
public final class Json {
    public static final int MAX_CHARS = 1_048_576;
    public static final int MAX_NESTING = 64;
    private Json() { }

    public static String quote(String input) {
        Writer writer = new Writer();
        writer.string(input);
        return writer.out.toString();
    }

    public static String stringify(Object value) {
        Writer writer = new Writer();
        writer.value(value, 0);
        return writer.out.toString();
    }

    /**
     * Serialize directly to UTF-8, avoiding the temporary String and the second
     * UTF-8 encoding pass previously performed by Response.jsonValue().
     * The same nesting, cycle, escaping and logical-character limits apply.
     */
    public static byte[] utf8(Object value) {
        Utf8Writer writer = new Utf8Writer();
        writer.value(value, 0);
        return writer.out.toByteArray();
    }

    private static final class Utf8Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        private Set<Object> active;
        private int units;

        private void reserve(int count) {
            if (count > MAX_CHARS - units) throw new IllegalArgumentException("JSON exceeds maximum length");
            units += count;
        }

        /** ASCII bytes only: numbers, punctuation and JSON escape sequences. */
        private void ascii(char c) {
            reserve(1);
            out.write(c);
        }

        private void ascii(String value) {
            reserve(value.length());
            for (int i = 0; i < value.length(); i++) out.write(value.charAt(i));
        }

        private void codePoint(int codePoint, int utf16Units) {
            reserve(utf16Units);
            if (codePoint <= 0x7f) out.write(codePoint);
            else if (codePoint <= 0x7ff) {
                out.write(0xc0 | (codePoint >>> 6));
                out.write(0x80 | (codePoint & 0x3f));
            } else if (codePoint <= 0xffff) {
                out.write(0xe0 | (codePoint >>> 12));
                out.write(0x80 | ((codePoint >>> 6) & 0x3f));
                out.write(0x80 | (codePoint & 0x3f));
            } else {
                out.write(0xf0 | (codePoint >>> 18));
                out.write(0x80 | ((codePoint >>> 12) & 0x3f));
                out.write(0x80 | ((codePoint >>> 6) & 0x3f));
                out.write(0x80 | (codePoint & 0x3f));
            }
        }

        private void string(String input) {
            if (input == null) { ascii("null"); return; }
            ascii('"');
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                switch (c) {
                    case '"' -> ascii("\\\"");
                    case '\\' -> ascii("\\\\");
                    case '\b' -> ascii("\\b");
                    case '\f' -> ascii("\\f");
                    case '\n' -> ascii("\\n");
                    case '\r' -> ascii("\\r");
                    case '\t' -> ascii("\\t");
                    case '<' -> ascii("\\u003c");
                    case '>' -> ascii("\\u003e");
                    case '&' -> ascii("\\u0026");
                    case '\u2028' -> ascii("\\u2028");
                    case '\u2029' -> ascii("\\u2029");
                    default -> {
                        if (c < 0x20) ascii("\\ufffd");
                        else if (Character.isHighSurrogate(c) && i + 1 < input.length()
                                && Character.isLowSurrogate(input.charAt(i + 1))) {
                            codePoint(Character.toCodePoint(c, input.charAt(++i)), 2);
                        } else if (Character.isSurrogate(c)) ascii("\\ufffd");
                        else codePoint(c, 1);
                    }
                }
            }
            ascii('"');
        }

        private void enter(Object value, int depth) {
            if (depth >= MAX_NESTING) throw new IllegalArgumentException("JSON nesting limit exceeded");
            if (active == null) active = Collections.newSetFromMap(new IdentityHashMap<>());
            if (!active.add(value)) throw new IllegalArgumentException("Cyclic JSON value");
        }

        private void leave(Object value) { active.remove(value); }

        private void value(Object input, int depth) {
            if (input == null) ascii("null");
            else if (input instanceof String text) string(text);
            else if (input instanceof Character ch) string(ch.toString());
            else if (input instanceof Boolean flag) ascii(flag ? "true" : "false");
            else if (input instanceof Double d) {
                if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite JSON number");
                ascii(d.toString());
            } else if (input instanceof Float f) {
                if (!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite JSON number");
                ascii(f.toString());
            } else if (input instanceof Byte || input instanceof Short || input instanceof Integer
                    || input instanceof Long || input instanceof BigDecimal || input instanceof BigInteger) {
                ascii(input.toString());
            } else if (input instanceof Map<?, ?> map) {
                enter(input, depth);
                try {
                    ascii('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new IllegalArgumentException("JSON object keys must be strings");
                        }
                        if (!first) ascii(',');
                        first = false;
                        string(key);
                        ascii(':');
                        value(entry.getValue(), depth + 1);
                    }
                    ascii('}');
                } finally { leave(input); }
            } else if (input instanceof Iterable<?> iterable) {
                enter(input, depth);
                try {
                    ascii('[');
                    boolean first = true;
                    for (Object item : iterable) {
                        if (!first) ascii(',');
                        first = false;
                        value(item, depth + 1);
                    }
                    ascii(']');
                } finally { leave(input); }
            } else if (input instanceof Object[] array) {
                enter(input, depth);
                try {
                    ascii('[');
                    for (int i = 0; i < array.length; i++) {
                        if (i > 0) ascii(',');
                        value(array[i], depth + 1);
                    }
                    ascii(']');
                } finally { leave(input); }
            } else {
                throw new IllegalArgumentException("Unsupported JSON value type: " + input.getClass().getName());
            }
        }
    }

    private static final class Writer {
        private final StringBuilder out = new StringBuilder();
        private final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());

        private void append(char value) {
            if (out.length() >= MAX_CHARS) throw new IllegalArgumentException("JSON exceeds maximum length");
            out.append(value);
        }

        private void append(String value) {
            if (value.length() > MAX_CHARS - out.length()) {
                throw new IllegalArgumentException("JSON exceeds maximum length");
            }
            out.append(value);
        }

        private void string(String input) {
            if (input == null) { append("null"); return; }
            append('"');
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                switch (c) {
                    case '"' -> append("\\\"");
                    case '\\' -> append("\\\\");
                    case '\b' -> append("\\b");
                    case '\f' -> append("\\f");
                    case '\n' -> append("\\n");
                    case '\r' -> append("\\r");
                    case '\t' -> append("\\t");
                    case '<' -> append("\\u003c");
                    case '>' -> append("\\u003e");
                    case '&' -> append("\\u0026");
                    case '\u2028' -> append("\\u2028");
                    case '\u2029' -> append("\\u2029");
                    default -> {
                        if (c < 0x20 || Character.isSurrogate(c)) {
                            if (Character.isHighSurrogate(c) && i + 1 < input.length()
                                    && Character.isLowSurrogate(input.charAt(i + 1))) {
                                append(c);
                                append(input.charAt(++i));
                            } else {
                                append("\\ufffd");
                            }
                        } else append(c);
                    }
                }
            }
            append('"');
        }

        private void enter(Object input, int depth) {
            if (depth >= MAX_NESTING) throw new IllegalArgumentException("JSON nesting limit exceeded");
            if (!active.add(input)) throw new IllegalArgumentException("Cyclic JSON value");
        }

        private void value(Object input, int depth) {
            if (input == null) append("null");
            else if (input instanceof String text) string(text);
            else if (input instanceof Character ch) string(ch.toString());
            else if (input instanceof Boolean flag) append(flag ? "true" : "false");
            else if (input instanceof Double d) {
                if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite JSON number");
                append(d.toString());
            } else if (input instanceof Float f) {
                if (!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite JSON number");
                append(f.toString());
            } else if (input instanceof Byte || input instanceof Short || input instanceof Integer
                    || input instanceof Long || input instanceof BigDecimal || input instanceof BigInteger) {
                append(input.toString());
            } else if (input instanceof Map<?, ?> map) {
                enter(input, depth);
                try {
                    append('{');
                    boolean first = true;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new IllegalArgumentException("JSON object keys must be strings");
                        }
                        if (!first) append(',');
                        first = false;
                        string(key);
                        append(':');
                        value(entry.getValue(), depth + 1);
                    }
                    append('}');
                } finally { active.remove(input); }
            } else if (input instanceof Iterable<?> iterable) {
                enter(input, depth);
                try {
                    append('[');
                    boolean first = true;
                    for (Object item : iterable) {
                        if (!first) append(',');
                        first = false;
                        value(item, depth + 1);
                    }
                    append(']');
                } finally { active.remove(input); }
            } else if (input instanceof Object[] array) {
                enter(input, depth);
                try {
                    append('[');
                    for (int i = 0; i < array.length; i++) {
                        if (i > 0) append(',');
                        value(array[i], depth + 1);
                    }
                    append(']');
                } finally { active.remove(input); }
            } else {
                throw new IllegalArgumentException("Unsupported JSON value type: " + input.getClass().getName());
            }
        }
    }
}
