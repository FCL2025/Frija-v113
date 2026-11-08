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
package handling.channel.handler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import client.inventory.IItem;
import client.inventory.ItemFlag;
import constants.GameConstants;
import constants.ItemConstants;
import client.inventory.ItemLoader;
import client.MapleCharacter;
import client.MapleCharacterUtil;
import client.MapleClient;
import client.inventory.MapleInventoryType;
import database.DatabaseConnection;
import java.util.Collections;
import java.util.Map;
import tools.data.input.SeekableLittleEndianAccessor;
import server.MapleDueyActions;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import tools.Pair;
import tools.packet.CharacterPacket;
import tools.packet.NPCPacket;

public class DueyHandler {

    private static final System.Logger LOG = System.getLogger(DueyHandler.class.getName());

    /*
     * 19 = Successful
     * 18 = One-of-a-kind Item is already in Reciever's delivery
     * 17 = The Character is unable to recieve the parcel
     * 15 = Same account
     * 14 = Name does not exist
     */
    public static final void dueyOperation(final SeekableLittleEndianAccessor slea, final MapleClient c) {

        final byte operation = slea.readByte();

        switch (operation) {
            case 1: { // Start Duey, 13 digit AS
                final String AS13Digit = slea.readMapleAsciiString();
        //		int unk = slea.readInt(); // Theres an int here, value = 1
                //  9 = error
                final int conv = c.getPlayer().getConversation();

                if (conv == 2) { // Duey
                    c.send(NPCPacket.sendDuey((byte) 10, loadItems(c.getPlayer())));
                }
                break;
            }
            case 3: { // Send Item
                if (c.getPlayer().getConversation() != 2) {
                    return;
                }
                final byte inventId = slea.readByte();
                final short itemPos = slea.readShort();
                final short amount = slea.readShort();
                final int mesos = slea.readInt();
                final String recipient = slea.readMapleAsciiString();
                boolean quickdelivery = slea.readByte() > 0;

                final int finalcost = mesos + GameConstants.getTaxAmount(mesos) + (quickdelivery ? 0 : 5000);

                if (mesos >= 0 && mesos <= 100000000 && c.getPlayer().getMeso() >= finalcost) {
                    // E20：以前用 getIdByName 取得的是收件人的「角色」id，卻拿去和寄件人的「帳號」id 比，
                    // 於是 15（同帳號）這個檢查形同虛設：寄給自己帳號的其他角色不會被擋，而收件人角色 id
                    // 碰巧等於寄件人帳號 id 時反而會被誤擋。改用商城送禮同一個查法，角色 id 與帳號 id 一起拿。
                    final Pair<Integer, Pair<Integer, Integer>> info = MapleCharacterUtil.getInfoByName(recipient, c.getPlayer().getWorld());
                    final int recipientId = info == null ? -1 : info.getLeft();
                    if (recipientId != -1) {
                        if (info.getRight().getLeft() != c.getAccID()) {
                            // 收件人在線上時應該通知他「有包裹」，但 RMI 時代那段通知被註解掉之後就沒有了，
                            // 所以 recipientOn 一律是 false（Checked 欄位一律寫 1）。要補的話，找人的方法現在是
                            // Find.findChannel(recipient) 再 ChannelServer.getInstance(ch).getPlayerStorage()
                            // .getCharacterByName(recipient)（同 ChatHandler 的寫法）；缺的是封包——v113 的 DUEY
                            // 「包裹送達」子操作碼在這棵樹裡沒有任何來源，要用真實客戶端確認後才能補。
                            final boolean recipientOn = false;

                            if (inventId > 0) {
                                final MapleInventoryType inv = MapleInventoryType.getByType(inventId);
                                final IItem item = c.getPlayer().getInventory(inv).getItem((byte) itemPos);
                                if (item == null) {
                                    c.send(NPCPacket.sendDuey((byte) 17, null)); // Unsuccessfull
                                    return;
                                }
                                final byte flag = item.getFlag();
                                if (ItemFlag.UNTRADEABLE.check(flag) || ItemFlag.LOCK.check(flag)) {
                                    c.send(CharacterPacket.enableActions());
                                    return;
                                }
                                if (c.getPlayer().getItemQuantity(item.getItemId(), false) >= amount) {
                                    final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
                                    if (!ii.isDropRestricted(item.getItemId()) && !ii.isAccountShared(item.getItemId())) {
                                        if (addItemToDB(item, amount, mesos, c.getPlayer().getName(), recipientId, recipientOn)) {
                                            if (ItemConstants.isThrowingStar(item.getItemId()) || ItemConstants.isBullet(item.getItemId())) {
                                                MapleInventoryManipulator.removeFromSlot(c, inv, (byte) itemPos, item.getQuantity(), true);
                                            } else {
                                                MapleInventoryManipulator.removeFromSlot(c, inv, (byte) itemPos, amount, true, false);
                                            }
                                            c.getPlayer().gainMeso(-finalcost, false);
                                            c.send(NPCPacket.sendDuey((byte) 19, null)); // Successfull
                                        } else {
                                            c.send(NPCPacket.sendDuey((byte) 17, null)); // Unsuccessful
                                        }
                                    } else {
                                        c.send(NPCPacket.sendDuey((byte) 17, null)); // Unsuccessfull
                                    }
                                } else {
                                    c.send(NPCPacket.sendDuey((byte) 17, null)); // Unsuccessfull
                                }
                            } else {
                                if (addMesoToDB(mesos, c.getPlayer().getName(), recipientId, recipientOn)) {
                                    c.getPlayer().gainMeso(-finalcost, false);

                                    c.send(NPCPacket.sendDuey((byte) 19, null)); // Successfull
                                } else {
                                    c.send(NPCPacket.sendDuey((byte) 17, null)); // Unsuccessfull
                                }
                            }
                        } else {
                            c.send(NPCPacket.sendDuey((byte) 15, null)); // Same acc error
                        }
                    } else {
                        c.send(NPCPacket.sendDuey((byte) 14, null)); // Name does not exist
                    }
                } else {
                    c.send(NPCPacket.sendDuey((byte) 12, null)); // Not enough mesos
                }
                break;
            }
            case 5: { // Recieve Package
                if (c.getPlayer().getConversation() != 2) {
                    return;
                }
                final int packageid = slea.readInt();
                final MapleDueyActions dp = loadSingleItem(packageid, c.getPlayer().getId());
                if (dp == null) {
                    return;
                }
                if (dp.getItem() != null && !MapleInventoryManipulator.checkSpace(c, dp.getItem().getItemId(), dp.getItem().getQuantity(), dp.getItem().getOwner())) {
                    c.send(NPCPacket.sendDuey((byte) 16, null)); // Not enough Space
                    return;
                } else if (dp.getMesos() < 0 || (dp.getMesos() + c.getPlayer().getMeso()) < 0) {
                    c.send(NPCPacket.sendDuey((byte) 17, null)); // Unsuccessfull
                    return;
                }
                removeItemFromDB(packageid, c.getPlayer().getId()); // Remove first
                if (dp.getItem() != null) {
                    MapleInventoryManipulator.addFromDrop(c, dp.getItem(), false);
                }
                if (dp.getMesos() != 0) {
                    c.getPlayer().gainMeso(dp.getMesos(), false);
                }
                c.send(NPCPacket.removeItemFromDuey(false, packageid));
                break;
            }
            case 6: { // Remove package
                if (c.getPlayer().getConversation() != 2) {
                    return;
                }
                final int packageid = slea.readInt();
                removeItemFromDB(packageid, c.getPlayer().getId());
                c.send(NPCPacket.removeItemFromDuey(true, packageid));
                break;
            }
            case 8: { // Close Duey
                c.getPlayer().setConversation(0);
                break;
            }
            default: {
                LOG.log(System.Logger.Level.INFO, "Unhandled Duey operation : " + slea.toString());
                break;
            }
        }

    }

