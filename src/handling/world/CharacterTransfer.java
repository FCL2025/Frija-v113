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
package handling.world;

import java.util.LinkedHashMap;
import java.util.Map;

import client.inventory.MapleMount;
import client.MapleCharacter;
import client.MapleQuestStatus;
import client.ISkill;
import client.SkillEntry;
import client.BuddylistEntry;
import client.CharacterNameAndId;
import client.inventory.MaplePet;
import server.quest.MapleQuest;
import tools.Pair;
import java.util.ArrayList;
import java.util.List;

/**
 * 換頻道／進出商城時，角色交給目的地頻道的那份資料。
 *
 * <p>流程是：來源端 {@code MapleCharacter.changeChannel} 或
 * {@code InterServerHandler.EnterCS} 建一個 CharacterTransfer，
 * {@code World.ChannelChange_Data} 把它登記成目的地 {@code PlayerStorage} 的
 * pending 角色，客戶端接上新的埠之後 {@code InterServerHandler.Loggedin} 取出它，
 * 由 {@code MapleCharacter.ReconstructChr} 重建成一個新的 MapleCharacter。沒取走
 * 的（客戶端沒接上來）由 {@code PlayerStorage.PersistingTask} 依 TranferTime 在
 * 40 秒後掃掉。整條路徑不經過資料庫，也不經過序列化。
 *
 * <h2>為什麼是一份欄位清單，而不是直接交遞活的 MapleCharacter</h2>
 *
 * <p>E10 評估過「改成交遞 MapleCharacter + rebind(client)」，結論是不改。理由是
 * 來源頻道並沒有把角色從舊頻道摘乾淨：
 *
 * <ul>
 * <li>{@code changeChannel} 走的是 {@code changeRemoval()}，不是斷線用的
 *     {@code MapleClient.removalTask()}，所以 {@code cancelAllTimers()} 沒有被呼叫。
 *     離開的角色身上 fairySchedule、mapTimeLimitTask、fishing、BerserkSchedule、
 *     dragonBloodSchedule、beholder* 這些 ScheduledFuture 全部還活著，而且它們抓著
 *     {@code this}、{@code map} 與舊的 {@code client}。現在這些計時器只是繼續戳一個
 *     沒人在讀的屍體物件（到期前算是洩漏，但不會弄壞任何東西）；如果改成交遞同一個
 *     物件，它們就變成對玩家正在用的角色的活寫入，而且 {@code map} 指著舊頻道的
 *     {@code MapleMap}。
 * <li>相對地，{@code ReconstructChr} 是在目的地頻道的 {@code MapleMapFactory} 上
 *     重新解析 {@code map}，其餘的頻道綁定狀態（controlled、summons、
 *     visibleMapObjects、doors、clones、anticheat、pets、effects、eventInstance）
 *     則由 {@code MapleCharacter(boolean)} 建構子重新開一份。「哪些東西該重算」因此
 *     是被這份清單明確定義的，而不是靠離開流程有沒有記得清。
 * </ul>
 *
 * <p>要注意這份清單只有「淺」的意義：inventorys、storage、cs、keymap、mbook、
 * InfoQuest、savedlocation／wishlist／rocks／regrocks／petStore 交出去的都是離開那個
 * 角色身上的同一個物件（Quest 的值則是在 ReconstructChr 裡逐欄重建的）。這些都是
 * 玩家私有的葉節點狀態，身上沒有指回 MapleMap／MapleClient／ChannelServer 的參考，
 * 所以是安全的；被刻意丟掉的正好就是那些綁頻道的東西。
 *
 * <p>buff、冷卻與異常狀態不走這裡，由 {@code PlayerBuffStorage} 另外遞送。
 */
public class CharacterTransfer {

    public int characterid, accountid, exp,
            beans, meso, hair, face, mapid, guildid,
            partyid, messengerid, mBookCover, dojo, ACash, MaplePoints,
            mount_itemid, mount_exp, points, vpoints, marriageId,
            familyid, seniorid, junior1, junior2, currentrep, totalrep, expression, constellation, blood, month, day, battleshipHP,prefix,
            gachexp;
    public byte channel, dojoRecord, gender, gmLevel, guildrank, alliancerank, clonez, fairyExp, buddysize, world, initialSpawnPoint, skinColor, mount_level, mount_Fatigue, subcategory;
    public long lastfametime, TranferTime;
    public String name, accountname, BlessOfFairy, chalkboard, charmessage;
    public short level, fame, str, dex, int_, luk, maxhp, maxmp, hp, mp, remainingAp, hpApUsed, job;
    public Object inventorys, skillmacro, storage, cs;
    public int[] savedlocation, wishlist, rocks, remainingSp, regrocks;
    public byte[] petStore;
    public Map<Integer, Integer> mbook = new LinkedHashMap<>();
    public Map<Integer, Pair<Byte, Integer>> keymap = new LinkedHashMap<>();
    public final List<Integer> famedcharacters = new ArrayList<>();
    public final Map<CharacterNameAndId, Boolean> buddies = new LinkedHashMap<>();
    public final Map<Integer, Object> Quest = new LinkedHashMap<>(); // Questid instead of MapleQuest, as it's huge. Cant be transporting MapleQuest.java
    public Map<Integer, String> InfoQuest = new LinkedHashMap<>();
    public final Map<Integer, SkillEntry> Skills = new LinkedHashMap<>(); // Skillid instead of Skill.java, as it's huge. Cant be transporting Skill.java and MapleStatEffect.java

