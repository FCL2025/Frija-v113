package tools.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.Encodings;
import tools.HexTool;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericLittleEndianAccessor;
import tools.data.input.GenericSeekableLittleEndianAccessor;
import tools.data.output.BAOSByteOutputStream;
import tools.data.output.GenericLittleEndianWriter;

/**
 * Writer/accessor round trips - the two halves of every packet in the tree.
 *
 * <p>These use {@link GenericLittleEndianWriter} directly rather than
 * {@code MaplePacketLittleEndianWriter}: the latter has a static field
 * initialised from {@code ServerProperties.getProperty("tms.Debug")}, and
 * {@code ServerProperties}' static initialiser queries {@code
 * auth_server_channel_ip} and calls {@code System.exit(0)} when the database is
 * not there. Loading that class from a test would take the JVM down mid-run.
 * The two classes share all of the writing code, so nothing is lost.
 */
class LittleEndianRoundTripTest {

    private ByteArrayOutputStream sink;
    private GenericLittleEndianWriter writer;

    private GenericLittleEndianWriter writer() {
        sink = new ByteArrayOutputStream();
        writer = new GenericLittleEndianWriter(new BAOSByteOutputStream(sink));
        return writer;
    }

    /**
     * A writer over a sink of a chosen starting size, for the growth tests.
     */
    private GenericLittleEndianWriter writer(final int initialCapacity) {
        sink = new ByteArrayOutputStream(initialCapacity);
        writer = new GenericLittleEndianWriter(new BAOSByteOutputStream(sink));
        return writer;
    }

    private GenericLittleEndianAccessor reader() {
        return new GenericLittleEndianAccessor(new ByteArrayByteStream(sink.toByteArray()));
    }

