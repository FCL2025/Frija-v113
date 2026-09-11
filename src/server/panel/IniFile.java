package server.panel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A line-preserving reader and writer for Settings.ini.
 *
 * The server reads Settings.ini with java.util.Properties, but Properties
 * cannot write it back: store() drops every comment and reorders the keys,
 * and in this file the comments are the documentation (how to rename the
 * database, what the pool settings mean, which keys are dead). So this class
 * keeps the file as a list of raw lines, each with exactly the terminator it
 * had (or none, for an unterminated last line), and set() edits only the
 * value portion of the one line it targets. Everything else - the UTF-8 BOM
 * the file starts with, the CRLF terminators, the alignment spaces before
 * '=', the "commented-out default" lines - round-trips byte for byte.
 *
 * The BOM matters more than it looks. Java's UTF-8 decoder does not strip
 * it, so Properties sees U+FEFF as the first character of line 1. Today line
 * 1 is a comment, so the damage is a bogus key (U+FEFF followed by
 * "#Database") that nothing reads; if a real key were ever on line 1 it
 * would silently become unreachable. This class strips the BOM before
 * parsing, remembers it, writes it back, and never inserts a line at the
 * top of the file.
 *
 * How a line is read (deliberately simpler than Properties; the two agree on
 * every key in the real file, which is checked in the panel's scratch test):
 * <ul>
 * <li>only whitespace: BLANK</li>
 * <li>first non-blank character '#' or '!': COMMENT. If the text after the
 * marker (and any spaces) looks like "key = value", key() and value() are
 * filled in so a UI can show the commented-out default next to the live
 * key. comment() is the text after the marker, trimmed.</li>
 * <li>otherwise, if the line contains '=': PAIR. The key is the text before
 * the first '=', trimmed; the value is the text after it with leading and
 * trailing whitespace removed. Properties keeps trailing whitespace; the file
 * has none and the difference is not worth mirroring. A line whose key would
 * be rejected by set() (it contains whitespace or ':') is OTHER instead -
 * Properties splits such a line somewhere else, and the panel should not
 * pretend to understand it.</li>
 * <li>anything else: OTHER, preserved verbatim.</li>
 * </ul>
 *
 * Values are written verbatim, with no escaping. Properties.load treats a
 * backslash as an escape (tab, Unicode, a doubled backslash) and a trailing
 * backslash as a line continuation, so a value containing one would read
 * back differently; the file has no backslashes and this class does not try
 * to encode them. Leading whitespace in a value is likewise not
 * representable: both readers strip it.
 *
 * Instances are not thread-safe; the panel serialises access itself.
 */
public final class IniFile {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CRLF = "\r\n";
    private static final String LF = "\n";

    /** What a line is. */
    public enum Kind {
        COMMENT, BLANK, PAIR, OTHER
    }

    /**
     * A read-only view of one line. text is the line without its terminator;
     * terminator is "\r\n", "\n", "\r" or "" for an unterminated last line.
     * key and value are set for a PAIR and for a COMMENT that looks like a
     * commented-out pair; comment only for a COMMENT. keyStart and valueStart
     * are char offsets into text (where the key begins, and where the value
     * begins after the '=' and any spaces), -1 when there is no key; set()
     * edits around them, and a viewer can use them to highlight the value.
     */
    public record Entry(Kind kind, String key, String value, String comment, String text, String terminator,
            int keyStart, int valueStart) {
    }

    private final boolean bom;
    private final List<Entry> lines;

    private IniFile(final boolean bom, final List<Entry> lines) {
        this.bom = bom;
        this.lines = lines;
    }

    /**
     * Reads the file. Decoding is strict: a file that is not valid UTF-8 is
     * refused with an IOException, because save() could not reproduce its
     * bytes and would silently replace the bad ones with U+FFFD.
     */
    public static IniFile load(final Path path) throws IOException {
        final byte[] bytes = Files.readAllBytes(path);
        final boolean bom = bytes.length >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
        final int offset = bom ? 3 : 0;
        final String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (final CharacterCodingException e) {
            throw new IOException(path + " is not valid UTF-8; refusing to load a file that could not be saved back intact", e);
        }
        final List<Entry> lines = new ArrayList<>();
        int start = 0;
        final int n = content.length();
        for (int i = 0; i < n; i++) {
            final char c = content.charAt(i);
            if (c == '\n') {
                lines.add(parseLine(content.substring(start, i), LF));
                start = i + 1;
            } else if (c == '\r') {
                final boolean crlf = i + 1 < n && content.charAt(i + 1) == '\n';
                lines.add(parseLine(content.substring(start, i), crlf ? CRLF : "\r"));
                if (crlf) {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n) {
            lines.add(parseLine(content.substring(start), ""));
        }
        return new IniFile(bom, lines);
    }

    /** Whether the file started with the UTF-8 BOM; toBytes() writes it back if so. */
    public boolean hasBom() {
        return bom;
    }

    /**
     * The active keys in file order. A key that appears on several lines is
     * listed once, at its first position; get() returns the last value, as
     * Properties does.
     */
    public List<String> keys() {
        return new ArrayList<>(entries().keySet());
    }

    /** The value of the last active line with this key, or null if there is none. */
    public String get(final String key) {
        Objects.requireNonNull(key, "key");
        for (int i = lines.size() - 1; i >= 0; i--) {
            final Entry e = lines.get(i);
            if (e.kind() == Kind.PAIR && e.key().equals(key)) {
                return e.value();
            }
        }
        return null;
    }

    /** Active keys and values in file order, last value winning for a duplicate key. */
    public Map<String, String> entries() {
        final Map<String, String> out = new LinkedHashMap<>();
        for (final Entry e : lines) {
            if (e.kind() == Kind.PAIR) {
                out.put(e.key(), e.value());
            }
        }
        return out;
    }

    /** Every line of the file, in order, for a settings viewer. */
    public List<Entry> lines() {
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    /**
     * Sets a value, changing as little of the file as possible.
     * <ol>
     * <li>If an active line with this key exists (the last one, if several),
     * only its value is replaced: the key, the alignment spaces, the '=' and
     * the whitespace after it are kept byte for byte. Any trailing whitespace
     * the old value had is dropped, because Properties would read it back as
     * part of the new value.</li>
     * <li>Otherwise, if a commented-out line "#key = value" exists, an active
     * line is inserted right after the last such comment, using the comment's
     * own spacing between key and value, so "#tms.DbPoolSize     = 64" yields
     * "tms.DbPoolSize     = 128" beneath it. The comment is left alone.</li>
     * <li>Otherwise "key = value" is appended.</li>
     * </ol>
     * An inserted line takes the terminator of the line it follows. If that
     * line was an unterminated last line it is given the file's dominant
     * terminator (CRLF if the file has any, else LF) and the new line goes
     * without, so a file that did not end in a newline still does not.
     *
     * @return true if the content changed; false if the key already had this
     * exact value
     * @throws IllegalArgumentException for a key that is blank, contains '=',
     * ':' or whitespace, or starts with '#' or '!', and for a value containing
     * a line break or ending in a backslash - none of these can be written
     * without changing meaning (a trailing backslash is a Properties line
     * continuation, and would swallow the next key on the next load)
     */
    public boolean set(final String key, final String value) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("Invalid key: '" + key + "'");
        }
        Objects.requireNonNull(value, "value");
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Value for " + key + " contains a line break");
        }
        if (value.endsWith("\\")) {
            throw new IllegalArgumentException("Value for " + key + " ends in a backslash");
        }
        int active = -1;
        int commented = -1;
        for (int i = 0; i < lines.size(); i++) {
            final Entry e = lines.get(i);
            if (e.key() == null || !e.key().equals(key)) {
                continue;
            }
            if (e.kind() == Kind.PAIR) {
                active = i;
            } else if (e.kind() == Kind.COMMENT) {
                commented = i;
            }
        }
        if (active >= 0) {
            final Entry e = lines.get(active);
            if (e.value().equals(value)) {
                return false;
            }
            lines.set(active, parseLine(e.text().substring(0, e.valueStart()) + value, e.terminator()));
            return true;
        }
        if (commented >= 0) {
            final Entry e = lines.get(commented);
            final String spacing = e.text().substring(e.keyStart() + key.length(), e.valueStart());
            insertAfter(commented, key + spacing + value);
        } else {
            insertAfter(lines.size() - 1, key + " = " + value);
        }
        return true;
    }

    /** The exact bytes save() writes: the BOM if the file had one, then every line with its own terminator. */
    public byte[] toBytes() {
        final StringBuilder sb = new StringBuilder();
        for (final Entry e : lines) {
            sb.append(e.text()).append(e.terminator());
        }
        final byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (!bom) {
            return body;
        }
        final byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    /**
     * Writes toBytes() to a temporary file in the same directory, forces it to
     * disk, then moves it over the target atomically, so a crash or a full disk
     * leaves the old file intact rather than a truncated one. The server only
     * reads the file at startup, so there is no reader to coordinate with.
     */
    public void save(final Path path) throws IOException {
        final Path target = path.toAbsolutePath();
        final Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                final ByteBuffer buf = ByteBuffer.wrap(toBytes());
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException e) {
                // The move already succeeded or already threw; a leftover .tmp is not worth masking either.
            }
        }
    }

    private void insertAfter(final int index, final String text) {
        final String terminator;
        if (index < 0) {
            terminator = dominantTerminator();
        } else {
            final Entry prev = lines.get(index);
            if (prev.terminator().isEmpty()) {
                // Only an unterminated last line has no terminator: give it one, and keep the file ending without.
                lines.set(index, parseLine(prev.text(), dominantTerminator()));
                terminator = "";
            } else {
                terminator = prev.terminator();
            }
        }
        lines.add(index + 1, parseLine(text, terminator));
    }

    private String dominantTerminator() {
        for (final Entry e : lines) {
            if (CRLF.equals(e.terminator())) {
                return CRLF;
            }
        }
        return LF;
    }

    private static Entry parseLine(final String text, final String terminator) {
        final int start = skipWhitespace(text, 0);
        if (start == text.length()) {
            return new Entry(Kind.BLANK, null, null, null, text, terminator, -1, -1);
        }
        final char first = text.charAt(start);
        final boolean comment = first == '#' || first == '!';
        final String commentText = comment ? text.substring(start + 1).strip() : null;
        final int keyStart = comment ? skipWhitespace(text, start + 1) : start;
        final int eq = text.indexOf('=', keyStart);
        final String key = eq < 0 ? null : text.substring(keyStart, eq).strip();
        if (key == null || !isValidKey(key)) {
            return new Entry(comment ? Kind.COMMENT : Kind.OTHER, null, null, commentText, text, terminator, -1, -1);
        }
        final int valueStart = skipWhitespace(text, eq + 1);
        final String value = text.substring(valueStart).stripTrailing();
        return new Entry(comment ? Kind.COMMENT : Kind.PAIR, key, value, commentText, text, terminator, keyStart, valueStart);
    }

    private static int skipWhitespace(final String text, final int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isValidKey(final String key) {
        if (key == null || key.isEmpty() || key.charAt(0) == '#' || key.charAt(0) == '!') {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            final char c = key.charAt(i);
            if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }
}
