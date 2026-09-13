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

    // ---------------------------------------------------------------------
    // Legacy formats, pinned with fixed vectors.
    //
    // These are hardcoded on purpose rather than round-tripped through the
    // current code: a round trip passes even if both halves change together,
    // which is exactly the regression that would lock out every account that
    // has not logged in since. Each vector below was produced independently
    // (MessageDigest directly, or LoginCryptoLegacy.hashPassword) and is the
    // literal string such a row holds.
    // ---------------------------------------------------------------------

    /** SHA-1("hunter2"), i.e. the oldest scheme: no salt, one pass. */
    private static final String SHA1_HUNTER2 = "f3bbbd66a63d4bf1747940578ec3d0103530e21d";
    /** SHA-512("hunter2" + "0123456789abcdef"), the scheme before PBKDF2. */
    private static final String SHA512_HUNTER2 =
            "43b954d21dda034a3e312728835786450019d426608b49703ce2a8e2ac8ccc56"
            + "ef56718ddc35c5bb9a5ca73925df8bed8ceb1f4e4e351ef6278eb1a7df98a478";
    private static final String SHA512_SALT = "0123456789abcdef";
    /** An OdinMS $H$ hash of "hunter2", from LoginCryptoLegacy.hashPassword. */
    private static final String LEGACY_H_HUNTER2 = "$H$cwKePXRFs2hcKB8xn8yIy1/mFTfFsWAwZY1i=";
    /**
     * SHA-1 of the first two UTF-8 bytes of "密碼" - what hashWithDigest actually
     * produces for it, and, because of the truncation, for "寇碼" as well.
     */
    private static final String SHA1_TRUNCATED_CJK = "ec57da331fc3d99cf0be7bf8748391658b18ff0a";

    @Test
    @DisplayName("legacy: an unsalted SHA-1 row written before PBKDF2 still verifies")
    void legacySha1VectorStillVerifies() {
        assertTrue(LoginCrypto.checkSha1Hash(SHA1_HUNTER2, "hunter2"));
        assertFalse(LoginCrypto.checkSha1Hash(SHA1_HUNTER2, "hunter3"));
    }

    @Test
    @DisplayName("legacy: a salted SHA-512 row written before PBKDF2 still verifies")
    void legacySaltedSha512VectorStillVerifies() {
        assertTrue(LoginCrypto.checkSaltedSha512Hash(SHA512_HUNTER2, "hunter2", SHA512_SALT));
        assertFalse(LoginCrypto.checkSaltedSha512Hash(SHA512_HUNTER2, "hunter3", SHA512_SALT));
        assertFalse(LoginCrypto.checkSaltedSha512Hash(SHA512_HUNTER2, "hunter2", "fedcba9876543210"));
    }

    @Test
    @DisplayName("legacy: an OdinMS $H$ row still verifies")
    void legacyOdinmsHashVectorStillVerifies() {
        assertTrue(LoginCryptoLegacy.isLegacyPassword(LEGACY_H_HUNTER2));
        assertTrue(LoginCryptoLegacy.checkPassword("hunter2", LEGACY_H_HUNTER2));
        assertFalse(LoginCryptoLegacy.checkPassword("hunter3", LEGACY_H_HUNTER2));
    }

    @Test
    @DisplayName("legacy: a non-ASCII row still verifies against its truncated hash")
    void legacyTruncatedNonAsciiVectorStillVerifies() {
        // The point of A12: this row really is the hash of two bytes, so the
        // truncating code has to stay to let its owner back in at all.
        assertTrue(LoginCrypto.checkSha1Hash(SHA1_TRUNCATED_CJK, "密碼"));
        // ...and, unavoidably, the colliding password gets in too.
        assertTrue(LoginCrypto.checkSha1Hash(SHA1_TRUNCATED_CJK, "寇碼"));
    }

    @Test
    @DisplayName("the four stored formats cannot be mistaken for one another")
    void theFourFormatsAreMutuallyDistinguishable() {
        // This is what makes MapleClient.passwordMatches' dispatch correct: it
        // tries PBKDF2 first and exclusively, then $H$, then the hex schemes.
        final String pbkdf2 = LoginCrypto.makePbkdf2Hash("hunter2", TEST_ITERATIONS);

        assertTrue(LoginCrypto.isPbkdf2Hash(pbkdf2));
        assertFalse(LoginCrypto.isPbkdf2Hash(SHA1_HUNTER2));
        assertFalse(LoginCrypto.isPbkdf2Hash(SHA512_HUNTER2));
        assertFalse(LoginCrypto.isPbkdf2Hash(LEGACY_H_HUNTER2));
        assertFalse(LoginCrypto.isPbkdf2Hash(null));

        // "$pb" is not "$H$", so the legacy branch never claims a PBKDF2 row.
        assertFalse(LoginCryptoLegacy.isLegacyPassword(pbkdf2));
        assertFalse(LoginCryptoLegacy.isLegacyPassword(SHA1_HUNTER2));
    }

    // ---------------------------------------------------------------------
    // PBKDF2 - the format new and re-verified passwords are written in.
    // ---------------------------------------------------------------------

    /**
     * The real default costs ~67 ms a call by design, so everything that does not
     * specifically test the default runs at this count instead. The default is
     * exercised once, in pbkdf2DefaultIsTheMeasuredCount.
     */
    private static final int TEST_ITERATIONS = 1000;

    /**
     * Fixed PBKDF2 vectors, iterations 1000, salt = the ASCII bytes of
     * "0123456789abcdef" (base64 "MDEyMzQ1Njc4OWFiY2RlZg"). Produced by
     * SecretKeyFactory directly and cross-checked against a hand-rolled RFC 8018
     * PBKDF2-HMAC-SHA512 over the password's full getBytes(UTF_8), which is what
     * pins the UTF-8 property below.
     */
    private static final String PBKDF2_HUNTER2 = "$pbkdf2$1000$MDEyMzQ1Njc4OWFiY2RlZg$"
            + "Xp/4UtI3VYuUskUJvg/ElBho/1QUob2t4wOqDH2dRs5/P6kzj5+E8oi97sPcw7P4ZvydZ2rKx3aQeetuxYWOOg";
    private static final String PBKDF2_MIMA = "$pbkdf2$1000$MDEyMzQ1Njc4OWFiY2RlZg$"
            + "2GEJGz48fzQmgbGv08g0BsStSuOPabhvpSKcT4NySELxu0D6ZKtyvl8og1VKejZOSNem6LPP58Me6CvMQBXxiQ";
    private static final String PBKDF2_KOUMA = "$pbkdf2$1000$MDEyMzQ1Njc4OWFiY2RlZg$"
            + "Y3PySvXNOUclOzEBZXwUHjCNNUyNS9TblJYTd++tmEeL33oBpbw7PD4GB3mDyZM/C51jRkirWDtjOgAGroTrPg";

    @Test
    @DisplayName("PBKDF2 round trip: the right password verifies and nothing else does")
    void pbkdf2RoundTrip() {
        final String stored = LoginCrypto.makePbkdf2Hash("hunter2", TEST_ITERATIONS);

        assertTrue(LoginCrypto.checkPbkdf2Hash(stored, "hunter2"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, "hunter3"), "one character different");
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, "Hunter2"), "case change");
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, "hunter2 "), "trailing space");
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, "hunter"), "a prefix of the password");
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, ""));
        // Unlike checkSha1Hash, which throws on a null (pinned above), the new
        // entry point answers false for either argument being null.
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, null));
        assertFalse(LoginCrypto.checkPbkdf2Hash(null, "hunter2"));
    }

    @Test
    @DisplayName("PBKDF2 round trips an empty password and a long astral-plane one")
    void pbkdf2RoundTripsAwkwardPasswords() {
        final String empty = LoginCrypto.makePbkdf2Hash("", TEST_ITERATIONS);
        assertTrue(LoginCrypto.checkPbkdf2Hash(empty, ""));
        assertFalse(LoginCrypto.checkPbkdf2Hash(empty, "x"));

        final String wide = LoginCrypto.makePbkdf2Hash("pässwörd😀密碼", TEST_ITERATIONS);
        assertTrue(LoginCrypto.checkPbkdf2Hash(wide, "pässwörd😀密碼"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(wide, "pässwörd😀密"));
    }

    @Test
    @DisplayName("the stored string is self-describing and fits accounts.password")
    void pbkdf2StoredFormat() {
        final String stored = LoginCrypto.makePbkdf2Hash("hunter2", TEST_ITERATIONS);

        assertTrue(stored.startsWith(LoginCrypto.PBKDF2_PREFIX), stored);
        // $pbkdf2$<iterations>$<16 bytes b64 = 22 chars>$<64 bytes b64 = 86 chars>
        assertTrue(stored.matches("\\$pbkdf2\\$\\d+\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{86}"), stored);
        assertEquals(5, stored.split("\\$", -1).length);
        assertEquals(TEST_ITERATIONS, Integer.parseInt(stored.split("\\$", -1)[2]));
    }

    @Test
    @DisplayName("the default is the measured 50000 iterations and still fits varchar(128)")
    void pbkdf2DefaultIsTheMeasuredCount() {
        // The one test that pays the real cost (~67 ms per call on the machine the
        // count was measured on). Three calls, so about a fifth of a second.
        final String stored = LoginCrypto.makePbkdf2Hash("hunter2");

        assertTrue(stored.startsWith("$pbkdf2$50000$"), stored);
        // 8 + 5 + 1 + 22 + 1 + 86. accounts.password is varchar(128), which leaves
        // room for the count to reach seven digits before the column has to grow.
        assertEquals(123, stored.length(), stored);
        assertTrue(stored.length() <= 128, "accounts.password is varchar(128)");

        assertTrue(LoginCrypto.checkPbkdf2Hash(stored, "hunter2"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(stored, "hunter3"));
    }

    @Test
    @DisplayName("the iteration count is read back from the string, not from the constant")
    void pbkdf2IterationCountIsForwardCompatible() {
        // The fixed vectors are 1000 iterations, not today's 50000 default, and
        // they verify: raising the default later leaves every stored row working.
        assertTrue(PBKDF2_HUNTER2.startsWith("$pbkdf2$1000$"));
        assertTrue(LoginCrypto.checkPbkdf2Hash(PBKDF2_HUNTER2, "hunter2"));

        // A third, arbitrary count round-trips too.
        final String odd = LoginCrypto.makePbkdf2Hash("hunter2", 7777);
        assertTrue(odd.startsWith("$pbkdf2$7777$"), odd);
        assertTrue(LoginCrypto.checkPbkdf2Hash(odd, "hunter2"));

        // ...and the count really is used: changing it invalidates the hash.
        assertFalse(LoginCrypto.checkPbkdf2Hash(PBKDF2_HUNTER2.replace("$1000$", "$1001$"), "hunter2"));

        // Out-of-range counts are refused at write time rather than stored.
        assertThrows(IllegalArgumentException.class, () -> LoginCrypto.makePbkdf2Hash("hunter2", 0));
        assertThrows(IllegalArgumentException.class, () -> LoginCrypto.makePbkdf2Hash("hunter2", -1));
        assertThrows(IllegalArgumentException.class, () -> LoginCrypto.makePbkdf2Hash("hunter2", 10000001));
    }

    @Test
    @DisplayName("every hash of the same password gets its own salt")
    void pbkdf2SaltsDiffer() {
        final Set<String> strings = new HashSet<>();
        final Set<String> salts = new HashSet<>();
        for (int i = 0; i < 16; i++) {
            final String stored = LoginCrypto.makePbkdf2Hash("hunter2", TEST_ITERATIONS);
            assertTrue(strings.add(stored), "makePbkdf2Hash repeated itself: " + stored);
            assertTrue(salts.add(stored.split("\\$", -1)[3]), "salt repeated: " + stored);
            assertTrue(LoginCrypto.checkPbkdf2Hash(stored, "hunter2"));
        }
    }

    @Test
    @DisplayName("PBKDF2 hashes the full UTF-8 bytes, closing the legacy collision (A12)")
    void pbkdf2ClosesTheLegacyTruncationCollision() {
        // "密碼" and "寇碼" share their first two UTF-8 bytes, which is all the
        // legacy schemes hash - they are the same stored value under all three:
        assertEquals(LoginCrypto.hexSha1("密碼"), LoginCrypto.hexSha1("寇碼"));
        assertTrue(LoginCrypto.checkSha1Hash(LoginCrypto.makeSaltedSha1Hash("密碼"), "寇碼"));

        // Under PBKDF2 they are two different passwords and never cross-verify.
        // The two fixed vectors share a salt and an iteration count, so the only
        // thing that can make them differ is the password bytes reaching the HMAC.
        assertNotEquals(PBKDF2_MIMA, PBKDF2_KOUMA);
        assertEquals(PBKDF2_MIMA.split("\\$", -1)[3], PBKDF2_KOUMA.split("\\$", -1)[3], "same salt");

        assertTrue(LoginCrypto.checkPbkdf2Hash(PBKDF2_MIMA, "密碼"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(PBKDF2_MIMA, "寇碼"));
        assertTrue(LoginCrypto.checkPbkdf2Hash(PBKDF2_KOUMA, "寇碼"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(PBKDF2_KOUMA, "密碼"));

        // Freshly generated ones behave the same way.
        final String mima = LoginCrypto.makePbkdf2Hash("密碼", TEST_ITERATIONS);
        final String kouma = LoginCrypto.makePbkdf2Hash("寇碼", TEST_ITERATIONS);
        assertTrue(LoginCrypto.checkPbkdf2Hash(mima, "密碼"));
        assertTrue(LoginCrypto.checkPbkdf2Hash(kouma, "寇碼"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(mima, "寇碼"));
        assertFalse(LoginCrypto.checkPbkdf2Hash(kouma, "密碼"));
    }

    @Test
    @DisplayName("a malformed or truncated stored string is rejected, not thrown on and not accepted")
    void pbkdf2RejectsMalformedStoredStrings() {
        final String salt = "MDEyMzQ1Njc4OWFiY2RlZg";
        final String hash = PBKDF2_HUNTER2.split("\\$", -1)[4];
        final String[] bad = {
            "",
            "$",
            "$pbkdf2$",
            "$pbkdf2$1000",
            "$pbkdf2$1000$" + salt,
            "$pbkdf2$1000$" + salt + "$",                       // empty hash field
            "$pbkdf2$1000$$" + hash,                            // empty salt field
            "$pbkdf2$1000$" + salt + "$" + hash + "$extra",     // a sixth field
            "$pbkdf2$$" + salt + "$" + hash,                    // no iteration count
            "$pbkdf2$0$" + salt + "$" + hash,                   // zero iterations
            "$pbkdf2$-1$" + salt + "$" + hash,                  // negative iterations
            "$pbkdf2$abc$" + salt + "$" + hash,                 // count is not a number
            "$pbkdf2$99999999999999$" + salt + "$" + hash,      // count overflows int
            "$pbkdf2$20000000$" + salt + "$" + hash,            // count over the ceiling
            "$pbkdf2$1000$!!!!$" + hash,                        // salt is not base64
            "$pbkdf2$1000$" + salt + "$!!!!",                   // hash is not base64
            "$pbkdf2$1000$" + salt + "$" + hash.substring(0, 82),  // 61-byte hash
            "$pbkdf2$1000$" + salt + "$" + hash + "AAAA",          // 67-byte hash
            "$PBKDF2$1000$" + salt + "$" + hash,                // wrong-case prefix
            "$pbkdf2$1000$AAAAAAAAAAAAAAAAAAAAAA$" + hash,       // well-formed but wrong salt
            SHA1_HUNTER2,
            SHA512_HUNTER2,
            LEGACY_H_HUNTER2,
        };
        for (final String stored : bad) {
            // assertFalse also proves it did not throw: a throw fails the test.
            assertFalse(LoginCrypto.checkPbkdf2Hash(stored, "hunter2"), "accepted: " + stored);
        }
        // The last two entries are the only ones that could plausibly be a real
        // row, and isPbkdf2Hash keeps them out of this branch entirely.
        assertFalse(LoginCrypto.isPbkdf2Hash(SHA512_HUNTER2));
        assertFalse(LoginCrypto.isPbkdf2Hash(LEGACY_H_HUNTER2));
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
