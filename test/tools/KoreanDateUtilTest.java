package tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Windows FILETIME / "Korean timestamp" conversions the client expects.
 *
 * <p>{@code getFileTimestamp} consults {@link KoreanDateUtil#isDST()}, which
 * asks the JVM's <em>default</em> time zone whether <em>now</em> is in daylight
 * saving time. That makes it the only function here that is not pure, so the
 * expectations below apply the same adjustment rather than hardcoding a number
 * that would only hold in Taipei (which has had no DST since 1979).
 */
class KoreanDateUtilTest {

    /** 100-nanosecond intervals between 1601-01-01 and 1970-01-01. */
    private static final long FT_UT_OFFSET = 116_444_736_000_000_000L;

    /** 2017-07-14T02:40:00Z, an exact minute, and the same instant plus 12.345 s. */
    private static final long ROUND = 1_500_000_000_000L;
    private static final long OFF_MINUTE = 1_500_000_012_345L;

    @Test
    @DisplayName("getTempBanTimestamp is milliseconds to FILETIME, with no DST adjustment")
    void tempBanTimestamp() {
        assertEquals(ROUND * 10_000L + FT_UT_OFFSET, KoreanDateUtil.getTempBanTimestamp(ROUND));
        assertEquals(131_444_736_000_000_000L, KoreanDateUtil.getTempBanTimestamp(ROUND));
        assertEquals(FT_UT_OFFSET, KoreanDateUtil.getTempBanTimestamp(0L));
    }

    @Test
    @DisplayName("getFileTimestamp keeps millisecond resolution when not rounding")
    void fileTimestampUnrounded() {
        assertEquals(dstAdjusted(OFF_MINUTE) * 10_000L + FT_UT_OFFSET,
                KoreanDateUtil.getFileTimestamp(OFF_MINUTE, false));
    }

    @Test
    @DisplayName("getFileTimestamp(.., true) truncates to a whole minute")
    void fileTimestampRounded() {
        final long adjusted = dstAdjusted(OFF_MINUTE);
        assertEquals((adjusted / 1000 / 60) * 600_000_000L + FT_UT_OFFSET,
                KoreanDateUtil.getFileTimestamp(OFF_MINUTE, true));

        // 12.345 s past the minute is thrown away, so the two instants agree.
        assertEquals(KoreanDateUtil.getFileTimestamp(ROUND, true),
                KoreanDateUtil.getFileTimestamp(OFF_MINUTE, true));

        // ... and on an exact minute, rounding changes nothing.
        assertEquals(KoreanDateUtil.getFileTimestamp(ROUND, false),
                KoreanDateUtil.getFileTimestamp(ROUND, true));
    }

    @Test
    @DisplayName("getItemTimestamp is minutes since its own year-2000 epoch, scaled")
    void itemTimestamp() {
        // Behaviour snapshot, not a specification.
        assertEquals(-755_215_672, KoreanDateUtil.getItemTimestamp(ROUND));

        // Minute resolution: the seconds within a minute do not move the answer.
        assertEquals(KoreanDateUtil.getItemTimestamp(ROUND), KoreanDateUtil.getItemTimestamp(OFF_MINUTE));

        // ... but a whole minute does, and it moves forwards.
        assertTrue(KoreanDateUtil.getItemTimestamp(ROUND + 60_000L) > KoreanDateUtil.getItemTimestamp(ROUND));
    }

    @Test
    @DisplayName("getQuestTimestamp is minutes since the Unix epoch, scaled")
    void questTimestamp() {
        // Behaviour snapshot, not a specification.
        assertEquals(30_604_375, KoreanDateUtil.getQuestTimestamp(ROUND));
        assertEquals(KoreanDateUtil.getQuestTimestamp(ROUND), KoreanDateUtil.getQuestTimestamp(OFF_MINUTE));
        assertTrue(KoreanDateUtil.getQuestTimestamp(ROUND + 600_000L) > KoreanDateUtil.getQuestTimestamp(ROUND));
    }

    @Test
    @DisplayName("both Korean timestamps grow monotonically with real time")
    void timestampsAreMonotonic() {
        long previousItem = Long.MIN_VALUE;
        long previousQuest = Long.MIN_VALUE;
        for (long t = ROUND; t < ROUND + 60L * 60_000L; t += 10L * 60_000L) {
            final long item = KoreanDateUtil.getItemTimestamp(t);
            final long quest = KoreanDateUtil.getQuestTimestamp(t);
            assertTrue(item > previousItem, "item timestamp went backwards at " + t);
            assertTrue(quest > previousQuest, "quest timestamp went backwards at " + t);
            previousItem = item;
            previousQuest = quest;
        }
    }

    // ---------------------------------------------------------------------
    // Vectors added with the java.time migration (D4). Every function here is a
    // wire format, so these pin the exact bytes rather than describing intent.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("getTempBanTimestamp handles negative and far-future millis")
    void tempBanTimestampEdges() {
        // No DST adjustment anywhere in this one, so the numbers are absolute.
        assertEquals(116_444_735_999_990_000L, KoreanDateUtil.getTempBanTimestamp(-1L));
        assertEquals(116_443_872_000_000_000L, KoreanDateUtil.getTempBanTimestamp(-86_400_000L));
        assertEquals(157_469_184_000_000_000L, KoreanDateUtil.getTempBanTimestamp(4_102_444_800_000L)); // 2100-01-01
        assertEquals(131_444_736_123_450_000L, KoreanDateUtil.getTempBanTimestamp(OFF_MINUTE));
    }

    @Test
    @DisplayName("getItemTimestamp returns its own epoch constant at its own epoch")
    void itemTimestampEpoch() {
        // REAL_YEAR2000 in, ITEM_YEAR2000 out - the anchor the whole scale hangs on.
        assertEquals(-1_085_019_342, KoreanDateUtil.getItemTimestamp(946_681_229_830L));
        assertEquals(-1_649_285_310, KoreanDateUtil.getItemTimestamp(0L));
        // Minute truncation is toward zero, and the Unix epoch is before the
        // item epoch, so -1 ms does not move the answer.
        assertEquals(KoreanDateUtil.getItemTimestamp(0L), KoreanDateUtil.getItemTimestamp(-1L));
        assertEquals(-1_649_336_808, KoreanDateUtil.getItemTimestamp(-86_400_000L));
        assertEquals(795_962_313, KoreanDateUtil.getItemTimestamp(4_102_444_800_000L));
    }

    @Test
    @DisplayName("getQuestTimestamp returns its own epoch constant at the Unix epoch")
    void questTimestampEpoch() {
        assertEquals(27_111_908, KoreanDateUtil.getQuestTimestamp(0L));
        assertEquals(27_111_908, KoreanDateUtil.getQuestTimestamp(-1L));
        assertEquals(27_111_908, KoreanDateUtil.getQuestTimestamp(59_999L));
        assertEquals(27_111_707, KoreanDateUtil.getQuestTimestamp(-86_400_000L));
        assertEquals(36_663_678, KoreanDateUtil.getQuestTimestamp(4_102_444_800_000L));
    }

    @Test
    @DisplayName("getFileTimestamp at zero and below zero")
    void fileTimestampEdges() {
        assertEquals(dstAdjusted(0L) * 10_000L + FT_UT_OFFSET, KoreanDateUtil.getFileTimestamp(0L, false));
        assertEquals(dstAdjusted(-1L) * 10_000L + FT_UT_OFFSET, KoreanDateUtil.getFileTimestamp(-1L, false));

        // Minute truncation is integer division, i.e. toward zero, not toward
        // the past: -1 ms rounds up to the epoch rather than down a minute.
        assertEquals((dstAdjusted(-1L) / 1000 / 60) * 600_000_000L + FT_UT_OFFSET,
                KoreanDateUtil.getFileTimestamp(-1L, true));
        assertEquals((dstAdjusted(-86_400_000L) / 1000 / 60) * 600_000_000L + FT_UT_OFFSET,
                KoreanDateUtil.getFileTimestamp(-86_400_000L, true));
    }

    @Test
    @DisplayName("isDST still answers what TimeZone.inDaylightTime answered")
    void isDstMatchesTheTimeZoneApiItReplaced() {
        // The implementation moved from TimeZone.inDaylightTime(new Date()) to
        // ZoneRules.isDaylightSavings(Instant.now()). Both read the JVM default
        // zone, and getFileTimestamp shifts an hour of wire time on the answer,
        // so pin them against each other.
        assertEquals(TimeZone.getDefault().inDaylightTime(new Date()), KoreanDateUtil.isDST());
    }

    private static long dstAdjusted(final long millis) {
        return KoreanDateUtil.isDST() ? millis - 3_600_000L : millis;
    }
}
