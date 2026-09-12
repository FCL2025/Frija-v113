package server.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The panel's hand-rolled JSON writer and reader. */
class JsonTest {

    private enum Colour { RED }

    // ---------------------------------------------------------------------
    // Writer.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("scalars")
    void writeScalars() {
        assertEquals("null", Json.write(null));
        assertEquals("true", Json.write(Boolean.TRUE));
        assertEquals("false", Json.write(Boolean.FALSE));
        assertEquals("42", Json.write(42));
        assertEquals("42", Json.write(42L));
        assertEquals("42", Json.write((short) 42));
        assertEquals("42", Json.write((byte) 42));
        assertEquals("-1", Json.write(-1));
        assertEquals("1.5", Json.write(1.5d));
        assertEquals("\"abc\"", Json.write("abc"));
        assertEquals("\"c\"", Json.write('c'));
        assertEquals("\"RED\"", Json.write(Colour.RED), "an enum goes out as its name");
    }

    @Test
    @DisplayName("NaN and infinity become null, because JSON has no word for them")
    void nonFiniteDoublesBecomeNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
        assertEquals("null", Json.write(Float.NEGATIVE_INFINITY));
    }

    @Test
    @DisplayName("a Map keeps its iteration order and stringifies its keys")
    void writeMap() {
        final Map<Object, Object> m = new LinkedHashMap<>();
        m.put("b", 1);
        m.put("a", 2);
        m.put(3, null);
        assertEquals("{\"b\":1,\"a\":2,\"3\":null}", Json.write(m));
        assertEquals("{}", Json.write(Map.of()));
    }

    @Test
    @DisplayName("Iterables and arrays both become arrays")
    void writeSequences() {
        assertEquals("[1,2,3]", Json.write(List.of(1, 2, 3)));
        assertEquals("[]", Json.write(new ArrayList<>()));
        assertEquals("[1,2,3]", Json.write(new int[]{1, 2, 3}));
        assertEquals("[\"a\",\"b\"]", Json.write(new String[]{"a", "b"}));
        assertEquals("[[1],[2]]", Json.write(new int[][]{{1}, {2}}));
        assertEquals("[true,false]", Json.write(new boolean[]{true, false}));
    }

    @Test
    @DisplayName("strings escape only what JSON requires")
    void writeStringEscapes() {
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"a\\nb\"", Json.write("a\nb"));
        assertEquals("\"a\\rb\"", Json.write("a\rb"));
        assertEquals("\"a\\tb\"", Json.write("a\tb"));
        assertEquals("\"a\\bb\"", Json.write("a\bb"));
        assertEquals("\"a\\fb\"", Json.write("a\fb"));
        assertEquals("\"a\\u0000b\"", Json.write("a\u0000b"));
        assertEquals("\"a\\u001fb\"", Json.write("a\u001Fb"));
        assertEquals("\"a/b\"", Json.write("a/b"), "a forward slash needs no escape");
    }

    @Test
    @DisplayName("Chinese goes out as itself, not as \\u escapes")
    void chineseIsNotEscaped() {
        // The response charset is pinned to UTF-8 by Router, so escaping would only
        // make the panel's network tab unreadable.
        assertEquals("\"楓之谷\"", Json.write("楓之谷"));
        assertEquals("\"已儲存\"", Json.write("已儲存"));
    }

    @Test
    @DisplayName("an unknown type falls back to toString()")
    void unknownTypesFallBackToToString() {
        final Object o = new Object() {
            @Override
            public String toString() {
                return "I am not JSON";
            }
        };
        assertEquals("\"I am not JSON\"", Json.write(o));
    }

    @Test
    @DisplayName("obj() builds an insertion-ordered map and rejects an odd argument list")
    void obj() {
        assertEquals("{\"a\":1,\"b\":\"x\"}", Json.write(Json.obj("a", 1, "b", "x")));
        assertEquals("{}", Json.write(Json.obj()));
        assertThrows(IllegalArgumentException.class, () -> Json.obj("a"));
    }

    // ---------------------------------------------------------------------
    // Reader.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("reading the documented shapes")
    void readShapes() {
        assertNull(Json.read("null"));
        assertEquals(Boolean.TRUE, Json.read("true"));
        assertEquals(Boolean.FALSE, Json.read("false"));
        assertEquals("abc", Json.read("\"abc\""));

        assertEquals(1L, Json.read("1"), "an integer comes back as a Long");
        assertInstanceOf(Long.class, Json.read("1"));
        assertEquals(-1L, Json.read("-1"));
        assertEquals(1.5d, Json.read("1.5"), "anything else numeric comes back as a Double");
        assertInstanceOf(Double.class, Json.read("1.5"));
        assertEquals(1.0e3d, Json.read("1e3"));

        assertInstanceOf(LinkedHashMap.class, Json.read("{}"));
        assertInstanceOf(ArrayList.class, Json.read("[]"));
    }

    @Test
    @DisplayName("whitespace between tokens is ignored")
    void whitespaceIsIgnored() {
        assertEquals(Map.of("a", 1L), Json.read("  {\n\t\"a\" : 1\r\n}  "));
        assertEquals(List.of(1L, 2L), Json.read(" [ 1 , 2 ] "));
    }

    @Test
    @DisplayName("escapes are read back, including \\u and the forward slash")
    void readStringEscapes() {
        assertEquals("a\"b", Json.read("\"a\\\"b\""));
        assertEquals("a\\b", Json.read("\"a\\\\b\""));
        assertEquals("a/b", Json.read("\"a\\/b\""));
        assertEquals("\b\f\n\r\t", Json.read("\"\\b\\f\\n\\r\\t\""));
        assertEquals("楓", Json.read("\"\\u6953\""));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "", " ", "{", "[", "\"", "{\"a\"}", "{\"a\":1,}", "{a:1}", "[1,]", "[1 2]",
        "tru", "nul", "\"\\x\"", "\"\\u12\"", "{} {}", "1 2", "\"a\u0001b\""})
    @DisplayName("malformed input throws IllegalArgumentException, which the router turns into a 400")
    void malformedInputThrows(final String text) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.read(text));
        assertTrue(e.getMessage().startsWith("JSON: "), e.getMessage());
        assertTrue(e.getMessage().contains("offset"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // Round trip.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("write then read returns an equal structure")
    void roundTrip() {
        final Map<String, Object> original = Json.obj(
                "name", "楓之谷",
                "channel", 3L,
                "rate", 1.5d,
                "gm", Boolean.TRUE,
                "note", null,
                "players", List.of(
                        Json.obj("id", 1L, "name", "a\"b\\c\nd"),
                        Json.obj("id", 2L, "name", "已儲存")),
                "empty", List.of());

        assertEquals(original, Json.read(Json.write(original)));
        // ... and serialising the parsed copy gives the identical text back.
        final String text = Json.write(original);
        assertEquals(text, Json.write(Json.read(text)));
    }

    @Test
    @DisplayName("every character above U+001F survives a round trip")
    void awkwardCharactersRoundTrip() {
        for (final String s : new String[]{"", " ", "\"", "\\", "/", "\n\r\t\b\f", "\u0000\u001F",
            "楓之谷", "emoji 🙂", "混合 mixed 123"}) {
            assertEquals(s, Json.read(Json.write(s)), "failed for " + s);
        }
    }
}
