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
package tools.data.input;

import java.awt.Point;
import java.util.Arrays;

import tools.Encodings;
import tools.HexTool;

/**
 * Reads one inbound packet as a little-endian stream of bytes.
 *
 * <p>This is the only reader in the tree. It used to be the middle of a
 * three-layer stack - {@code ByteInputStream} (an interface for "something that
 * hands out one byte"), one implementation of it, and this class on top - even
 * though the only byte source that was ever constructed was a {@code byte[]}.
 * The array and the cursor now live here, and {@link ByteArrayByteStream} is
 * just the handle the call sites name.
 *
 * <p>Instances are confined to one handler thread. {@code ExecutorFilter} runs
 * at most one task per session at a time, so the packet a thread is reading is
 * its own; nothing here is synchronized and nothing should need to be.
 *
 * @version 1.0
 * @author Frz
 * @since Revision 323
 */
public class GenericLittleEndianAccessor implements SeekableLittleEndianAccessor {

    private final byte[] arr;
    private int pos;
    private long bytesRead;

    /**
     * Class constructor - wraps the accessor around a packet's bytes.
     *
     * @param bs The byte stream to wrap the accessor around.
     */
    public GenericLittleEndianAccessor(final ByteArrayByteStream bs) {
        this.arr = bs.arr;
    }

    /**
     * The one place a byte leaves the array. Reading past the end throws
     * {@link ArrayIndexOutOfBoundsException}, which is what the movement
     * parsers in {@code PlayerHandler} and {@code MobHandler} catch to reject a
     * truncated packet - so every read below must stay on this path rather than
     * bulk-copying without a bounds check.
     *
     * @return The next byte, unsigned, as an integer.
     */
    private int next() {
        bytesRead++;
        return arr[pos++] & 0xFF;
    }

    @Override
    public final int readByteAsInt() {
        return next();
    }

    @Override
    public final byte readByte() {
        return (byte) next();
    }

    @Override
    public final int readInt() {
        final int byte1 = next();
        final int byte2 = next();
        final int byte3 = next();
        final int byte4 = next();
        return (byte4 << 24) + (byte3 << 16) + (byte2 << 8) + byte1;
    }

    @Override
    public final short readShort() {
        final int byte1 = next();
        final int byte2 = next();
        return (short) ((byte2 << 8) + byte1);
    }

    @Override
    public final char readChar() {
        return (char) readShort();
    }

    @Override
    public final long readLong() {
        final int byte1 = next();
        final int byte2 = next();
        final int byte3 = next();
        final int byte4 = next();
        final int byte5 = next();
        final int byte6 = next();
        final int byte7 = next();
        final int byte8 = next();

        // The arithmetic has to be done in long: Java masks an int shift distance
        // to five bits, so an int-width `byte8 << 56` would really be `<< 24` and
        // the top four bytes would fold onto the bottom four.
        return ((byte8 & 0xFFL) << 56) + ((byte7 & 0xFFL) << 48) + ((byte6 & 0xFFL) << 40) + ((byte5 & 0xFFL) << 32)
                + ((byte4 & 0xFFL) << 24) + ((byte3 & 0xFFL) << 16) + ((byte2 & 0xFFL) << 8) + (byte1 & 0xFFL);
    }

    @Override
    public final float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public final double readDouble() {
        return Double.longBitsToDouble(readLong());
    }

    /**
     * Reads a string of exactly <code>n</code> bytes.
     *
     * <p>BIG5, from {@code tools.Encodings} - never the platform default, which
     * JEP 400 changed to UTF-8 in JDK 18. It is the same charset
     * {@code GenericLittleEndianWriter} encodes with.
     *
     * @param n Number of bytes to read.
     * @return The string read.
     */
    @Override
    public final String readAsciiString(final int n) {
        return new String(read(n), Encodings.BIG5);
    }

    /**
     * Reads a MapleStory convention lengthed string: a short integer giving the
     * length <em>in bytes</em>, then that many bytes.
     *
     * @return The string read.
     */
    @Override
    public final String readMapleAsciiString() {
        return readAsciiString(readShort());
    }

    /**
     * Reads a MapleStory Position, i.e. two short integers.
     *
     * @return The Position read.
     */
    @Override
    public final Point readPos() {
        final int x = readShort();
        final int y = readShort();
        return new Point(x, y);
    }

    @Override
    public final byte[] read(final int num) {
        final byte[] ret = new byte[num];
        for (int x = 0; x < num; x++) {
            ret[x] = readByte();
        }
        return ret;
    }

    /**
     * Skips <code>num</code> bytes, counting them as read.
     *
     * <p>Deliberately a read loop rather than an arithmetic jump: running off
     * the end of a truncated packet has to throw here too.
     *
     * @param num Number of bytes to skip.
     */
    @Override
    public void skip(final int num) {
        for (int x = 0; x < num; x++) {
            next();
        }
    }

    @Override
    public final void seek(final long offset) {
        pos = (int) offset;
    }

    @Override
    public final long getPosition() {
        return pos;
    }

    @Override
    public final long getBytesRead() {
        return bytesRead;
    }

    @Override
    public final long available() {
        return arr.length - pos;
    }

    @Override
    public final String toString() {
        return toString(false);
    }

    /**
     * The packet as hex digits - what the monitor logs and the "packet parsing
     * error" reports print.
     *
     * @param b Whether to show the whole packet as well as the unread tail.
     * @return The stream as a string.
     */
    @Override
    public final String toString(final boolean b) {
        String nows = "";
        if (arr.length - pos > 0) {
            nows = HexTool.toString(Arrays.copyOfRange(arr, pos, arr.length));
        }
        if (b) {
            return "All: " + HexTool.toString(arr) + "\nNow: " + nows;
        } else {
            return "Data: " + nows;
        }
    }
}
