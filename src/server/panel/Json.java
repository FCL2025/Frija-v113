package server.panel;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON writer and reader for the management panel.
 *
 * <p>Hand-written because the panel is the only thing in the process that
 * speaks JSON, and pulling in a library for it would mean a tenth jar in
 * {@code dist/lib/} - every one of the nine already there is a forced choice.
 * The writer takes the plain Java shapes the API handlers build
 * ({@link Map}, {@link Iterable}, arrays, strings, numbers, booleans,
 * {@code null}, enums) and nothing else; the reader produces the same shapes,
 * with objects as {@link LinkedHashMap}, arrays as {@link ArrayList}, integers
 * as {@link Long} and everything else numeric as {@link Double}.
 *
 * <p>Output is UTF-8 text with every character above U+001F written as is -
 * the wire charset is fixed to UTF-8 by {@link Router}, so there is no need to
 * {@code \\u}-escape Chinese, and the responses stay readable in a browser's
 * network tab.
 */
public final class Json {

    private Json() {
    }

    /** Serialises {@code value}. Unknown types fall back to {@code toString()}. */
    public static String write(final Object value) {
        final StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(final StringBuilder sb, final Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) {
            sb.append(value);
        } else if (value instanceof Double || value instanceof Float) {
            final double d = ((Number) value).doubleValue();
            // JSON has no NaN or infinity; null is the least surprising stand-in.
            sb.append(Double.isFinite(d) ? value.toString() : "null");
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof CharSequence || value instanceof Character || value instanceof Enum) {
            writeString(sb, value.toString());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (final Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (final Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (value.getClass().isArray()) {
            sb.append('[');
            final int n = Array.getLength(value);
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(sb, Array.get(value, i));
            }
            sb.append(']');
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** A convenience for building objects in insertion order: {@code Json.obj("a", 1, "b", "x")}. */
    public static Map<String, Object> obj(final Object... keyValues) {
        if ((keyValues.length & 1) != 0) {
            throw new IllegalArgumentException("odd number of arguments");
        }
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    /**
     * Parses one JSON value. Throws {@link IllegalArgumentException} with the
     * character offset on malformed input - the router turns that into a 400.
     */
    public static Object read(final String text) {
        final Parser p = new Parser(text);
        p.skipWs();
        final Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    private static final class Parser {

        private final String s;
        private int pos;

        Parser(final String s) {
            this.s = s;
        }

        IllegalArgumentException error(final String what) {
            return new IllegalArgumentException("JSON: " + what + " at offset " + pos);
        }

        void skipWs() {
            while (pos < s.length()) {
                final char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            final char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private void expect(final String word) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
        }

        private Map<String, Object> object() {
            final Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw error("expected string key");
                }
                final String key = string();
                skipWs();
                if (peek() != ':') {
                    throw error("expected ':'");
                }
                pos++;
                skipWs();
                m.put(key, value());
                skipWs();
                final char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            final List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                final char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return l;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos);
        }

        private String string() {
            pos++; // opening quote
            final StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                final char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw error("unterminated escape");
                    }
                    final char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("bad \\u escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (final NumberFormatException ex) {
                                throw error("bad \\u escape");
                            }
                            pos += 4;
                        }
                        default -> throw error("bad escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        private Number number() {
            final int start = pos;
            boolean integral = true;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length()) {
                final char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            final String text = s.substring(start, pos);
            try {
                if (integral) {
                    return Long.valueOf(text);
                }
                return Double.valueOf(text);
            } catch (final NumberFormatException e) {
                throw error("bad number '" + text + "'");
            }
        }
    }
}
