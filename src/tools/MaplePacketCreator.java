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
package tools;

import client.BuddylistEntry;
import client.MapleBuffStat;
import client.MapleCharacter;
import client.MapleClient;
import client.MapleDisease;
import client.MapleKeyLayout;
import client.MapleQuestStatus;
import client.MapleStat;
import client.SkillMacro;
import client.inventory.IEquip.ScrollResult;
import client.inventory.IItem;
import client.inventory.MapleInventoryType;
import client.inventory.MapleRing;
import handling.MaplePacket;
import handling.channel.MapleGuildRanking.GuildRankingInfo;
import handling.world.MapleParty;
import handling.world.MaplePartyCharacter;
import handling.world.PartyOperation;
import handling.world.guild.MapleBBSThread;
import handling.world.guild.MapleGuild;
import handling.world.guild.MapleGuildAlliance;
import handling.world.guild.MapleGuildCharacter;
import java.awt.Point;
import java.util.Collection;
import java.util.List;
import server.MapleDueyActions;
import server.MapleShopItem;
import server.MapleStatEffect;
import server.MapleTrade;
import server.events.MapleSnowball.MapleSnowballs;
import server.life.MapleMonster;
import server.life.MapleNPC;
import server.life.PlayerNPC;
import server.life.SummonAttackEntry;
import server.maps.MapleDragon;
import server.maps.MapleMap;
import server.maps.MapleMapItem;
import server.maps.MapleMist;
import server.maps.MapleReactor;
import server.maps.MapleSummon;
import server.movement.LifeMovementFragment;
import server.shops.HiredMerchant;
import tools.data.output.MaplePacketLittleEndianWriter;
import tools.packet.BuddyPacket;
import tools.packet.BuffPacket;
import tools.packet.CharacterPacket;
import tools.packet.ChatPacket;
import tools.packet.EventPacket;
import tools.packet.FamilyPacket;
import tools.packet.GuildPacket;
import tools.packet.InventoryPacket;
import tools.packet.MapPacket;
import tools.packet.MessengerPacket;
import tools.packet.MobPacket;
import tools.packet.NPCPacket;
import tools.packet.PartyPacket;
import tools.packet.PlayerShopPacket;
import tools.packet.QuestPacket;
import tools.packet.ServerPacket;
import tools.packet.UIPacket;

/**
 * E5 過渡門面（transitional facade）。
 *
 * <p>原本 4,756 行的封包工廠已依領域拆進 {@code tools/packet/*}：
 * {@link tools.packet.ServerPacket}、{@link tools.packet.CharacterPacket}、
 * {@link tools.packet.MapPacket}、{@link tools.packet.InventoryPacket}、
 * {@link tools.packet.BuffPacket}、{@link tools.packet.ChatPacket}、
 * {@link tools.packet.GuildPacket}、{@link tools.packet.PartyPacket}、
 * {@link tools.packet.BuddyPacket}、{@link tools.packet.MessengerPacket}、
 * {@link tools.packet.NPCPacket}、{@link tools.packet.QuestPacket}、
 * {@link tools.packet.EventPacket}，以及既有的 {@link tools.packet.MobPacket}、
 * {@link tools.packet.PlayerShopPacket}、{@link tools.packet.UIPacket}、
 * {@link tools.packet.FamilyPacket}。
 *
 * <p>這個類別現在只剩下對應的轉呼叫，簽章、可見度與參數名稱一律不變，
 * 所以既有的 87 個 Java 呼叫端與 {@code Libs/scripts/} 的 JS 腳本一行都不用改。
 * <b>新程式碼請直接呼叫上列新類別</b>；下一個 commit 會改寫呼叫端並刪掉這個門面。
 *
 * <p>每個門面方法上方都有一行 {@code // E5 門面 → tools.packet.XxxPacket} 註解，
 * 標明它真正的家，供下一步的 sed 改寫使用。刻意不加 {@code @Deprecated}：
 * 87 個未改的呼叫端會因此產生 1,005 個 {@code [deprecation]} 警告
 * （405 → 1410），而這棵樹的 {@code deprecation} 一向維持在 0。
 */
public class MaplePacketCreator {

    public final static List<Pair<MapleStat, Integer>> EMPTY_STATUPDATE = CharacterPacket.EMPTY_STATUPDATE;

    // E5 門面 → tools.packet.ServerPacket
    public static final MaplePacket getServerIP(final int port, final int clientId) {
        return ServerPacket.getServerIP(port, clientId);
    }

