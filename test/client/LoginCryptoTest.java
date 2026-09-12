package client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Password hashing and comparison. This is the login path: if it regresses,
 * nobody can log in, and if it regresses the other way everybody can.
 */
class LoginCryptoTest {

    @Test
    @DisplayName("hexSha1 is lowercase hex SHA-1 of the ASCII input")
    void hexSha1IsSha1() {
        // The canonical SHA-1 of "test" - this one really is a specification.
        assertEquals("a94a8fe5ccb19ba61c4c0873d391e987982fbbd3", LoginCrypto.hexSha1("test"));
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", LoginCrypto.hexSha1(""));
        assertTrue(LoginCrypto.hexSha1("test").matches("[0-9a-f]{40}"));
    }

    @Test
    @DisplayName("makeSaltedSha1Hash is plain (unsalted) SHA-1, despite the name")
    void makeSaltedSha1HashIsUnsalted() {
        assertEquals(LoginCrypto.hexSha1("hunter2"), LoginCrypto.makeSaltedSha1Hash("hunter2"));
    }

    @Test
    @DisplayName("checkSha1Hash accepts the right password and rejects everything else")
    void checkSha1Hash() {
        final String stored = LoginCrypto.makeSaltedSha1Hash("hunter2");
        assertTrue(LoginCrypto.checkSha1Hash(stored, "hunter2"));
        assertFalse(LoginCrypto.checkSha1Hash(stored, "hunter3"));
        assertFalse(LoginCrypto.checkSha1Hash(stored, ""));
        assertFalse(LoginCrypto.checkSha1Hash(stored, "hunter2 "));
    }

    @Test
    @DisplayName("makeSaltedSha512Hash is SHA-512 of password + salt")
    void makeSaltedSha512Hash() throws Exception {
        assertEquals(sha512Hex("hunter2" + "0123456789abcdef"),
                LoginCrypto.makeSaltedSha512Hash("hunter2", "0123456789abcdef"));
        assertTrue(LoginCrypto.makeSaltedSha512Hash("a", "b").matches("[0-9a-f]{128}"));
    }

    @Test
    @DisplayName("checkSaltedSha512Hash needs both the password and the salt")
    void checkSaltedSha512Hash() {
        final String salt = LoginCrypto.makeSalt();
        final String stored = LoginCrypto.makeSaltedSha512Hash("hunter2", salt);

        assertTrue(LoginCrypto.checkSaltedSha512Hash(stored, "hunter2", salt));
        assertFalse(LoginCrypto.checkSaltedSha512Hash(stored, "hunter3", salt));
        assertFalse(LoginCrypto.checkSaltedSha512Hash(stored, "hunter2", LoginCrypto.makeSalt()));
    }

    // ---------------------------------------------------------------------
    // Constant-time comparison.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("comparison is exact: a prefix of the stored hash is not accepted")
    void aPrefixOfTheHashIsNotAccepted() {
        // MessageDigest.isEqual returns false for a length mismatch without
        // looking at the contents, so this is also the length check.
        final String stored = LoginCrypto.makeSaltedSha1Hash("hunter2");
        assertFalse(LoginCrypto.checkSha1Hash(stored.substring(0, 39), "hunter2"));
        assertFalse(LoginCrypto.checkSha1Hash(stored + "0", "hunter2"));
        assertFalse(LoginCrypto.checkSha1Hash("", "hunter2"));
    }

    @Test
    @DisplayName("comparison is case sensitive - the stored hash must already be lowercase")
    void comparisonIsCaseSensitive() {
        final String stored = LoginCrypto.makeSaltedSha1Hash("hunter2");
        assertNotEquals(stored, stored.toUpperCase());
        assertFalse(LoginCrypto.checkSha1Hash(stored.toUpperCase(), "hunter2"));
    }

    @Test
    @DisplayName("a null stored hash still throws, as String.equals did")
    void nullHashThrows() {
        // Documented on LoginCrypto.hashEquals: both callers reject a null row
        // before they get here, and MessageDigest.isEqual would have answered
        // false rather than throwing, which would be a worse failure mode.
        assertThrows(NullPointerException.class, () -> LoginCrypto.checkSha1Hash(null, "hunter2"));
    }

