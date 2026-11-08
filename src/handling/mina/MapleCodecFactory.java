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

import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolEncoder;

/**
 * MINA 2 asks for the codec per session rather than per service, so both
 * methods take an {@link IoSession} now. Both codecs are stateless - the
 * per-session state they need (the cipher and the half-read packet length) is
 * reached through the session's own attributes - so one instance of each still
 * serves every connection.
 */
public class MapleCodecFactory implements ProtocolCodecFactory {

    private final ProtocolEncoder encoder = new MaplePacketEncoder();
    private final ProtocolDecoder decoder = new MaplePacketDecoder();

    @Override
    public ProtocolEncoder getEncoder(final IoSession session) throws Exception {
        return encoder;
    }

    @Override
    public ProtocolDecoder getDecoder(final IoSession session) throws Exception {
        return decoder;
    }
}
