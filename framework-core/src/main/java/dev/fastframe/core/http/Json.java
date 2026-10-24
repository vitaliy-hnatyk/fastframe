package dev.fastframe.core.http;

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
