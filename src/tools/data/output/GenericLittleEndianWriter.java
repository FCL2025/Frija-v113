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
package tools.data.output;

import java.awt.Point;
import java.io.ByteArrayOutputStream;

import tools.Encodings;

/**
 * Writes a little-endian sequence of bytes - the write half of the wire format.
 *
 * <p>The bytes land in a {@link ByteArrayOutputStream}, which grows as the
 * packet does. This used to reach it through a {@code ByteOutputStream}
 * interface with one implementation, set after construction by a protected
 * setter; the stream is now a final field taken in the constructor, which is
 * also what retires the {@code -Xlint:this-escape} hazard that setter created
 * for {@link MaplePacketLittleEndianWriter}.
 *
 * <p>Instances are not shared: a packet builder creates one, fills it, and
 * turns it into a {@code MaplePacket}.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 323
 */
public class GenericLittleEndianWriter implements LittleEndianWriter {

    private final ByteArrayOutputStream baos;

    /**
     * The length of <code>str</code> in BIG5 bytes, which is what a
     * maple-convention string's length prefix counts. Not the character count:
     * every CJK character is two bytes.
     */
    public static int getlength(final String str) {
        return str.getBytes(Encodings.BIG5).length;
    }

    /**
     * Class constructor - the only public one.
     *
     * @param bos The stream to wrap this object around.
     */
    public GenericLittleEndianWriter(final BAOSByteOutputStream bos) {
        this(bos.baos);
    }

    /**
     * For {@link MaplePacketLittleEndianWriter}, which has to keep the stream
     * itself so it can read the finished packet back out of it.
     *
     * @param baos The stream to write into.
     */
    GenericLittleEndianWriter(final ByteArrayOutputStream baos) {
        this.baos = baos;
    }

    /**
     * Write <code>i</code> zero bytes.
     */
    @Override
    public final void writeZeroBytes(final int i) {
        for (int x = 0; x < i; x++) {
            baos.write(0);
        }
    }

    @Override
    public final void write(final byte[] b) {
        baos.writeBytes(b);
    }

    @Override
    public final void write(final byte b) {
        baos.write(b);
    }

    @Override
    public final void write(final int b) {
        baos.write(b);
    }

    @Override
    public final void writeShort(final short i) {
        baos.write(i & 0xFF);
        baos.write((i >>> 8) & 0xFF);
    }

    @Override
    public final void writeShort(final int i) {
        baos.write(i & 0xFF);
        baos.write((i >>> 8) & 0xFF);
    }

    @Override
    public final void writeInt(final int i) {
        baos.write(i & 0xFF);
        baos.write((i >>> 8) & 0xFF);
        baos.write((i >>> 16) & 0xFF);
        baos.write((i >>> 24) & 0xFF);
    }

    @Override
    public final void writeLong(final long l) {
        baos.write((int) (l & 0xFF));
        baos.write((int) ((l >>> 8) & 0xFF));
        baos.write((int) ((l >>> 16) & 0xFF));
        baos.write((int) ((l >>> 24) & 0xFF));
        baos.write((int) ((l >>> 32) & 0xFF));
        baos.write((int) ((l >>> 40) & 0xFF));
        baos.write((int) ((l >>> 48) & 0xFF));
        baos.write((int) ((l >>> 56) & 0xFF));
    }

    /**
     * Writes a string as BIG5 bytes, with no length prefix.
     */
    @Override
    public final void writeAsciiString(final String s) {
        write(s.getBytes(Encodings.BIG5));
    }

    /**
     * Writes a string into a fixed <code>max</code>-byte field, zero-padded.
     *
     * <p>Note that the truncation is by <em>characters</em> and the padding by
     * <em>bytes</em>, so a CJK string longer than the field still writes more
     * than <code>max</code> bytes, and one whose byte length exceeds
     * <code>max</code> while its character length does not throws out of
     * {@code substring}. That is what this has always done and what the callers
     * - fixed-width name fields, all of them short ASCII in practice - were
     * written against.
     */
    @Override
    public final void writeAsciiString(String s, final int max) {
        byte[] b = s.getBytes(Encodings.BIG5);
        if (b.length > max) {
            s = s.substring(0, max);
            b = s.getBytes(Encodings.BIG5);
        }
        write(b);
        for (int i = b.length; i < max; i++) {
            write(0);
        }
    }

    /**
     * Writes a maple-convention string: a short integer giving the length
     * <em>in BIG5 bytes</em>, then those bytes.
     */
    @Override
    public final void writeMapleAsciiString(final String s) {
        final byte[] b = s.getBytes(Encodings.BIG5);
        writeShort((short) b.length);
        write(b);
    }

    /**
     * Writes a 2D 4 byte position information.
     */
    @Override
    public final void writePos(final Point s) {
        writeShort(s.x);
        writeShort(s.y);
    }
}
