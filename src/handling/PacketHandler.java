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
package handling;

import client.MapleClient;
import tools.data.input.SeekableLittleEndianAccessor;

/**
 * What one inbound opcode does. One of these per {@link RecvPacketOpcode} lives
 * in {@link PacketHandlerRegistry}; each was a {@code case} of the {@code
 * switch} that used to be {@code MapleServerHandler.handlePacket}.
 *
 * <p>Implementations run on the {@code Maple-Handler-N} pool and several run at
 * once, one per session - see the concurrency invariants in CLAUDE.md. They are
 * registered once and then shared by every session on every channel, so an
 * implementation must be stateless: all of the per-packet state it is allowed
 * to keep is on its own stack.
 */
@FunctionalInterface
public interface PacketHandler {

    /**
     * @param slea the packet, positioned just past the two-byte header. Handlers
     * read it destructively and several of the registered ones do part of that
     * reading themselves before delegating.
     * @param c the session's client. Never null and always receiving:
     * {@code MapleServerHandler.messageReceived} has already dropped the packet
     * otherwise, and has already enforced {@link
     * RecvPacketOpcode#NeedsChecking()} against {@code c.isLoggedIn()}.
     * {@code c.getPlayer()} may still be null, exactly as before.
     * @param cs true when the packet arrived on the cash-shop port. Login and
     * channel both pass false. Only {@code PLAYER_LOGGEDIN} and {@code
     * CHANGE_MAP} look at it; the rest ignore it.
     * @throws Exception whatever the handler throws.
     * {@code MapleServerHandler.messageReceived} catches it and writes it to
     * {@code Logs/Log_Packet_Except.rtf} rather than losing the session.
     */
    void handle(SeekableLittleEndianAccessor slea, MapleClient c, boolean cs) throws Exception;
}