    // ---------------------------------------------------------------------
    // The documented char-count-vs-byte-count quirk in hashWithDigest.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a non-ASCII password is hashed only as far as its character count in bytes")
    void nonAsciiPasswordsAreTruncatedToTheirCharacterCount() throws Exception {
        // hashWithDigest does `Digester.update(in.getBytes("UTF-8"), 0, in.length())`
        // - a char count applied to a byte array. "密碼" is two characters and six
        // UTF-8 bytes, so only the first two bytes reach the digest. The source
        // comment says this is kept on purpose: correcting it would invalidate the
        // stored hash of every account with a non-ASCII password.
        final byte[] utf8 = "密碼".getBytes(StandardCharsets.UTF_8);
        assertEquals(6, utf8.length);
        assertEquals(2, "密碼".length());

        final MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(Arrays.copyOf(utf8, 2));
        assertEquals(hex(md.digest()), LoginCrypto.hexSha1("密碼"));
    }

    @Test
    @DisplayName("two different non-ASCII passwords sharing a UTF-8 prefix hash the same")
    void differentNonAsciiPasswordsCanCollide() {
        // 密 is U+5BC6 (E5 AF 86) and 寇 is U+5BC7 (E5 AF 87): the same first two
        // UTF-8 bytes, which is all that gets hashed for a two-character password.
        // The direct consequence of the quirk above, pinned so that it is a known
        // property of the tree and not a surprise during an incident.
        assertEquals(LoginCrypto.hexSha1("密碼"), LoginCrypto.hexSha1("寇碼"));
        assertTrue(LoginCrypto.checkSha1Hash(LoginCrypto.makeSaltedSha1Hash("密碼"), "寇碼"));

        // ASCII passwords are unaffected, because there one char is one byte.
        assertNotEquals(LoginCrypto.hexSha1("ab"), LoginCrypto.hexSha1("ac"));
    }

    // ---------------------------------------------------------------------
    // Salts and the other generators.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("makeSalt returns 32 lowercase hex characters and does not repeat")
    void makeSalt() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            final String salt = LoginCrypto.makeSalt();
            assertTrue(salt.matches("[0-9a-f]{32}"), "not 16 bytes of lowercase hex: " + salt);
            assertTrue(seen.add(salt), "makeSalt repeated itself: " + salt);
        }
    }

    @Test
    @DisplayName("Generate_13DigitAsiasoftPassport is letter + 11 digits + letter, and never uses 0")
    void passport() {
        for (int i = 0; i < 32; i++) {
            final String passport = LoginCrypto.Generate_13DigitAsiasoftPassport();
            assertEquals(13, passport.length());
            // The digit table is "1".."9" - there is no zero in it.
            assertTrue(passport.matches("[A-Z][1-9]{11}[A-Z]"), passport);
        }
    }

    @Test
    @DisplayName("rand_r undoes rand_s for a 128-character input")
    void randSAndRandR() {
        // rand_r is hardcoded to substring(6, 6 + 128), so it only inverts rand_s
        // for an input of exactly 128 characters - which is what its one caller
        // passes (a hex SHA-512).
        final String body = "f".repeat(128);
        final String wrapped = LoginCrypto.rand_s(body);
        assertEquals(6 + 128, wrapped.length());
        assertEquals(body, LoginCrypto.rand_r(wrapped));
    }

    @Test
    @DisplayName("rand_r on a shorter string runs off the end")
    void randROnAShortStringThrows() {
        assertThrows(StringIndexOutOfBoundsException.class, () -> LoginCrypto.rand_r(LoginCrypto.rand_s("short")));
    }

    private static String sha512Hex(final String in) throws Exception {
        final MessageDigest md = MessageDigest.getInstance("SHA-512");
        // Mirrors hashWithDigest: the digest is fed in.length() bytes, and every
        // string used here is ASCII so the two counts agree.
        md.update(in.getBytes(StandardCharsets.UTF_8), 0, in.length());
        return hex(md.digest());
    }

    private static String hex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
