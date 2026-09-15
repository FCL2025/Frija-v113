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

import java.util.Map;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.awt.Point;

import client.inventory.Equip;
import client.inventory.IEquip;
import client.inventory.IEquip.ScrollResult;
import client.inventory.IItem;
import client.ISkill;
import client.inventory.ItemFlag;
import client.inventory.MaplePet;
import client.inventory.MaplePet.PetFlag;
import client.inventory.MapleMount;
import client.MapleCharacter;
import client.MapleClient;
import client.inventory.MapleInventoryType;
import client.inventory.MapleInventory;
import client.MapleStat;
import client.PlayerStats;
import constants.GameConstants;
import constants.ItemConstants;
import constants.JobConstants;
import client.SkillFactory;
import client.anticheat.CheatingOffense;
import handling.channel.ChannelServer;
import handling.world.Broadcast;
import handling.world.MaplePartyCharacter;
import java.awt.Rectangle;
import java.util.Collections;
import java.util.concurrent.locks.Lock;
import server.AutobanManager;
import server.Randomizer;
import server.RandomRewards;
import server.MapleShopFactory;
import server.MapleItemInformationProvider;
import server.MapleInventoryManipulator;
import server.StructRewardItem;
import server.quest.MapleQuest;
import server.maps.SavedLocationType;
import server.maps.FieldLimitType;
import server.maps.MapleMap;
import server.maps.MapleMapItem;
import server.maps.MapleMapObject;
import server.maps.MapleMapObjectType;
import server.life.MapleMonster;
import server.life.MapleLifeFactory;
import scripting.NPCScriptManager;
import server.StructPotentialItem;
import server.maps.MapleMist;
import server.shops.HiredMerchant;
import server.shops.IMaplePlayerShop;
import tools.Pair;
import tools.packet.BuffPacket;
import tools.packet.CharacterPacket;
import tools.packet.ChatPacket;
import tools.packet.EventPacket;
import tools.packet.InventoryPacket;
import tools.packet.MTSCSPacket;
import tools.packet.MapPacket;
import tools.packet.MobPacket;
import tools.packet.PetPacket;
import tools.packet.PlayerShopPacket;
import tools.data.input.SeekableLittleEndianAccessor;

public class InventoryHandler {

    private static final System.Logger LOG = System.getLogger(InventoryHandler.class.getName());

    public static final void itemMove(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().getPlayerShop() != null || c.getPlayer().getConversation() > 0 || c.getPlayer().getTrade() != null) { //hack
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final MapleInventoryType type = MapleInventoryType.getByType(slea.readByte()); //04
        final short src = slea.readShort();                                            //01 00
        final short dst = slea.readShort();                                            //00 00
        final short quantity = slea.readShort();                                       //53 01

        if (src < 0 && dst > 0) {
            MapleInventoryManipulator.unequip(c, src, dst);
        } else if (dst < 0) {
            MapleInventoryManipulator.equip(c, src, dst);
        } else if (dst == 0) {
            MapleInventoryManipulator.drop(c, type, src, quantity);
        } else {
            MapleInventoryManipulator.move(c, type, src, dst);
        }
    }

    public static final void itemSort(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());

        final MapleInventoryType pInvType = MapleInventoryType.getByType(slea.readByte());
        if (pInvType == MapleInventoryType.UNDEFINED) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        final MapleInventory pInv = c.getPlayer().getInventory(pInvType); //Mode should correspond with MapleInventoryType
        boolean sorted = false;