    public CharacterTransfer(final MapleCharacter chr) {
        this.characterid = chr.getId();
        this.accountid = chr.getAccountID();
        this.accountname = chr.getClient().getAccountName();
        this.channel = (byte) chr.getClient().getChannel();
        this.ACash = chr.getCSPoints(1);
        this.MaplePoints = chr.getCSPoints(2);
        this.vpoints = chr.getVPoints();
        this.name = chr.getName();
        this.fame = chr.getFame();
        this.gender = chr.getGender();
        this.level = chr.getLevel();
        this.str = chr.getStat().getStr();
        this.dex = chr.getStat().getDex();
        this.int_ = chr.getStat().getInt();
        this.luk = chr.getStat().getLuk();
        this.hp = chr.getStat().getHp();
        this.mp = chr.getStat().getMp();
        this.maxhp = chr.getStat().getMaxHp();
        this.maxmp = chr.getStat().getMaxMp();
        this.exp = chr.getExp();
        this.hpApUsed = chr.getHpApUsed();
        this.remainingAp = chr.getRemainingAp();
        this.remainingSp = chr.getRemainingSps();
        this.beans = chr.getBeans();
        this.meso = chr.getMeso();
        this.skinColor = chr.getSkinColor();
        this.job = chr.getJob();
        this.hair = chr.getHair();
        this.face = chr.getFace();
        this.mapid = chr.getMapId();
        this.initialSpawnPoint = chr.getInitialSpawnpoint();
        this.marriageId = chr.getMarriageId();
        this.world = chr.getWorld();
        this.guildid = chr.getGuildId();
        this.guildrank = chr.getGuildRank();
        this.alliancerank = chr.getAllianceRank();
        this.gmLevel = (byte) chr.getGMLevel();
        this.points = chr.getPoints();
        this.fairyExp = chr.getFairyExp();
        this.clonez = chr.getNumClones();
        this.petStore = chr.getPetStores();
        this.subcategory = chr.getSubcategory();
        this.currentrep = chr.getCurrentRep();
        this.totalrep = chr.getTotalRep();
        this.familyid = chr.getFamilyId();
        this.seniorid = chr.getSeniorId();
        this.junior1 = chr.getJunior1();
        this.junior2 = chr.getJunior2();
        this.charmessage = chr.getcharmessage();
        this.expression = chr.getexpression();
        this.constellation = chr.getconstellation();
        this.blood = chr.getblood();
        this.month = chr.getmonth();
        this.day = chr.getday();
        this.battleshipHP = chr.currentBattleshipHP();
        this.prefix = chr.getPrefix();
        this.gachexp = chr.getGachExp();
        boolean uneq = false;
        for (int i = 0; i < this.petStore.length; i++) {
            final MaplePet pet = chr.getPet(i);
            if (this.petStore[i] == 0) {
                this.petStore[i] = (byte) -1;
            }
            if (pet != null) {
                uneq = true;
                this.petStore[i] = (byte) Math.max(this.petStore[i], pet.getInventoryPosition());
            }

        }
        if (uneq) {
            chr.unequipAllPets();
        }
        for (final BuddylistEntry qs : chr.getBuddylist().getBuddies()) {
            this.buddies.put(new CharacterNameAndId(qs.getCharacterId(), qs.getName(), qs.getLevel(), qs.getJob(), qs.getGroup()), qs.isVisible());
        }
        this.buddysize = chr.getBuddyCapacity();

        this.partyid = chr.getPartyId();

        if (chr.getMessenger() != null) {
            this.messengerid = chr.getMessenger().getId();
        } else {
            this.messengerid = 0;
        }

        this.mBookCover = chr.getMonsterBookCover();
        this.dojo = chr.getDojo();
        this.dojoRecord = (byte) chr.getDojoRecord();

        this.InfoQuest = chr.getInfoQuest_Map();

        for (final Map.Entry<MapleQuest, MapleQuestStatus> qs : chr.getQuest_Map().entrySet()) {
            this.Quest.put(qs.getKey().getId(), qs.getValue());
        }

        this.mbook = chr.getMonsterBook().getCards();
        this.inventorys = chr.getInventorys();

        for (final Map.Entry<ISkill, SkillEntry> qs : chr.getSkills().entrySet()) {
            this.Skills.put(qs.getKey().getId(), qs.getValue());
        }

        this.BlessOfFairy = chr.getBlessOfFairyOrigin();
        this.chalkboard = chr.getChalkboard();
        this.skillmacro = chr.getMacros();
        this.keymap = chr.getKeyLayout().layout();
        this.savedlocation = chr.getSavedLocations();
        this.wishlist = chr.getWishlist();
        this.rocks = chr.getRocks();
        this.regrocks = chr.getRegRocks();
        for (final Integer zz : chr.getFamedCharacters()) {
            this.famedcharacters.add(zz);
        }
        this.lastfametime = chr.getLastFameTime();
        this.storage = chr.getStorage();
        this.cs = chr.getCashInventory();

        final MapleMount mount = chr.getMount();
        this.mount_itemid = mount.getItemId();
        this.mount_Fatigue = mount.getFatigue();
        this.mount_level = mount.getLevel();
        this.mount_exp = mount.getExp();
        TranferTime = System.currentTimeMillis();
    }
}
