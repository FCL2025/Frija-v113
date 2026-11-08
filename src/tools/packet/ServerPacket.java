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

import java.net.Inet4Address;
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

    private static final System.Logger LOG = System.getLogger(ServerPacket.class.getName());

    /**
     * 告訴客戶端要連去哪裡的那 4 個 bytes（{@code tms.IP}）。
     *
     * <p>A28：以前兩個呼叫端各自 {@code InetAddress.getByName(...).getAddress()}，
     * {@code UnknownHostException} 的 catch 是空的。解析失敗時那 4 bytes 根本不寫，
     * 後面的 port 與 clientId 整段往前錯位，封包短一截而且沒有任何紀錄；
     * {@code tms.IP} 若是只解析得到 IPv6 的主機名，{@code getAddress()} 回 16 bytes，
     * 封包反而長一截。客戶端兩種都解不出來。
     *
     * <p>現在一律回 4 bytes：取第一個 IPv4 位址；找不到就記 ERROR 並送 0.0.0.0，
     * 讓封包長度保持正確、客戶端連線失敗得乾淨，原因寫在日誌裡。
     * 每次呼叫都重新解析，因為面板可以在執行中改 {@code tms.IP}（{@code SettingsApi} 的 MEMORY 類）。
     */
    static byte[] serverAddress() {
        final String host = ServerProperties.getProperty("tms.IP");
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a instanceof Inet4Address) {
                    return a.getAddress();
                }
            }
            LOG.log(System.Logger.Level.ERROR, "tms.IP = " + host + " 沒有 IPv4 位址，送給客戶端的是 0.0.0.0");
        } catch (UnknownHostException e) {
            LOG.log(System.Logger.Level.ERROR, "tms.IP = " + host + " 無法解析，送給客戶端的是 0.0.0.0：" + e);
        }
        return new byte[4];
    }

    public static final MaplePacket getServerIP(final int port, final int clientId) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SERVER_IP.getValue());
        mplew.writeShort(0);
        mplew.write(serverAddress());
        mplew.writeShort(port);
        mplew.writeInt(clientId);
        mplew.writeZeroBytes(5);

        return mplew.getPacket();
    }

    public static final MaplePacket getChannelChange(final int port) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.CHANGE_CHANNEL.getValue());
        mplew.write(1);
        mplew.write(serverAddress());
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
