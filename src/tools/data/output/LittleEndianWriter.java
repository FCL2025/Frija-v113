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

/**
 * The write half of the wire format: a little-endian sequence of bytes.
 *
 * <p>{@link GenericLittleEndianWriter} is the only implementation, and
 * {@link MaplePacketLittleEndianWriter} the only subclass of that. The
 * interface stays because {@code tools.packet.PartyPacket} writes its shared
 * party block against it.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 323
 */
public interface LittleEndianWriter {

    /**
     * Write <code>i</code> zero bytes.
     */
    void writeZeroBytes(final int i);

    void write(final byte b[]);

    void write(final byte b);

    void write(final int b);

    void writeShort(final short s);

    void writeShort(final int i);

    void writeInt(final int i);

    void writeLong(final long l);

    /**
     * Writes a string as BIG5 bytes, with no length prefix.
     */
    void writeAsciiString(final String s);

    /**
     * Writes a string into a fixed <code>max</code>-byte field, zero-padded.
     */
    void writeAsciiString(String s, final int max);

    /**
     * Writes a maple-convention string: a short integer giving the length
     * <em>in BIG5 bytes</em>, then those bytes.
     */
    void writeMapleAsciiString(final String s);

    /**
     * Writes a 2D 4 byte position information.
     */
    void writePos(final Point s);
}
