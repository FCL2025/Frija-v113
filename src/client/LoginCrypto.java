package client;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Random;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import tools.HexTool;

public class LoginCrypto {

    protected final static int extralength = 6;
    private final static String[] Alphabet = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};
    private final static String[] Number = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
    /**
     * SecureRandom, not java.util.Random: this feeds {@link #makeSalt()}, and a
     * java.util.Random salt carries only 48 bits of seed state taken from the
     * clock, so the salts a server hands out are predictable from each other.
     * A predictable salt lets an attacker precompute against the whole account
     * table at once, which is the thing salting is supposed to prevent.
     * SecureRandom extends Random, so every other use here is unchanged.
     */
    private final static Random rand = new SecureRandom();

    public static String Generate_13DigitAsiasoftPassport() {
        StringBuilder sb = new StringBuilder();
        sb.append(Alphabet[rand.nextInt(Alphabet.length)]); // First Letter

        for (int i = 0; i < 11; i++) {
            sb.append(Number[rand.nextInt(Number.length)]); // 11 Numbers
        }
        sb.append(Alphabet[rand.nextInt(Alphabet.length)]); // Last Letter

        return sb.toString();
    }

    private static String toSimpleHexString(final byte[] bytes) {
        return HexTool.toString(bytes).replace(" ", "").toLowerCase();
    }

    /**
     * The three legacy schemes' one digest pass. <b>This method is knowingly
     * wrong and must stay wrong.</b>
     *
     * The length argument is {@code in.length()}, a count of <i>chars</i>, applied
     * to an array of UTF-8 <i>bytes</i>. For an input that is not pure ASCII the
     * two differ and the tail - in practice the tail of the salt, or of the
     * password itself when it is short - is silently dropped from the digest. Two
     * different non-ASCII passwords whose first {@code in.length()} UTF-8 bytes
     * agree therefore hash to the same value, and either one logs in:
     * {@code hexSha1("密碼") == hexSha1("寇碼")}, pinned in
     * {@code LoginCryptoTest}.
     *
     * It is not fixed here because the stored hashes really are hashes of the
     * truncated bytes. Changing this to {@code in.getBytes("UTF-8").length} would
     * not correct those rows, it would lock out every account holding one. The
     * repair is {@link #makePbkdf2Hash(String)}, which hashes the full UTF-8 bytes
     * and which {@code MapleClient.login} rewrites every row into the first time
     * its owner logs in - after that the account is collision-free for good.
     *
     * <b>Residual hole, deliberately left open.</b> Between now and that first
     * login the account is still on a legacy hash, so a colliding password gets in
     * once - and the lazy upgrade then stores the PBKDF2 of <i>that</i> password,
     * handing the account to whoever used it. Closing it needs a forced password
     * reset for every non-ASCII password, which cannot be done from inside the
     * hash function; nothing in this file can make the window smaller. It is
     * written down rather than papered over.
     */
    private static String hashWithDigest(final String in, final String digest) {
        try {
            MessageDigest Digester = MessageDigest.getInstance(digest);
            // in.length() is a char count and the array is UTF-8 bytes - see the
            // javadoc above. Do not "fix" this line.
            Digester.update(in.getBytes("UTF-8"), 0, in.length());
            byte[] sha1Hash = Digester.digest();
            return toSimpleHexString(sha1Hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException("Hashing the password failed", ex);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Encoding the string failed", e);
        }

    }

    public static String hexSha1(final String in) {
        return hashWithDigest(in, "SHA-1");
    }

    private static String hexSha512(final String in) {
        return hashWithDigest(in, "SHA-512");
    }

    public static boolean checkSha1Hash(final String hash, final String password) {
        return hashEquals(hash, makeSaltedSha1Hash(password));
    }

    public static boolean checkSaltedSha512Hash(final String hash, final String password, final String salt) {
        return hashEquals(hash, makeSaltedSha512Hash(password, salt));
    }

    /**
     * MessageDigest.isEqual, not String.equals: equals answers as soon as it
     * reaches a character that differs, so how long a rejected login takes says
     * how much of the stored hash the guess already matched, and the hash can be
     * recovered one character at a time instead of being brute-forced whole.
     * isEqual reads every byte of an equal-length pair before it answers.
     *
     * Both sides are the lowercase hex of {@link #toSimpleHexString}, so US_ASCII
     * encodes them exactly - the platform default is never named here on purpose.
     * A null hash still throws, the way String.equals did, because both callers
     * ({@code MapleClient.passwordMatches}, {@code MapleCharacterUtil}) already
     * reject a null before they get this far.
     */
    private static boolean hashEquals(final String hash, final String computed) {
        return MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII), computed.getBytes(StandardCharsets.US_ASCII));
    }

    public static String makeSaltedSha512Hash(final String password, final String salt) {
        return hexSha512(password + salt);
    }

    public static String makeSaltedSha1Hash(final String password) {
        return hexSha1(password);
    }

    public static String makeSalt() {
        byte[] salt = new byte[16];
        rand.nextBytes(salt);
        return toSimpleHexString(salt);
    }

    // -----------------------------------------------------------------------
    // PBKDF2 - the scheme new and re-verified passwords are written in.
    //
    // The three legacy schemes are one digest pass each: an attacker who walks
    // off with the accounts table tries billions of candidates a second against
    // them on a single GPU. PBKDF2 puts a work factor in the way, and stores it
    // next to the hash so it can be raised later without invalidating anything.
    // -----------------------------------------------------------------------

    /**
     * Marks a stored value as the PBKDF2 format, as
     * {@code $pbkdf2$<iterations>$<salt-b64>$<hash-b64>}.
     *
     * Both hex schemes store {@code [0-9a-f]} only and the OdinMS legacy hash
     * starts with {@code "$H$"}, so the four formats the {@code accounts.password}
     * column can hold are told apart by looking at the string. The iteration count
     * and the salt travel inside it, which is why no second column is needed and
     * why {@code accounts.salt} is simply not consulted for such a row.
     *
     * A stored string is 8 + digits + 1 + 22 + 1 + 86 characters, i.e. 123 for the
     * current five-digit count. {@code accounts.password} is {@code varchar(128)},
     * so the count has room to reach seven digits before the column has to grow.
     */
    public static final String PBKDF2_PREFIX = "$pbkdf2$";
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA512";
    /**
     * Measured on the reference machine (8 cores, JDK 26.0.2.1), 25 verifications
     * per count, 64-byte output: 10000 -> 16 ms median, 20000 -> 32, 30000 -> 41,
     * 40000 -> 56, <b>50000 -> 67 ms (min 66, p90 71)</b>, 60000 -> 95 (p90 108),
     * 80000 -> 104, 100000 -> 185.
     *
     * The budget is 50-100 ms per verification: this is one machine serving a
     * handful of logins a minute, so the cost is invisible to a player and buys
     * four orders of magnitude against an offline attacker. 50000 is the largest
     * count that stays inside the budget at the 90th percentile, not just at the
     * median. Raising it later needs no migration - the count is stored with each
     * hash, and a row written at the old count keeps verifying at the old count
     * until its owner next logs in.
     */
    private static final int PBKDF2_ITERATIONS = 50000;
    /** 16 bytes, the same width as {@link #makeSalt()}; 22 base64 characters. */
    private static final int PBKDF2_SALT_BYTES = 16;
    /** The full HMAC-SHA-512 output, i.e. exactly one PBKDF2 block. */
    private static final int PBKDF2_HASH_BYTES = 64;
    /**
     * Sanity bounds for parsing. A stored string is attacker-influenced only if the
     * database is already lost, but a garbled row must not be able to park a
     * Maple-Handler thread on a billion-round derivation.
     */
    private static final int PBKDF2_MAX_ITERATIONS = 10000000;
    private static final int PBKDF2_MAX_SALT_BYTES = 64;

    /**
     * True if {@code stored} is in the PBKDF2 format. Only looks at the prefix -
     * a malformed body is rejected by {@link #checkPbkdf2Hash}, not here, so that
     * a corrupt row is never silently re-read as a legacy hash.
     */
    public static boolean isPbkdf2Hash(final String stored) {
        return stored != null && stored.startsWith(PBKDF2_PREFIX);
    }

    /**
     * Hashes {@code password} at the current {@link #PBKDF2_ITERATIONS} with a
     * fresh random salt, ready to be stored in {@code accounts.password}.
     */
    public static String makePbkdf2Hash(final String password) {
        return makePbkdf2Hash(password, PBKDF2_ITERATIONS);
    }

    /**
     * As {@link #makePbkdf2Hash(String)} with an explicit iteration count. Exists
     * so the tests can run at a count that does not cost 67 ms a call, and so the
     * count can be raised from a tool without a recompile. Server code should call
     * the one-argument form.
     */
    public static String makePbkdf2Hash(final String password, final int iterations) {
        if (iterations < 1 || iterations > PBKDF2_MAX_ITERATIONS) {
            throw new IllegalArgumentException("PBKDF2 iteration count out of range: " + iterations);
        }
        final byte[] salt = new byte[PBKDF2_SALT_BYTES];
        rand.nextBytes(salt);
        final Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
        return PBKDF2_PREFIX + iterations + "$" + b64.encodeToString(salt) + "$"
                + b64.encodeToString(pbkdf2(password, salt, iterations));
    }

    /**
     * True if {@code password} is the password behind the PBKDF2 string
     * {@code stored}. Every malformed input - wrong prefix, wrong field count, a
     * count or salt or digest that is not the right shape, base64 that does not
     * decode - answers false rather than throwing, because this runs on the login
     * path and one bad row must not take the login handler down with it.
     */
    public static boolean checkPbkdf2Hash(final String stored, final String password) {
        if (stored == null || password == null || !stored.startsWith(PBKDF2_PREFIX)) {
            return false;
        }
        // "" / "pbkdf2" / iterations / salt / hash. The limit is -1 so a trailing
        // empty field is kept and counted rather than dropped.
        final String[] parts = stored.split("\\$", -1);
        if (parts.length != 5) {
            return false;
        }
        final int iterations;
        final byte[] salt;
        final byte[] expected;
        try {
            // NumberFormatException is an IllegalArgumentException, and so is what
            // Base64 throws for a bad character - one catch covers both.
            iterations = Integer.parseInt(parts[2]);
            salt = Base64.getDecoder().decode(parts[3]);
            expected = Base64.getDecoder().decode(parts[4]);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        // The digest width is fixed by the algorithm named in the prefix, so a
        // truncated string is rejected outright instead of being verified against
        // its own truncation. The iteration count is free to differ from today's
        // default; that is the whole point of storing it.
        if (iterations < 1 || iterations > PBKDF2_MAX_ITERATIONS
                || salt.length < 1 || salt.length > PBKDF2_MAX_SALT_BYTES
                || expected.length != PBKDF2_HASH_BYTES) {
            return false;
        }
        // Constant time, like hashEquals above - and on the raw bytes, so there is
        // no encoding to get wrong.
        return MessageDigest.isEqual(expected, pbkdf2(password, salt, iterations));
    }

    /**
     * The derivation itself.
     *
     * The password reaches {@code PBEKeySpec} as a {@code char[]} and the JDK's
     * {@code PBKDF2WithHmacSHA512} encodes it as <b>full UTF-8</b> before it goes
     * into the HMAC - verified against a hand-rolled RFC 8018 PBKDF2 over
     * {@code getBytes(UTF_8)} for ASCII, Latin-1-supplement, CJK and astral-plane
     * inputs, and pinned by a fixed vector in {@code LoginCryptoTest}. That is
     * precisely what {@link #hashWithDigest} gets wrong, and it is why a password
     * once written in this format can no longer collide with another.
     */
    private static byte[] pbkdf2(final String password, final byte[] salt, final int iterations) {
        PBEKeySpec spec = null;
        try {
            spec = new PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_HASH_BYTES * 8);
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            // PBKDF2WithHmacSHA512 is a required SunJCE algorithm; if it is missing
            // the JVM is broken, and silently answering "wrong password" to every
            // login would be a far worse way to find that out.
            throw new RuntimeException("Hashing the password failed", ex);
        } finally {
            if (spec != null) {
                spec.clearPassword();
            }
        }
    }

    public static String rand_s(final String in) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < extralength; i++) {
            sb.append(rand.nextBoolean() ? Alphabet[rand.nextInt(Alphabet.length)] : Number[rand.nextInt(Number.length)]);
        }
        return sb.toString() + in;
    }

    public static String rand_r(final String in) {
        return in.substring(extralength, extralength + 128);
    }
}
