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
package tools.packet;

import java.net.InetAddress;
import handling.ByteArrayMaplePacket;
import handling.MaplePacket;
import handling.SendPacketOpcode;
import java.net.UnknownHostException;
import server.ServerProperties;
import tools.data.output.MaplePacketLittleEndianWriter;
import tools.HexTool;

/**
 * 伺服器 / 頻道切換等連線層級封包（E5 由 MaplePacketCreator 拆出）。
 */
public class ServerPacket {

    public static final MaplePacket getServerIP(final int port, final int clientId) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SERVER_IP.getValue());
        mplew.writeShort(0);
        try {
            mplew.write(InetAddress.getByName(ServerProperties.getProperty("tms.IP")).getAddress());
        } catch (UnknownHostException e) {
            // tms.IP 無法解析時就不寫這 4 bytes，封包會短一截；這個分支沒有備援來源。
        }
        mplew.writeShort(port);
        mplew.writeInt(clientId);
        mplew.writeZeroBytes(5);

        return mplew.getPacket();
    }

    public static final MaplePacket getChannelChange(final int port) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.CHANGE_CHANNEL.getValue());
        mplew.write(1);
        try {
            mplew.write(InetAddress.getByName(ServerProperties.getProperty("tms.IP")).getAddress());
        } catch (UnknownHostException e) {
            // tms.IP 無法解析時就不寫這 4 bytes，封包會短一截；這個分支沒有備援來源。
        }
        mplew.writeShort(port);

        return mplew.getPacket();
    }

    public static MaplePacket getRelogResponse() {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter(3);

        mplew.writeShort(SendPacketOpcode.RELOG_RESPONSE.getValue());
        mplew.write(1);

        return mplew.getPacket();
    }

    /**
     * Possible values for <code>type</code>:<br>
     * 1: You cannot move that channel. Please try again later.<br>
     * 2: You cannot go into the cash shop. Please try again later.<br>
     * 3: The Item-Trading shop is currently unavailable, please try again
     * later.<br>
     * 4: You cannot go into the trade shop, due to the limitation of user
     * count.<br>
     * 5: You do not meet the minimum level requirement to access the Trade
     * Shop.<br>
     *
     * @param type The type
     * @return The "block" packet.
     */
    public static MaplePacket serverBlocked(int type) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SERVER_BLOCKED.getValue());
        mplew.write(type);

        return mplew.getPacket();
    }

    public static MaplePacket getPacketFromHexString(String hex) {
        return new ByteArrayMaplePacket(HexTool.getByteArrayFromHexString(hex));
    }
}