        while (!sorted) {
            final byte freeSlot = (byte) pInv.getNextFreeSlot();
            if (freeSlot != -1) {
                byte itemSlot = -1;
                for (byte i = (byte) (freeSlot + 1); i <= pInv.getSlotLimit(); i++) {
                    if (pInv.getItem(i) != null) {
                        itemSlot = i;
                        break;
                    }
                }
                if (itemSlot > 0) {
                    MapleInventoryManipulator.move(c, pInvType, itemSlot, freeSlot);
                } else {
                    sorted = true;
                }
            } else {
                sorted = true;
            }
        }
        c.send(EventPacket.finishedSort(pInvType.getType()));
        c.send(CharacterPacket.enableActions());
    }

    public static final void itemGather(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        // [41 00] [E5 1D 55 00] [01]
        // [32 00] [01] [01] // Sent after

        c.getPlayer().updateTick(slea.readInt());
        final byte mode = slea.readByte();
        final MapleInventoryType invType = MapleInventoryType.getByType(mode);
        MapleInventory Inv = c.getPlayer().getInventory(invType);

        final List<IItem> itemMap = new LinkedList<>();
        for (IItem item : Inv.list()) {
            itemMap.add(item.copy()); // clone all  items T___T.
        }
        for (IItem itemStats : itemMap) {
            MapleInventoryManipulator.removeById(c, invType, itemStats.getItemId(), itemStats.getQuantity(), true, false);
        }

        final List<IItem> sortedItems = sortItems(itemMap);
        for (IItem item : sortedItems) {
            MapleInventoryManipulator.addFromDrop(c, item, false);
        }
        c.send(EventPacket.finishedGather(mode));
        c.send(CharacterPacket.enableActions());
        itemMap.clear();
        sortedItems.clear();
    }

    private static final List<IItem> sortItems(final List<IItem> passedMap) {
        final List<Integer> itemIds = new ArrayList<>(); // empty list.
        for (IItem item : passedMap) {
            itemIds.add(item.getItemId()); // adds all item ids to the empty list to be sorted.
        }
        Collections.sort(itemIds); // sorts item ids

        final List<IItem> sortedList = new LinkedList<>(); // ordered list pl0x <3.

        for (Integer val : itemIds) {
            for (IItem item : passedMap) {
                if (val == item.getItemId()) { // Goes through every index and finds the first value that matches
                    sortedList.add(item);
                    passedMap.remove(item);
                    break;
                }
            }
        }
        return sortedList;
    }

    public static final boolean useRewardItem(final byte slot, final int itemId, final MapleClient c, final MapleCharacter chr) {
        final IItem toUse = c.getPlayer().getInventory(ItemConstants.getInventoryType(itemId)).getItem(slot);
        c.send(CharacterPacket.enableActions());
        if (toUse != null && toUse.getQuantity() >= 1 && toUse.getItemId() == itemId) {
            if (chr.getInventory(MapleInventoryType.EQUIP).getNextFreeSlot() > -1 && chr.getInventory(MapleInventoryType.USE).getNextFreeSlot() > -1 && chr.getInventory(MapleInventoryType.SETUP).getNextFreeSlot() > -1 && chr.getInventory(MapleInventoryType.ETC).getNextFreeSlot() > -1) {
                final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
                final Pair<Integer, List<StructRewardItem>> rewards = ii.getRewardItem(itemId);

                if (rewards != null && rewards.getLeft() > 0) {
                    boolean rewarded = false;
                    while (!rewarded) {
                        for (StructRewardItem reward : rewards.getRight()) {
                            if (reward.prob() > 0 && Randomizer.nextInt(rewards.getLeft()) < reward.prob()) { // Total prob
                                if (ItemConstants.getInventoryType(reward.itemid()) == MapleInventoryType.EQUIP) {
                                    final IItem item = ii.getEquipById(reward.itemid());
                                    if (reward.period() > 0) {
                                        item.setExpiration(System.currentTimeMillis() + (reward.period() * 60 * 60 * 10));
                                    }
                                    MapleInventoryManipulator.addbyItem(c, item);
                                } else {
                                    MapleInventoryManipulator.addById(c, reward.itemid(), reward.quantity());
                                }
                                MapleInventoryManipulator.removeById(c, ItemConstants.getInventoryType(itemId), itemId, 1, false, false);

                                c.send(InventoryPacket.showRewardItemAnimation(reward.itemid(), reward.effect()));
                                chr.getMap().broadcastMessage(chr, InventoryPacket.showRewardItemAnimation(reward.itemid(), reward.effect(), chr.getId()), false);
                                rewarded = true;
                                return true;
                            }
                        }
                    }
                } else {
                    chr.dropMessage(6, "Unknown error.");
                }
            } else {
                chr.dropMessage(6, "你有一個欄位滿了 請空出來再打開");
            }
        }
        return false;
    }

    public static final void useItem(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (chr == null || !chr.isAlive() || chr.getMapId() == 749040100 || chr.getMap() == null/* || chr.hasDisease(MapleDisease.POTION)*/) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        final long time = System.currentTimeMillis();
        if (chr.getNextConsume() > time) {
            chr.dropMessage(5, "You may not use this item yet.");
            c.send(CharacterPacket.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        if (!FieldLimitType.PotionUse.check(chr.getMap().getFieldLimit()) || chr.getMapId() == 610030600) { //cwk quick hack
            if (MapleItemInformationProvider.getInstance().getItemEffect(toUse.getItemId()).applyTo(chr)) {
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
                if (chr.getMap().getConsumeItemCoolTime() > 0) {
                    chr.setNextConsume(time + (chr.getMap().getConsumeItemCoolTime() * 1000));
                }
            }

        } else {
            c.send(CharacterPacket.enableActions());
        }
    }

    public static final void useReturnScroll(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (!chr.isAlive() || chr.getMapId() == 749040100) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        if (!FieldLimitType.PotionUse.check(chr.getMap().getFieldLimit())) {
            if (MapleItemInformationProvider.getInstance().getItemEffect(toUse.getItemId()).applyReturnScroll(chr)) {
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
            } else {
                c.send(CharacterPacket.enableActions());
            }
        } else {
            c.send(CharacterPacket.enableActions());
        }
    }

    public static final void useMagnify(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        c.getPlayer().updateTick(slea.readInt());
        final IItem magnify = c.getPlayer().getInventory(MapleInventoryType.USE).getItem((byte) slea.readShort());
        final IItem toReveal = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readShort());
        if (magnify == null || toReveal == null) {
            c.send(InventoryPacket.getInventoryFull());
            return;
        }
        final Equip eqq = (Equip) toReveal;
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int reqLevel = ii.getReqLevel(eqq.getItemId()) / 10;
        if (eqq.getState() == 1 && (magnify.getItemId() == 2460003 || (magnify.getItemId() == 2460002 && reqLevel <= 12) || (magnify.getItemId() == 2460001 && reqLevel <= 7) || (magnify.getItemId() == 2460000 && reqLevel <= 3))) {
            final List<List<StructPotentialItem>> pots = new LinkedList<>(ii.getAllPotentialInfo().values());
            int new_state = Math.abs(eqq.getPotential1());
            if (new_state > 7 || new_state < 5) { //luls
                new_state = 5;
            }
            final int lines = (eqq.getPotential2() != 0 ? 3 : 2);
            while (eqq.getState() != new_state) {
                //31001 = haste, 31002 = door, 31003 = se, 31004 = hb
                for (int i = 0; i < lines; i++) { //2 or 3 line
                    boolean rewarded = false;
                    while (!rewarded) {
                        StructPotentialItem pot = pots.get(Randomizer.nextInt(pots.size())).get(reqLevel);
                        if (pot != null && pot.reqLevel / 10 <= reqLevel && ItemConstants.optionTypeFits(pot.optionType, eqq.getItemId()) && ItemConstants.potentialIDFits(pot.potentialID, new_state, i)) { //optionType
                            //have to research optionType before making this truely sea-like
                            if (i == 0) {
                                eqq.setPotential1(pot.potentialID);
                            } else if (i == 1) {
                                eqq.setPotential2(pot.potentialID);
                            } else if (i == 2) {
                                eqq.setPotential3(pot.potentialID);
                            }
                            rewarded = true;
                        }
                    }
                }
            }
            c.send(InventoryPacket.scrolledItem(magnify, toReveal, false, true));
            c.getPlayer().getMap().broadcastMessage(InventoryPacket.getPotentialReset(c.getPlayer().getId(), eqq.getPosition()));
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, magnify.getPosition(), (short) 1, false);
        } else {
            c.send(InventoryPacket.getInventoryFull());
            return;
        }
    }

    public static final boolean useUpgradeScroll(final byte slot, final byte dst, final byte ws, final MapleClient c, final MapleCharacter chr) {
        return useUpgradeScroll(slot, dst, ws, c, chr, 0);
    }

    public static final boolean useUpgradeScroll(final byte slot, final byte dst, final byte ws, final MapleClient c, final MapleCharacter chr, final int vegas) {
        boolean whiteScroll = false; // white scroll being used?
        boolean legendarySpirit = false; // legendary spirit skill
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();

        if ((ws & 2) == 2) {
            whiteScroll = true;
        }

        IEquip toScroll;
        if (dst < 0) {
            toScroll = (IEquip) chr.getInventory(MapleInventoryType.EQUIPPED).getItem(dst);
        } else { // legendary spirit
            legendarySpirit = true;
            toScroll = (IEquip) chr.getInventory(MapleInventoryType.EQUIP).getItem(dst);
        }
        if (toScroll == null) {
            return false;
        }
        final byte oldLevel = toScroll.getLevel();
        final byte oldEnhance = toScroll.getEnhance();
        final byte oldState = toScroll.getState();
        final byte oldFlag = toScroll.getFlag();
        final byte oldSlots = toScroll.getUpgradeSlots();

        IItem scroll = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        if (scroll == null) {
            c.send(InventoryPacket.getInventoryFull());
            return false;
        }
        if (!ItemConstants.isSpecialScroll(scroll.getItemId()) && !ItemConstants.isCleanSlate(scroll.getItemId()) && !ItemConstants.isEquipScroll(scroll.getItemId()) && !ItemConstants.isPotentialScroll(scroll.getItemId())) {
            if (toScroll.getUpgradeSlots() < 1) {
                c.send(InventoryPacket.getInventoryFull());
                return false;
            }
        } else if (ItemConstants.isEquipScroll(scroll.getItemId())) {
            if (toScroll.getUpgradeSlots() >= 1 || toScroll.getEnhance() >= 100 || vegas > 0 || ii.isCash(toScroll.getItemId())) {
                c.send(InventoryPacket.getInventoryFull());
                return false;
            }
        } else if (ItemConstants.isPotentialScroll(scroll.getItemId())) {
            if (toScroll.getState() >= 1 || (toScroll.getLevel() == 0 && toScroll.getUpgradeSlots() == 0) || vegas > 0 || ii.isCash(toScroll.getItemId())) {
                c.send(InventoryPacket.getInventoryFull());
                return false;
            }
        }
        if (!ItemConstants.canScroll(toScroll.getItemId()) && !ItemConstants.isChaosScroll(toScroll.getItemId())) {
            c.send(InventoryPacket.getInventoryFull());
            return false;
        }
        if ((ItemConstants.isCleanSlate(scroll.getItemId()) || ItemConstants.isTablet(scroll.getItemId()) || ItemConstants.isChaosScroll(scroll.getItemId())) && (vegas > 0 || ii.isCash(toScroll.getItemId()))) {
            c.send(InventoryPacket.getInventoryFull());
            return false;
        }
        if (ItemConstants.isTablet(scroll.getItemId()) && toScroll.getDurability() < 0) { //not a durability item
            c.send(InventoryPacket.getInventoryFull());
            return false;
        } else if (!ItemConstants.isTablet(scroll.getItemId()) && toScroll.getDurability() >= 0) {
            c.send(InventoryPacket.getInventoryFull());
            return false;
        }

        IItem wscroll = null;

        // Anti cheat and validation
        List<Integer> scrollReqs = ii.getScrollReqs(scroll.getItemId());
        if (scrollReqs.size() > 0 && !scrollReqs.contains(toScroll.getItemId())) {
            c.send(InventoryPacket.getInventoryFull());
            return false;
        }

        if (whiteScroll) {
            wscroll = chr.getInventory(MapleInventoryType.USE).findById(2340000);
            if (wscroll == null) {
                whiteScroll = false;
            }
        }
        if (scroll.getItemId() == 2049115 && toScroll.getItemId() != 1003068) {
            //ravana
            return false;
        }
        if (ItemConstants.isTablet(scroll.getItemId())) {
            switch (scroll.getItemId() % 1000 / 100) {
                case 0: //1h
                    if (ItemConstants.isTwoHanded(toScroll.getItemId()) || !ItemConstants.isWeapon(toScroll.getItemId())) {
                        return false;
                    }
                    break;
                case 1: //2h
                    if (!ItemConstants.isTwoHanded(toScroll.getItemId()) || !ItemConstants.isWeapon(toScroll.getItemId())) {
                        return false;
                    }
                    break;
                case 2: //armor
                    if (ItemConstants.isAccessory(toScroll.getItemId()) || ItemConstants.isWeapon(toScroll.getItemId())) {
                        return false;
                    }
                    break;
                case 3: //accessory
                    if (!ItemConstants.isAccessory(toScroll.getItemId()) || ItemConstants.isWeapon(toScroll.getItemId())) {
                        return false;
                    }
                    break;
            }
        } else if (!ItemConstants.isAccessoryScroll(scroll.getItemId()) && !ItemConstants.isChaosScroll(scroll.getItemId()) && !ItemConstants.isCleanSlate(scroll.getItemId()) && !ItemConstants.isEquipScroll(scroll.getItemId()) && !ItemConstants.isPotentialScroll(scroll.getItemId())) {
            if (!ii.canScroll(scroll.getItemId(), toScroll.getItemId())) {
                return false;
            }
        }
        if (ItemConstants.isAccessoryScroll(scroll.getItemId()) && !ItemConstants.isAccessory(toScroll.getItemId())) {
            return false;
        }
        if (scroll.getQuantity() <= 0) {
            return false;
        }

        if (legendarySpirit && vegas == 0) {
            if (chr.getSkillLevel(SkillFactory.getSkill(1003)) <= 0 && chr.getSkillLevel(SkillFactory.getSkill(10001003)) <= 0 && chr.getSkillLevel(SkillFactory.getSkill(20001003)) <= 0 && chr.getSkillLevel(SkillFactory.getSkill(20011003)) <= 0 && chr.getSkillLevel(SkillFactory.getSkill(30001003)) <= 0) {
                AutobanManager.getInstance().addPoints(c, 50, 120000, "Using the Skill 'Legendary Spirit' without having it.");
                return false;
            }
        }

        // Scroll Success/ Failure/ Curse
        final IEquip scrolled = (IEquip) ii.scrollEquipWithId(toScroll, scroll, whiteScroll, chr, vegas);
        ScrollResult scrollSuccess;
        if (scrolled == null) {
            scrollSuccess = IEquip.ScrollResult.CURSE;
        } else if (scrolled.getLevel() > oldLevel || scrolled.getEnhance() > oldEnhance || scrolled.getState() > oldState || scrolled.getFlag() > oldFlag) {
            scrollSuccess = IEquip.ScrollResult.SUCCESS;
        } else if ((ItemConstants.isCleanSlate(scroll.getItemId()) && scrolled.getUpgradeSlots() > oldSlots)) {
            scrollSuccess = IEquip.ScrollResult.SUCCESS;
        } else {
            scrollSuccess = IEquip.ScrollResult.FAIL;
        }

        // Update
        chr.getInventory(MapleInventoryType.USE).removeItem(scroll.getPosition(), (short) 1, false);
        if (whiteScroll) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, wscroll.getPosition(), (short) 1, false, false);
        }

        if (scrollSuccess == IEquip.ScrollResult.CURSE) {
            c.send(InventoryPacket.scrolledItem(scroll, toScroll, true, false));
            if (dst < 0) {
                chr.getInventory(MapleInventoryType.EQUIPPED).removeItem(toScroll.getPosition());
            } else {
                chr.getInventory(MapleInventoryType.EQUIP).removeItem(toScroll.getPosition());
            }
        } else if (vegas == 0) {
            c.send(InventoryPacket.scrolledItem(scroll, scrolled, false, false));
        }

        chr.getMap().broadcastMessage(chr, InventoryPacket.getScrollEffect(c.getPlayer().getId(), scrollSuccess, legendarySpirit), vegas == 0);

        // equipped item was scrolled and changed
        if (dst < 0 && (scrollSuccess == IEquip.ScrollResult.SUCCESS || scrollSuccess == IEquip.ScrollResult.CURSE) && vegas == 0) {
            chr.equipChanged();
        }
        return true;
    }

    public static final void useCatchItem(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemid = slea.readInt();
        final MapleMonster mob = chr.getMap().getMonsterByOid(slea.readInt());
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemid && mob != null) {
            switch (itemid) {
                case 2270004: { //Purification Marble
                    final MapleMap map = chr.getMap();

                    if (mob.getHp() <= mob.getMobMaxHp() / 2) {
                        map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 1));
                        map.killMonster(mob, chr, true, false, (byte) 0);
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1, false, false);
                        MapleInventoryManipulator.addById(c, 4001169, (short) 1);
                    } else {
                        map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 0));
                        chr.dropMessage(5, "The monster has too much physical strength, so you cannot catch it.");
                    }
                    break;
                }
                case 2270002: { // Characteristic Stone
                    final MapleMap map = chr.getMap();

                    if (mob.getHp() <= mob.getMobMaxHp() / 2) {
                        map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 1));
                        map.killMonster(mob, chr, true, false, (byte) 0);
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1, false, false);
                    } else {
                        map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 0));
                        chr.dropMessage(5, "The monster has too much physical strength, so you cannot catch it.");
                    }
                    break;
                }
                case 2270000: { // Pheromone Perfume
                    if (mob.getId() != 9300101) {
                        break;
                    }
                    final MapleMap map = c.getPlayer().getMap();

                    map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 1));
                    map.killMonster(mob, chr, true, false, (byte) 0);
                    MapleInventoryManipulator.addById(c, 1902000, (short) 1, null);
                    MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1, false, false);
                    break;
                }
                case 2270003: { // Cliff's Magic Cane
                    if (mob.getId() != 9500320) {
                        break;
                    }
                    final MapleMap map = c.getPlayer().getMap();

                    if (mob.getHp() <= mob.getMobMaxHp() / 2) {
                        map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 1));
                        map.killMonster(mob, chr, true, false, (byte) 0);
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1, false, false);
                    } else {
                        map.broadcastMessage(MobPacket.catchMonster(mob.getId(), itemid, (byte) 0));
                        chr.dropMessage(5, "The monster has too much physical strength, so you cannot catch it.");
                    }
                    break;
                }
            }
        }
        c.send(CharacterPacket.enableActions());
    }

    public static final void useMountFood(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemid = slea.readInt(); //2260000 usually
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        final MapleMount mount = chr.getMount();

        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemid && mount != null) {
            final int fatigue = mount.getFatigue();

            boolean levelup = false;
            mount.setFatigue((byte) -30);

            if (fatigue > 0) {
                mount.increaseExp();
                final int level = mount.getLevel();
                if (mount.getExp() >= GameConstants.getMountExpNeededForLevel(level + 1) && level < 31) {
                    mount.setLevel((byte) (level + 1));
                    levelup = true;
                }
            }
            chr.getMap().broadcastMessage(BuffPacket.updateMount(chr, levelup));
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
        }
        c.send(CharacterPacket.enableActions());
    }

    public static final void useScriptedNPCItem(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);
        long expiration_days = 0;
        int mountid = 0;

        if (toUse != null && toUse.getQuantity() >= 1 && toUse.getItemId() == itemId) {
            switch (toUse.getItemId()) {
                case 2430007: // Blank Compass
                {
                    final MapleInventory inventory = chr.getInventory(MapleInventoryType.SETUP);
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);

                    if (inventory.countById(3994102) >= 20 // Compass Letter "North"
                            && inventory.countById(3994103) >= 20 // Compass Letter "South"
                            && inventory.countById(3994104) >= 20 // Compass Letter "East"
                            && inventory.countById(3994105) >= 20) { // Compass Letter "West"
                        MapleInventoryManipulator.addById(c, 2430008, (short) 1); // Gold Compass
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994102, 20, false, false);
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994103, 20, false, false);
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994104, 20, false, false);
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.SETUP, 3994105, 20, false, false);
                    } else {
                        MapleInventoryManipulator.addById(c, 2430007, (short) 1); // Blank Compass
                    }
                    NPCScriptManager.getInstance().start(c, 2084001);
                    break;
                }
                case 2430008: // Gold Compass
                {
                    chr.saveLocation(SavedLocationType.RICHIE);
                    MapleMap map;
                    boolean warped = false;

                    for (int i = 390001000; i <= 390001004; i++) {
                        map = c.getChannelServer().getMapFactory().getMap(i);

                        if (map.getCharactersSize() == 0) {
                            chr.changeMap(map, map.getPortal(0));
                            warped = true;
                            break;
                        }
                    }
                    if (warped) { // Removal of gold compass
                        MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, 2430008, 1, false, false);
                    } else { // Or mabe some other message.
                        c.getPlayer().dropMessage(5, "All maps are currently in use, please try again later.");
                    }
                    break;
                }
                case 2430112: //miracle cube
                    if (c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() >= 1) {
                        if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430112) >= 25) {
                            if (MapleInventoryManipulator.checkSpace(c, 2049400, 1, "") && MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, 2430112, 25, true, false)) {
                                MapleInventoryManipulator.addById(c, 2049400, (short) 1);
                            } else {
                                c.getPlayer().dropMessage(5, "Please make some space.");
                            }
                        } else if (c.getPlayer().getInventory(MapleInventoryType.USE).countById(2430112) >= 10) {
                            if (MapleInventoryManipulator.checkSpace(c, 2049400, 1, "") && MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, 2430112, 10, true, false)) {
                                MapleInventoryManipulator.addById(c, 2049401, (short) 1);
                            } else {
                                c.getPlayer().dropMessage(5, "Please make some space.");
                            }
                        } else {
                            c.getPlayer().dropMessage(5, "There needs to be 10 Fragments for a Potential Scroll, 25 for Advanced Potential Scroll.");
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "Please make some space.");
                    }
                    break;
                case 2430036: //croco 1 day
                    mountid = 1027;
                    expiration_days = 1;
                    break;
                case 2430037: //black scooter 1 day
                    mountid = 1028;
                    expiration_days = 1;
                    break;
                case 2430038: //pink scooter 1 day
                    mountid = 1029;
                    expiration_days = 1;
                    break;
                case 2430039: //clouds 1 day
                    mountid = 1030;
                    expiration_days = 1;
                    break;
                case 2430040: //balrog 1 day
                    mountid = 1031;
                    expiration_days = 1;
                    break;
                case 2430053: //croco 30 day
                    mountid = 1027;
                    expiration_days = 1;
                    break;
                case 2430054: //black scooter 30 day
                    mountid = 1028;
                    expiration_days = 30;
                    break;
                case 2430055: //pink scooter 30 day
                    mountid = 1029;
                    expiration_days = 30;
                    break;
                case 2430056: //mist rog 30 day
                    mountid = 1035;
                    expiration_days = 30;
                    break;
                //race kart 30 day? unknown 2430057
                case 2430072: //ZD tiger 7 day
                    mountid = 1034;
                    expiration_days = 7;
                    break;
                case 2430073: //lion 15 day
                    mountid = 1036;
                    expiration_days = 15;
                    break;
                case 2430074: //unicorn 15 day
                    mountid = 1037;
                    expiration_days = 15;
                    break;
                case 2430075: //low rider 15 day
                    mountid = 1038;
                    expiration_days = 15;
                    break;
                case 2430076: //red truck 15 day
                    mountid = 1039;
                    expiration_days = 15;
                    break;
                case 2430077: //gargoyle 15 day
                    mountid = 1040;
                    expiration_days = 15;
                    break;
                case 2430080: //shinjo 20 day
                    mountid = 1042;
                    expiration_days = 20;
                    break;
                case 2430082: //orange mush 7 day
                    mountid = 1044;
                    expiration_days = 7;
                    break;
                case 2430091: //nightmare 10 day
                    mountid = 1049;
                    expiration_days = 10;
                    break;
                case 2430092: //yeti 10 day
                    mountid = 1050;
                    expiration_days = 10;
                    break;
                case 2430093: //ostrich 10 day
                    mountid = 1051;
                    expiration_days = 10;
                    break;
                case 2430101: //pink bear 10 day
                    mountid = 1052;
                    expiration_days = 10;
                    break;
                case 2430102: //transformation robo 10 day
                    mountid = 1053;
                    expiration_days = 10;
                    break;
                case 2430103: //chicken 30 day
                    mountid = 1054;
                    expiration_days = 30;
                    break;
                case 2430117: //lion 1 year
                    mountid = 1036;
                    expiration_days = 365;
                    break;
                case 2430118: //red truck 1 year
                    mountid = 1039;
                    expiration_days = 365;
                    break;
                case 2430119: //gargoyle 1 year
                    mountid = 1040;
                    expiration_days = 365;
                    break;
                case 2430120: //unicorn 1 year
                    mountid = 1037;
                    expiration_days = 365;
                    break;
                case 2430136: //owl 30 day
                    mountid = 1069;
                    expiration_days = 30;
                    break;
                case 2430137: //owl 1 year
                    mountid = 1069;
                    expiration_days = 365;
                    break;
                case 2430201: //giant bunny 60 day
                    mountid = 1096;
                    expiration_days = 60;
                    break;
                case 2430228: //tiny bunny 60 day
                    mountid = 1101;
                    expiration_days = 60;
                    break;
                case 2430229: //bunny rickshaw 60 day
                    mountid = 1102;
                    expiration_days = 60;
                    break;
            }
        }
        if (mountid > 0) {
            mountid += (JobConstants.isAran(c.getPlayer().getJob()) ? 20000000 : (JobConstants.isEvan(c.getPlayer().getJob()) ? 20010000 : (JobConstants.isKOC(c.getPlayer().getJob()) ? 10000000 : (JobConstants.isResist(c.getPlayer().getJob()) ? 30000000 : 0))));
            if (c.getPlayer().getSkillLevel(mountid) > 0) {
                c.getPlayer().dropMessage(5, "You already have this skill.");
            } else if (expiration_days > 0) {
                MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (byte) 1, false);
                c.getPlayer().changeSkillLevel(SkillFactory.getSkill(mountid), (byte) 1, (byte) 1, System.currentTimeMillis() + (expiration_days * 24 * 60 * 60 * 1000));
                c.getPlayer().dropMessage(5, "The skill has been attained.");
            }
        }
        c.send(CharacterPacket.enableActions());
    }

    public static final void useSummonBag(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (!chr.isAlive()) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse != null && toUse.getQuantity() >= 1 && toUse.getItemId() == itemId) {

            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);

            if (c.getPlayer().isGM() || !FieldLimitType.SummoningBag.check(chr.getMap().getFieldLimit())) {
                final List<Pair<Integer, Integer>> toSpawn = MapleItemInformationProvider.getInstance().getSummonMobs(itemId);

                if (toSpawn == null) {
                    c.send(CharacterPacket.enableActions());
                    return;
                }
                MapleMonster ht;
                int type = 0;

                for (int i = 0; i < toSpawn.size(); i++) {
                    if (Randomizer.nextInt(99) <= toSpawn.get(i).getRight()) {
                        ht = MapleLifeFactory.getMonster(toSpawn.get(i).getLeft());
                        chr.getMap().spawnMonster_sSack(ht, chr.getPosition(), type);
                    }
                }
            }
        }
        c.send(CharacterPacket.enableActions());
    }

    public static final void useTreasureChest(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        final short slot = slea.readShort();
        final int itemid = slea.readInt();

        final IItem toUse = chr.getInventory(MapleInventoryType.ETC).getItem((byte) slot);
        if (toUse == null || toUse.getQuantity() <= 0 || toUse.getItemId() != itemid) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        int reward;
        int keyIDforRemoval = 0;
        String box;

        switch (toUse.getItemId()) {
            case 4280000: // Gold box
                reward = RandomRewards.getInstance().getGoldBoxReward();
                keyIDforRemoval = 5490000;
                box = "金寶箱";
                break;
            case 4280001: // Silver box
                reward = RandomRewards.getInstance().getSilverBoxReward();
                keyIDforRemoval = 5490001;
                box = "銀寶箱";
                break;
            default: // Up to no good
                return;
        }

        // Get the quantity
        int amount = 1;
        switch (reward) {
            case 2000004:
                amount = 200; // Elixir
                break;
            case 2000005:
                amount = 100; // Power Elixir
                break;
        }
        if (chr.getInventory(MapleInventoryType.CASH).countById(keyIDforRemoval) > 0) {
            final IItem item = MapleInventoryManipulator.addbyId_Gachapon(c, reward, (short) amount);

            if (item == null) {
                chr.dropMessage(5, "請確認是否有金鑰匙或者你身上的空間滿了.");
                c.send(CharacterPacket.enableActions());
                return;
            }
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.ETC, (byte) slot, (short) 1, true);
            MapleInventoryManipulator.removeById(c, MapleInventoryType.CASH, keyIDforRemoval, 1, true, false);
            c.send(InventoryPacket.getShowItemGain(reward, (short) amount, true));

            if (ItemConstants.gachaponRareItem(item.getItemId()) > 0) {
                Broadcast.broadcastMessage(ChatPacket.getGachaponMega("[" + box + "] " + c.getPlayer().getName(), " : 從金寶箱中獲得", item, (byte) 2).getBytes());
            }
        } else {
            chr.dropMessage(5, "請確認是否有銀鑰匙或者你身上的空間滿了.");
            c.send(CharacterPacket.enableActions());
        }
    }

    /**
     * What one cash-item branch of {@link #UseCashItem} wants done once it has run.
     *
     * <p>The 1,247-line {@code switch (itemId)} that used to be the body of {@code
     * UseCashItem} kept two mutable locals - {@code used} and {@code cc} - that the
     * branches wrote and the code after the switch read: {@code used} decides whether
     * the item is consumed, {@code cc} whether the player is bounced to the next
     * channel. Three branches skipped the lot by returning outright. Now that each
     * branch is its own method, those two booleans are its return value and {@link
     * #ABORT} is that bare {@code return;} - it skips the consumption, the {@code
     * enableActions()} and the channel change, exactly as returning from the middle
     * of the switch used to.
     */
    private enum CashUse {

        /** Neither: the branch ran and asked for nothing. The old plain {@code break;}. */
        NONE(false, false),
        /** Consume one of the item from the cash inventory slot it was used from. */
        USED(true, false),
        /** Change channel without consuming the item. */
        CC(false, true),
        /** Consume the item and change channel. */
        USED_CC(true, true),
        /** Leave {@link #UseCashItem} at once - no consumption, no {@code enableActions()}. */
        ABORT(false, false);

        final boolean used;
        final boolean cc;

        CashUse(final boolean used, final boolean cc) {
            this.used = used;
            this.cc = cc;
        }

        static CashUse of(final boolean used) {
            return used ? USED : NONE;
        }

        static CashUse of(final boolean used, final boolean cc) {
            return used ? (cc ? USED_CC : USED) : (cc ? CC : NONE);
        }
    }

    /**
     * Use one item from the cash inventory.
     *
     * <p>This reads the slot and the item id, checks that the slot really holds that
     * item, and then hands the rest of the packet to the one {@code use…} method
     * registered below for that id. Each of those methods is an arm of this table and
     * nothing else calls it; the reads it makes from {@code slea} carry on from the two
     * made here, so what an arm reads - and in what order - is part of the protocol and
     * may not be reordered or shared with another arm.
     */
    public static final void useCashItem(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        // 保留作為紀錄：其他版本的這個封包開頭有 tick 欄位，v113 沒有。
        // 陷阱：這一行含 slea.readInt()，直接取消註解會多吃 4 bytes，
        // 底下整個 UseCashItem 的讀取位置全部跑掉。
//        c.getPlayer().updateTick(slea.readInt());
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();

        final IItem toUse = c.getPlayer().getInventory(MapleInventoryType.CASH).getItem(slot);
        if (toUse == null || toUse.getItemId() != itemId || toUse.getQuantity() < 1) {
            c.send(CharacterPacket.enableActions());
            return;
        }

        final CashUse result = switch (itemId) {
            case 5043001, // NPC Teleport Rock
                 5043000 -> useNpcTeleportRock(slea, c); // NPC Teleport Rock
            case 5042000 -> useWarpTicket5042000(c);
            case 5042001 -> useWarpTicket5042001(c);
            case 5560000, // The Teleport tick
                 5561000 -> useTeleportTicket(slea, c); // The VIP Teleport tick
            case 2320000, // The Teleport Rock
                 5041000, // VIP Teleport Rock
                 5040000, // The Teleport Rock
                 5040001 -> useTeleportRock(slea, c, itemId); // Teleport Coke
            case 5050000 -> useApReset(slea, c); // AP Reset
            case 5050001, // SP Reset (1st job)
                 5050002, // SP Reset (2nd job)
                 5050003, // SP Reset (3rd job)
                 5050004 -> useSpReset(slea, c); // SP Reset (4th job)
            case 5500000 -> useMagicHourglass1Day(slea, c); // Magic Hourglass 1 day
            case 5500001 -> useMagicHourglass7Days(slea, c); // Magic Hourglass 7 day
            case 5500002 -> useMagicHourglass20Days(slea, c); // Magic Hourglass 20 day
            case 5500005 -> useMagicHourglass50Days(slea, c); // Magic Hourglass 50 day
            case 5500006 -> useMagicHourglass99Days(slea, c); // Magic Hourglass 99 day
            case 5060000 -> useItemTag(slea, c); // Item Tag
            case 5062000 -> useMiracleCube(slea, c, toUse); //miracle cube
            case 5520001, //p.karma
                 5520000 -> useKarma(slea, c, itemId); // Karma
            case 5570000 -> useViciousHammer(slea, c); // Vicious Hammer
            case 5610001,
                 5610000 -> useVega(slea, c, itemId); // Vega 30
            case 5060001 -> useSealingLock(slea, c); // Sealing Lock
            case 5061000 -> useSealingLock7Days(slea, c); // Sealing Lock 7 days
            case 5061001 -> useSealingLock30Days(slea, c); // Sealing Lock 30 days
            case 5061002 -> useSealingLock90Days(slea, c); // Sealing Lock 90 days
            case 5060003 -> usePeanut(c); //peanut
            case 5070000 -> useMegaphone5070000(slea, c); // Megaphone
            case 5071000 -> useMegaphone5071000(slea, c); // Megaphone
            case 5077000 -> useTripleMegaphone(slea, c); // 3 line Megaphone
            case 5073000 -> useHeartMegaphone(slea, c); // Heart Megaphone
            case 5074000 -> useSkullMegaphone(slea, c); // Skull Megaphone
            case 5072000 -> useSuperMegaphone(slea, c); // Super Megaphone
            case 5076000 -> useItemMegaphone(slea, c); // Item Megaphone
            case 5075000, // MapleTV Messenger
                 5075001, // MapleTV Star Messenger
                 5075002 -> useMapleTvMessengerUnavailable(c); // MapleTV Heart Messenger
            case 5075003, 5075004, 5075005 -> useMapleTvMessenger(slea, c, itemId);
            case 5090100, // Wedding Invitation Card
                 5090000 -> useNote(slea, c); // Note
            case 5100000 -> useCongratulatorySong(c); // Congratulatory Song
            case 5190001, 5190002, 5190003, 5190004, 5190005, 5190006,
                 5190007, 5190008,
                 5190000 -> useAddPetFlag(slea, c, itemId); // Pet Flags
            case 5191001, 5191002, 5191003, 5191004,
                 5191000 -> useRemovePetFlag(slea, c, itemId); // Pet Flags
            case 5170000 -> usePetNameChange(slea, c); // Pet name change
            case 5240000, 5240001, 5240002, 5240003, 5240004, 5240005,
                 5240006, 5240007, 5240008, 5240009, 5240010, 5240011,
                 5240012, 5240013, 5240014, 5240015, 5240016, 5240017,
                 5240018, 5240019, 5240020, 5240021, 5240022, 5240023,
                 5240024, 5240025, 5240026, 5240027,
                 5240028 -> usePetFood(c, itemId); // Pet food
            case 5230000 -> useOwlOfMinerva(slea, c); // owl of minerva
            case 5281001, //idk, but probably
                 5280001, // Gas Skill
                 5281000 -> useGasSkill(c); // Passed gas
            case 5370000 -> useChalkboard(slea, c); // Chalkboard
            case 5370001 -> useBlackboard(slea, c); // BlackBoard
            case 5390000, // Diablo Messenger
                 5390001, // Cloud 9 Messenger
                 5390002, // Loveholic Messenger
                 5390003, // New Year Megassenger 1
                 5390004, // New Year Megassenger 2
                 5390005, // Cute Tiger Messenger
                 5390006 -> useAvatarMegaphone(slea, c, itemId); // Tiger Roar's Messenger
            case 5450000 -> useTravellingMerchant(c); // Mu Mu the Travelling Merchant
            case 5152100, 5152101, 5152102, 5152103, 5152104, 5152105,
                 5152106, 5152107 -> useEyeColourCoupon(c, itemId);
            default -> useOtherCashItem(slea, c, itemId, slot);
        };
        if (result == CashUse.ABORT) {
            return;
        }
        final boolean used = result.used;
        final boolean cc = result.cc;

        if (used) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.CASH, slot, (short) 1, false, true);
        }
        c.send(CharacterPacket.enableActions());
        if (cc) {
            if (!c.getPlayer().isAlive() || c.getPlayer().getEventInstance() != null || FieldLimitType.ChannelSwitch.check(c.getPlayer().getMap().getFieldLimit())) {
                c.getPlayer().dropMessage(1, "Auto change channel failed.");
                return;
            }
            c.getPlayer().dropMessage(5, "Auto changing channels. Please wait.");
            c.getPlayer().changeChannel(c.getChannel() == ChannelServer.getChannelCount() ? 1 : (c.getChannel() + 1));
        }
    }

    private static CashUse useNpcTeleportRock(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final int questid = slea.readShort() & 0xFFFF; // 無號，見 NPCHandler.QuestAction
        final int npcid = slea.readInt();
        final MapleQuest quest = MapleQuest.getInstance(questid);

        if (c.getPlayer().getQuest(quest).getStatus() == 1 && quest.canComplete(c.getPlayer(), npcid)) {
            final int mapId = MapleLifeFactory.getNPCLocation(npcid);
            if (mapId != -1) {
                final MapleMap map = c.getChannelServer().getMapFactory().getMap(mapId);
                if (map.containsNPC(npcid) && !FieldLimitType.VipRock.check(c.getPlayer().getMap().getFieldLimit()) && !FieldLimitType.VipRock.check(map.getFieldLimit()) && c.getPlayer().getEventInstance() == null) {
                    c.getPlayer().changeMap(map, map.getPortal(0));
                }
                used = true;
            } else {
                c.getPlayer().dropMessage(1, "Unknown error has occurred.");
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useWarpTicket5042000(final MapleClient c) {
        boolean used = false;
        if(c.getPlayer().getEventInstance() == null){
            final MapleMap target = c.getChannelServer().getMapFactory().getMap(701000000);
            c.getPlayer().changeMap(target, target.getPortal(0));
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useWarpTicket5042001(final MapleClient c) {
        boolean used = false;
        if(c.getPlayer().getEventInstance() == null){
            final MapleMap target = c.getChannelServer().getMapFactory().getMap(741000000);
            c.getPlayer().changeMap(target, target.getPortal(0));
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useTeleportTicket(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        slea.skip(1);
        final MapleMap target = c.getChannelServer().getMapFactory().getMap(slea.readInt());
        if (c.getPlayer().getEventInstance() == null) { //Makes sure this map doesn't have a forced return map
            c.getPlayer().changeMap(target, target.getPortal(0));
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useTeleportRock(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        boolean used = false;
        if (slea.readByte() == 0) { // Rocktype
            final MapleMap target = c.getChannelServer().getMapFactory().getMap(slea.readInt());
            if ((itemId == 5041000 && c.getPlayer().isRockMap(target.getId())) || (itemId != 5041000 && c.getPlayer().isRegRockMap(target.getId()))) {
                if (!FieldLimitType.VipRock.check(c.getPlayer().getMap().getFieldLimit()) && !FieldLimitType.VipRock.check(target.getFieldLimit()) && c.getPlayer().getEventInstance() == null) { //Makes sure this map doesn't have a forced return map
                    c.getPlayer().changeMap(target, target.getPortal(0));
                    used = true;
                }
            }
        } else {
            final MapleCharacter victim = c.getChannelServer().getPlayerStorage().getCharacterByName(slea.readMapleAsciiString());
            if (victim != null && !victim.isGM() && c.getPlayer().getEventInstance() == null && victim.getEventInstance() == null) {
                if (!FieldLimitType.VipRock.check(c.getPlayer().getMap().getFieldLimit()) && !FieldLimitType.VipRock.check(c.getChannelServer().getMapFactory().getMap(victim.getMapId()).getFieldLimit())) {
                    if (itemId == 5041000 || (victim.getMapId() / 100000000) == (c.getPlayer().getMapId() / 100000000)) { // Viprock or same continent
                        c.getPlayer().changeMap(victim.getMap(), victim.getMap().findClosestSpawnpoint(victim.getPosition()));
                        used = true;
                    }
                }
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useApReset(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        List<Pair<MapleStat, Integer>> statupdate = new ArrayList<>(2);
        final int apto = slea.readInt();
        final int apfrom = slea.readInt();

        if (apto == apfrom) {
            return CashUse.of(used); // Hack
        }
        final int job = c.getPlayer().getJob();
        final PlayerStats playerst = c.getPlayer().getStat();
        used = true;

        switch (apto) { // AP to
            case 64: // str
                if (playerst.getStr() >= 999) {
                    used = false;
                }
                break;
            case 128: // dex
                if (playerst.getDex() >= 999) {
                    used = false;
                }
                break;
            case 256: // int
                if (playerst.getInt() >= 999) {
                    used = false;
                }
                break;
            case 512: // luk
                if (playerst.getLuk() >= 999) {
                    used = false;
                }
                break;
            case 2048: // hp
                if (playerst.getMaxHp() >= 30000) {
                    used = false;
                }
                break;
            case 8192: // mp
                if (playerst.getMaxMp() >= 30000) {
                    used = false;
                }
                break;
        }
        switch (apfrom) { // AP to
            case 64: // str
                if (playerst.getStr() <= 4 || (c.getPlayer().getJob() % 1000 / 100 == 1 && playerst.getStr() <= 35)) {
                    used = false;
                }
                break;
            case 128: // dex
                if (playerst.getDex() <= 4 || (c.getPlayer().getJob() % 1000 / 100 == 3 && playerst.getDex() <= 25) || (c.getPlayer().getJob() % 1000 / 100 == 4 && playerst.getDex() <= 25) || (c.getPlayer().getJob() % 1000 / 100 == 5 && playerst.getDex() <= 20)) {
                    used = false;
                }
                break;
            case 256: // int
                if (playerst.getInt() <= 4 || (c.getPlayer().getJob() % 1000 / 100 == 2 && playerst.getInt() <= 20)) {
                    used = false;
                }
                break;
            case 512: // luk
                if (playerst.getLuk() <= 4) {
                    used = false;
                }
                break;
            case 2048: // hp
                if (/*playerst.getMaxMp() < ((c.getPlayer().getLevel() * 14) + 134) || */c.getPlayer().getHpApUsed() <= 0 || c.getPlayer().getHpApUsed() >= 10000) {
                    used = false;
                }
                break;
            case 8192: // mp
                if (/*playerst.getMaxMp() < ((c.getPlayer().getLevel() * 14) + 134) || */c.getPlayer().getHpApUsed() <= 0 || c.getPlayer().getHpApUsed() >= 10000) {
                    used = false;
                }
                break;
        }
        if (used) {
            switch (apto) { // AP to
                case 64: { // str
                    final int toSet = playerst.getStr() + 1;
                    playerst.setStr((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.STR, toSet));
                    break;
                }
                case 128: { // dex
                    final int toSet = playerst.getDex() + 1;
                    playerst.setDex((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.DEX, toSet));
                    break;
                }
                case 256: { // int
                    final int toSet = playerst.getInt() + 1;
                    playerst.setInt((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.INT, toSet));
                    break;
                }
                case 512: { // luk
                    final int toSet = playerst.getLuk() + 1;
                    playerst.setLuk((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.LUK, toSet));
                    break;
                }
                case 2048: // hp
                    int maxhp = playerst.getMaxHp(); // 累加期間用 int，最後 clamp 到 30000 才轉回 short

                    if (job == 0) { // Beginner
                        maxhp += Randomizer.rand(8, 12);
                    } else if ((job >= 100 && job <= 132) || (job >= 3200 && job <= 3212)) { // Warrior
                        ISkill improvingMaxHP = SkillFactory.getSkill(1000001);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp += Randomizer.rand(20, 25);
                        if (improvingMaxHPLevel >= 1) {
                            maxhp += improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if ((job >= 200 && job <= 232) || (JobConstants.isEvan(job))) { // Magician
                        maxhp += Randomizer.rand(10, 20);
                    } else if ((job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 1300 && job <= 1312) || (job >= 1400 && job <= 1412) || (job >= 3300 && job <= 3312)) { // Bowman
                        maxhp += Randomizer.rand(16, 20);
                    } else if ((job >= 500 && job <= 522) || (job >= 3500 && job <= 3512)) { // Pirate
                        ISkill improvingMaxHP = SkillFactory.getSkill(5100000);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp += Randomizer.rand(18, 22);
                        if (improvingMaxHPLevel >= 1) {
                            maxhp += improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 1500 && job <= 1512) { // Pirate
                        ISkill improvingMaxHP = SkillFactory.getSkill(15100000);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp += Randomizer.rand(18, 22);
                        if (improvingMaxHPLevel >= 1) {
                            maxhp += improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 1100 && job <= 1112) { // Soul Master
                        ISkill improvingMaxHP = SkillFactory.getSkill(11000000);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp += Randomizer.rand(36, 42);
                        if (improvingMaxHPLevel >= 1) {
                            maxhp += improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 1200 && job <= 1212) { // Flame Wizard
                        maxhp += Randomizer.rand(15, 21);
                    } else if (job >= 2000 && job <= 2112) { // Aran
                        maxhp += Randomizer.rand(40, 50);
                    } else { // GameMaster
                        maxhp += Randomizer.rand(50, 100);
                    }
                    maxhp = Math.min(30000, Math.abs(maxhp));
                    c.getPlayer().setHpApUsed((short) (c.getPlayer().getHpApUsed() + 1));
                    playerst.setMaxHp((short) maxhp); // clamp 過，必定在 0..30000
                    statupdate.add(new Pair<>(MapleStat.MAXHP, maxhp));
                    break;

                case 8192: // mp
                    int maxmp = playerst.getMaxMp(); // 同上

                    if (job == 0) { // Beginner
                        maxmp += Randomizer.rand(6, 8);
                    } else if (job >= 100 && job <= 132) { // Warrior
                        maxmp += Randomizer.rand(5, 7);
                    } else if ((job >= 200 && job <= 232) || (JobConstants.isEvan(job)) || (job >= 3200 && job <= 3212)) { // Magician
                        ISkill improvingMaxMP = SkillFactory.getSkill(2000001);
                        int improvingMaxMPLevel = c.getPlayer().getSkillLevel(improvingMaxMP);
                        maxmp += Randomizer.rand(18, 20);
                        if (improvingMaxMPLevel >= 1) {
                            maxmp += improvingMaxMP.getEffect(improvingMaxMPLevel).getY() * 2;
                        }
                    } else if ((job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 500 && job <= 522) || (job >= 3200 && job <= 3212) || (job >= 3500 && job <= 3512) || (job >= 1300 && job <= 1312) || (job >= 1400 && job <= 1412) || (job >= 1500 && job <= 1512)) { // Bowman
                        maxmp += Randomizer.rand(10, 12);
                    } else if (job >= 1100 && job <= 1112) { // Soul Master
                        maxmp += Randomizer.rand(6, 9);
                    } else if (job >= 1200 && job <= 1212) { // Flame Wizard
                        ISkill improvingMaxMP = SkillFactory.getSkill(12000000);
                        int improvingMaxMPLevel = c.getPlayer().getSkillLevel(improvingMaxMP);
                        maxmp += Randomizer.rand(18, 20);
                        if (improvingMaxMPLevel >= 1) {
                            maxmp += improvingMaxMP.getEffect(improvingMaxMPLevel).getY() * 2;
                        }
                    } else if (job >= 2000 && job <= 2112) { // Aran
                        maxmp += Randomizer.rand(6, 9);
                    } else { // GameMaster
                        maxmp += Randomizer.rand(50, 100);
                    }
                    maxmp = Math.min(30000, Math.abs(maxmp));
                    c.getPlayer().setHpApUsed((short) (c.getPlayer().getHpApUsed() + 1));
                    playerst.setMaxMp((short) maxmp); // clamp 過，必定在 0..30000
                    statupdate.add(new Pair<>(MapleStat.MAXMP, maxmp));
                    break;
            }
            switch (apfrom) { // AP from
                case 64: { // str
                    final int toSet = playerst.getStr() - 1;
                    playerst.setStr((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.STR, toSet));
                    break;
                }
                case 128: { // dex
                    final int toSet = playerst.getDex() - 1;
                    playerst.setDex((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.DEX, toSet));
                    break;
                }
                case 256: { // int
                    final int toSet = playerst.getInt() - 1;
                    playerst.setInt((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.INT, toSet));
                    break;
                }
                case 512: { // luk
                    final int toSet = playerst.getLuk() - 1;
                    playerst.setLuk((short) toSet);
                    statupdate.add(new Pair<>(MapleStat.LUK, toSet));
                    break;
                }
                case 2048: // HP
                    int maxhp = playerst.getMaxHp(); // 扣回 AP：只會往下減，轉回 short 與原本逐步截斷等價
                    if (job == 0) { // Beginner
                        maxhp -= 12;
                    } else if (job >= 100 && job <= 132) { // Warrior
                        ISkill improvingMaxHP = SkillFactory.getSkill(1000001);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp -= 24;
                        if (improvingMaxHPLevel >= 1) {
                            maxhp -= improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 200 && job <= 232) { // Magician
                        maxhp -= 10;
                    } else if ((job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 1300 && job <= 1312) || (job >= 1400 && job <= 1412) || (job >= 3300 && job <= 3312) || (job >= 3500 && job <= 3512)) { // Bowman, Thief
                        maxhp -= 15;
                    } else if (job >= 500 && job <= 522) { // Pirate
                        ISkill improvingMaxHP = SkillFactory.getSkill(5100000);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp -= 15;
                        if (improvingMaxHPLevel > 0) {
                            maxhp -= improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 1500 && job <= 1512) { // Pirate
                        ISkill improvingMaxHP = SkillFactory.getSkill(15100000);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp -= 15;
                        if (improvingMaxHPLevel > 0) {
                            maxhp -= improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 1100 && job <= 1112) { // Soul Master
                        ISkill improvingMaxHP = SkillFactory.getSkill(11000000);
                        int improvingMaxHPLevel = c.getPlayer().getSkillLevel(improvingMaxHP);
                        maxhp -= 27;
                        if (improvingMaxHPLevel >= 1) {
                            maxhp -= improvingMaxHP.getEffect(improvingMaxHPLevel).getY();
                        }
                    } else if (job >= 1200 && job <= 1212) { // Flame Wizard
                        maxhp -= 12;
                    } else if ((job >= 2000 && job <= 2112) || (job >= 3200 && job <= 3212)) { // Aran
                        maxhp -= 40;
                    } else { // GameMaster
                        maxhp -= 20;
                    }
                    c.getPlayer().setHpApUsed((short) (c.getPlayer().getHpApUsed() - 1));
                    playerst.setHp(maxhp);
                    playerst.setMaxHp((short) maxhp);
                    statupdate.add(new Pair<>(MapleStat.MAXHP, maxhp));
                    break;
                case 8192: // MP
                    int maxmp = playerst.getMaxMp(); // 同上
                    if (job == 0) { // Beginner
                        maxmp -= 8;
                    } else if (job >= 100 && job <= 132) { // Warrior
                        maxmp -= 4;
                    } else if (job >= 200 && job <= 232) { // Magician
                        ISkill improvingMaxMP = SkillFactory.getSkill(2000001);
                        int improvingMaxMPLevel = c.getPlayer().getSkillLevel(improvingMaxMP);
                        maxmp -= 20;
                        if (improvingMaxMPLevel >= 1) {
                            maxmp -= improvingMaxMP.getEffect(improvingMaxMPLevel).getY();
                        }
                    } else if ((job >= 500 && job <= 522) || (job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 1300 && job <= 1312) || (job >= 1400 && job <= 1412) || (job >= 1500 && job <= 1512) || (job >= 3300 && job <= 3312) || (job >= 3500 && job <= 3512)) { // Pirate, Bowman. Thief
                        maxmp -= 10;
                    } else if (job >= 1100 && job <= 1112) { // Soul Master
                        maxmp -= 6;
                    } else if (job >= 1200 && job <= 1212) { // Flame Wizard
                        ISkill improvingMaxMP = SkillFactory.getSkill(12000000);
                        int improvingMaxMPLevel = c.getPlayer().getSkillLevel(improvingMaxMP);
                        maxmp -= 25;
                        if (improvingMaxMPLevel >= 1) {
                            maxmp -= improvingMaxMP.getEffect(improvingMaxMPLevel).getY();
                        }
                    } else if (job >= 2000 && job <= 2112) { // Aran
                        maxmp -= 5;
                    } else { // GameMaster
                        maxmp -= 20;
                    }
                    c.getPlayer().setHpApUsed((short) (c.getPlayer().getHpApUsed() - 1));
                    playerst.setMp(maxmp);
                    playerst.setMaxMp((short) maxmp);
                    statupdate.add(new Pair<>(MapleStat.MAXMP, maxmp));
                    break;
            }
            c.send(CharacterPacket.updatePlayerStats(statupdate, true, c.getPlayer().getJob()));
        }
        return CashUse.of(used);
    }

    private static CashUse useSpReset(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
int skill1 = slea.readInt();
int skill2 = slea.readInt();

ISkill skillSPTo = SkillFactory.getSkill(skill1);
ISkill skillSPFrom = SkillFactory.getSkill(skill2);

if (skillSPTo.isBeginnerSkill() || skillSPFrom.isBeginnerSkill()) {
    return CashUse.of(used);
}
if ((c.getPlayer().getSkillLevel(skillSPTo) + 1 <= skillSPTo.getMaxLevel()) && c.getPlayer().getSkillLevel(skillSPFrom) > 0) {
    c.getPlayer().changeSkillLevel(skillSPFrom, (byte) (c.getPlayer().getSkillLevel(skillSPFrom) - 1), c.getPlayer().getMasterLevel(skillSPFrom));
    c.getPlayer().changeSkillLevel(skillSPTo, (byte) (c.getPlayer().getSkillLevel(skillSPTo) + 1), c.getPlayer().getMasterLevel(skillSPTo));
    used = true;
}
        return CashUse.of(used);
    }

    private static CashUse useMagicHourglass1Day(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int days = 1;
        if (item != null && !ItemConstants.isAccessory(item.getItemId()) && item.getExpiration() > -1 && !ii.isCash(item.getItemId()) && System.currentTimeMillis() + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
            boolean change = true;
            for (String z : GameConstants.RESERVED) {
                if (c.getPlayer().getName().indexOf(z) != -1 || item.getOwner().indexOf(z) != -1) {
                    change = false;
                }
            }
            if (change) {
                item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIPPED);
                used = true;
            } else {
                c.getPlayer().dropMessage(1, "It may not be used on this item.");
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useMagicHourglass7Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int days = 7;
        if (item != null && !ItemConstants.isAccessory(item.getItemId()) && item.getExpiration() > -1 && !ii.isCash(item.getItemId()) && System.currentTimeMillis() + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
            boolean change = true;
            for (String z : GameConstants.RESERVED) {
                if (c.getPlayer().getName().indexOf(z) != -1 || item.getOwner().indexOf(z) != -1) {
                    change = false;
                }
            }
            if (change) {
                item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIPPED);
                used = true;
            } else {
                c.getPlayer().dropMessage(1, "It may not be used on this item.");
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useMagicHourglass20Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int days = 20;
        if (item != null && !ItemConstants.isAccessory(item.getItemId()) && item.getExpiration() > -1 && !ii.isCash(item.getItemId()) && System.currentTimeMillis() + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
            boolean change = true;
            for (String z : GameConstants.RESERVED) {
                if (c.getPlayer().getName().indexOf(z) != -1 || item.getOwner().indexOf(z) != -1) {
                    change = false;
                }
            }
            if (change) {
                item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIPPED);
                used = true;
            } else {
                c.getPlayer().dropMessage(1, "It may not be used on this item.");
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useMagicHourglass50Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int days = 50;
        if (item != null && !ItemConstants.isAccessory(item.getItemId()) && item.getExpiration() > -1 && !ii.isCash(item.getItemId()) && System.currentTimeMillis() + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
            boolean change = true;
            for (String z : GameConstants.RESERVED) {
                if (c.getPlayer().getName().indexOf(z) != -1 || item.getOwner().indexOf(z) != -1) {
                    change = false;
                }
            }
            if (change) {
                item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIPPED);
                used = true;
            } else {
                c.getPlayer().dropMessage(1, "It may not be used on this item.");
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useMagicHourglass99Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readShort());
        final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
        final int days = 99;
        if (item != null && !ItemConstants.isAccessory(item.getItemId()) && item.getExpiration() > -1 && !ii.isCash(item.getItemId()) && System.currentTimeMillis() + (100 * 24 * 60 * 60 * 1000L) > item.getExpiration() + (days * 24 * 60 * 60 * 1000L)) {
            boolean change = true;
            for (String z : GameConstants.RESERVED) {
                if (c.getPlayer().getName().indexOf(z) != -1 || item.getOwner().indexOf(z) != -1) {
                    change = false;
                }
            }
            if (change) {
                item.setExpiration(item.getExpiration() + (days * 24 * 60 * 60 * 1000));
                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIPPED);
                used = true;
            } else {
                c.getPlayer().dropMessage(1, "It may not be used on this item.");
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useItemTag(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIPPED).getItem(slea.readByte());

        if (item != null && item.getOwner().equals("")) {
            boolean change = true;
            for (String z : GameConstants.RESERVED) {
                if (c.getPlayer().getName().indexOf(z) != -1 || item.getOwner().indexOf(z) != -1) {
                    change = false;
                }
            }
            if (change) {
                item.setOwner(c.getPlayer().getName());
                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIPPED);
                used = true;
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useMiracleCube(final SeekableLittleEndianAccessor slea, final MapleClient c, final IItem toUse) {
        boolean used = false;
        final IItem item = c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readInt());
        if (item != null && c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() >= 1) {
            final Equip eq = (Equip) item;
            if (eq.getState() >= 5) {
                eq.renewPotential();
                c.send(InventoryPacket.scrolledItem(toUse, item, false, true));
                c.getPlayer().forceReAddItem_NoUpdate(item, MapleInventoryType.EQUIP);
                MapleInventoryManipulator.addById(c, 2430112, (short) 1);
                used = true;
            } else {
                c.getPlayer().dropMessage(5, "Make sure your equipment has a potential.");
            }
        } else {
            c.getPlayer().dropMessage(5, "Make sure you have room for a Fragment.");
        }
        return CashUse.of(used);
    }

    private static CashUse useKarma(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        boolean used = false;
        final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
        final IItem item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());

        if (item != null && !ItemFlag.KARMA_EQ.check(item.getFlag()) && !ItemFlag.KARMA_USE.check(item.getFlag())) {
            if ((itemId == 5520000 && MapleItemInformationProvider.getInstance().isKarmaEnabled(item.getItemId())) || (itemId == 5520001 && MapleItemInformationProvider.getInstance().isPKarmaEnabled(item.getItemId()))) {
                byte flag = item.getFlag();
                if (type == MapleInventoryType.EQUIP) {
                    flag = (byte) (flag | ItemFlag.KARMA_EQ.getValue()); // ItemFlag 的值都 <= 0x10，位元或不會超出 byte
                } else {
                    flag = (byte) (flag | ItemFlag.KARMA_USE.getValue());
                }
                item.setFlag(flag);

                c.getPlayer().forceReAddItem_Flag(item, type);
                used = true;
            }
        }
        return CashUse.of(used);
    }

    private static CashUse useViciousHammer(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        boolean cc = false;
        slea.readInt(); // Inventory type, Hammered eq is always EQ.
        final Equip item = (Equip) c.getPlayer().getInventory(MapleInventoryType.EQUIP).getItem((byte) slea.readInt());
        // another int here, D3 49 DC 00
        if (item != null) {
            if (ItemConstants.canHammer(item.getItemId()) && MapleItemInformationProvider.getInstance().getSlots(item.getItemId()) > 0 && item.getViciousHammer() <= 2) {
                item.setViciousHammer((byte) (item.getViciousHammer() + 1));
                item.setUpgradeSlots((byte) (item.getUpgradeSlots() + 1));

                c.getPlayer().forceReAddItem(item, MapleInventoryType.EQUIP);
                used = true;
                cc = true;
            } else {
                c.getPlayer().dropMessage(5, "You may not use it on this item.");
                cc = true;
            }
        }
        return CashUse.of(used, cc);
    }

    private static CashUse useVega(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        boolean used = false;
        boolean cc = false;
        slea.readInt(); // Inventory type, always eq
        final byte dst = (byte) slea.readInt();
        slea.readInt(); // Inventory type, always use
        final byte src = (byte) slea.readInt();
        used = useUpgradeScroll(src, dst, (byte) 2, c, c.getPlayer(), itemId); //cannot use ws with vega but we dont care
        cc = used;
        return CashUse.of(used, cc);
    }

    private static CashUse useSealingLock(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
        final IItem item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
        // another int here, lock = 5A E5 F2 0A, 7 day = D2 30 F3 0A
        if (item != null && item.getExpiration() == -1) {
            byte flag = item.getFlag();
            flag = (byte) (flag | ItemFlag.LOCK.getValue());
            item.setFlag(flag);

            c.getPlayer().forceReAddItem_Flag(item, type);
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useSealingLock7Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
        final IItem item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
        // another int here, lock = 5A E5 F2 0A, 7 day = D2 30 F3 0A
        if (item != null && item.getExpiration() == -1) {
            byte flag = item.getFlag();
            flag = (byte) (flag | ItemFlag.LOCK.getValue());
            item.setFlag(flag);
            item.setExpiration(System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000));

            c.getPlayer().forceReAddItem_Flag(item, type);
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useSealingLock30Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
        final IItem item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
        // another int here, lock = 5A E5 F2 0A, 7 day = D2 30 F3 0A
        if (item != null && item.getExpiration() == -1) {
            byte flag = item.getFlag();
            flag = (byte) (flag | ItemFlag.LOCK.getValue());
            item.setFlag(flag);

            item.setExpiration(System.currentTimeMillis() + (30 * 24 * 60 * 60 * 1000));

            c.getPlayer().forceReAddItem_Flag(item, type);
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useSealingLock90Days(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final MapleInventoryType type = MapleInventoryType.getByType((byte) slea.readInt());
        final IItem item = c.getPlayer().getInventory(type).getItem((byte) slea.readInt());
        // another int here, lock = 5A E5 F2 0A, 7 day = D2 30 F3 0A
        if (item != null && item.getExpiration() == -1) {
            byte flag = item.getFlag();
            flag = (byte) (flag | ItemFlag.LOCK.getValue());
            item.setFlag(flag);

            item.setExpiration(System.currentTimeMillis() + (90 * 24 * 60 * 60 * 1000));

            c.getPlayer().forceReAddItem_Flag(item, type);
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse usePeanut(final MapleClient c) {
        boolean used = false;
        IItem item = c.getPlayer().getInventory(MapleInventoryType.ETC).findById(4170023);
        if (item == null || item.getQuantity() <= 0) { // hacking{
            return CashUse.ABORT;
        }
        if (getIncubatedItems(c)) {
            MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.ETC, item.getPosition(), (short) 1, false);
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useMegaphone5070000(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
      if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
     c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String message = slea.readMapleAsciiString();

            if (message.length() > 65) {
                return CashUse.of(used);
            }
            final StringBuilder sb = new StringBuilder();
            addMedalString(c.getPlayer(), sb);
            sb.append(c.getPlayer().getName());
            sb.append(" : ");
            sb.append(message);
            final boolean ear = slea.readByte() != 0;

            c.getPlayer().getMap().broadcastMessage(ChatPacket.serverNotice(2, sb.toString()));
            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useMegaphone5071000(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
        if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String message = slea.readMapleAsciiString();

            if (message.length() > 65) {
                return CashUse.of(used);
            }
              final boolean ear = slea.readByte() != 0;
       
            final StringBuilder sb = new StringBuilder();
            addMedalString(c.getPlayer(), sb);
            sb.append(c.getPlayer().getName());
            sb.append(" : ");
            sb.append(message);
          c.getPlayer().getMap().broadcastMessage(ChatPacket.serverNotice(2, sb.toString()));
              
            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useTripleMegaphone(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
      if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final byte numLines = slea.readByte();
            if (numLines > 3) {
                return CashUse.ABORT;
            }
            final List<String> messages = new LinkedList<>();
            String message;
            for (int i = 0; i < numLines; i++) {
                message = slea.readMapleAsciiString();
                if (message.length() > 65) {
                    break;
                }
                messages.add(c.getPlayer().getName() + " : " + message);
            }
            final boolean ear = slea.readByte() > 0;

            Broadcast.broadcastSmega(ChatPacket.tripleSmega(messages, ear, c.getChannel()).getBytes());

            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useHeartMegaphone(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
                    if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String message = slea.readMapleAsciiString();

            if (message.length() > 65) {
                return CashUse.of(used);
            }
            final StringBuilder sb = new StringBuilder();
            addMedalString(c.getPlayer(), sb);
            sb.append(c.getPlayer().getName());
            sb.append(" : ");
            sb.append(message);

            final boolean ear = slea.readByte() != 0;

            Broadcast.broadcastSmega(ChatPacket.serverNotice(11, c.getChannel(), sb.toString(), ear).getBytes());
                
            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useSkullMegaphone(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
      if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String message = slea.readMapleAsciiString();

            if (message.length() > 65) {
                return CashUse.of(used);
            }
            final StringBuilder sb = new StringBuilder();
            addMedalString(c.getPlayer(), sb);
            sb.append(c.getPlayer().getName());
            sb.append(" : ");
            sb.append(message);

            final boolean ear = slea.readByte() != 0;
            if (c.getPlayer().isPlayer() && message.indexOf("幹") != -1 || message.indexOf("豬") != -1 || message.indexOf("笨") != -1 || message.indexOf("靠") != -1 || message.indexOf("腦包") != -1 || message.indexOf("腦") != -1 || message.indexOf("智障") != -1 || message.indexOf("白目") != -1 || message.indexOf("白吃") != -1){ 
                     c.getPlayer().dropMessage("說髒話是不禮貌的，請勿說髒話。");                 
                     c.send(CharacterPacket.enableActions());
                     return CashUse.ABORT;
                 }
            Broadcast.broadcastSmega(ChatPacket.serverNotice(12, c.getChannel(), sb.toString(), ear).getBytes());

            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useSuperMegaphone(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須要10等以上才能使用.");
            return CashUse.of(used);
        }
       if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String message = slea.readMapleAsciiString();

            if (message.length() > 65) {
                return CashUse.of(used);
            }
            final StringBuilder sb = new StringBuilder();
            addMedalString(c.getPlayer(), sb);
            sb.append(c.getPlayer().getName());
            sb.append(" : ");
            sb.append(message);

            final boolean ear = slea.readByte() != 0;

            Broadcast.broadcastSmega(ChatPacket.serverNotice(3, c.getChannel(), sb.toString(), ear).getBytes());
     
            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useItemMegaphone(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
       if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String message = slea.readMapleAsciiString();

            if (message.length() > 65) {
                return CashUse.of(used);
            }
            final StringBuilder sb = new StringBuilder();
            addMedalString(c.getPlayer(), sb);
            sb.append(c.getPlayer().getName());
            sb.append(" : ");
            sb.append(message);

            final boolean ear = slea.readByte() > 0;

            IItem item = null;
            if (slea.readByte() == 1) { //item
                byte invType = (byte) slea.readInt();
                byte pos = (byte) slea.readInt();
                item = c.getPlayer().getInventory(MapleInventoryType.getByType(invType)).getItem(pos);
            }
            Broadcast.broadcastSmega(ChatPacket.itemMegaphone(sb.toString(), ear, c.getChannel(), item).getBytes());
            used = true;
        } else {
            c.getPlayer().dropMessage(5, "目前喇叭停止使用.");
        }
        return CashUse.of(used);
    }

    private static CashUse useMapleTvMessengerUnavailable(final MapleClient c) {
        c.getPlayer().dropMessage(5, "There are no MapleTVs to broadcast the message to.");
        return CashUse.NONE;
    }

    private static CashUse useMapleTvMessenger(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.NONE;
        }
        int tvType = itemId % 10;
        if (tvType == 3) {
            slea.readByte(); //who knows
        }
        boolean ear = tvType != 1 && tvType != 2 && slea.readByte() > 1; //for tvType 1/2, there is no byte. 
        MapleCharacter victim = tvType == 1 || tvType == 4 ? null : c.getChannelServer().getPlayerStorage().getCharacterByName(slea.readMapleAsciiString()); //for tvType 4, there is no string.
        if (tvType == 0 || tvType == 3) { //doesn't allow two
            victim = null;
        } else if (victim == null) {
            c.getPlayer().dropMessage(1, "That character is not in the channel.");
            return CashUse.NONE;
        }
        String message = slea.readMapleAsciiString();
        Broadcast.broadcastSmega(ChatPacket.serverNotice(3, c.getChannel(), c.getPlayer().getName() + " : " + message, ear).getBytes());
        return CashUse.NONE;
    }

    private static CashUse useNote(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final String sendTo = slea.readMapleAsciiString();
        final String msg = slea.readMapleAsciiString();
        c.getPlayer().sendNote(sendTo, msg);
        used = true;
        return CashUse.of(used);
    }

    private static CashUse useCongratulatorySong(final MapleClient c) {
        boolean used = false;
        c.getPlayer().getMap().broadcastMessage(MTSCSPacket.playCashSong(5100000, c.getPlayer().getName()));
        used = true;
        return CashUse.of(used);
    }

    private static CashUse useAddPetFlag(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        boolean used = false;
        final int uniqueid = (int) slea.readLong();
        MaplePet pet = c.getPlayer().getPet(0);
        int slo = 0;

        if (pet == null) {
            return CashUse.of(used);
        }
        if (pet.getUniqueId() != uniqueid) {
            pet = c.getPlayer().getPet(1);
            slo = 1;
            if (pet != null) {
                if (pet.getUniqueId() != uniqueid) {
                    pet = c.getPlayer().getPet(2);
                    slo = 2;
                    if (pet != null) {
                        if (pet.getUniqueId() != uniqueid) {
                            return CashUse.of(used);
                        }
                    } else {
                        return CashUse.of(used);
                    }
                }
            } else {
                return CashUse.of(used);
            }
        }
        PetFlag zz = PetFlag.getByAddId(itemId);
        if (zz != null && !zz.check(pet.getFlags())) {
            pet.setFlags(pet.getFlags() | zz.getValue());
            c.send(PetPacket.updatePet(pet, c.getPlayer().getInventory(MapleInventoryType.CASH).getItem((byte) pet.getInventoryPosition())));
            c.send(CharacterPacket.enableActions());
            c.send(MTSCSPacket.changePetFlag(uniqueid, true, zz.getValue()));
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useRemovePetFlag(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        boolean used = false;
        final int uniqueid = (int) slea.readLong();
        MaplePet pet = c.getPlayer().getPet(0);
        int slo = 0;

        if (pet == null) {
            return CashUse.of(used);
        }
        if (pet.getUniqueId() != uniqueid) {
            pet = c.getPlayer().getPet(1);
            slo = 1;
            if (pet != null) {
                if (pet.getUniqueId() != uniqueid) {
                    pet = c.getPlayer().getPet(2);
                    slo = 2;
                    if (pet != null) {
                        if (pet.getUniqueId() != uniqueid) {
                            return CashUse.of(used);
                        }
                    } else {
                        return CashUse.of(used);
                    }
                }
            } else {
                return CashUse.of(used);
            }
        }
        PetFlag zz = PetFlag.getByDelId(itemId);
        if (zz != null && zz.check(pet.getFlags())) {
            pet.setFlags(pet.getFlags() - zz.getValue());
            c.send(PetPacket.updatePet(pet, c.getPlayer().getInventory(MapleInventoryType.CASH).getItem((byte) pet.getInventoryPosition())));
            c.send(CharacterPacket.enableActions());
            c.send(MTSCSPacket.changePetFlag(uniqueid, false, zz.getValue()));
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse usePetNameChange(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        MaplePet pet = c.getPlayer().getPet(0);
        int slo = 0;

        if (pet == null) {
            return CashUse.of(used);
        }
        String nName = slea.readMapleAsciiString();
        pet.setName(nName);
        c.send(PetPacket.updatePet(pet, c.getPlayer().getInventory(MapleInventoryType.CASH).getItem((byte) pet.getInventoryPosition())));
        c.send(CharacterPacket.enableActions());
        c.getPlayer().getMap().broadcastMessage(MTSCSPacket.changePetName(c.getPlayer(), nName, slo));
        used = true;
        return CashUse.of(used);
    }

    private static CashUse usePetFood(final MapleClient c, final int itemId) {
        boolean used = false;
        MaplePet pet = c.getPlayer().getPet(0);

        if (pet == null) {
            return CashUse.of(used);
        }
        if (!pet.canConsume(itemId)) {
            pet = c.getPlayer().getPet(1);
            if (pet != null) {
                if (!pet.canConsume(itemId)) {
                    pet = c.getPlayer().getPet(2);
                    if (pet != null) {
                        if (!pet.canConsume(itemId)) {
                            return CashUse.of(used);
                        }
                    } else {
                        return CashUse.of(used);
                    }
                }
            } else {
                return CashUse.of(used);
            }
        }
        final byte petindex = c.getPlayer().getPetIndex(pet);
        pet.setFullness(100);
        if (pet.getCloseness() < 30000) {
            if (pet.getCloseness() + 100 > 30000) {
                pet.setCloseness(30000);
            } else {
                pet.setCloseness(pet.getCloseness() + 100);
            }
            if (pet.getCloseness() >= GameConstants.getClosenessNeededForLevel(pet.getLevel() + 1)) {
                pet.setLevel(pet.getLevel() + 1);
                c.send(PetPacket.showOwnPetLevelUp(c.getPlayer().getPetIndex(pet)));
                c.getPlayer().getMap().broadcastMessage(PetPacket.showPetLevelUp(c.getPlayer(), petindex));
            }
        }
        c.send(PetPacket.updatePet(pet, c.getPlayer().getInventory(MapleInventoryType.CASH).getItem(pet.getInventoryPosition())));
        c.getPlayer().getMap().broadcastMessage(c.getPlayer(), PetPacket.commandResponse(c.getPlayer().getId(), (byte) 1, petindex, true, true), true);
        used = true;
        return CashUse.of(used);
    }

    private static CashUse useOwlOfMinerva(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        boolean used = false;
        final int itemSearch = slea.readInt();
        final List<HiredMerchant> hms = c.getChannelServer().searchMerchant(itemSearch);
        if (hms.size() > 0) {
            c.send(PlayerShopPacket.getOwlSearched(itemSearch, hms));
            used = true;
        } else {
            c.getPlayer().dropMessage(1, "找不到物品");
        }
        return CashUse.of(used);
    }

    private static CashUse useGasSkill(final MapleClient c) {
        boolean used = false;
        Rectangle bounds = new Rectangle((int) c.getPlayer().getPosition().getX(), (int) c.getPlayer().getPosition().getY(), 1, 1);
        MapleMist mist = new MapleMist(bounds, c.getPlayer());
        c.getPlayer().getMap().spawnMist(mist, 10000, true);
        c.getPlayer().getMap().broadcastMessage(ChatPacket.getChatText(c.getPlayer().getId(), "Oh no, I farted!", false, 1));
        c.send(CharacterPacket.enableActions());
        used = true;
        return CashUse.of(used);
    }

    private static CashUse useChalkboard(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().getMapId() / 1000000 == 109) {
            c.getPlayer().dropMessage(1, "請勿在活動地圖使用黑板");
        } else {
            c.getPlayer().setChalkboard(slea.readMapleAsciiString());
        }
        return CashUse.NONE;
    }

    private static CashUse useBlackboard(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().getMapId() / 1000000 == 109) {
            c.getPlayer().dropMessage(1, "請勿在活動地圖使用黑板");
        } else {
            c.getPlayer().setChalkboard(slea.readMapleAsciiString());
        }
        return CashUse.NONE;
    }

    private static CashUse useAvatarMegaphone(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId) {
        boolean used = false;
        if (c.getPlayer().getLevel() < 10) {
            c.getPlayer().dropMessage(5, "必須等級10級以上才可以使用.");
            return CashUse.of(used);
        }
        if (!c.getPlayer().getCheatTracker().canAvatarSmega2()) {
            c.getPlayer().dropMessage(6, "很抱歉為了防止刷廣,所以你每10秒只能用一次.");
            return CashUse.of(used);
        }
        if (!c.getChannelServer().getMegaphoneMuteState()) {
            final String text = slea.readMapleAsciiString();
            if (text.length() > 55) {
                return CashUse.of(used);
            }
            final boolean ear = slea.readByte() != 0;
            Broadcast.broadcastSmega(ChatPacket.getAvatarMega(c.getPlayer(), c.getChannel(), itemId, text, ear).getBytes());
            used = true;
        }
        return CashUse.of(used);
    }

    private static CashUse useTravellingMerchant(final MapleClient c) {
        MapleShopFactory.getInstance().getShop(61).sendShop(c);
        return CashUse.NONE;
    }

    private static CashUse useEyeColourCoupon(final MapleClient c, final int itemId) {
        boolean used = false;
        int color_num = (itemId - 5152100);
        int current = (c.getPlayer().getFace()% 100) + ((c.getPlayer().getFace()/1000))*1000;
        int newface = current + (color_num * 100);
        if (MapleItemInformationProvider.getInstance().faceExists(newface))
        {
            c.getPlayer().setFace(newface);
            c.getPlayer().updateSingleStat(MapleStat.FACE, newface);
            c.getPlayer().equipChanged();
            used = true;
        }else{
             c.getPlayer().dropMessage(6,"該臉部沒有此瞳孔顏色唷");
        }
        return CashUse.of(used);
    }

    private static CashUse useOtherCashItem(final SeekableLittleEndianAccessor slea, final MapleClient c, final int itemId, final byte slot) {
        boolean used = false;
        if (itemId / 10000 == 512) {
            final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            final String msg = slea.readMapleAsciiString();
            c.getPlayer().getMap().startMapEffect(msg, itemId);

            final int buff = ii.getStateChangeItem(itemId);
            if (buff != 0) {
                for (MapleCharacter mChar : c.getPlayer().getMap().getCharactersThreadsafe()) {
                    ii.getItemEffect(buff).applyTo(mChar);
                }
            }
            used = true;
        } else if (itemId / 10000 == 510) {
            c.getPlayer().getMap().startJukebox(c.getPlayer().getName(), itemId);
            used = true;
        } else if (itemId / 10000 == 520) {
            final int mesars = MapleItemInformationProvider.getInstance().getMeso(itemId);
            if (mesars > 0 && c.getPlayer().getMeso() < (Integer.MAX_VALUE - mesars)) {
                used = true;
                if (Math.random() > 0.1) {
                    final int gainmes = Randomizer.nextInt(mesars);
                    c.getPlayer().gainMeso(gainmes, false);
                    c.send(MTSCSPacket.sendMesobagSuccess(gainmes));
                } else {
                    c.send(MTSCSPacket.sendMesobagFailed());
                }
            }
            /*                } else if (itemId / 10000 == 562) {
             useSkillBook(slot, itemId, c, c.getPlayer()); //this should handle removing*/
        } else if (itemId / 10000 == 553) {
            useRewardItem(slot, itemId, c, c.getPlayer());// this too
        } else {
            // One event, one record: the id and the packet dump were always
            // read together, and two threads could otherwise interleave.
            LOG.log(System.Logger.Level.INFO, "Unhandled CS item : " + itemId
                    + System.lineSeparator() + slea.toString(true));
        }
        return CashUse.of(used);
    }

    public static final void pickup_Player(final SeekableLittleEndianAccessor slea, MapleClient c, final MapleCharacter chr) {
        if (c.getPlayer().getPlayerShop() != null || c.getPlayer().getConversation() > 0 || c.getPlayer().getTrade() != null) { //hack
            return;
        }
        chr.updateTick(slea.readInt());
        slea.skip(1); // [4] Seems to be tickcount, [1] always 0
        final Point Client_Reportedpos = slea.readPos();
        if (chr == null) {
            return;
        }
        final MapleMapObject ob = chr.getMap().getMapObject(slea.readInt(), MapleMapObjectType.ITEM);

        if (ob == null) {
            c.send(CharacterPacket.enableActions());
            return;
        }
        final MapleMapItem mapitem = (MapleMapItem) ob;
        final Lock lock = mapitem.getLock();
        lock.lock();
        try {
            if (mapitem.isPickedUp()) {
                c.send(CharacterPacket.enableActions());
                return;
            }
            if (mapitem.getOwner() != chr.getId() && ((!mapitem.isPlayerDrop() && mapitem.getDropType() == 0) || (mapitem.isPlayerDrop() && chr.getMap().getEverlast()))) {
                c.send(CharacterPacket.enableActions());
                return;
            }
            if (!mapitem.isPlayerDrop() && mapitem.getDropType() == 1 && mapitem.getOwner() != chr.getId() && (chr.getParty() == null || chr.getParty().getMemberById(mapitem.getOwner()) == null)) {
                c.send(CharacterPacket.enableActions());
                return;
            }
            final double Distance = Client_Reportedpos.distanceSq(mapitem.getPosition());
            if (Distance > 2500) {
                chr.getCheatTracker().registerOffense(CheatingOffense.ITEMVAC_CLIENT, String.valueOf(Distance));
            } else if (chr.getPosition().distanceSq(mapitem.getPosition()) > 640000.0) {
                chr.getCheatTracker().registerOffense(CheatingOffense.ITEMVAC_SERVER);
            }
            if (mapitem.getMeso() > 0) {
                if (chr.getParty() != null && mapitem.getOwner() != chr.getId()) {
                    final List<MapleCharacter> toGive = new LinkedList<>();

                    for (MaplePartyCharacter z : chr.getParty().getMembers()) {
                        MapleCharacter m = chr.getMap().getCharacterById(z.getId());
                        if (m != null) {
                            toGive.add(m);
                        }
                    }
                    for (final MapleCharacter m : toGive) {
                        m.gainMeso(mapitem.getMeso() / toGive.size() + (m.getStat().hasPartyBonus ? (int) (mapitem.getMeso() / 20.0) : 0), true, true);
                    }
                } else {
                    chr.gainMeso(mapitem.getMeso(), true, true);
                }
                removeItem(chr, mapitem, ob);
            } else {
                if (MapleItemInformationProvider.getInstance().isPickupBlocked(mapitem.getItem().getItemId())) {
                    c.send(CharacterPacket.enableActions());
                    c.getPlayer().dropMessage(5, "This item cannot be picked up.");
                } else if (useItem(c, mapitem.getItemId())) {
                    removeItem(c.getPlayer(), mapitem, ob);
                } else if (MapleInventoryManipulator.checkSpace(c, mapitem.getItem().getItemId(), mapitem.getItem().getQuantity(), mapitem.getItem().getOwner())) {
                    if (mapitem.getItem().getQuantity() >= 50 && ItemConstants.isUpgradeScroll(mapitem.getItem().getItemId())) {
                        c.setMonitored(true); //hack check
                    }
                    if (MapleInventoryManipulator.addFromDrop(c, mapitem.getItem(), true, mapitem.getDropper() instanceof MapleMonster)) {
                        removeItem(chr, mapitem, ob);
                    }
                } else {
                    c.send(InventoryPacket.getInventoryFull());
                    c.send(InventoryPacket.getShowInventoryFull());
                    c.send(CharacterPacket.enableActions());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public static final void pickup_Pet(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        if (chr == null) {
            return;
        }
        final byte petz = c.getPlayer().getPetIndex((int) slea.readLong/*Int*/());
        final MaplePet pet = chr.getPet(petz);
        slea.skip(1); // [4] Zero, [4] Seems to be tickcount, [1] Always zero
        chr.updateTick(slea.readInt());
        final Point Client_Reportedpos = slea.readPos();
        final MapleMapObject ob = chr.getMap().getMapObject(slea.readInt(), MapleMapObjectType.ITEM);

        if (ob == null || pet == null) {
            return;
        }
        final MapleMapItem mapitem = (MapleMapItem) ob;
        final Lock lock = mapitem.getLock();
        lock.lock();
        try {
            if (mapitem.isPickedUp()) {
                c.send(InventoryPacket.getInventoryFull());
                return;
            }
            if (mapitem.getOwner() != chr.getId() && mapitem.isPlayerDrop()) {
                return;
            }
            if (mapitem.getOwner() != chr.getId() && ((!mapitem.isPlayerDrop() && mapitem.getDropType() == 0) || (mapitem.isPlayerDrop() && chr.getMap().getEverlast()))) {
                c.send(CharacterPacket.enableActions());
                return;
            }
            if (!mapitem.isPlayerDrop() && mapitem.getDropType() == 1 && mapitem.getOwner() != chr.getId() && (chr.getParty() == null || chr.getParty().getMemberById(mapitem.getOwner()) == null)) {
                c.send(CharacterPacket.enableActions());
                return;
            }
            final double Distance = Client_Reportedpos.distanceSq(mapitem.getPosition());
            if (Distance > 10000 && (mapitem.getMeso() > 0 || mapitem.getItemId() != 4001025)) {
                chr.getCheatTracker().registerOffense(CheatingOffense.PET_ITEMVAC_CLIENT, String.valueOf(Distance));
            } else if (pet.getPos().distanceSq(mapitem.getPosition()) > 640000.0) {
                chr.getCheatTracker().registerOffense(CheatingOffense.PET_ITEMVAC_SERVER);

            }

            if (mapitem.getMeso() > 0) {
                if (chr.getParty() != null && mapitem.getOwner() != chr.getId()) {
                    final List<MapleCharacter> toGive = new LinkedList<>();
                    final int splitMeso = mapitem.getMeso() * 40 / 100;
                    for (MaplePartyCharacter z : chr.getParty().getMembers()) {
                        MapleCharacter m = chr.getMap().getCharacterById(z.getId());
                        if (m != null && m.getId() != chr.getId()) {
                            toGive.add(m);
                        }
                    }
                    for (final MapleCharacter m : toGive) {
                        m.gainMeso(splitMeso / toGive.size() + (m.getStat().hasPartyBonus ? (int) (mapitem.getMeso() / 20.0) : 0), true);
                    }
                    chr.gainMeso(mapitem.getMeso() - splitMeso, true);
                } else {
                    chr.gainMeso(mapitem.getMeso(), true);
                }
                removeItem_Pet(chr, mapitem, petz);
            } else {
                if (MapleItemInformationProvider.getInstance().isPickupBlocked(mapitem.getItemId()) || mapitem.getItemId() / 10000 == 291) {
                    c.send(CharacterPacket.enableActions());
                } else if (useItem(c, mapitem.getItemId())) {
                    removeItem_Pet(chr, mapitem, petz);
                } else if (MapleInventoryManipulator.checkSpace(c, mapitem.getItemId(), mapitem.getItem().getQuantity(), mapitem.getItem().getOwner())) {
                    if (mapitem.getItem().getQuantity() >= 50 && mapitem.getItemId() == 2340000) {
                        c.setMonitored(true); //hack check
                    }
                    MapleInventoryManipulator.addFromDrop(c, mapitem.getItem(), true, mapitem.getDropper() instanceof MapleMonster);
                    removeItem_Pet(chr, mapitem, petz);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public static final boolean useItem(final MapleClient c, final int id) {
        if (ItemConstants.isUse(id)) { // TO prevent caching of everything, waste of mem
            final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            final byte consumeval = ii.isConsumeOnPickup(id);

            if (consumeval > 0) {
                if (consumeval == 2) {
                    if (c.getPlayer().getParty() != null) {
                        for (final MaplePartyCharacter pc : c.getPlayer().getParty().getMembers()) {
                            final MapleCharacter chr = c.getPlayer().getMap().getCharacterById(pc.getId());
                            if (chr != null) {
                                ii.getItemEffect(id).applyTo(chr);
                            }
                        }
                    } else {
                        ii.getItemEffect(id).applyTo(c.getPlayer());
                    }
                } else {
                    ii.getItemEffect(id).applyTo(c.getPlayer());
                }
                c.send(InventoryPacket.getShowItemGain(id, (byte) 1));
                return true;
            }
        }
        return false;
    }

    public static final void removeItem_Pet(final MapleCharacter chr, final MapleMapItem mapitem, int pet) {
        mapitem.setPickedUp(true);
        chr.getMap().broadcastMessage(MapPacket.removeItemFromMap(mapitem.getObjectId(), 5, chr.getId(), pet), mapitem.getPosition());
        chr.getMap().removeMapObject(mapitem);
        if (mapitem.isRandDrop()) {
            chr.getMap().spawnRandDrop();
        }
    }

    private static final void removeItem(final MapleCharacter chr, final MapleMapItem mapitem, final MapleMapObject ob) {
        mapitem.setPickedUp(true);
        chr.getMap().broadcastMessage(MapPacket.removeItemFromMap(mapitem.getObjectId(), 2, chr.getId()), mapitem.getPosition());
        chr.getMap().removeMapObject(ob);
        if (mapitem.isRandDrop()) {
            chr.getMap().spawnRandDrop();
        }
    }

    private static final void addMedalString(final MapleCharacter c, final StringBuilder sb) {
        final IItem medal = c.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -21);
        if (medal != null) { // Medal
            sb.append("<");
            sb.append(MapleItemInformationProvider.getInstance().getName(medal.getItemId()));
            sb.append("> ");
        }
    }

    private static final boolean getIncubatedItems(MapleClient c) {
        if (c.getPlayer().getInventory(MapleInventoryType.EQUIP).getNumFreeSlot() < 2 || c.getPlayer().getInventory(MapleInventoryType.USE).getNumFreeSlot() < 2 || c.getPlayer().getInventory(MapleInventoryType.SETUP).getNumFreeSlot() < 2) {
            c.getPlayer().dropMessage(5, "Please make room in your inventory.");
            return false;
        }
        final int[] ids = {2430091, 2430092, 2430093, 2430101, 2430102, //mounts 
            2340000, //rares
            1152000, 1152001, 1152004, 1152005, 1152006, 1152007, 1152008, //toenail only comes when db is out.
            1000040, 1102246, 1082276, 1050169, 1051210, 1072447, 1442106, //blizzard
            3010019, //chairs
            1001060, 1002391, 1102004, 1050039, 1102040, 1102041, 1102042, 1102043, //equips
            1082145, 1082146, 1082147, 1082148, 1082149, 1082150, //wg
            2043704, 2040904, 2040409, 2040307, 2041030, 2040015, 2040109, 2041035, 2041036, 2040009, 2040511, 2040408, 2043804, 2044105, 2044903, 2044804, 2043009, 2043305, 2040610, 2040716, 2041037, 2043005, 2041032, 2040305, //scrolls
            2040211, 2040212, 1022097, //dragon glasses
            2049000, 2049001, 2049002, 2049003, //clean slate
            1012058, 1012059, 1012060, 1012061, //pinocchio nose msea only.
            1332100, 1382058, 1402073, 1432066, 1442090, 1452058, 1462076, 1472069, 1482051, 1492024, 1342009,//durability weapons level 105
            2049400, 2049401, 2049301};
        //out of 1000
        final int[] chances = {100, 100, 100, 100, 100,
            1,
            10, 10, 10, 10, 10, 10, 10,
            5, 5, 5, 5, 5, 5, 5,
            2,
            10, 10, 10, 10, 10, 10, 10, 10,
            5, 5, 5, 5, 5, 5,
            10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10,
            5, 5, 10,
            10, 10, 10, 10,
            5, 5, 5, 5,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            1, 2, 1, 2};
        int z = Randomizer.nextInt(ids.length);
        while (chances[z] < Randomizer.nextInt(1000)) {
            z = Randomizer.nextInt(ids.length);
        }
        int z_2 = Randomizer.nextInt(ids.length);
        while (z_2 == z || chances[z_2] < Randomizer.nextInt(1000)) {
            z_2 = Randomizer.nextInt(ids.length);
        }
        c.send(EventPacket.getPeanutResult(ids[z], (short) 1, ids[z_2], (short) 1));
        return MapleInventoryManipulator.addById(c, ids[z], (short) 1) && MapleInventoryManipulator.addById(c, ids[z_2], (short) 1);

    }

    public static final void owlMinerva(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        final byte slot = (byte) slea.readShort();
        final int itemid = slea.readInt();
        final IItem toUse = c.getPlayer().getInventory(MapleInventoryType.USE).getItem(slot);
        if (toUse != null && toUse.getQuantity() > 0 && toUse.getItemId() == itemid && itemid == 2310000) {
            final int itemSearch = slea.readInt();
            final List<HiredMerchant> hms = c.getChannelServer().searchMerchant(itemSearch);
            if (hms.size() > 0) {
                c.send(PlayerShopPacket.getOwlSearched(itemSearch, hms));
                MapleInventoryManipulator.removeById(c, MapleInventoryType.USE, itemid, 1, true, false);
            } else {
                c.getPlayer().dropMessage(1, "找不到物品");
            }
        }
        c.send(CharacterPacket.enableActions());
    }

    public static final void owl(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        if (c.getPlayer().haveItem(5230000, 1, true, true) || c.getPlayer().haveItem(2310000, 1, true, true)) {
            if (c.getPlayer().getMapId() >= 910000000 && c.getPlayer().getMapId() <= 910000022) {
                c.send(PlayerShopPacket.getOwlOpen());
                LOG.log(System.Logger.Level.INFO, "TEST");
            } else {
                c.getPlayer().dropMessage(5, "只能在自由市場使用");
                c.send(CharacterPacket.enableActions());
            }
        }
    }
    public static final int OWL_ID = 2; //don't change. 0 = owner ID, 1 = store ID, 2 = object ID

    public static final void useSkillBook(final SeekableLittleEndianAccessor slea, final MapleClient c, final MapleCharacter chr) {
        slea.skip(4);
        final byte slot = (byte) slea.readShort();
        final int itemId = slea.readInt();
        final IItem toUse = chr.getInventory(MapleInventoryType.USE).getItem(slot);

        if (toUse == null || toUse.getQuantity() < 1 || toUse.getItemId() != itemId) {
            return;
        }
        final Map<String, Integer> skilldata = MapleItemInformationProvider.getInstance().getSkillStats(toUse.getItemId());
        if (skilldata == null) { // Hacking or used an unknown item
            return;
        }
        boolean canuse = false, success = false;
        int skill = 0, maxlevel = 0;

        final int SuccessRate = skilldata.get("success");
        final int ReqSkillLevel = skilldata.get("reqSkillLevel");
        final int MasterLevel = skilldata.get("masterLevel");

        byte i = 0;
        Integer CurrentLoopedSkillId;
        for (;;) {
            CurrentLoopedSkillId = skilldata.get("skillid" + i);
            i++;
            if (CurrentLoopedSkillId == null) {
                break; // End of data
            }
            if (Math.floor(CurrentLoopedSkillId / 10000) == chr.getJob()) {
                final ISkill CurrSkillData = SkillFactory.getSkill(CurrentLoopedSkillId);
                if (chr.getSkillLevel(CurrSkillData) >= ReqSkillLevel && chr.getMasterLevel(CurrSkillData) < MasterLevel) {
                    canuse = true;
                    if (Randomizer.nextInt(99) <= SuccessRate && SuccessRate != 0) {
                        success = true;
                        final ISkill skill2 = CurrSkillData;
                        chr.changeSkillLevel(skill2, chr.getSkillLevel(skill2), (byte) MasterLevel);
                    } else {
                        success = false;
                    }
                    MapleInventoryManipulator.removeFromSlot(c, MapleInventoryType.USE, slot, (short) 1, false);
                    break;
                } else { // Failed to meet skill requirements
                    canuse = false;
                }
            }
        }
        c.send(CharacterPacket.useSkillBook(chr, skill, maxlevel, canuse, success));
    }

    public static final void owlWarp(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        c.send(CharacterPacket.enableActions());
        if (c.getPlayer().getMapId() >= 910000000 && c.getPlayer().getMapId() <= 910000022 && c.getPlayer().getPlayerShop() == null) {
            final int id = slea.readInt();
            final int map = slea.readInt();
            if (map >= 910000001 && map <= 910000022) {
                final MapleMap mapp = c.getChannelServer().getMapFactory().getMap(map);
                c.getPlayer().changeMap(mapp, mapp.getPortal(0));
                HiredMerchant merchant = null;
                List<MapleMapObject> objects;
                switch (OWL_ID) {
                    case 0:
                        objects = mapp.getAllHiredMerchantsThreadsafe();
                        for (MapleMapObject ob : objects) {
                            if (ob instanceof IMaplePlayerShop) {
                                final IMaplePlayerShop ips = (IMaplePlayerShop) ob;
                                if (ips instanceof HiredMerchant) {
                                    final HiredMerchant merch = (HiredMerchant) ips;
                                    if (merch.getOwnerId() == id) {
                                        merchant = merch;
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                    case 1:
                        objects = mapp.getAllHiredMerchantsThreadsafe();
                        for (MapleMapObject ob : objects) {
                            if (ob instanceof IMaplePlayerShop) {
                                final IMaplePlayerShop ips = (IMaplePlayerShop) ob;
                                if (ips instanceof HiredMerchant) {
                                    final HiredMerchant merch = (HiredMerchant) ips;
                                    if (merch.getStoreId() == id) {
                                        merchant = merch;
                                        break;
                                    }
                                }
                            }
                        }
                        break;
                    default:
                        final MapleMapObject ob = mapp.getMapObject(id, MapleMapObjectType.HIRED_MERCHANT);
                        if (ob instanceof IMaplePlayerShop) {
                            final IMaplePlayerShop ips = (IMaplePlayerShop) ob;
                            if (ips instanceof HiredMerchant) {
                                merchant = (HiredMerchant) ips;
                            }
                        }
                        break;
                }
                if (merchant != null) {
                    if (merchant.isOwner(c.getPlayer())) {
                        merchant.setOpen(false);
                        merchant.removeAllVisitors((byte) 16, (byte) 0);
                        c.getPlayer().setPlayerShop(merchant);
                        c.send(PlayerShopPacket.getHiredMerch(c.getPlayer(), merchant, false));
                    } else {
                        if (!merchant.isOpen() || !merchant.isAvailable()) {
                            c.getPlayer().dropMessage(1, "This shop is in maintenance, please come by later.");
                        } else {
                            if (merchant.getFreeSlot() == -1) {
                                c.getPlayer().dropMessage(1, "This shop has reached it's maximum capacity, please come by later.");
                            } else if (merchant.isInBlackList(c.getPlayer().getName())) {
                                c.getPlayer().dropMessage(1, "You have been banned from this store.");
                            } else {
                                c.getPlayer().setPlayerShop(merchant);
                                merchant.addVisitor(c.getPlayer());
                                c.send(PlayerShopPacket.getHiredMerch(c.getPlayer(), merchant, false));
                            }
                        }
                    }
                } else {
                    c.getPlayer().dropMessage(1, "This shop is in maintenance, please come by later.");
                }
            }
        }
    }
}