    private static final boolean addMesoToDB(final int mesos, final String sName, final int recipientID, final boolean isOn) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("INSERT INTO dueypackages (RecieverId, SenderName, Mesos, TimeStamp, Checked, Type) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, recipientID);
            ps.setString(2, sName);
            ps.setInt(3, mesos);
            ps.setLong(4, System.currentTimeMillis());
            ps.setInt(5, isOn ? 0 : 1);
            ps.setInt(6, 3);

            ps.executeUpdate();

            return true;
        } catch (SQLException se) {
            LOG.log(System.Logger.Level.ERROR, "Error saving Duey meso package to SQL", se);
            return false;
        }
    }

    private static final boolean addItemToDB(final IItem item, final int quantity, final int mesos, final String sName, final int recipientID, final boolean isOn) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("INSERT INTO dueypackages (RecieverId, SenderName, Mesos, TimeStamp, Checked, Type) VALUES (?, ?, ?, ?, ?, ?)", DatabaseConnection.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, recipientID);
            ps.setString(2, sName);
            ps.setInt(3, mesos);
            ps.setLong(4, System.currentTimeMillis() + (15 * 24 * 60 * 60 * 1000) );
            ps.setInt(5, isOn ? 0 : 1);

            ps.setInt(6, item.getType());
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    ItemLoader.DUEY.saveItems(Collections.singletonList(new Pair<>(item, ItemConstants.getInventoryType(item.getItemId()))), rs.getInt(1));
                }
            }

            return true;
        } catch (SQLException se) {
            LOG.log(System.Logger.Level.ERROR, "Error saving Duey item package to SQL", se);
            return false;
        }
    }

    public static final List<MapleDueyActions> loadItems(final MapleCharacter chr) {
        List<MapleDueyActions> packages = new LinkedList<>();
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * FROM dueypackages WHERE RecieverId = ?")) {
            ps.setInt(1, chr.getId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final int packageid = rs.getInt("packageid");
                    MapleDueyActions dueypack = getItemByPID(packageid);
                    dueypack.setSender(rs.getString("SenderName"));
                    dueypack.setMesos(rs.getInt("Mesos"));
                    dueypack.setSentTime(rs.getLong("TimeStamp"));
                    if (dueypack.getSentTime() > System.currentTimeMillis()) {
                        packages.add(dueypack);
                    } else {
                        removeItemFromDB(packageid, chr.getId());
                    }
                }
            }
            return packages;
        } catch (SQLException se) {
            LOG.log(System.Logger.Level.ERROR, "Error loading Duey packages from SQL", se);
            return null;
        }
    }

    public static final MapleDueyActions loadSingleItem(final int packageid, final int charid) {
        List<MapleDueyActions> packages = new LinkedList<>();
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * FROM dueypackages WHERE PackageId = ? and RecieverId = ?")) {
            ps.setInt(1, packageid);
            ps.setInt(2, charid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    MapleDueyActions dueypack = getItemByPID(packageid);
                    dueypack.setSender(rs.getString("SenderName"));
                    dueypack.setMesos(rs.getInt("Mesos"));
                    dueypack.setSentTime(rs.getLong("TimeStamp"));
                    if (dueypack.getSentTime() > System.currentTimeMillis()) {
                        packages.add(dueypack);
                    } else {
                        removeItemFromDB(packageid, charid);
                    }
                    return dueypack;
                } else {
                    return null;
                }
            }
        } catch (SQLException se) {
            return null;
        }
    }

    public static final void reciveMsg(final MapleClient c, final int recipientId) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("UPDATE dueypackages SET Checked = 0 where RecieverId = ?")) {
            ps.setInt(1, recipientId);
            ps.executeUpdate();
        } catch (SQLException se) {
            LOG.log(System.Logger.Level.ERROR, "Error marking Duey packages as read", se);
        }
    }

    private static final void removeItemFromDB(final int packageid, final int charid) {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("DELETE FROM dueypackages WHERE PackageId = ? and RecieverId = ?")) {
            ps.setInt(1, packageid);
            ps.setInt(2, charid);
            ps.executeUpdate();
        } catch (SQLException se) {
            LOG.log(System.Logger.Level.ERROR, "Error deleting Duey package from SQL", se);
        }
    }

    private static final MapleDueyActions getItemByPID(final int packageid) {
        try {
            Map<Integer, Pair<IItem, MapleInventoryType>> iter = ItemLoader.DUEY.loadItems(false, packageid);
            if (iter != null && iter.size() > 0) {
                for (Pair<IItem, MapleInventoryType> i : iter.values()) {
                    return new MapleDueyActions(packageid, i.getLeft());
                }
            }
        } catch (Exception se) {
            LOG.log(System.Logger.Level.ERROR, "Error loading Duey package items", se);
        }
        return new MapleDueyActions(packageid);
    }
}