    // E5 門面 → tools.packet.ServerPacket
    public static final MaplePacket getChannelChange(final int port) {
        return ServerPacket.getChannelChange(port);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket getCharInfo(final MapleCharacter chr) {
        return CharacterPacket.getCharInfo(chr);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket enableActions() {
        return CharacterPacket.enableActions();
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket updatePlayerStats(final List<Pair<MapleStat, Integer>> stats, final int evan) {
        return CharacterPacket.updatePlayerStats(stats, evan);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket updatePlayerStats(final List<Pair<MapleStat, Integer>> stats, final boolean itemReaction, final int evan) {
        return CharacterPacket.updatePlayerStats(stats, itemReaction, evan);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket updateSp(MapleCharacter chr, final boolean itemReaction) {
        return CharacterPacket.updateSp(chr, itemReaction);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket updateSp(MapleCharacter chr, final boolean itemReaction, final boolean overrideJob) {
        return CharacterPacket.updateSp(chr, itemReaction, overrideJob);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket getWarpToMap(final MapleMap to, final int spawnPoint, final MapleCharacter chr) {
        return MapPacket.getWarpToMap(to, spawnPoint, chr);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket instantMapWarp(final byte portal) {
        return MapPacket.instantMapWarp(portal);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket spawnPortal(final int townId, final int targetId, final int skillId, final Point pos) {
        return MapPacket.spawnPortal(townId, targetId, skillId, pos);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket spawnDoor(final int oid, final Point pos, final boolean town) {
        return MapPacket.spawnDoor(oid, pos, town);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeDoor(int oid, boolean town) {
        return MapPacket.removeDoor(oid, town);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnSummon(MapleSummon summon, boolean animated) {
        return MapPacket.spawnSummon(summon, animated);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeSummon(MapleSummon summon, boolean animated) {
        return MapPacket.removeSummon(summon, animated);
    }

    // E5 門面 → tools.packet.ServerPacket
    public static MaplePacket getRelogResponse() {
        return ServerPacket.getRelogResponse();
    }

    // E5 門面 → tools.packet.ServerPacket
    public static MaplePacket serverBlocked(int type) {
        return ServerPacket.serverBlocked(type);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket serverMessage(String message) {
        return ChatPacket.serverMessage(message);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket serverNotice(int type, String message) {
        return ChatPacket.serverNotice(type, message);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket serverNotice(int type, int channel, String message) {
        return ChatPacket.serverNotice(type, channel, message);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket serverNotice(int type, int channel, String message, boolean smegaEar) {
        return ChatPacket.serverNotice(type, channel, message, smegaEar);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getGachaponMega(String name, String message, IItem item, byte rareness) {
        return ChatPacket.getGachaponMega(name, message, item, rareness);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getGachaponMega(final String name, final String message, final IItem item, final byte rareness,int ch) {
        return ChatPacket.getGachaponMega(name, message, item, rareness, ch);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket tripleSmega(List<String> message, boolean ear, int channel) {
        return ChatPacket.tripleSmega(message, ear, channel);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getAvatarMega(MapleCharacter chr, int channel, int itemId, String message, boolean ear) {
        return ChatPacket.getAvatarMega(chr, channel, itemId, message, ear);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket itemMegaphone(String msg, boolean whisper, int channel, IItem item) {
        return ChatPacket.itemMegaphone(msg, whisper, channel, item);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnNPC(MapleNPC life, boolean show) {
        return MapPacket.spawnNPC(life, show);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeNPC(final int objectid) {
        return MapPacket.removeNPC(objectid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnNPCRequestController(MapleNPC life, boolean MiniMap) {
        return MapPacket.spawnNPCRequestController(life, MiniMap);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnPlayerNPC(PlayerNPC npc) {
        return MapPacket.spawnPlayerNPC(npc);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getChatText(int cidfrom, String text, boolean whiteBG, int show) {
        return ChatPacket.getChatText(cidfrom, text, whiteBG, show);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket GameMaster_Func(int value) {
        return CharacterPacket.GameMaster_Func(value);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket testCombo(int value) {
        return CharacterPacket.testCombo(value);
    }

    // E5 門面 → tools.packet.ServerPacket
    public static MaplePacket getPacketFromHexString(String hex) {
        return ServerPacket.getPacketFromHexString(hex);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket GainEXP_Monster(final int gain, final boolean white, final int partyinc, final int Class_Bonus_EXP, final int Equipment_Bonus_EXP, final int Premium_Bonus_EXP) {
        return CharacterPacket.GainEXP_Monster(gain, white, partyinc, Class_Bonus_EXP, Equipment_Bonus_EXP, Premium_Bonus_EXP);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket GainEXP_Others(final int gain, final boolean inChat, final boolean white) {
        return CharacterPacket.GainEXP_Others(gain, inChat, white);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket getShowFameGain(final int gain) {
        return CharacterPacket.getShowFameGain(gain);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket showMesoGain(final int gain, final boolean inChat) {
        return CharacterPacket.showMesoGain(gain, inChat);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getShowItemGain(int itemId, short quantity) {
        return InventoryPacket.getShowItemGain(itemId, quantity);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getShowItemGain(int itemId, short quantity, boolean inChat) {
        return InventoryPacket.getShowItemGain(itemId, quantity, inChat);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket showRewardItemAnimation(int itemId, String effect) {
        return InventoryPacket.showRewardItemAnimation(itemId, effect);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket showRewardItemAnimation(int itemId, String effect, int from_playerid) {
        return InventoryPacket.showRewardItemAnimation(itemId, effect, from_playerid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket dropItemFromMapObject(MapleMapItem drop, Point dropfrom, Point dropto, byte mod) {
        return MapPacket.dropItemFromMapObject(drop, dropfrom, dropto, mod);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket spawnPlayerMapobject(MapleCharacter chr) {
        return CharacterPacket.spawnPlayerMapobject(chr);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket removePlayerFromMap(int cid) {
        return CharacterPacket.removePlayerFromMap(cid);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket facialExpression(MapleCharacter from, int expression) {
        return CharacterPacket.facialExpression(from, expression);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket movePlayer(int cid, List<LifeMovementFragment> moves, Point startPos) {
        return CharacterPacket.movePlayer(cid, moves, startPos);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket moveSummon(int cid, int oid, Point startPos, List<LifeMovementFragment> moves) {
        return MapPacket.moveSummon(cid, oid, startPos, moves);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket summonAttack(final int cid, final int summonSkillId, final byte animation, final List<SummonAttackEntry> allDamage, final int level) {
        return MapPacket.summonAttack(cid, summonSkillId, animation, allDamage, level);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket closeRangeAttack(int cid, int tbyte, int skill, int level, byte display, byte animation, byte speed, List<AttackPair> damage, final boolean energy, int lvl, byte mastery, byte unk, int charge) {
        return CharacterPacket.closeRangeAttack(cid, tbyte, skill, level, display, animation, speed, damage, energy, lvl, mastery, unk, charge);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket rangedAttack(int cid, byte tbyte, int skill, int level, byte display, byte animation, byte speed, int itemid, List<AttackPair> damage, final Point pos, int lvl, byte mastery, byte unk) {
        return CharacterPacket.rangedAttack(cid, tbyte, skill, level, display, animation, speed, itemid, damage, pos, lvl, mastery, unk);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket magicAttack(int cid, int tbyte, int skill, int level, byte display, byte animation, byte speed, List<AttackPair> damage, int charge, int lvl, byte unk) {
        return CharacterPacket.magicAttack(cid, tbyte, skill, level, display, animation, speed, damage, charge, lvl, unk);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getNPCShop(MapleClient c, int sid, List<MapleShopItem> items) {
        return NPCPacket.getNPCShop(c, sid, items);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket confirmShopTransaction(byte code) {
        return NPCPacket.confirmShopTransaction(code);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket addInventorySlot(MapleInventoryType type, IItem item) {
        return InventoryPacket.addInventorySlot(type, item);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket addInventorySlot(MapleInventoryType type, IItem item, boolean fromDrop) {
        return InventoryPacket.addInventorySlot(type, item, fromDrop);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket updateInventorySlot(MapleInventoryType type, IItem item, boolean fromDrop) {
        return InventoryPacket.updateInventorySlot(type, item, fromDrop);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket moveInventoryItem(MapleInventoryType type, short src, short dst) {
        return InventoryPacket.moveInventoryItem(type, src, dst);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket moveInventoryItem(MapleInventoryType type, short src, short dst, short equipIndicator) {
        return InventoryPacket.moveInventoryItem(type, src, dst, equipIndicator);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket moveAndMergeInventoryItem(MapleInventoryType type, short src, short dst, short total) {
        return InventoryPacket.moveAndMergeInventoryItem(type, src, dst, total);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket moveAndMergeWithRestInventoryItem(MapleInventoryType type, short src, short dst, short srcQ, short dstQ) {
        return InventoryPacket.moveAndMergeWithRestInventoryItem(type, src, dst, srcQ, dstQ);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket clearInventoryItem(MapleInventoryType type, short slot, boolean fromDrop) {
        return InventoryPacket.clearInventoryItem(type, slot, fromDrop);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket updateSpecialItemUse(IItem item, byte invType) {
        return InventoryPacket.updateSpecialItemUse(item, invType);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket updateSpecialItemUse(IItem item, byte invType, short pos) {
        return InventoryPacket.updateSpecialItemUse(item, invType, pos);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket updateSpecialItemUse_(IItem item, byte invType) {
        return InventoryPacket.updateSpecialItemUse_(item, invType);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket updateSpecialItemUse_(IItem item, byte invType, short pos) {
        return InventoryPacket.updateSpecialItemUse_(item, invType, pos);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket scrolledItem(IItem scroll, IItem item, boolean destroyed, boolean potential) {
        return InventoryPacket.scrolledItem(scroll, item, destroyed, potential);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getScrollEffect(int chr, ScrollResult scrollSuccess, boolean legendarySpirit) {
        return InventoryPacket.getScrollEffect(chr, scrollSuccess, legendarySpirit);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getPotentialEffect(final int chr, final int itemid) {
        return InventoryPacket.getPotentialEffect(chr, itemid);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getPotentialReset(final int chr, final short pos) {
        return InventoryPacket.getPotentialReset(chr, pos);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static final MaplePacket ItemMaker_Success() {
        return InventoryPacket.ItemMaker_Success();
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static final MaplePacket ItemMaker_Success_3rdParty(final int from_playerid) {
        return InventoryPacket.ItemMaker_Success_3rdParty(from_playerid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket explodeDrop(int oid) {
        return MapPacket.explodeDrop(oid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeItemFromMap(int oid, int animation, int cid) {
        return MapPacket.removeItemFromMap(oid, animation, cid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeItemFromMap(int oid, int animation, int cid, int slot) {
        return MapPacket.removeItemFromMap(oid, animation, cid, slot);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket updateCharLook(MapleCharacter chr) {
        return CharacterPacket.updateCharLook(chr);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static void addRingInfo(MaplePacketLittleEndianWriter mplew, List<MapleRing> rings) {
        CharacterPacket.addRingInfo(mplew, rings);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket dropInventoryItem(MapleInventoryType type, short src) {
        return InventoryPacket.dropInventoryItem(type, src);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket dropInventoryItemUpdate(MapleInventoryType type, IItem item) {
        return InventoryPacket.dropInventoryItemUpdate(type, item);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket damagePlayer(int skill, int monsteridfrom, int cid, int damage, int fake, byte direction, int reflect, boolean is_pg, int oid, int pos_x, int pos_y) {
        return CharacterPacket.damagePlayer(skill, monsteridfrom, cid, damage, fake, direction, reflect, is_pg, oid, pos_x, pos_y);
    }

    // E5 門面 → tools.packet.QuestPacket
    public static final MaplePacket updateQuest(final MapleQuestStatus quest) {
        return QuestPacket.updateQuest(quest);
    }

    // E5 門面 → tools.packet.QuestPacket
    public static final MaplePacket updateInfoQuest(final int quest, final String data) {
        return QuestPacket.updateInfoQuest(quest, data);
    }

    // E5 門面 → tools.packet.QuestPacket
    public static MaplePacket updateQuestInfo(MapleCharacter c, int quest, int npc, byte progress) {
        return QuestPacket.updateQuestInfo(c, quest, npc, progress);
    }

    // E5 門面 → tools.packet.QuestPacket
    public static MaplePacket updateQuestFinish(int quest, int npc, int nextquest) {
        return QuestPacket.updateQuestFinish(quest, npc, nextquest);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket charInfo(final MapleCharacter chr, final boolean isSelf) {
        return CharacterPacket.charInfo(chr, isSelf);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveMount(int buffid, int skillid, List<Pair<MapleBuffStat, Integer>> statups) {
        return BuffPacket.giveMount(buffid, skillid, statups);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket givePirate(List<Pair<MapleBuffStat, Integer>> statups, int duration, int skillid) {
        return BuffPacket.givePirate(statups, duration, skillid);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveForeignPirate(List<Pair<MapleBuffStat, Integer>> statups, int duration, int cid, int skillid) {
        return BuffPacket.giveForeignPirate(statups, duration, cid, skillid);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveHoming(int skillid, int mobid) {
        return BuffPacket.giveHoming(skillid, mobid);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveEnergyChargeTest(int bar, int bufflength) {
        return BuffPacket.giveEnergyChargeTest(bar, bufflength);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveEnergyChargeTest(int cid, int bar, int bufflength) {
        return BuffPacket.giveEnergyChargeTest(cid, bar, bufflength);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveBuff(int buffid, int bufflength, List<Pair<MapleBuffStat, Integer>> statups, MapleStatEffect effect) {
        return BuffPacket.giveBuff(buffid, bufflength, statups, effect);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveDebuff(final List<Pair<MapleDisease, Integer>> statups, int skillid, int level, int duration) {
        return BuffPacket.giveDebuff(statups, skillid, level, duration);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveForeignDebuff(int cid, final List<Pair<MapleDisease, Integer>> statups, int skillid, int level) {
        return BuffPacket.giveForeignDebuff(cid, statups, skillid, level);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket cancelForeignDebuff(int cid, long mask, boolean first) {
        return BuffPacket.cancelForeignDebuff(cid, mask, first);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket showMonsterRiding(int cid, List<Pair<MapleBuffStat, Integer>> statups, int itemId, int skillId) {
        return BuffPacket.showMonsterRiding(cid, statups, itemId, skillId);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket giveForeignBuff(int cid, List<Pair<MapleBuffStat, Integer>> statups, MapleStatEffect effect) {
        return BuffPacket.giveForeignBuff(cid, statups, effect);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket cancelForeignBuff(int cid, List<MapleBuffStat> statups) {
        return BuffPacket.cancelForeignBuff(cid, statups);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket cancelBuff(List<MapleBuffStat> statups) {
        return BuffPacket.cancelBuff(statups);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket cancelHoming() {
        return BuffPacket.cancelHoming();
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket cancelDebuff(long mask, boolean first) {
        return BuffPacket.cancelDebuff(mask, first);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket updateMount(MapleCharacter chr, boolean levelup) {
        return BuffPacket.updateMount(chr, levelup);
    }

    // E5 門面 → tools.packet.BuffPacket
    public static MaplePacket mountInfo(MapleCharacter chr) {
        return BuffPacket.mountInfo(chr);
    }

    // E5 門面 → tools.packet.PlayerShopPacket
    public static MaplePacket getPlayerShopNewVisitor(MapleCharacter c, int slot) {
        return PlayerShopPacket.getPlayerShopNewVisitor(c, slot);
    }

    // E5 門面 → tools.packet.PlayerShopPacket
    public static MaplePacket getPlayerShopRemoveVisitor(int slot,int status2) {
        return PlayerShopPacket.getPlayerShopRemoveVisitor(slot, status2);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradePartnerAdd(MapleCharacter c) {
        return NPCPacket.getTradePartnerAdd(c);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradeInvite(MapleCharacter c) {
        return NPCPacket.getTradeInvite(c);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradeMesoSet(byte number, int meso) {
        return NPCPacket.getTradeMesoSet(number, meso);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradeItemAdd(byte number, IItem item) {
        return NPCPacket.getTradeItemAdd(number, item);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradeStart(MapleClient c, MapleTrade trade, byte number) {
        return NPCPacket.getTradeStart(c, trade, number);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradeConfirmation() {
        return NPCPacket.getTradeConfirmation();
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket TradeMessage(final byte UserSlot, final byte message) {
        return NPCPacket.TradeMessage(UserSlot, message);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getTradeCancel(final byte UserSlot, final int unsuccessful) {
        return NPCPacket.getTradeCancel(UserSlot, unsuccessful);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getNPCTalk(int npc, byte msgType, String talk, String endBytes, byte type) {
        return NPCPacket.getNPCTalk(npc, msgType, talk, endBytes, type);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static final MaplePacket getMapSelection(final int npcid, final String sel) {
        return NPCPacket.getMapSelection(npcid, sel);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getNPCTalkStyle(int npc, String talk, int... args) {
        return NPCPacket.getNPCTalkStyle(npc, talk, args);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getNPCTalkNum(int npc, String talk, int def, int min, int max) {
        return NPCPacket.getNPCTalkNum(npc, talk, def, min, max);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket getNPCTalkText(int npc, String talk) {
        return NPCPacket.getNPCTalkText(npc, talk);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showForeignEffect(int cid, int effect) {
        return CharacterPacket.showForeignEffect(cid, effect);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showBuffeffect(int cid, int skillid, int effectid) {
        return CharacterPacket.showBuffeffect(cid, skillid, effectid);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showBuffeffect(int cid, int skillid, int effectid, byte direction) {
        return CharacterPacket.showBuffeffect(cid, skillid, effectid, direction);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showOwnBuffEffect(int skillid, int effectid) {
        return CharacterPacket.showOwnBuffEffect(skillid, effectid);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showOwnBuffEffect(int skillid, int effectid, byte direction) {
        return CharacterPacket.showOwnBuffEffect(skillid, effectid, direction);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showItemLevelupEffect() {
        return CharacterPacket.showItemLevelupEffect();
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showForeignItemLevelupEffect(int cid) {
        return CharacterPacket.showForeignItemLevelupEffect(cid);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showSpecialEffect(int effect) {
        return CharacterPacket.showSpecialEffect(effect);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showSpecialEffect(int cid, int effect) {
        return CharacterPacket.showSpecialEffect(cid, effect);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket updateSkill(int skillid, int level, int masterlevel, long expiration) {
        return CharacterPacket.updateSkill(skillid, level, masterlevel, expiration);
    }

    // E5 門面 → tools.packet.QuestPacket
    public static final MaplePacket updateQuestMobKills(final MapleQuestStatus status) {
        return QuestPacket.updateQuestMobKills(status);
    }

    // E5 門面 → tools.packet.QuestPacket
    public static MaplePacket getShowQuestCompletion(int id) {
        return QuestPacket.getShowQuestCompletion(id);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket getKeymap(MapleKeyLayout layout) {
        return CharacterPacket.getKeymap(layout);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getWhisper(String sender, int channel, String text) {
        return ChatPacket.getWhisper(sender, channel, text);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getWhisperReply(String target, byte reply) {
        return ChatPacket.getWhisperReply(target, reply);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getFindReplyWithMap(String target, int mapid, final boolean buddy) {
        return ChatPacket.getFindReplyWithMap(target, mapid, buddy);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getFindReply(String target, int channel, final boolean buddy) {
        return ChatPacket.getFindReply(target, channel, buddy);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getInventoryFull() {
        return InventoryPacket.getInventoryFull();
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getShowInventoryFull() {
        return InventoryPacket.getShowInventoryFull();
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket showItemUnavailable() {
        return InventoryPacket.showItemUnavailable();
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getShowInventoryStatus(int mode) {
        return InventoryPacket.getShowInventoryStatus(mode);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getStorage(int npcId, byte slots, Collection<IItem> items, int meso) {
        return InventoryPacket.getStorage(npcId, slots, items, meso);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket getStorageFull() {
        return InventoryPacket.getStorageFull();
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket mesoStorage(byte slots, int meso) {
        return InventoryPacket.mesoStorage(slots, meso);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket storeStorage(byte slots, MapleInventoryType type, Collection<IItem> items) {
        return InventoryPacket.storeStorage(slots, type, items);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket takeOutStorage(byte slots, MapleInventoryType type, Collection<IItem> items) {
        return InventoryPacket.takeOutStorage(slots, type, items);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static MaplePacket fairyPendantMessage(int type, int percent) {
        return InventoryPacket.fairyPendantMessage(type, percent);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket giveFameResponse(int mode, String charname, int newfame) {
        return CharacterPacket.giveFameResponse(mode, charname, newfame);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket giveFameErrorResponse(int status) {
        return CharacterPacket.giveFameErrorResponse(status);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket receiveFame(int mode, String charnameFrom) {
        return CharacterPacket.receiveFame(mode, charnameFrom);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket partyCreated(int partyid) {
        return PartyPacket.partyCreated(partyid);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket partyInvite(MapleCharacter from) {
        return PartyPacket.partyInvite(from);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket partyStatusMessage(int message) {
        return PartyPacket.partyStatusMessage(message);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket partyStatusMessage(int message, String charname) {
        return PartyPacket.partyStatusMessage(message, charname);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket updateParty(int forChannel, MapleParty party, PartyOperation op, MaplePartyCharacter target) {
        return PartyPacket.updateParty(forChannel, party, op, target);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket partyPortal(int townId, int targetId, int skillId, Point position) {
        return PartyPacket.partyPortal(townId, targetId, skillId, position);
    }

    // E5 門面 → tools.packet.PartyPacket
    public static MaplePacket updatePartyMemberHP(int cid, int curhp, int maxhp) {
        return PartyPacket.updatePartyMemberHP(cid, curhp, maxhp);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket multiChat(String name, String chattext, int mode) {
        return ChatPacket.multiChat(name, chattext, mode);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket getClock(int time) {
        return MapPacket.getClock(time);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket getClockTime(int hour, int min, int sec) {
        return MapPacket.getClockTime(hour, min, sec);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnMist(final MapleMist mist) {
        return MapPacket.spawnMist(mist);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeMist(final int oid, boolean eruption) {
        return MapPacket.removeMist(oid, eruption);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket damageSummon(int cid, int summonSkillId, int damage, int unkByte, int monsterIdFrom) {
        return MapPacket.damageSummon(cid, summonSkillId, damage, unkByte, monsterIdFrom);
    }

    // E5 門面 → tools.packet.BuddyPacket
    public static MaplePacket buddylistMessage(byte message) {
        return BuddyPacket.buddylistMessage(message);
    }

    // E5 門面 → tools.packet.BuddyPacket
    public static MaplePacket updateBuddylist(Collection<BuddylistEntry> buddylist) {
        return BuddyPacket.updateBuddylist(buddylist);
    }

    // E5 門面 → tools.packet.BuddyPacket
    public static MaplePacket requestBuddylistAdd(int cidFrom, String nameFrom, int levelFrom, int jobFrom) {
        return BuddyPacket.requestBuddylistAdd(cidFrom, nameFrom, levelFrom, jobFrom);
    }

    // E5 門面 → tools.packet.BuddyPacket
    public static MaplePacket updateBuddyChannel(int characterid, int channel) {
        return BuddyPacket.updateBuddyChannel(characterid, channel);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket itemEffect(int characterid, int itemid) {
        return CharacterPacket.itemEffect(characterid, itemid);
    }

    // E5 門面 → tools.packet.BuddyPacket
    public static MaplePacket updateBuddyCapacity(int capacity) {
        return BuddyPacket.updateBuddyCapacity(capacity);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showChair(int characterid, int itemid) {
        return CharacterPacket.showChair(characterid, itemid);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket cancelChair(int id) {
        return CharacterPacket.cancelChair(id);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnReactor(MapleReactor reactor) {
        return MapPacket.spawnReactor(reactor);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket triggerReactor(MapleReactor reactor, int stance) {
        return MapPacket.triggerReactor(reactor, stance);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket destroyReactor(MapleReactor reactor) {
        return MapPacket.destroyReactor(reactor);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket musicChange(String song) {
        return MapPacket.musicChange(song);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket showEffect(String effect) {
        return MapPacket.showEffect(effect);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket playSound(String sound) {
        return MapPacket.playSound(sound);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket environmentChange(String env, int mode) {
        return MapPacket.environmentChange(env, mode);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket environmentMove(String env, int mode) {
        return MapPacket.environmentMove(env, mode);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket startMapEffect(String msg, int itemid, boolean active) {
        return MapPacket.startMapEffect(msg, itemid, active);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeMapEffect() {
        return MapPacket.removeMapEffect();
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket fuckGuildInfo(MapleCharacter c) {
        return GuildPacket.fuckGuildInfo(c);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket showGuildInfo(MapleCharacter c) {
        return GuildPacket.showGuildInfo(c);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildMemberOnline(int gid, int cid, boolean bOnline) {
        return GuildPacket.guildMemberOnline(gid, cid, bOnline);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildInvite(int gid, String charName, int levelFrom, int jobFrom) {
        return GuildPacket.guildInvite(gid, charName, levelFrom, jobFrom);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket denyGuildInvitation(String charname) {
        return GuildPacket.denyGuildInvitation(charname);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket genericGuildMessage(byte code) {
        return GuildPacket.genericGuildMessage(code);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket newGuildMember(MapleGuildCharacter mgc) {
        return GuildPacket.newGuildMember(mgc);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket memberLeft(MapleGuildCharacter mgc, boolean bExpelled) {
        return GuildPacket.memberLeft(mgc, bExpelled);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket changeRank(MapleGuildCharacter mgc) {
        return GuildPacket.changeRank(mgc);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildNotice(int gid, String notice) {
        return GuildPacket.guildNotice(gid, notice);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildMemberLevelJobUpdate(MapleGuildCharacter mgc) {
        return GuildPacket.guildMemberLevelJobUpdate(mgc);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket rankTitleChange(int gid, String[] ranks) {
        return GuildPacket.rankTitleChange(gid, ranks);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildDisband(int gid) {
        return GuildPacket.guildDisband(gid);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildEmblemChange(int gid, short bg, byte bgcolor, short logo, byte logocolor) {
        return GuildPacket.guildEmblemChange(gid, bg, bgcolor, logo, logocolor);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket guildCapacityChange(int gid, int capacity) {
        return GuildPacket.guildCapacityChange(gid, capacity);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket removeGuildFromAlliance(MapleGuildAlliance alliance, MapleGuild expelledGuild, boolean expelled) {
        return GuildPacket.removeGuildFromAlliance(alliance, expelledGuild, expelled);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket changeAlliance(MapleGuildAlliance alliance, final boolean in) {
        return GuildPacket.changeAlliance(alliance, in);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket changeAllianceLeader(int allianceid, int newLeader, int oldLeader) {
        return GuildPacket.changeAllianceLeader(allianceid, newLeader, oldLeader);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket updateAllianceLeader(int allianceid, int newLeader, int oldLeader) {
        return GuildPacket.updateAllianceLeader(allianceid, newLeader, oldLeader);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket sendAllianceInvite(String allianceName, MapleCharacter inviter) {
        return GuildPacket.sendAllianceInvite(allianceName, inviter);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket changeGuildInAlliance(MapleGuildAlliance alliance, MapleGuild guild, final boolean add) {
        return GuildPacket.changeGuildInAlliance(alliance, guild, add);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket changeAllianceRank(int allianceid, MapleGuildCharacter player) {
        return GuildPacket.changeAllianceRank(allianceid, player);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket createGuildAlliance(MapleGuildAlliance alliance) {
        return GuildPacket.createGuildAlliance(alliance);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket getAllianceInfo(MapleGuildAlliance alliance) {
        return GuildPacket.getAllianceInfo(alliance);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket getAllianceUpdate(MapleGuildAlliance alliance) {
        return GuildPacket.getAllianceUpdate(alliance);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket getGuildAlliance(MapleGuildAlliance alliance) {
        return GuildPacket.getGuildAlliance(alliance);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket addGuildToAlliance(MapleGuildAlliance alliance, MapleGuild newGuild) {
        return GuildPacket.addGuildToAlliance(alliance, newGuild);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket allianceMemberOnline(int alliance, int gid, int id, boolean online) {
        return GuildPacket.allianceMemberOnline(alliance, gid, id, online);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket updateAlliance(MapleGuildCharacter mgc, int allianceid) {
        return GuildPacket.updateAlliance(mgc, allianceid);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket updateAllianceRank(int allianceid, MapleGuildCharacter mgc) {
        return GuildPacket.updateAllianceRank(allianceid, mgc);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket disbandAlliance(int alliance) {
        return GuildPacket.disbandAlliance(alliance);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket BBSThreadList(final List<MapleBBSThread> bbs, int start) {
        return GuildPacket.BBSThreadList(bbs, start);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket showThread(MapleBBSThread thread) {
        return GuildPacket.showThread(thread);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket showGuildRanks(int npcid, List<GuildRankingInfo> all) {
        return GuildPacket.showGuildRanks(npcid, all);
    }

    // E5 門面 → tools.packet.GuildPacket
    public static MaplePacket updateGP(int gid, int GP) {
        return GuildPacket.updateGP(gid, GP);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket skillEffect(MapleCharacter from, int skillId, byte level, byte flags, byte speed, byte unk) {
        return CharacterPacket.skillEffect(from, skillId, level, flags, speed, unk);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket skillCancel(MapleCharacter from, int skillId) {
        return CharacterPacket.skillCancel(from, skillId);
    }

    // E5 門面 → tools.packet.MobPacket
    public static MaplePacket showMagnet(int mobid, byte success) {
        return MobPacket.showMagnet(mobid, success);
    }

    // E5 門面 → tools.packet.UIPacket
    public static MaplePacket sendHint(String hint, int width, int height) {
        return UIPacket.sendHint(hint, width, height);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket messengerInvite(String from, int messengerid) {
        return MessengerPacket.messengerInvite(from, messengerid);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket addMessengerPlayer(String from, MapleCharacter chr, int position, int channel) {
        return MessengerPacket.addMessengerPlayer(from, chr, position, channel);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket removeMessengerPlayer(int position) {
        return MessengerPacket.removeMessengerPlayer(position);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket updateMessengerPlayer(String from, MapleCharacter chr, int position, int channel) {
        return MessengerPacket.updateMessengerPlayer(from, chr, position, channel);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket joinMessenger(int position) {
        return MessengerPacket.joinMessenger(position);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket messengerChat(String text) {
        return MessengerPacket.messengerChat(text);
    }

    // E5 門面 → tools.packet.MessengerPacket
    public static MaplePacket messengerNote(String text, int mode, int mode2) {
        return MessengerPacket.messengerNote(text, mode, mode2);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getFindReplyWithCS(String target, final boolean buddy) {
        return ChatPacket.getFindReplyWithCS(target, buddy);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket getFindReplyWithMTS(String target, final boolean buddy) {
        return ChatPacket.getFindReplyWithMTS(target, buddy);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showEquipEffect() {
        return CharacterPacket.showEquipEffect();
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket showEquipEffect(int team) {
        return CharacterPacket.showEquipEffect(team);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket summonSkill(int cid, int summonSkillId, int newStance) {
        return MapPacket.summonSkill(cid, summonSkillId, newStance);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket skillCooldown(int sid, int time) {
        return CharacterPacket.skillCooldown(sid, time);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket useSkillBook(MapleCharacter chr, int skillid, int maxlevel, boolean canuse, boolean success) {
        return CharacterPacket.useSkillBook(chr, skillid, maxlevel, canuse, success);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket getMacros(SkillMacro[] macros) {
        return CharacterPacket.getMacros(macros);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket updateAriantPQRanking(String name, int score, boolean empty) {
        return EventPacket.updateAriantPQRanking(name, score, empty);
    }

    // E5 門面 → tools.packet.MobPacket
    public static MaplePacket catchMonster(int mobid, int itemid, byte success) {
        return MobPacket.catchMonster(mobid, itemid, success);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket showAriantScoreBoard() {
        return EventPacket.showAriantScoreBoard();
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket boatPacket(int effect) {
        return MapPacket.boatPacket(effect);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket boatEffect(int effect) {
        return MapPacket.boatEffect(effect);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket removeItemFromDuey(boolean remove, int Package) {
        return NPCPacket.removeItemFromDuey(remove, Package);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static MaplePacket sendDuey(byte operation, List<MapleDueyActions> packages) {
        return NPCPacket.sendDuey(operation, packages);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket Mulung_DojoUp2() {
        return EventPacket.Mulung_DojoUp2();
    }

    // E5 門面 → tools.packet.QuestPacket
    public static MaplePacket showQuestMsg(final String msg) {
        return QuestPacket.showQuestMsg(msg);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket Mulung_Pts(int recv, int total) {
        return EventPacket.Mulung_Pts(recv, total);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket showOXQuiz(int questionSet, int questionId, boolean askQuestion) {
        return EventPacket.showOXQuiz(questionSet, questionId, askQuestion);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket leftKnockBack() {
        return CharacterPacket.leftKnockBack();
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket rollSnowball(int type, MapleSnowballs ball1, MapleSnowballs ball2) {
        return EventPacket.rollSnowball(type, ball1, ball2);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket enterSnowBall() {
        return EventPacket.enterSnowBall();
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket hitSnowBall(int team, int damage, int distance, int delay) {
        return EventPacket.hitSnowBall(team, damage, distance, delay);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket snowballMessage(int team, int message) {
        return EventPacket.snowballMessage(team, message);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket finishedSort(int type) {
        return EventPacket.finishedSort(type);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket coconutScore(int[] coconutscore) {
        return EventPacket.coconutScore(coconutscore);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket hitCoconut(boolean spawn, int id, int type) {
        return EventPacket.hitCoconut(spawn, id, type);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket finishedGather(int type) {
        return EventPacket.finishedGather(type);
    }

    // E5 門面 → tools.packet.ChatPacket
    public static MaplePacket yellowChat(String msg) {
        return ChatPacket.yellowChat(msg);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket getPeanutResult(int itemId, short quantity, int itemId2, short quantity2) {
        return EventPacket.getPeanutResult(itemId, quantity, itemId2, quantity2);
    }

    // E5 門面 → tools.packet.FamilyPacket
    public static MaplePacket sendLevelup(boolean family, int level, String name) {
        return FamilyPacket.sendLevelup(family, level, name);
    }

    // E5 門面 → tools.packet.FamilyPacket
    public static MaplePacket sendMarriage(boolean family, String name) {
        return FamilyPacket.sendMarriage(family, name);
    }

    // E5 門面 → tools.packet.FamilyPacket
    public static MaplePacket sendJobup(boolean family, int jobid, String name) {
        return FamilyPacket.sendJobup(family, jobid, name);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket showZakumShrine(boolean spawned, int time) {
        return MapPacket.showZakumShrine(spawned, time);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket showHorntailShrine(boolean spawned, int time) {
        return MapPacket.showHorntailShrine(spawned, time);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket showChaosZakumShrine(boolean spawned, int time) {
        return MapPacket.showChaosZakumShrine(spawned, time);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket showChaosHorntailShrine(boolean spawned, int time) {
        return MapPacket.showChaosHorntailShrine(spawned, time);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket stopClock() {
        return MapPacket.stopClock();
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket spawnDragon(MapleDragon d) {
        return MapPacket.spawnDragon(d);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket removeDragon(int chrid) {
        return MapPacket.removeDragon(chrid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket moveDragon(MapleDragon d, Point startPos, List<LifeMovementFragment> moves) {
        return MapPacket.moveDragon(d, startPos, moves);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket temporaryStats_Aran() {
        return CharacterPacket.temporaryStats_Aran();
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket temporaryStats_Balrog(final MapleCharacter chr) {
        return CharacterPacket.temporaryStats_Balrog(chr);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket temporaryStats(final List<Pair<MapleStat.Temp, Integer>> stats) {
        return CharacterPacket.temporaryStats(stats);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket temporaryStats_Reset() {
        return CharacterPacket.temporaryStats_Reset();
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket showHpHealed(final int cid, final int amount) {
        return CharacterPacket.showHpHealed(cid, amount);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket showOwnHpHealed(final int amount) {
        return CharacterPacket.showOwnHpHealed(amount);
    }

    // E5 門面 → tools.packet.NPCPacket
    public static final MaplePacket sendRepairWindow(int npc) {
        return NPCPacket.sendRepairWindow(npc);
    }

    // E5 門面 → tools.packet.EventPacket
    public static final MaplePacket sendPyramidUpdate(final int amount) {
        return EventPacket.sendPyramidUpdate(amount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static final MaplePacket sendPyramidResult(final byte rank, final int amount) {
        return EventPacket.sendPyramidResult(rank, amount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static final MaplePacket sendPyramidEnergy(final String type, final String amount) {
        return EventPacket.sendPyramidEnergy(type, amount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static final MaplePacket sendString(final int type, final String object, final String amount) {
        return EventPacket.sendString(type, object, amount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static final MaplePacket sendGhostPoint(final String type, final String amount) {
        return EventPacket.sendGhostPoint(type, amount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static final MaplePacket sendGhostStatus(final String type, final String amount) {
        return EventPacket.sendGhostStatus(type, amount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket MulungEnergy(int energy) {
        return EventPacket.MulungEnergy(energy);
    }

    // E5 門面 → tools.packet.UIPacket
    public static MaplePacket getPollQuestion() {
        return UIPacket.getPollQuestion();
    }

    // E5 門面 → tools.packet.UIPacket
    public static MaplePacket getPollReply(String message) {
        return UIPacket.getPollReply(message);
    }

    // E5 門面 → tools.packet.UIPacket
    public static MaplePacket getEvanTutorial(String data) {
        return UIPacket.getEvanTutorial(data);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket showEventInstructions() {
        return EventPacket.showEventInstructions();
    }

    // E5 門面 → tools.packet.PlayerShopPacket
    public static MaplePacket getOwlOpen() {
        return PlayerShopPacket.getOwlOpen();
    }

    // E5 門面 → tools.packet.PlayerShopPacket
    public static MaplePacket getOwlSearched(final int itemSearch, final List<HiredMerchant> hms) {
        return PlayerShopPacket.getOwlSearched(itemSearch, hms);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket getRPSMode(byte mode, int mesos, int selection, int answer) {
        return EventPacket.getRPSMode(mode, mesos, selection, answer);
    }

    // E5 門面 → tools.packet.InventoryPacket
    public static final MaplePacket getSlotUpdate(byte invType, byte newSlots) {
        return InventoryPacket.getSlotUpdate(invType, newSlots);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket followRequest(int chrid) {
        return CharacterPacket.followRequest(chrid);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket followEffect(int initiator, int replier, Point toMap) {
        return CharacterPacket.followEffect(initiator, replier, toMap);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket getFollowMsg(int opcode) {
        return CharacterPacket.getFollowMsg(opcode);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static MaplePacket moveFollow(Point otherStart, Point myStart, Point otherEnd, List<LifeMovementFragment> moves) {
        return CharacterPacket.moveFollow(otherStart, myStart, otherEnd, moves);
    }

    // E5 門面 → tools.packet.CharacterPacket
    public static final MaplePacket getFollowMessage(final String msg) {
        return CharacterPacket.getFollowMessage(msg);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket getNodeProperties(final MapleMonster objectid, final MapleMap map) {
        return MapPacket.getNodeProperties(objectid, map);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket getMovingPlatforms(final MapleMap map) {
        return MapPacket.getMovingPlatforms(map);
    }

    // E5 門面 → tools.packet.MapPacket
    public static final MaplePacket getUpdateEnvironment(final MapleMap map) {
        return MapPacket.getUpdateEnvironment(map);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket sendEngagementRequest(String name, int cid) {
        return EventPacket.sendEngagementRequest(name, cid);
    }

    // E5 門面 → tools.packet.MapPacket
    public static MaplePacket trembleEffect(int type, int delay) {
        return MapPacket.trembleEffect(type, delay);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket sendEngagement(final byte msg, final int item, final MapleCharacter male, final MapleCharacter female) {
        return EventPacket.sendEngagement(msg, item, male, female);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket englishQuizMsg(String msg) {
        return EventPacket.englishQuizMsg(msg);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket openBeans(int beansCount, int type) {
        return EventPacket.openBeans(beansCount, type);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket updateBeans(int cid, int beansCount) {
        return EventPacket.updateBeans(cid, beansCount);
    }

    // E5 門面 → tools.packet.EventPacket
    public static MaplePacket showBeans(int size, int Pos, int Type) {
        return EventPacket.showBeans(size, Pos, Type);
    }
}
