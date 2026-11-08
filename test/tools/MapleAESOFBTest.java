package tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import constants.ServerConstants;

/**
 * The wire cipher. This is the one thing in the tree that a client cannot
 * recover from if it regresses: {@code MaplePacketDecoder} drives a per-session
 * AES-OFB stream, so a single wrong byte desynchronises the session forever.
 *
 * <p>The "known vector" tests below are <b>behaviour snapshots, not a
 * specification</b>. They were produced by running this very implementation and
 * pinned so that a refactor which changes the bytes on the wire fails here
 * instead of in a v113 client. If one of them fails, the question to ask is
 * "what did I change?", not "is the expected value right?".
 *
 * <p>{@link ServerConstants#MAPLE_VERSION} is a compile-time constant
 * ({@code static final short}), so javac inlines it and no part of
 * {@code ServerConstants} is loaded at run time. That matters: several classes
 * near here reach {@code ServerProperties}, whose static initialiser queries the
 * database and calls {@code System.exit(0)} when it cannot.
 */
class MapleAESOFBTest {

    /** An arbitrary but fixed IV, so every expectation below is reproducible. */
    private static final byte[] IV = {0x12, 0x34, 0x56, 0x78};

    private static MapleAESOFB cipher() {
        // The constructor keeps the array it is handed, so every instance gets its own.
        return new MapleAESOFB(IV.clone(), ServerConstants.MAPLE_VERSION);
    }

    @Test
    @DisplayName("crypt is its own inverse when both sides start from the same IV")
    void cryptRoundTrips() {
        final byte[] plain = "MapleStory v113".getBytes(StandardCharsets.US_ASCII);

        final byte[] encrypted = cipher().crypt(plain.clone());
        assertFalse(java.util.Arrays.equals(plain, encrypted), "crypt must actually change the data");

        final byte[] decrypted = cipher().crypt(encrypted.clone());
        assertArrayEquals(plain, decrypted);
    }

    @Test
    @DisplayName("crypt round trips across the 0x5B0 / 0x5B4 block boundaries")
    void cryptRoundTripsALargePayload() {
        // The first block is 0x5B0 bytes and every later one 0x5B4; a payload that
        // crosses both boundaries is the only way to exercise that loop.
        final byte[] plain = new byte[0x5B0 + 0x5B4 + 17];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 31 + 7);
        }
        assertArrayEquals(plain, cipher().crypt(cipher().crypt(plain.clone())));
    }

    @Test
    @DisplayName("crypt mutates the array it is given and returns that same array")
    void cryptIsInPlace() {
        final byte[] data = {1, 2, 3, 4};
        final byte[] returned = cipher().crypt(data);
        assertSameArray(data, returned);
    }

    private static void assertSameArray(final byte[] expected, final byte[] actual) {
        assertTrue(expected == actual, "crypt is documented as in-place; callers rely on the returned array being the argument");
    }

    @Test
    @DisplayName("the caller's IV array is never written to")
    void theCallersIvIsNotMutated() {
        final byte[] callersIv = IV.clone();
        final MapleAESOFB c = new MapleAESOFB(callersIv, ServerConstants.MAPLE_VERSION);
        c.crypt(new byte[]{1, 2, 3});
        assertArrayEquals(IV, callersIv);
    }

    @Test
    @DisplayName("crypt advances the IV, so the same plaintext encrypts differently next time")
    void cryptAdvancesTheIv() {
        final MapleAESOFB c = cipher();
        final byte[] plain = "MapleStory".getBytes(StandardCharsets.US_ASCII);

        final byte[] first = c.crypt(plain.clone());
        final byte[] second = c.crypt(plain.clone());

        assertFalse(java.util.Arrays.equals(first, second),
                "a stream cipher that reuses its keystream is not a stream cipher");
        // Two calls, so the IV has been stepped twice.
        assertArrayEquals(MapleAESOFB.getNewIv(MapleAESOFB.getNewIv(IV)), c.getIv());
    }

    @Test
    @DisplayName("an empty payload still advances the IV")
    void emptyPayloadStillAdvancesTheIv() {
        final MapleAESOFB c = cipher();
        assertEquals(0, c.crypt(new byte[0]).length);
        assertArrayEquals(MapleAESOFB.getNewIv(IV), c.getIv());
    }

    @Test
    @DisplayName("behaviour snapshot: crypt of a fixed plaintext under a fixed IV")
    void knownVectorCrypt() {
        // Snapshot, not a specification - see the class comment.
        final byte[] expected = {
            (byte) 0xAF, (byte) 0x71, (byte) 0xBA, (byte) 0x93, (byte) 0x6F,
            (byte) 0x30, (byte) 0x2E, (byte) 0x05, (byte) 0xB5, (byte) 0x4C};
        assertArrayEquals(expected, cipher().crypt("MapleStory".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @DisplayName("behaviour snapshot: getNewIv")
    void knownVectorGetNewIv() {
        // Snapshot, not a specification - see the class comment.
        final byte[] expected = {(byte) 0xDD, (byte) 0xEF, (byte) 0x64, (byte) 0x61};
        assertArrayEquals(expected, MapleAESOFB.getNewIv(IV.clone()));
    }

    @Test
    @DisplayName("getNewIv depends only on the old IV, and does not write to it")
    void getNewIvIsPure() {
        final byte[] old = IV.clone();
        final byte[] a = MapleAESOFB.getNewIv(old);
        final byte[] b = MapleAESOFB.getNewIv(old);
        assertArrayEquals(IV, old);
        assertArrayEquals(a, b);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 10, 255, 256, 4096, 0xFFFF})
    @DisplayName("a header round trips back to the length it encodes")
    void headerRoundTripsThroughGetPacketLength(final int length) {
        final byte[] header = cipher().getPacketHeader(length);
        assertEquals(4, header.length);
        assertEquals(length, MapleAESOFB.getPacketLength(asInt(header)));
    }

    @Test
    @DisplayName("checkPacket accepts a header this instance produced")
    void checkPacketAcceptsItsOwnHeader() {
        final MapleAESOFB c = cipher();
        final byte[] header = c.getPacketHeader(42);
        assertTrue(c.checkPacket(header));
        assertTrue(c.checkPacket(asInt(header)));
    }

    @Test
    @DisplayName("checkPacket rejects a header built for another MAPLE_VERSION")
    void checkPacketRejectsAForeignVersion() {
        final byte[] header = cipher().getPacketHeader(42);
        final MapleAESOFB other = new MapleAESOFB(IV.clone(), (short) 1);
        assertFalse(other.checkPacket(header));
        assertFalse(other.checkPacket(asInt(header)));
    }

    @Test
    @DisplayName("getPacketHeader does not advance the IV")
    void headerDoesNotAdvanceTheIv() {
        final MapleAESOFB c = cipher();
        c.getPacketHeader(1);
        c.getPacketHeader(2);
        assertArrayEquals(IV, c.getIv());
    }

    @Test
    @DisplayName("toString shows the IV as hex")
    void toStringShowsTheIv() {
        assertEquals("IV: " + HexTool.toString(IV), cipher().toString());
    }

    /** The four header bytes as MaplePacketDecoder packs them: big-endian. */
    private static int asInt(final byte[] header) {
        return ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
    }
}
