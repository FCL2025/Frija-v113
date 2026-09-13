/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package tools;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Provides a suite of tools for manipulating Korean Timestamps.
 *
 * <p><b>The arithmetic below is a wire format and stays in raw integers.</b> The
 * client parses these as Windows FILETIME-style values - 100-nanosecond ticks
 * since 1601 for the 64-bit ones, and two home-grown scaled-minute encodings for
 * the 32-bit item and quest stamps. {@code java.time} has no type for any of
 * them, and rewriting the multiplications in terms of {@code Duration} or
 * {@code Instant} would only add a conversion that has to round-trip back to the
 * same long. Only {@link #isDST()}, which really is a calendar question, moved.
 *
 * @author Frz
 * @since Revision 746
 * @version 1.0
 */
public class KoreanDateUtil {

    private final static int ITEM_YEAR2000 = -1085019342;
    private final static long REAL_YEAR2000 = 946681229830l;
    private final static int QUEST_UNIXAGE = 27111908;
    private final static long FT_UT_OFFSET = 116444736000000000L; // 100 nsseconds from 1/1/1601 -> 1/1/1970

    /**
     * Converts a Unix Timestamp into File Time
     *
     * @param realTimestamp The actual timestamp in milliseconds.
     * @return A 64-bit long giving a filetime timestamp
     */
    public static final long getTempBanTimestamp(final long realTimestamp) {
        // long time = (realTimestamp / 1000);//seconds
        return ((realTimestamp * 10000) + FT_UT_OFFSET);
    }

    /**
     * Gets a timestamp for item expiration.
     *
     * @param realTimestamp The actual timestamp in milliseconds.
     * @return The Korean timestamp for the real timestamp.
     */
    public static final int getItemTimestamp(final long realTimestamp) {
        final int time = (int) ((realTimestamp - REAL_YEAR2000) / 1000 / 60); // convert to minutes
        return (int) (time * 35.762787) + ITEM_YEAR2000;
    }

    /**
     * Gets a timestamp for quest repetition.
     *
     * @param realTimestamp The actual timestamp in milliseconds.
     * @return The Korean timestamp for the real timestamp.
     */
    public static final int getQuestTimestamp(final long realTimestamp) {
        final int time = (int) (realTimestamp / 1000 / 60); // convert to minutes
        return (int) (time * 0.1396987) + QUEST_UNIXAGE;
    }

    /**
     * Whether the JVM's own time zone is on daylight saving time right now.
     *
     * <p>The system zone, not UTC, and deliberately so: this is the adjustment
     * {@link #getFileTimestamp} applies before it hands a value to the client,
     * and the client reads it against the machine's local clock. Taipei has had
     * no DST since 1979, so on the intended deployment this is a constant
     * {@code false}; it is kept because the server can be run anywhere.
     *
     * <p>{@code ZoneRules.isDaylightSavings} is the same question the old
     * {@code TimeZone.inDaylightTime(new Date())} asked - "is the offset in
     * force at this instant more than the standard offset" - just without
     * allocating a {@code Date}.
     */
    public static boolean isDST() {
        return ZoneId.systemDefault().getRules().isDaylightSavings(Instant.now());
    }

    public static long getFileTimestamp(long timeStampinMillis, boolean roundToMinutes) {
        if (isDST()) {
            timeStampinMillis -= 3600000L;
        }
        long time;
        if (roundToMinutes) {
            time = (timeStampinMillis / 1000 / 60) * 600000000;
        } else {
            time = timeStampinMillis * 10000;
        }
        return time + FT_UT_OFFSET;
    }
}
