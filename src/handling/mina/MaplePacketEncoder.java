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

import client.MapleClient;
import handling.MaplePacket;
import tools.MapleAESOFB;

import java.util.concurrent.locks.Lock;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter;
import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.ProtocolEncoder;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

public class MaplePacketEncoder implements ProtocolEncoder {

    @Override
    public void encode(final IoSession session, final Object message, final ProtocolEncoderOutput out) throws Exception {
        final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);

        if (client != null) {
            final MapleAESOFB send_crypto = client.getSendCrypto();

            final byte[] inputInitialPacket = ((MaplePacket) message).getBytes();
            final byte[] unencrypted = new byte[inputInitialPacket.length];
            System.arraycopy(inputInitialPacket, 0, unencrypted, 0, inputInitialPacket.length); // Copy the input > "unencrypted"
            final byte[] ret = new byte[unencrypted.length + 4]; // Create new bytes with length = "unencrypted" + 4

            // Anything can write to a session - the player's own handler
            // thread, another player's thread broadcasting to the map, a timer
            // task - so two threads can be in here for the same session at
            // once. send_crypto chains its IV from one packet to the next, so
            // the client will only decrypt them in the order they were
            // encrypted. Two threads that encrypt in one order and reach the
            // write queue in the other leave a client that can never
            // resynchronise: it simply stops understanding the server.
            //
            // This lock is therefore necessary but, under MINA 2, NOT
            // SUFFICIENT - see WriteOrderFilter below, which is what actually
            // makes encrypt-then-queue atomic. It is kept because the cipher is
            // this class's business and the lock is reentrant, so re-taking it
            // inside the filter's critical section costs nothing.
            final Lock mutex = client.getLock();
            mutex.lock();
            try {
                final byte[] header = send_crypto.getPacketHeader(unencrypted.length);
//		MapleCustomEncryption.encryptData(unencrypted); // Encrypting Data
                send_crypto.crypt(unencrypted); // Crypt it with IV
                System.arraycopy(header, 0, ret, 0, 4); // Copy the header > "Ret", first 4 bytes
                System.arraycopy(unencrypted, 0, ret, 4, unencrypted.length); // Copy the unencrypted > "ret"
                out.write(IoBuffer.wrap(ret));
            } finally {
                mutex.unlock();
            }
        } else { // no client object created yet, send unencrypted (hello)
            out.write(IoBuffer.wrap(((MaplePacket) message).getBytes()));
        }
    }

    @Override
    public void dispose(IoSession session) throws Exception {
        // nothing to do
    }

    /**
     * Keeps "encrypt this packet" and "put it in the write queue" atomic per
     * session. Without it the server desynchronises clients under load.
     *
     * <p>Under MINA 1.1 the encoder's own lock was enough, because
     * {@code ProtocolEncoderOutput.write} handed the buffer straight on.
     * <b>MINA 2 changed that and it is the trap of this migration.</b> There,
     * {@code out.write} only appends to a queue, and
     * {@link ProtocolCodecFilter#filterWrite} drains that queue and calls
     * {@code nextFilter.filterWrite} <em>after {@code encode} has returned</em>
     * - which is after {@code encode}'s {@code finally} block has released the
     * lock. So the encryption was still serialised, but the handover no longer
     * was: two threads could encrypt as 1, 2 and queue as 2, 1. The IV chain is
     * built in encryption order, so the client decrypts packet 2 with packet
     * 1's IV, gets garbage, and never recovers. A loopback test with eight
     * threads writing concurrently reproduced this on packet 38 of 2000.
     *
     * <p>The fix is to take the lock one filter further out, around the whole
     * write path rather than around the encode. Everything from here down is
     * synchronous on the calling thread - the codec filter encodes and drains
     * inline, {@code ExecutorFilter} does not intercept {@code WRITE} (it is
     * not in its default event set), and the session's write queue is an
     * unbounded {@code ConcurrentLinkedQueue} whose {@code offer} cannot block
     * - so nothing waits on I/O while the lock is held, and the lock is
     * per-client, so two players never contend.
     *
     * <p><b>It has to be added to the chain after the codec filter</b>:
     * {@code fireFilterWrite} starts at the tail and walks towards the head, so
     * on the write path the last filter added runs first. Inbound it is a
     * pass-through. {@link MapleAcceptor} is the only place that installs it.
     *
     * <p>Sessions with no client yet (the handshake) are passed straight
     * through - there is nothing to serialise against, and the encoder's
     * "unencrypted hello" branch depends on that packet going out before
     * {@code CLIENT_KEY} is set.
     */
    public static final class WriteOrderFilter extends IoFilterAdapter {

        @Override
        public void filterWrite(final IoFilter.NextFilter nextFilter, final IoSession session,
                final WriteRequest writeRequest) throws Exception {
            final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);
            if (client == null) {
                nextFilter.filterWrite(session, writeRequest);
                return;
            }
            final Lock mutex = client.getLock();
            mutex.lock();
            try {
                nextFilter.filterWrite(session, writeRequest);
            } finally {
                mutex.unlock();
            }
        }
    }
}
