package server;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The server's source of randomness: damage rolls, drop rolls, mob spawn
 * points, the handshake IVs.
 *
 * <p>This was one shared {@link java.util.Random}. That is thread-safe but not
 * concurrent - every call CASes the same seed field, so every handler thread
 * and every timer thread rolling a number contends on one cache line, and the
 * losers spin and retry. Since a hit calculation makes several of these calls
 * per attack, it is one of the hottest shared words in the process.
 *
 * <p>{@link ThreadLocalRandom} gives each thread its own seed, so there is no
 * sharing to contend over. The numbers are drawn from the same algorithm and
 * the ranges are unchanged; nothing here ever seeded for reproducibility.
 */
public class Randomizer {

    private Randomizer() {
    }

    private static ThreadLocalRandom rand() {
        return ThreadLocalRandom.current();
    }

    public static final int nextInt() {
        return rand().nextInt();
    }

    public static final int nextInt(final int arg0) {
        return rand().nextInt(arg0);
    }

    public static final void nextBytes(final byte[] bytes) {
        rand().nextBytes(bytes);
    }

    public static final boolean nextBoolean() {
        return rand().nextBoolean();
    }

    public static final double nextDouble() {
        return rand().nextDouble();
    }

    public static final float nextFloat() {
        return rand().nextFloat();
    }

    public static final long nextLong() {
        return rand().nextLong();
    }

    /** Inclusive of both bounds, as it has always been. */
    public static final int rand(final int lbound, final int ubound) {
        return (int) ((rand().nextDouble() * (ubound - lbound + 1)) + lbound);
    }
}
