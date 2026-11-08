/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
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

import org.apache.mina.core.future.DefaultWriteFuture;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.session.DummySession;

/**
 * A session for a {@code MapleClient} that has no connection behind it - clone
 * characters ({@code MapleCharacter.cloneLooks}) and the offline player NPCs
 * built by {@code !makeofflinep}.
 *
 * <p>This used to be 167 lines of {@code BaseIoSession} subclass returning null
 * from every method, because MINA 1.1 shipped nothing like it. MINA 2 has
 * {@link DummySession} - the same idea, written by the MINA project and kept
 * working by it - so all that is left here is the one behaviour this fork
 * actually needs on top of it.
 *
 * <p>That behaviour is {@link #write}. {@code DummySession} would accept a
 * write, walk its (empty) filter chain and fire {@code messageSent} into a
 * do-nothing handler; correct, but pure waste, and a clone standing on a busy
 * map is written to constantly. So writes are dropped here instead - which is
 * also what the old class did, and what the callers assume: a clone client is
 * built with {@code new MapleClient(null, null, ...)}, i.e. with no send
 * cipher, so there is nothing that could encrypt a packet for it anyway.
 *
 * <p>Dropping is not the same as failing. The future handed back is already
 * marked written, so a caller that waits on one or chains a listener onto it
 * does not block and does not see an exception. Nothing does today -
 * {@code MapleClient.send} discards the result - but the old class returned a
 * bare {@code null} here, which would have been an NPE the moment anything did.
 *
 * <p>Everything else is inherited: {@code closeNow()} works, attributes work,
 * and {@code getRemoteAddress()} answers MINA's anonymous address ({@code "?"})
 * rather than the {@code null} the old class returned, so the handful of places
 * that format a client's address no longer have to guard against one.
 *
 * @author Frz
 * @since Revision 518
 * @version 2.0
 */
public class MockIOSession extends DummySession {

    /**
     * Silently discards the packet. See the class javadoc - the returned future
     * reports success rather than being null or failed.
     */
    @Override
    public WriteFuture write(final Object message) {
        return DefaultWriteFuture.newWrittenFuture(this);
    }
}
