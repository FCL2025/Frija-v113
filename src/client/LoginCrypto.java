package client;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Random;
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

    private static String hashWithDigest(final String in, final String digest) {
        try {
            MessageDigest Digester = MessageDigest.getInstance(digest);
            // NOTE: the length argument is in.length(), a count of chars, applied to
            // an array of UTF-8 bytes. For a password that is not pure ASCII the two
            // differ and the tail of the input - in practice the tail of the salt -
            // is silently dropped from the hash. Left as it is on purpose: every
            // stored hash was produced this way, so correcting it to
            // in.getBytes("UTF-8").length would invalidate the password of every
            // account that uses a non-ASCII one. Fix it only together with a forced
            // password reset for those accounts.
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
