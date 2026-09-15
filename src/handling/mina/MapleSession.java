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
package handling.mina;

import java.net.SocketAddress;

import org.apache.mina.core.session.IoSession;

import handling.MaplePacket;

/**
 * What {@code MapleClient.getSession()} hands out: the five things the rest of
 * the tree ever asks a connection for, and nothing else.
 *
 * <p>This exists because of one detail of the MINA 2 upgrade.
 * {@code IoSession.close()} is {@code @Deprecated} there - MINA 2 split it into
 * {@link IoSession#closeNow()} and {@link IoSession#closeOnFlush()} - and there
 * are twenty-six {@code c.getSession().close()} call sites scattered across
 * handlers, GM commands, the anti-cheat and the panel. Returning a raw
 * {@code IoSession} would have made every one of them a deprecation warning,
 * and this tree's build is at zero warnings with {@code -Xlint:all}. Every one
 * of those sites means "hang up on this client now", which is exactly
 * {@code closeNow()}, so the translation happens once, here.
 *
 * <p>Having to write it turned out to be worth more than the warning it
 * silences: {@code MapleClient.send} was already the only place in the tree
 * that called {@code IoSession.write}, and this finishes that job. A caller
 * outside {@code handling.mina} can no longer reach MINA's API surface at all -
 * no filter chain, no attributes, no write queue - so replacing the network
 * layer later really is confined to this package.
 *
 * <p>Not thread-safe in itself and does not need to be: it holds one final
 * field and every method on it delegates to {@code IoSession}, which is.
 */
public final class MapleSession {

    private final IoSession session;

    public MapleSession(final IoSession session) {
        this.session = session;
    }

    /**
     * The MINA session underneath. Only {@code handling.MapleServerHandler}
     * needs this - it takes MINA's own callback signatures - and it is the one
     * place outside this package that is allowed to.
     */
    public IoSession unwrap() {
        return session;
    }

    /**
     * Queues one packet for this client. Goes through
     * {@link MaplePacketEncoder}, which encrypts it and hands it to the write
     * queue under {@code MapleClient.getLock()}, in that order and without
     * releasing the lock in between - the send cipher chains its IV from one
     * packet to the next, so a client that receives two packets in the other
     * order can never resynchronise.
     */
    public void write(final MaplePacket packet) {
        session.write(packet);
    }

    /**
     * Hangs up now, discarding anything still queued. This is MINA 1.1's
     * {@code close()}, which MINA 2 renamed {@code closeNow()} and deprecated
     * the old name of; {@code closeOnFlush()} is the other half of that split
     * and is deliberately not offered, because no call site here wanted it.
     */
    public void close() {
        session.closeNow();
    }

    /** False once this session has been closed. */
    public boolean isConnected() {
        return session.isConnected();
    }

    /** True from the moment a close starts. */
    public boolean isClosing() {
        return session.isClosing();
    }

    /** MINA hands these back with a leading slash, e.g. {@code /1.2.3.4:5678}. */
    public SocketAddress getRemoteAddress() {
        return session.getRemoteAddress();
    }

    /** Used for the {@code CLIENT_KEY} liveness check in {@code DebugMessage}. */
    public Object getAttribute(final Object key) {
        return session.getAttribute(key);
    }
}
