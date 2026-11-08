package tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * MapleStory's "shanda" custom encryption, the second layer under AES-OFB.
 *
 * <p>Both call sites are commented out ({@code MaplePacketDecoder} line 68 and
 * {@code MaplePacketEncoder} line 64), so nothing in the running server reaches
 * this class today. It is pinned anyway, because re-enabling those two lines is
 * a one-character change, and a pair that does not round trip would otherwise be
 * discovered by a client that can no longer log in.
 *
 * <p>Expected byte arrays here are <b>behaviour snapshots</b> taken from this
 * implementation, not a specification.
 */
class MapleCustomEncryptionTest {

    private static final byte[] SAMPLE = {0x01, 0x02, 0x03, 0x04, 0x05};

    @Test
    @DisplayName("behaviour snapshot: encryptData")
    void knownVectorEncrypt() {
        final byte[] expected = {(byte) 0x18, (byte) 0x03, (byte) 0xA1, (byte) 0x9E, (byte) 0xF5};
        assertArrayEquals(expected, MapleCustomEncryption.encryptData(SAMPLE.clone()));
    }

    @Test
    @DisplayName("behaviour snapshot: decryptData")
    void knownVectorDecrypt() {
        final byte[] expected = {(byte) 0xFE, (byte) 0x0F, (byte) 0xEB, (byte) 0x96, (byte) 0x98};
        assertArrayEquals(expected, MapleCustomEncryption.decryptData(SAMPLE.clone()));
    }

    @Test
    @DisplayName("both directions mutate in place and return the same array")
    void bothDirectionsAreInPlace() {
        final byte[] data = SAMPLE.clone();
        assertTrue(data == MapleCustomEncryption.encryptData(data));
        assertFalse(Arrays.equals(SAMPLE, data), "encryptData must have changed the caller's array");

        final byte[] other = SAMPLE.clone();
        assertTrue(other == MapleCustomEncryption.decryptData(other));
    }

    @Test
    @DisplayName("an empty array is a no-op in both directions")
    void emptyArrayIsANoOp() {
        assertEquals(0, MapleCustomEncryption.encryptData(new byte[0]).length);
        assertEquals(0, MapleCustomEncryption.decryptData(new byte[0]).length);
    }

    @Test
    @DisplayName("encryptData then decryptData returns the original")
    void encryptThenDecryptRoundTrips() {
        assertArrayEquals(SAMPLE, MapleCustomEncryption.decryptData(MapleCustomEncryption.encryptData(SAMPLE.clone())));
    }

    @ParameterizedTest(name = "{0} bytes")
    @ValueSource(ints = {0, 1, 2, 5, 255, 256, 300})
    @DisplayName("encryptData then decryptData returns the original at every length")
    void encryptThenDecryptRoundTripsAtEveryLength(final int length) {
        // encryptData runs six rounds (`for (int j = 0; j < 6; j++)`), alternating
        // a forward pass and a backward pass; decryptData has to undo all six, in
        // reverse order, with the branch that matches each round's parity. It used
        // to run `for (int j = 1; j <= 1; j++)` - one round, which inverted only
        // encryptData's last one and left the other five on the data.
        //
        // Both directions work in place, so the original is kept as a copy. The
        // lengths either side of 256 matter because each round seeds itself with
        // `dataLength`, which is a byte and wraps there.
        final byte[] original = new byte[length];
        for (int i = 0; i < length; i++) {
            original[i] = (byte) (i * 7 + 3);
        }
        assertArrayEquals(original,
                MapleCustomEncryption.decryptData(MapleCustomEncryption.encryptData(original.clone())));
    }

    @Test
    @DisplayName("BitTools.rollLeft and rollRight are byte rotations, not shifts")
    void rollsAreRotations() {
        for (int i = 0; i < 256; i++) {
            final byte b = (byte) i;
            for (int n = 0; n < 8; n++) {
                assertEquals(b, BitTools.rollRight(BitTools.rollLeft(b, n), n),
                        "rollRight must undo rollLeft for value " + i + " count " + n);
            }
            assertEquals(b, BitTools.rollLeft(b, 8), "a full turn is the identity");
            assertEquals(b, BitTools.rollRight(b, 8), "a full turn is the identity");
        }
        // 0b1100_1101 rotated left three places is 0b0110_1110.
        assertEquals((byte) 0b0110_1110, BitTools.rollLeft((byte) 0b1100_1101, 3));
        assertEquals((byte) 0b1011_1001, BitTools.rollRight((byte) 0b1100_1101, 3));
    }

    @Test
    @DisplayName("BitTools.multiplyBytes repeats the prefix")
    void multiplyBytesRepeats() {
        assertArrayEquals(new byte[]{1, 2, 1, 2, 1, 2}, BitTools.multiplyBytes(new byte[]{1, 2, 3, 4}, 2, 3));
    }
}
