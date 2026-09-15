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

import java.io.ByteArrayOutputStream;

import handling.ByteArrayMaplePacket;
import handling.MaplePacket;
import server.ServerProperties;
import tools.HexTool;

/**
 * Writes a maplestory-packet little-endian stream of bytes. Every outbound
 * packet in the tree is built with one of these.
 *
 * @author Frz
 * @version 1.0
 * @since Revision 352
 */
// final: nothing extends this writer - the classes in tools/packet instantiate
// it directly - and being final also keeps -Xlint:this-escape out of the
// picture for good.
public final class MaplePacketLittleEndianWriter extends GenericLittleEndianWriter {

    private static final System.Logger LOG = System.getLogger(MaplePacketLittleEndianWriter.class.getName());
    private static final boolean DEBUG_MODE = Boolean.parseBoolean(ServerProperties.getProperty("tms.Debug", "false"));

    private final ByteArrayOutputStream baos;

    /**
     * Constructor - initializes this stream with a default size.
     */
    public MaplePacketLittleEndianWriter() {
        this(32);
    }

    /**
     * Constructor - initializes this stream with size <code>size</code>.
     *
     * @param size The initial size of the underlying stream. It grows as
     * needed; this is a starting guess, not a limit.
     */
    public MaplePacketLittleEndianWriter(final int size) {
        this(new ByteArrayOutputStream(size));
    }

    private MaplePacketLittleEndianWriter(final ByteArrayOutputStream baos) {
        super(baos);
        this.baos = baos;
    }

    /**
     * Gets a <code>MaplePacket</code> instance representing this sequence of
     * bytes.
     *
     * @return A <code>MaplePacket</code> with the bytes in this stream.
     */
    public MaplePacket getPacket() {
        // Every outbound packet comes through here, so the hex dump is behind
        // both of its switches: tms.Debug as before, and the logger's own level
        // - a DEBUG record is dropped unless someone lowers
        // tools.data.output.MaplePacketLittleEndianWriter.level to FINE in
        // Libs/logging.properties. Neither one builds the string.
        if (DEBUG_MODE && LOG.isLoggable(System.Logger.Level.DEBUG)) {
            MaplePacket packet = new ByteArrayMaplePacket(baos.toByteArray());
            LOG.log(System.Logger.Level.DEBUG, "Packet to be sent:\n" + packet.toString());
        }
        return new ByteArrayMaplePacket(baos.toByteArray());
    }

    /**
     * Changes this packet into a human-readable hexadecimal stream of bytes.
     *
     * @return This packet as hex digits.
     */
    @Override
    public String toString() {
        return HexTool.toString(baos.toByteArray());
    }
}
