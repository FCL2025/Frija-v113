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

/**
 * The read half of the wire format: a little-endian stream of bytes.
 *
 * <p>{@link GenericLittleEndianAccessor} is the only implementation. The
 * interface stays because call sites are written against it -
 * {@code DamageParse} takes a {@code LittleEndianAccessor} and every packet
 * handler takes its seekable subtype.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 323
 */
public interface LittleEndianAccessor {

    byte readByte();

    /**
     * The unsigned view of {@link #readByte()}.
     */
    int readByteAsInt();

    char readChar();

    short readShort();

    int readInt();

    long readLong();

    float readFloat();

    double readDouble();

    /**
     * Skips ahead <code>num</code> bytes.
     */
    void skip(int num);

    /**
     * Reads <code>num</code> bytes.
     */
    byte[] read(int num);

    /**
     * Reads a string of exactly <code>n</code> bytes, decoded as BIG5.
     */
    String readAsciiString(int n);

    /**
     * Reads a MapleStory convention lengthed string: a short integer giving the
     * length <em>in bytes</em>, then that many BIG5 bytes.
     */
    String readMapleAsciiString();

    /**
     * Reads a MapleStory Position, i.e. two short integers.
     */
    Point readPos();

    long getBytesRead();

    /**
     * The number of bytes left for reading.
     */
    long available();

    /**
     * The stream as hex digits; <code>b</code> shows the whole packet as well
     * as the unread tail.
     */
    String toString(final boolean b);
}
