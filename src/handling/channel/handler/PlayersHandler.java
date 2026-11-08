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

import client.inventory.IItem;
import client.MapleCharacter;
import client.MapleClient;
import client.inventory.MapleInventoryType;
import client.MapleStat;
import client.anticheat.CheatingOffense;
import constants.GameConstants;
import constants.ItemConstants;
import scripting.ReactorScriptManager;
import server.events.MapleCoconut;
import server.events.MapleCoconut.MapleCoconuts;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import server.events.MapleEventType;
import server.maps.MapleDoor;
import server.maps.MapleMapObject;
import server.maps.MapleMapObjectType;
import server.maps.MapleReactor;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.packet.CharacterPacket;
import tools.packet.ChatPacket;
import tools.packet.EventPacket;

public class PlayersHandler {

    private static final System.Logger LOG = System.getLogger(PlayersHandler.class.getName());

    public static void note(final SeekableLittleEndianAccessor slea, final MapleCharacter chr) {
        final byte type = slea.readByte();

        switch (type) {
            case 0:
                String name = slea.readMapleAsciiString();
                String msg = slea.readMapleAsciiString();
                boolean fame = slea.readByte() > 0;
                slea.readInt(); //0?
                IItem itemz = chr.getCashInventory().findByCashId((int) slea.readLong());
                if (itemz == null || !itemz.getGiftFrom().equalsIgnoreCase(name) || !chr.getCashInventory().canSendNote(itemz.getUniqueId())) {
                    return;
                }
                try {
                    chr.sendNote(name, msg, fame ? 1 : 0);
                    chr.getCashInventory().sendedNote(itemz.getUniqueId());
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.ERROR, "Error sending a cash-item note", e);
                }
                break;
            case 1:
                final byte num = slea.readByte();
                slea.skip(2);

                for (int i = 0; i < num; i++) {
                    final int id = slea.readInt();
                    chr.deleteNote(id, slea.readByte() > 0 ? 1 : 0);
                }
                break;
            default:
                LOG.log(System.Logger.Level.INFO, "Unhandled note action, " + type + "");
        }
    }

    public static void giveFame(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        final int who = slea.readInt();
        final int mode = slea.readByte();

        final int famechange = mode == 0 ? -1 : 1;
        final MapleCharacter target = (MapleCharacter) chr.getMap().getMapObject(who, MapleMapObjectType.PLAYER);

        if (target == chr) { // faming self
            chr.getCheatTracker().registerOffense(CheatingOffense.FAMING_SELF);
            return;
        } else if (chr.getLevel() < 15) {
            chr.getCheatTracker().registerOffense(CheatingOffense.FAMING_UNDER_15);
            return;
        }
        switch (chr.canGiveFame(target)) {
            case OK:
                if (Math.abs(target.getFame() + famechange) <= 30000) {
                    target.addFame(famechange);
                    target.updateSingleStat(MapleStat.FAME, target.getFame());
                }
                if (!chr.isGM()) {
                    chr.hasGivenFame(target);
                }
                c.send(CharacterPacket.giveFameResponse(mode, target.getName(), target.getFame()));
                target.getClient().send(CharacterPacket.receiveFame(mode, chr.getName()));
                break;
            case NOT_TODAY:
                c.send(CharacterPacket.giveFameErrorResponse(3));
                break;
            case NOT_THIS_MONTH:
                c.send(CharacterPacket.giveFameErrorResponse(4));
                break;
        }
    }

    public static void useDoor(final SeekableLittleEndianAccessor slea, final MapleCharacter chr) {
        final int oid = slea.readInt();
        final boolean mode = slea.readByte() == 0; // specifies if backwarp or not, 1 town to target, 0 target to town

        for (MapleMapObject obj : chr.getMap().getAllDoorsThreadsafe()) {
            final MapleDoor door = (MapleDoor) obj;
            if (door.getOwnerId() == oid) {
                door.warp(chr, mode);
                break;
            }
        }
    }

    public static void transformPlayer(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        // D9 A4 FD 00
        // 11 00
        // A0 C0 21 00
        // 07 00 64 66 62 64 66 62 64
        chr.updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final String target = slea.readMapleAsciiString().toLowerCase();

        final IItem toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        switch (itemId) {
            case 2212000:
                for (final MapleCharacter search_chr : c.getPlayer().getMap().getCharactersThreadsafe()) {
                    if (search_chr.getName().toLowerCase().equals(target)) {
                        MapleItemInformationProvider.getInstance().getItemEffect(2210023).applyTo(search_chr);
                        search_chr.dropMessage(6, chr.getName() + " has played a prank on you!");
                        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
                    }
                }
                break;
        }
    }

    public static void hitReactor(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        final int oid = slea.readInt();
        final int charPos = slea.readInt();
        final short stance = slea.readShort();
        final MapleReactor reactor = c.getPlayer().getMap().getReactorByOid(oid);

        if (reactor == null || !reactor.isAlive()) {
            return;
        }
        reactor.hitReactor(charPos, stance, c);
    }

    public static void touchReactor(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        final int oid = slea.readInt();
        final boolean touched = slea.readByte() > 0;
        final MapleReactor reactor = c.getPlayer().getMap().getReactorByOid(oid);
        if (!touched || reactor == null || !reactor.isAlive() || reactor.getReactorId() < 6109013 || reactor.getReactorId() > 6109027) {
            return;
        }
        ReactorScriptManager.getInstance().act(c, reactor); //not sure how touched boolean comes into play
    }

    public static void hitCoconut(SeekableLittleEndianAccessor slea, MapleClient c) {
        /*CB 00 A6 00 06 01
         * A6 00 = coconut id
         * 06 01 = ?
         */
        int id = slea.readShort();
        String co = "農夫的樂趣";
        MapleCoconut map = (MapleCoconut) c.getChannelServer().getEvent(MapleEventType.Coconut);
        if (map == null || !map.isRunning()) {
            map = (MapleCoconut) c.getChannelServer().getEvent(MapleEventType.CokePlay);
            co = "可樂熊";
            if (map == null || !map.isRunning()) {
                return;
            }
        }
        MapleCoconuts nut = map.getCoconut(id);
        if (nut == null || !nut.isHittable()) {
            return;
        }
        if (System.currentTimeMillis() < nut.getHitTime()) {
            return;
        }
        if (nut.getHits() > 2 && Math.random() < 0.4 && !nut.isStopped()) {
            nut.setHittable(false);
            if (Math.random() < 0.01 && map.getStopped() > 0) {
                nut.setStopped(true);
                map.stopCoconut();
                c.getPlayer().getMap().broadcastMessage(EventPacket.hitCoconut(false, id, 1));
                return;
            }
            nut.resetHits(); // For next event (without restarts)
            if (Math.random() < 0.05 && map.getBombings() > 0) {
                c.getPlayer().getMap().broadcastMessage(EventPacket.hitCoconut(false, id, 2));
                map.bombCoconut();
            } else if (map.getFalling() > 0) {
                c.getPlayer().getMap().broadcastMessage(EventPacket.hitCoconut(false, id, 3));
                map.fallCoconut();
                // 保留作為紀錄：原本每打落一顆就廣播一則「某某為某隊得分」的英文訊息，被關掉了。
                // 上面的區域變數 co（農夫的樂趣／可樂熊）只有這兩行在用。
                if (c.getPlayer().getTeam() == 0) {
                    map.addMapleScore();
                    //c.getPlayer().getMap().broadcastMessage(ChatPacket.serverNotice(5, c.getPlayer().getName() + " of Team Maple knocks down a " + co + "."));
                } else {
                    map.addStoryScore();
                    //c.getPlayer().getMap().broadcastMessage(ChatPacket.serverNotice(5, c.getPlayer().getName() + " of Team Story knocks down a " + co + "."));
                }
                c.getPlayer().getMap().broadcastMessage(EventPacket.coconutScore(map.getCoconutScore()));
            }
        } else {
            nut.hit();
            c.getPlayer().getMap().broadcastMessage(EventPacket.hitCoconut(false, id, 1));
        }
    }

    public static void followRequest(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        MapleCharacter tt = c.getPlayer().getMap().getCharacterById(slea.readInt());
        if (slea.readByte() > 0) {
            //1 when changing map
            tt = c.getPlayer().getMap().getCharacterById(c.getPlayer().getFollowId());
            if (tt != null && tt.getFollowId() == c.getPlayer().getId()) {
                tt.setFollowOn(true);
                c.getPlayer().setFollowOn(true);
            } else {
                c.getPlayer().checkFollow();
            }
            return;
        }
        if (slea.readByte() > 0) { //cancelling follow
            tt = c.getPlayer().getMap().getCharacterById(c.getPlayer().getFollowId());
            if (tt != null && tt.getFollowId() == c.getPlayer().getId() && c.getPlayer().isFollowOn()) {
                c.getPlayer().checkFollow();
            }
            return;
        }
        if (tt != null && tt.getPosition().distanceSq(c.getPlayer().getPosition()) < 10000 && tt.getFollowId() == 0 && c.getPlayer().getFollowId() == 0 && tt.getId() != c.getPlayer().getId()) { //estimate, should less
            tt.setFollowId(c.getPlayer().getId());
            tt.setFollowOn(false);
            tt.setFollowInitiator(false);
            c.getPlayer().setFollowOn(false);
            c.getPlayer().setFollowInitiator(false);
            tt.getClient().send(CharacterPacket.followRequest(c.getPlayer().getId()));
        } else {
            c.send(ChatPacket.serverNotice(1, "You are too far away."));
        }
    }

    public static void followReply(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().getFollowId() > 0 && c.getPlayer().getFollowId() == slea.readInt()) {
            MapleCharacter tt = c.getPlayer().getMap().getCharacterById(c.getPlayer().getFollowId());
            if (tt != null && tt.getPosition().distanceSq(c.getPlayer().getPosition()) < 10000 && tt.getFollowId() == 0 && tt.getId() != c.getPlayer().getId()) { //estimate, should less
                boolean accepted = slea.readByte() > 0;
                if (accepted) {
                    tt.setFollowId(c.getPlayer().getId());
                    tt.setFollowOn(true);
                    tt.setFollowInitiator(true);
                    c.getPlayer().setFollowOn(true);
                    c.getPlayer().setFollowInitiator(false);
                    c.getPlayer().getMap().broadcastMessage(CharacterPacket.followEffect(tt.getId(), c.getPlayer().getId(), null));
                } else {
                    c.getPlayer().setFollowId(0);
                    tt.setFollowId(0);
                    tt.getClient().send(CharacterPacket.getFollowMsg(5));
                }
            } else {
                if (tt != null) {
                    tt.setFollowId(0);
                    c.getPlayer().setFollowId(0);
                }
                c.send(ChatPacket.serverNotice(1, "You are too far away."));
            }
        } else {
            c.getPlayer().setFollowId(0);
        }
    }

    public static void ringAction(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        final byte mode = slea.readByte();
        if (mode == 0) {
            final String name = slea.readMapleAsciiString();
            final int itemid = slea.readInt();
            final int newItemId = 1112300 + (itemid - 2240004);
            final MapleCharacter chr = c.getChannelServer().getPlayerStorage().getCharacterByName(name);
            int errcode = 0;
            if (c.getPlayer().getMarriageId() > 0) {
                errcode = 0x17;
            } else if (chr == null) {
                errcode = 0x12;
            } else if (chr.getMapId() != c.getPlayer().getMapId()) {
                errcode = 0x13;
            } else if (!c.getPlayer().haveItem(itemid, 1) || itemid < 2240004 || itemid > 2240015) {
                errcode = 0x0D;
            } else if (chr.getMarriageId() > 0 || chr.getMarriageItemId() > 0) {
                errcode = 0x18;
            } else if (!MapleInventoryManipulator.checkSpace(c, newItemId, 1, "")) {
                errcode = 0x14;
            } else if (!MapleInventoryManipulator.checkSpace(chr.getClient(), newItemId, 1, "")) {
                errcode = 0x15;
            }
            if (errcode > 0) {
                c.send(EventPacket.sendEngagement((byte) errcode, 0, null, null));
                c.send(CharacterPacket.enableActions());
                return;
            }
            c.getPlayer().setMarriageItemId(itemid);
            chr.getClient().send(EventPacket.sendEngagementRequest(c.getPlayer().getName(), c.getPlayer().getId()));
            //1112300 + (itemid - 2240004)
        } else if (mode == 1) {
            c.getPlayer().setMarriageItemId(0);
        } else if (mode == 2) { //accept/deny proposal
            final boolean accepted = slea.readByte() > 0;
            final String name = slea.readMapleAsciiString();
            final int id = slea.readInt();
            final MapleCharacter chr = c.getChannelServer().getPlayerStorage().getCharacterByName(name);
            if (c.getPlayer().getMarriageId() > 0 || chr == null || chr.getId() != id || chr.getMarriageItemId() <= 0 || !chr.haveItem(chr.getMarriageItemId(), 1) || chr.getMarriageId() > 0) {
                c.send(EventPacket.sendEngagement((byte) 0x1D, 0, null, null));
                c.send(CharacterPacket.enableActions());
                return;
            }
            if (accepted) {
                final int newItemId = 1112300 + (chr.getMarriageItemId() - 2240004);
                if (!MapleInventoryManipulator.checkSpace(c, newItemId, 1, "") || !MapleInventoryManipulator.checkSpace(chr.getClient(), newItemId, 1, "")) {
                    c.send(EventPacket.sendEngagement((byte) 0x15, 0, null, null));
                    c.send(CharacterPacket.enableActions());
                    return;
                }
                MapleInventoryManipulator.addById(c, newItemId, (short) 1);
                MapleInventoryManipulator.removeById(chr.getClient(), MapleInventoryType.USE, chr.getMarriageItemId(), 1, false, false);
                MapleInventoryManipulator.addById(chr.getClient(), newItemId, (short) 1);
                chr.getClient().send(EventPacket.sendEngagement((byte) 0x10, newItemId, chr, c.getPlayer()));
                chr.setMarriageId(c.getPlayer().getId());
                c.getPlayer().setMarriageId(chr.getId());
            } else {
                chr.getClient().send(EventPacket.sendEngagement((byte) 0x1E, 0, null, null));
            }
            c.send(CharacterPacket.enableActions());
            chr.setMarriageItemId(0);
        } else if (mode == 3) { //drop, only works for ETC
            final int itemId = slea.readInt();
            final MapleInventoryType type = ItemConstants.getInventoryType(itemId);
            final IItem item = c.getPlayer().getInventory(type).findById(itemId);
            if (item != null && type == MapleInventoryType.ETC && itemId / 10000 == 421) {
                MapleInventoryManipulator.drop(c, type, item.getPosition(), item.getQuantity());
            }
        }
    }
    
    public static void solomon(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        c.send(CharacterPacket.enableActions());
        c.getPlayer().updateTick(slea.readInt());
        IItem item = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slea.readShort());
        if (item == null || item.getItemId() != slea.readInt() || item.getQuantity() <= 0 || c.getPlayer().getGachExp() > 0 || c.getPlayer().getLevel() > 50 || MapleItemInformationProvider.getInstance().getItemEffect(item.getItemId()).getEXP() <= 0) {
            return;
        }
        c.getPlayer().setGachExp(c.getPlayer().getGachExp() + MapleItemInformationProvider.getInstance().getItemEffect(item.getItemId()).getEXP());
        MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, item.getPosition(), (short) 1, false);
        c.getPlayer().updateSingleStat(MapleStat.GACHAPONEXP, c.getPlayer().getGachExp());
    }

    public static void gachExp(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        c.send(CharacterPacket.enableActions());
        c.getPlayer().updateTick(slea.readInt());
        if (c.getPlayer().getGachExp() <= 0) {
            return;
        }
        c.getPlayer().gainExp(c.getPlayer().getGachExp() * GameConstants.getExpRate_Quest(c.getPlayer().getLevel()), true, true, false , false);
        c.getPlayer().setGachExp(0);
        c.getPlayer().updateSingleStat(MapleStat.GACHAPONEXP, 0);
    }

    public static void updateCharInfo(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        int type = slea.readByte();
        if (type == 0) { // 角色訊息
            String charmessage = slea.readMapleAsciiString();
            c.getPlayer().setcharmessage(charmessage);
        } else if (type == 1) { // 表情
            int expression = slea.readByte();
            c.getPlayer().setexpression(expression);
        } else if (type == 2) { // 生日及星座
            int blood = slea.readByte();
            int month = slea.readByte();
            int day = slea.readByte();
            int constellation = slea.readByte();
            c.getPlayer().setblood(blood);
            c.getPlayer().setmonth(month);
            c.getPlayer().setday(day);
            c.getPlayer().setconstellation(constellation);
        }
    }
}
