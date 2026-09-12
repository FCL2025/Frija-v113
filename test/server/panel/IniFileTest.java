package server.panel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The line-preserving Settings.ini reader/writer behind the panel's settings
 * page.
 *
 * <p>The whole point of the class is that the comments in Settings.ini are the
 * documentation, so the test that matters most is
 * {@link #loadThenSaveIsByteForByteIdentical}: read a file, write it back
 * unchanged, and get the same bytes - BOM, CRLF, alignment, blank lines,
 * commented-out defaults and a missing final newline included.
 */
class IniFileTest {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * A miniature Settings.ini with every shape the real file has: a BOM, CRLF
     * terminators, a comment header, aligned '=' columns, a blank line, a
     * commented-out default, a duplicate key and no final newline.
     */
    private static final String SAMPLE
            = "#Database\r\n"
            + "tms.Url      = jdbc:mysql://localhost:3306/twmsv113?characterEncoding=BIG5\r\n"
            + "tms.User     = root\r\n"
            + "\r\n"
            + "# The pool size; uncomment to override the default.\r\n"
            + "#tms.DbPoolSize     = 64\r\n"
            + "tms.Count    = 3\r\n"
            + "tms.Count    = 4\r\n"
            + "tms.PanelPort= 8080";

    private static byte[] sampleBytes() {
        final byte[] body = SAMPLE.getBytes(StandardCharsets.UTF_8);
        final byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    private static Path write(final Path dir, final byte[] bytes) throws IOException {
        final Path p = dir.resolve("Settings.ini");
        Files.write(p, bytes);
        return p;
    }

    private static IniFile sample(final Path dir) throws IOException {
        return IniFile.load(write(dir, sampleBytes()));
    }

    // ---------------------------------------------------------------------
    // Round trip.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("load then save reproduces the file byte for byte")
    void loadThenSaveIsByteForByteIdentical(@TempDir final Path dir) throws IOException {
        final byte[] original = sampleBytes();
        final IniFile ini = IniFile.load(write(dir, original));
        assertTrue(ini.hasBom());
        assertArrayEquals(original, ini.toBytes());
    }

    @Test
    @DisplayName("save() writes what toBytes() returns, and load() reads it back")
    void saveRoundTripsThroughTheFilesystem(@TempDir final Path dir) throws IOException {
        final Path p = write(dir, sampleBytes());
        final IniFile ini = IniFile.load(p);
        ini.set("tms.User", "twms");
        ini.save(p);

        assertArrayEquals(ini.toBytes(), Files.readAllBytes(p));
        assertEquals("twms", IniFile.load(p).get("tms.User"));
        try (var entries = Files.list(dir)) {
            assertEquals(1, entries.count(), "save() must not leave its temporary file behind");
        }
    }

    @Test
    @DisplayName("a file without a BOM stays without one")
    void noBomStaysNoBom(@TempDir final Path dir) throws IOException {
        final byte[] body = "a = 1\n".getBytes(StandardCharsets.UTF_8);
        final IniFile ini = IniFile.load(write(dir, body));
        assertFalse(ini.hasBom());
        assertArrayEquals(body, ini.toBytes());
    }

    @Test
    @DisplayName("LF, CR and CRLF terminators each survive on their own line")
    void mixedTerminatorsSurvive(@TempDir final Path dir) throws IOException {
        final byte[] body = "a = 1\nb = 2\r\nc = 3\rd = 4".getBytes(StandardCharsets.UTF_8);
        final IniFile ini = IniFile.load(write(dir, body));
        assertArrayEquals(body, ini.toBytes());
        assertEquals(List.of("a", "b", "c", "d"), ini.keys());
    }

    @Test
    @DisplayName("a file that is not valid UTF-8 is refused rather than silently mangled")
    void invalidUtf8IsRefused(@TempDir final Path dir) throws IOException {
        // 0xFF never appears in valid UTF-8. Loading it would decode to U+FFFD and
        // save() would then write different bytes than it read.
        final Path p = write(dir, new byte[]{'a', ' ', '=', ' ', (byte) 0xFF});
        final IOException e = assertThrows(IOException.class, () -> IniFile.load(p));
        assertTrue(e.getMessage().contains("not valid UTF-8"), e.getMessage());
    }

    // ---------------------------------------------------------------------
    // Reading.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("keys() is file order, get() is last-value-wins - as java.util.Properties does it")
    void readingSemantics(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        assertEquals(List.of("tms.Url", "tms.User", "tms.Count", "tms.PanelPort"), ini.keys());
        assertEquals("4", ini.get("tms.Count"), "the later line wins");
        assertEquals("8080", ini.get("tms.PanelPort"), "a line with no space around '=' still parses");
        assertNull(ini.get("tms.DbPoolSize"), "a commented-out default is not an active key");
        assertNull(ini.get("nope"));
    }

    @Test
    @DisplayName("this reader agrees with java.util.Properties on every active key")
    void agreesWithProperties(@TempDir final Path dir) throws IOException {
        final Properties props = new Properties();
        props.load(new StringReader(SAMPLE));

        final IniFile ini = sample(dir);
        assertEquals(props.stringPropertyNames(), ini.entries().keySet());
        for (final String key : props.stringPropertyNames()) {
            assertEquals(props.getProperty(key), ini.get(key), "disagreed on " + key);
        }
    }

    @Test
    @DisplayName("lines() classifies every line, and fills in a commented-out pair")
    void lineKinds(@TempDir final Path dir) throws IOException {
        final List<IniFile.Entry> lines = sample(dir).lines();
        assertEquals(9, lines.size());

        assertEquals(IniFile.Kind.COMMENT, lines.get(0).kind());
        assertEquals("Database", lines.get(0).comment());
        assertNull(lines.get(0).key(), "'#Database' has no '=', so it is not a commented-out pair");

        assertEquals(IniFile.Kind.PAIR, lines.get(1).kind());
        assertEquals("tms.Url", lines.get(1).key());

        assertEquals(IniFile.Kind.BLANK, lines.get(3).kind());

        final IniFile.Entry commentedDefault = lines.get(5);
        assertEquals(IniFile.Kind.COMMENT, commentedDefault.kind());
        assertEquals("tms.DbPoolSize", commentedDefault.key(), "a UI wants to show the default next to the live key");
        assertEquals("64", commentedDefault.value());

        assertEquals("", lines.get(8).terminator(), "the last line is unterminated");
    }

    // ---------------------------------------------------------------------
    // Writing.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("set() on an existing key rewrites only that line's value")
    void setTouchesOnlyTheValue(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        assertTrue(ini.set("tms.User", "twms"));

        final String after = text(ini);
        assertEquals(SAMPLE.replace("tms.User     = root", "tms.User     = twms"), after);
        assertTrue(after.contains("tms.User     = twms"), "the alignment spaces must survive");
    }

    @Test
    @DisplayName("set() on a duplicated key edits the last line, which is the one that wins")
    void setEditsTheWinningLine(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        assertTrue(ini.set("tms.Count", "8"));
        assertEquals(SAMPLE.replace("tms.Count    = 4", "tms.Count    = 8"), text(ini));
        assertEquals("8", ini.get("tms.Count"));
    }

    @Test
    @DisplayName("set() to the value already there changes nothing and returns false")
    void setToTheSameValueIsANoOp(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        final byte[] before = ini.toBytes();
        assertFalse(ini.set("tms.User", "root"));
        assertArrayEquals(before, ini.toBytes());
    }

    @Test
    @DisplayName("set() on a commented-out default inserts an active line beneath it, keeping its spacing")
    void setBelowACommentedOutDefault(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        assertTrue(ini.set("tms.DbPoolSize", "128"));

        assertEquals(SAMPLE.replace(
                "#tms.DbPoolSize     = 64\r\n",
                "#tms.DbPoolSize     = 64\r\ntms.DbPoolSize     = 128\r\n"), text(ini));
        assertEquals("128", ini.get("tms.DbPoolSize"));
        assertTrue(text(ini).contains("#tms.DbPoolSize     = 64"), "the comment is left alone");
    }

    @Test
    @DisplayName("set() on an unknown key appends, and an unterminated file stays unterminated")
    void setAppendsAnUnknownKey(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        assertTrue(ini.set("tms.PanelToken", "abc123"));

        // The old last line gains the file's dominant terminator (CRLF) and the new
        // last line goes without, so the file still does not end in a newline.
        assertEquals(SAMPLE + "\r\ntms.PanelToken = abc123", text(ini));
        assertEquals("abc123", ini.get("tms.PanelToken"));
    }

    @Test
    @DisplayName("a value is written verbatim, including '=' and '#'")
    void valuesAreNotEscaped(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        ini.set("tms.Url", "jdbc:mysql://h/db?a=1&b=2#x");
        assertEquals("jdbc:mysql://h/db?a=1&b=2#x", ini.get("tms.Url"));
    }

    @Test
    @DisplayName("set() refuses a key or value it could not write back unchanged")
    void setRejectsUnwritableInput(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        for (final String key : new String[]{"", " ", "a b", "a=b", "a:b", "#a", "!a"}) {
            assertThrows(IllegalArgumentException.class, () -> ini.set(key, "x"), "accepted key '" + key + "'");
        }
        // A null key is rejected by isValidKey before the value is looked at, so
        // it is an IllegalArgumentException and not the NullPointerException the
        // value gets.
        assertThrows(IllegalArgumentException.class, () -> ini.set(null, "x"));
        assertThrows(NullPointerException.class, () -> ini.set("a", null));
        // A line break would split the line; a trailing backslash is a Properties
        // line continuation and would swallow the next key on the next load.
        assertThrows(IllegalArgumentException.class, () -> ini.set("a", "x\ny"));
        assertThrows(IllegalArgumentException.class, () -> ini.set("a", "x\r"));
        assertThrows(IllegalArgumentException.class, () -> ini.set("a", "x\\"));
    }

    @Test
    @DisplayName("everything set() writes reads back through java.util.Properties as well")
    void writtenValuesSurviveProperties(@TempDir final Path dir) throws IOException {
        final IniFile ini = sample(dir);
        ini.set("tms.User", "twms");
        ini.set("tms.DbPoolSize", "128");
        ini.set("tms.PanelToken", "abc123");

        final Properties props = new Properties();
        props.load(new StringReader(text(ini)));
        assertEquals("twms", props.getProperty("tms.User"));
        assertEquals("128", props.getProperty("tms.DbPoolSize"));
        assertEquals("abc123", props.getProperty("tms.PanelToken"));
        assertEquals("4", props.getProperty("tms.Count"));
    }

    @Test
    @DisplayName("an empty file can still be written to")
    void emptyFile(@TempDir final Path dir) throws IOException {
        final IniFile ini = IniFile.load(write(dir, new byte[0]));
        assertTrue(ini.keys().isEmpty());
        assertTrue(ini.set("a", "1"));
        // With no line to copy a terminator from, the file's dominant terminator
        // is LF - there is no CRLF in it to prefer.
        assertEquals("a = 1\n", text(ini));
    }

    /** The saved bytes as text, with the BOM removed so the assertions stay readable. */
    private static String text(final IniFile ini) {
        final byte[] bytes = ini.toBytes();
        final int offset = ini.hasBom() ? BOM.length : 0;
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }
}