    /**
     * The accessor a packet handler is really handed: {@code
     * handling.MapleServerHandler.messageReceived} builds one of these per
     * inbound packet.
     */
    private GenericSeekableLittleEndianAccessor seekableReader() {
        return new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream(sink.toByteArray()));
    }

    @Test
    @DisplayName("bytes, shorts and ints round trip, including the signed extremes")
    void scalarsRoundTrip() {
        final GenericLittleEndianWriter w = writer();
        w.write((byte) -1);
        w.write((byte) 0x7F);
        w.writeShort(Short.MIN_VALUE);
        w.writeShort(Short.MAX_VALUE);
        w.writeShort(-1);
        w.writeInt(Integer.MIN_VALUE);
        w.writeInt(Integer.MAX_VALUE);
        w.writeInt(-1);

        final GenericLittleEndianAccessor r = reader();
        assertEquals((byte) -1, r.readByte());
        assertEquals((byte) 0x7F, r.readByte());
        assertEquals(Short.MIN_VALUE, r.readShort());
        assertEquals(Short.MAX_VALUE, r.readShort());
        assertEquals((short) -1, r.readShort());
        assertEquals(Integer.MIN_VALUE, r.readInt());
        assertEquals(Integer.MAX_VALUE, r.readInt());
        assertEquals(-1, r.readInt());
        assertEquals(0, r.available());
    }

    @Test
    @DisplayName("readByteAsInt is the unsigned view of readByte")
    void readByteAsIntIsUnsigned() {
        writer().write((byte) 0xFE);
        assertEquals(0xFE, reader().readByteAsInt());
    }

    @Test
    @DisplayName("the byte order really is little-endian")
    void byteOrderIsLittleEndian() {
        writer().writeInt(0x01020304);
        assertArrayEquals(new byte[]{0x04, 0x03, 0x02, 0x01}, sink.toByteArray());

        writer().writeShort(0x0102);
        assertArrayEquals(new byte[]{0x02, 0x01}, sink.toByteArray());

        writer().writeLong(0x0102030405060708L);
        assertArrayEquals(new byte[]{0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01}, sink.toByteArray());
    }

    @Test
    @DisplayName("a float round trips through writeInt/readFloat")
    void floatRoundTrips() {
        writer().writeInt(Float.floatToIntBits(1.5f));
        assertEquals(1.5f, reader().readFloat());
    }

    @Test
    @DisplayName("writeZeroBytes and read(n)")
    void bulkBytesRoundTrip() {
        final GenericLittleEndianWriter w = writer();
        w.write(new byte[]{1, 2, 3});
        w.writeZeroBytes(4);
        assertArrayEquals(new byte[]{1, 2, 3, 0, 0, 0, 0}, reader().read(7));
    }

    @Test
    @DisplayName("skip advances the reader without changing what follows")
    void skipWorks() {
        final GenericLittleEndianWriter w = writer();
        w.writeInt(0xDEADBEEF);
        w.writeInt(7);
        final GenericLittleEndianAccessor r = reader();
        r.skip(4);
        assertEquals(7, r.readInt());
        assertEquals(8, r.getBytesRead());
    }

    @Test
    @DisplayName("a Point round trips as two shorts")
    void pointRoundTrips() {
        writer().writePos(new Point(-5, 1000));
        assertEquals(new Point(-5, 1000), reader().readPos());
    }

    // ---------------------------------------------------------------------
    // Strings. Everything the client sees is BIG5 - see tools.Encodings.
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "abc", "楓之谷", "台服 v113", "唐唐償庫", "混合 mixed 字串 123"})
    @DisplayName("writeMapleAsciiString / readMapleAsciiString round trip, Chinese included")
    void mapleAsciiStringRoundTrips(final String s) {
        writer().writeMapleAsciiString(s);
        assertEquals(s, reader().readMapleAsciiString());
    }

    @Test
    @DisplayName("a Maple string is a BIG5 *byte* count followed by BIG5 bytes")
    void mapleAsciiStringIsBig5OnTheWire() {
        // 楓之谷 is three characters but six BIG5 bytes; the length prefix counts
        // bytes, not characters. Getting this wrong is how a JEP 400 default
        // charset silently corrupts every name on the wire.
        final String s = "楓之谷";
        assertEquals(3, s.length());
        assertEquals(6, s.getBytes(Encodings.BIG5).length);

        writer().writeMapleAsciiString(s);
        final byte[] wire = sink.toByteArray();
        assertEquals(2 + 6, wire.length);
        assertEquals(6, (wire[0] & 0xFF) | ((wire[1] & 0xFF) << 8));
        assertArrayEquals(s.getBytes(Encodings.BIG5), java.util.Arrays.copyOfRange(wire, 2, wire.length));
    }

    @Test
    @DisplayName("GenericLittleEndianWriter.getlength measures BIG5 bytes")
    void getlengthMeasuresBig5Bytes() {
        assertEquals(0, GenericLittleEndianWriter.getlength(""));
        assertEquals(3, GenericLittleEndianWriter.getlength("abc"));
        assertEquals(6, GenericLittleEndianWriter.getlength("楓之谷"));
        assertEquals(9, GenericLittleEndianWriter.getlength("台服 v113"));
    }

    @Test
    @DisplayName("writeAsciiString(s, max) zero-pads to max")
    void fixedWidthAsciiStringIsZeroPadded() {
        writer().writeAsciiString("abc", 6);
        assertArrayEquals(new byte[]{'a', 'b', 'c', 0, 0, 0}, sink.toByteArray());
    }

    @Test
    @DisplayName("readAsciiString reads exactly n bytes and decodes them as BIG5")
    void readAsciiStringReadsFixedWidth() {
        writer().write("楓之谷".getBytes(Encodings.BIG5));
        assertEquals("楓之", reader().readAsciiString(4));
    }

    // ---------------------------------------------------------------------
    // readLong and readDouble. readLong has to do its shifts in long: Java
    // masks an int shift distance to five bits, so an int-width `byte8 << 56`
    // is really `byte8 << 24`, which used to fold the high four bytes onto the
    // low four and left readDouble - which is built on readLong - unable to
    // reproduce a double at all.
    // ---------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(longs = {0L, 1L, 123456789L, Integer.MAX_VALUE})
    @DisplayName("writeLong/readLong round trip for values that fit in a positive int")
    void longRoundTripsWithinIntRange(final long value) {
        writer().writeLong(value);
        assertEquals(value, reader().readLong());
    }

    @Test
    @DisplayName("readLong keeps the top four bytes")
    void readLongKeepsTheTopFourBytes() {
        // The eight bytes go out low-first, so the *last* byte on the wire is the
        // most significant one and has to be shifted 56 places. Doing that in int
        // arithmetic used to add it onto the low four bytes instead, so
        // 0x0102030405060708 read back as 0x0000000006080A0C and -1L as -2.
        writer().writeLong(0x0102030405060708L);
        assertArrayEquals(new byte[]{0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01}, sink.toByteArray());
        assertEquals(0x0102030405060708L, reader().readLong());

        writer().writeLong(-1L);
        assertEquals(-1L, reader().readLong());

        writer().writeLong(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, reader().readLong());
    }

    @Test
    @DisplayName("(int) readLong() is the low four bytes, which is all any caller in the tree wants")
    void castingReadLongToIntKeepsTheLowFourBytes() {
        // Every in-tree caller writes `(int) slea.readLong()` on a cash-item id.
        // Those ids fit in 32 bits, so the client sends them with the high four
        // bytes zero and the cast reads exactly the same as it did before readLong
        // was fixed; when the high bytes are *not* zero the cast still truncates
        // to the low four, where the folding used to corrupt them.
        writer().writeLong(0x00000000_1234ABCDL);
        assertEquals(0x1234ABCD, (int) reader().readLong());

        writer().writeLong(0x7EDCBA98_1234ABCDL);
        assertEquals(0x1234ABCD, (int) reader().readLong());
    }

    @Test
    @DisplayName("writeLong/readLong round trip for every long")
    void longRoundTripsForAllValues() {
        for (final long value : new long[]{Long.MIN_VALUE, -1L, 0x0102030405060708L, Long.MAX_VALUE}) {
            writer().writeLong(value);
            assertEquals(value, reader().readLong());
        }
    }

    @Test
    @DisplayName("a double round trips through writeLong/readDouble")
    void doubleRoundTrips() {
        writer().writeLong(Double.doubleToLongBits(3.5d));
        assertEquals(3.5d, reader().readDouble());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(doubles = {0.0d, -0.5d, -1234.5678d, Double.MIN_VALUE, Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("readDouble reproduces the awkward doubles too")
    void doubleRoundTripsForTheAwkwardValues(final double value) {
        // A negative double has bit 63 set and Double.MIN_VALUE is nothing *but*
        // its lowest bit, so between them these cover both ends of the 64 bits.
        writer().writeLong(Double.doubleToLongBits(value));
        assertEquals(value, reader().readDouble());
    }

    @Test
    @DisplayName("available() counts down as the accessor reads")
    void availableCountsDown() {
        writer().writeInt(1);
        final GenericLittleEndianAccessor r = reader();
        assertEquals(4, r.available());
        r.readByte();
        assertEquals(3, r.available());
        assertTrue(r.toString().startsWith("Data: "));
    }

    @Test
    @DisplayName("readChar is the unsigned 16-bit view of readShort")
    void readCharRoundTrips() {
        writer().writeShort(0xFFFE);
        assertEquals((char) 0xFFFE, reader().readChar());
    }

    @Test
    @DisplayName("toString(true) shows the whole packet and the unread tail")
    void toStringShowsAllAndNow() {
        writer().writeInt(0x04030201);
        final GenericLittleEndianAccessor r = reader();
        r.readShort();
        final String dump = r.toString(true);
        assertTrue(dump.startsWith("All: " + HexTool.toString(new byte[]{0x01, 0x02, 0x03, 0x04})), dump);
        assertTrue(dump.contains("\nNow: "), dump);
        assertEquals("Data: " + HexTool.toString(new byte[]{0x03, 0x04}), r.toString());
    }

    // ---------------------------------------------------------------------
    // Growth. A packet's size is not known up front - the character list and
    // the cash shop catalogue run to tens of kilobytes - so the sink has to
    // grow as it is written. This is why the writer's backing store stays a
    // ByteArrayOutputStream instead of becoming a fixed-capacity buffer.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a packet far larger than the initial capacity round trips intact")
    void writerGrowsPastItsInitialCapacity() {
        final int count = 40_000; // 160 KB out of a four-byte sink
        final GenericLittleEndianWriter w = writer(4);
        for (int i = 0; i < count; i++) {
            w.writeInt(i);
        }
        assertEquals(count * 4, sink.size());

        final GenericLittleEndianAccessor r = reader();
        for (int i = 0; i < count; i++) {
            assertEquals(i, r.readInt(), "int " + i);
        }
        assertEquals(0, r.available());
    }

    @Test
    @DisplayName("a value that straddles a resize boundary keeps its byte order")
    void growthKeepsByteOrderAcrossAResize() {
        // 32 is MaplePacketLittleEndianWriter's default starting size, so this
        // long has four bytes on each side of the first resize.
        final GenericLittleEndianWriter w = writer(32);
        w.writeZeroBytes(28);
        w.writeLong(0x0102030405060708L);
        assertEquals(36, sink.size());

        final GenericLittleEndianAccessor r = reader();
        r.skip(28);
        assertEquals(0x0102030405060708L, r.readLong());
    }

    @Test
    @DisplayName("a long BIG5 string survives several resizes")
    void longBig5StringRoundTrips() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("楓之谷");
        }
        final String s = sb.toString();
        assertEquals(12000, s.getBytes(Encodings.BIG5).length);

        writer(8).writeMapleAsciiString(s);
        assertEquals(2 + 12000, sink.size());
        assertEquals(s, reader().readMapleAsciiString());
    }

    // ---------------------------------------------------------------------
    // Seeking. GenericSeekableLittleEndianAccessor differs from its parent in
    // exactly one way, and that difference is the reason it still exists.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("seek rewinds and the same bytes read again")
    void seekRewinds() {
        final GenericLittleEndianWriter w = writer();
        w.writeInt(0x11223344);
        w.writeInt(0x55667788);

        final GenericSeekableLittleEndianAccessor r = seekableReader();
        assertEquals(0, r.getPosition());
        assertEquals(0x11223344, r.readInt());
        assertEquals(4, r.getPosition());

        // MobHandler's pattern: back up by the bytes just consumed.
        r.seek(r.getPosition() - 4);
        assertEquals(0, r.getPosition());
        assertEquals(0x11223344, r.readInt());
        assertEquals(0x55667788, r.readInt());
        assertEquals(8, r.getPosition());
        assertEquals(0, r.available());
    }

    @Test
    @DisplayName("seek to either end of the packet, and to the last byte")
    void seekToTheBoundaries() {
        writer().writeInt(0x11223344);
        final GenericSeekableLittleEndianAccessor r = seekableReader();

        r.seek(4);
        assertEquals(4, r.getPosition());
        assertEquals(0, r.available());

        r.seek(0);
        assertEquals(0, r.getPosition());
        assertEquals(4, r.available());
        assertEquals(0x11223344, r.readInt());

        r.seek(3);
        assertEquals(1, r.available());
        assertEquals((byte) 0x11, r.readByte());
        assertEquals(0, r.available());
    }

    @Test
    @DisplayName("the seekable accessor skips by jumping, the plain one by reading")
    void skipDiffersBetweenTheTwoAccessors() {
        // The one behavioural difference between the two classes: a jump does
        // not count the bytes it passes over towards getBytesRead().
        final GenericLittleEndianWriter w = writer();
        w.writeInt(1);
        w.writeInt(2);

        final GenericSeekableLittleEndianAccessor seekable = seekableReader();
        seekable.skip(4);
        assertEquals(4, seekable.getPosition());
        assertEquals(2, seekable.readInt());
        assertEquals(4, seekable.getBytesRead());

        final GenericLittleEndianAccessor plain = reader();
        plain.skip(4);
        assertEquals(2, plain.readInt());
        assertEquals(8, plain.getBytesRead());
    }

    @Test
    @DisplayName("reading past the end throws ArrayIndexOutOfBoundsException")
    void readingPastTheEndThrows() {
        // PlayerHandler and MobHandler wrap MovementParse in a catch of exactly
        // this type to reject a truncated packet, so neither read(n) nor skip(n)
        // may be rewritten into an unchecked bulk copy or an arithmetic jump.
        writer().writeShort(1);
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader().readInt());
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader().read(3));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader().skip(3));
        assertThrows(ArrayIndexOutOfBoundsException.class, () -> reader().readLong());
    }

    // ---------------------------------------------------------------------
    // readLong and readDouble, bit by bit.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("every single bit of a long survives the round trip")
    void everyBitOfALongRoundTrips() {
        // One bit set per iteration over all 64 positions. This fails on any
        // shift that is off by a byte, including the int-width folding readLong
        // used to do to everything above bit 31.
        for (int bit = 0; bit < 64; bit++) {
            final long value = 1L << bit;
            writer().writeLong(value);
            assertEquals(value, reader().readLong(), "bit " + bit);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(longs = {Long.MIN_VALUE + 1, -2L, 0x00000000FFFFFFFFL, 0x0000000100000000L,
        0xFF00000000000000L, 0x00FF000000000000L, Long.MAX_VALUE - 1})
    @DisplayName("readLong reproduces the boundary values around the 32-bit seam")
    void readLongBoundaryValues(final long value) {
        writer().writeLong(value);
        assertEquals(value, reader().readLong());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(doubles = {-0.0d, Double.MIN_NORMAL, -Double.MAX_VALUE, Double.NaN, 1.0d / 3.0d})
    @DisplayName("readDouble reproduces the remaining boundary values")
    void readDoubleBoundaryValues(final double value) {
        // JUnit compares doubles by their bits, so -0.0 does not equal 0.0 here
        // and NaN does equal NaN - which is exactly the comparison that matters
        // for a round trip.
        writer().writeLong(Double.doubleToLongBits(value));
        assertEquals(value, reader().readDouble());
    }

    // ---------------------------------------------------------------------
    // The remaining BIG5 and fixed-width string corners.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("an empty maple string is just a zero length prefix")
    void emptyMapleStringIsTwoZeroBytes() {
        writer().writeMapleAsciiString("");
        assertArrayEquals(new byte[]{0, 0}, sink.toByteArray());
        assertEquals("", reader().readMapleAsciiString());
    }

    @Test
    @DisplayName("readAsciiString(0) reads nothing and leaves the cursor alone")
    void readAsciiStringOfZeroLength() {
        writer().writeInt(7);
        final GenericLittleEndianAccessor r = reader();
        assertEquals("", r.readAsciiString(0));
        assertEquals(7, r.readInt());
    }

    @Test
    @DisplayName("writeAsciiString(s, max) truncates when the string overflows the field")
    void fixedWidthAsciiStringTruncates() {
        // Six ASCII bytes into a four-byte field: cut to four, nothing padded.
        writer().writeAsciiString("abcdef", 4);
        assertArrayEquals(new byte[]{'a', 'b', 'c', 'd'}, sink.toByteArray());
    }

    @Test
    @DisplayName("a BIG5 name fills a fixed-width field byte for byte")
    void fixedWidthAsciiStringPadsBig5() {
        // 15 is the party/buddy name field. 楓之谷 is six BIG5 bytes, so nine
        // zeroes follow - the padding is counted in bytes, not characters.
        writer().writeAsciiString("楓之谷", 15);
        final byte[] wire = sink.toByteArray();
        assertEquals(15, wire.length);
        assertArrayEquals("楓之谷".getBytes(Encodings.BIG5), java.util.Arrays.copyOfRange(wire, 0, 6));
        assertArrayEquals(new byte[9], java.util.Arrays.copyOfRange(wire, 6, 15));
    }

    @Test
    @DisplayName("writeAsciiString(s) writes BIG5 bytes with no length prefix")
    void unprefixedAsciiStringIsBig5() {
        writer().writeAsciiString("台服 v113");
        assertArrayEquals("台服 v113".getBytes(Encodings.BIG5), sink.toByteArray());
        assertEquals("台服 v113", reader().readAsciiString(9));
    }
}
