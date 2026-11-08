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
import tools.MapleAESOFB;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.CumulativeProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

/**
 * MINA 2's {@link CumulativeProtocolDecoder} keeps the 1.1 contract exactly -
 * return {@code true} for "I consumed a whole message, call me again",
 * {@code false} for "not enough bytes yet, keep the rest for next time" - and
 * still insists that a {@code true} return has advanced the position. Only the
 * buffer type changed, from {@code org.apache.mina.common.ByteBuffer} to
 * {@link IoBuffer}; both are big-endian by default, so {@code getInt()} reads
 * the same four bytes the same way and {@link MapleAESOFB} is untouched.
 */
public class MaplePacketDecoder extends CumulativeProtocolDecoder {

    public static final String DECODER_STATE_KEY = MaplePacketDecoder.class.getName() + ".STATE";

    public static class DecoderState {

        public int packetlength = -1;
    }

    @Override
    protected boolean doDecode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        final DecoderState decoderState = (DecoderState) session.getAttribute(DECODER_STATE_KEY);

        /*	if (decoderState == null) {
         decoderState = new DecoderState();
         session.setAttribute(DECODER_STATE_KEY, decoderState);
         }*/
        final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);

        if (decoderState.packetlength == -1) {
            if (in.remaining() >= 4) {
                final int packetHeader = in.getInt();
                if (!client.getReceiveCrypto().checkPacket(packetHeader)) {
                    // MINA 2: close() is deprecated in favour of the explicit
                    // closeNow() / closeOnFlush() pair. This one is the old
                    // close(), i.e. closeNow().
                    session.closeNow();
                    return false;
                }
                decoderState.packetlength = MapleAESOFB.getPacketLength(packetHeader);
            } else {
                return false;
            }
        }
        if (in.remaining() >= decoderState.packetlength) {
            final byte decryptedPacket[] = new byte[decoderState.packetlength];
            in.get(decryptedPacket, 0, decoderState.packetlength);
            decoderState.packetlength = -1;

            client.getReceiveCrypto().crypt(decryptedPacket);
// D9：v113 不套第二層 MapleCustomEncryption，只有 AES-OFB。MaplePacketEncoder 有
// 對稱的一行。兩行一起記錄了這個版本的線路格式，不要以為是忘了打開。
//	    MapleCustomEncryption.decryptData(decryptedPacket);
            out.write(decryptedPacket);
            return true;
        }
        return false;
    }
}
