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
package client;

import client.inventory.IItem;
import client.inventory.ItemLoader;
import client.inventory.MapleInventory;
import client.inventory.MapleInventoryType;
import client.inventory.MapleMount;
import client.inventory.MaplePet;
import constants.JobConstants;
import database.DatabaseConnection;
import database.DatabaseException;
import handling.channel.ChannelServer;
import handling.world.MapleParty;
import handling.world.Party;
import handling.world.guild.MapleGuildCharacter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import server.CashShop;
import server.MaplePortal;
import server.MapleStorage;
import server.life.PlayerNPC;
import server.maps.MapleMapFactory;
import server.maps.SavedLocationType;
import server.quest.MapleQuest;
import tools.Pair;

/**
 * {@code MapleCharacter} 的持久化層：所有直接對資料庫讀寫角色狀態的程式碼。
 *
 * <p>從 {@code MapleCharacter} 原封不動搬出來的五個方法（E4 第一刀）：
 * {@link #loadCharFromDB}、{@link #saveToDB}、{@link #saveNewCharToDB}、
 * {@link #saveInventory}、{@link #deleteWhereCharacterId}。SQL、順序、參數索引、
 * 交易邊界與例外處理都與搬移前逐字相同；{@code MapleCharacter} 留下同名的轉呼叫，
 * 所以既有的呼叫端一行都沒有改。
 *
 * <p>本類別刻意與 {@code MapleCharacter} 同 package：它要寫進 / 讀出的欄位有六十幾個，
 * 其中載入端的三十幾個根本沒有 setter。把那些欄位從 {@code private} 放寬成 package-private，
 * 比替它們發明三十幾個新的 setter（那等於把「可寫」的介面開得更大）來得窄，也讓這次搬移
 * 維持成一個可以逐字對照的 diff。放寬的成員清單見 {@code MapleCharacter} 的欄位宣告區。
 *
 * <p><b>連線池規則</b>（見 CLAUDE.md 的 Persistence 一節）：這裡沒有任何一個
 * {@code Connection} 被存進欄位或交給別的執行緒。{@code saveInventory} 與
 * {@code deleteWhereCharacterId} 的 {@code Connection} 是<em>參數</em>，絕對不能關。
 * {@code saveToDB} 借到的那一條連線，會被它內部再借連線的 helper（例如
 * {@code MapleStorage.saveToDB}、{@code CashShop.save}）依執行緒重入拿到同一條，
 * 所以整次存檔仍然是同一個交易。
 */
public class CharacterRepository {

    /**
     * 命名與分級規則見 {@code tools.Logging}。這裡是存檔／載入路徑，所有
     * {@code [charsave]} 開頭的訊息都是「這次存檔被放棄或回滾了」的唯一紀錄，
     * 因此一律 {@code ERROR}；唯一的例外是 quest generated key 那一筆，
     * 它只跳過一筆怪物計數而整次存檔照常完成，所以是 {@code WARNING}。
     */
    private static final System.Logger LOG = System.getLogger(CharacterRepository.class.getName());

    private CharacterRepository() {
    }

    public static MapleCharacter loadCharFromDB(int charid, MapleClient client, boolean channelserver) {
        final MapleCharacter ret = new MapleCharacter(channelserver);
        ret.client = client;
        ret.id = charid;

        PreparedStatement ps = null;
        PreparedStatement pse = null;
        ResultSet rs = null;

        try (Connection con = DatabaseConnection.getConnection()) {
            ps = con.prepareStatement("SELECT * FROM characters WHERE id = ?");
            ps.setInt(1, charid);
            rs = ps.executeQuery();
            if (!rs.next()) {
                throw new RuntimeException("Loading the Char Failed (char not found)");
            }
            ret.name = rs.getString("name");
            ret.level = rs.getShort("level");
            ret.fame = rs.getShort("fame");

            ret.stats.str = rs.getShort("str");
            ret.stats.dex = rs.getShort("dex");
            ret.stats.int_ = rs.getShort("int");
            ret.stats.luk = rs.getShort("luk");
            ret.stats.maxhp = rs.getShort("maxhp");
            ret.stats.maxmp = rs.getShort("maxmp");
            ret.stats.hp = rs.getShort("hp");
            ret.stats.mp = rs.getShort("mp");

            ret.exp = rs.getInt("exp");
            ret.hpApUsed = rs.getShort("hpApUsed");
            final String[] sp = rs.getString("sp").split(",");
            for (int i = 0; i < ret.remainingSp.length; i++) {
                ret.remainingSp[i] = Integer.parseInt(sp[i]);
            }
            ret.remainingAp = rs.getShort("ap");
            ret.beans = rs.getInt("beans");
            ret.meso = rs.getInt("meso");
            ret.gmLevel = rs.getByte("gm");
            ret.skinColor = rs.getByte("skincolor");
            ret.gender = rs.getByte("gender");
            ret.job = rs.getShort("job");
            ret.hair = rs.getInt("hair");
            ret.face = rs.getInt("face");
            ret.accountid = rs.getInt("accountid");
            ret.mapid = rs.getInt("map");
            ret.initialSpawnPoint = rs.getByte("spawnpoint");
            ret.world = rs.getByte("world");
            ret.guildid = rs.getInt("guildid");
            ret.guildrank = rs.getByte("guildrank");
            ret.allianceRank = rs.getByte("allianceRank");
            ret.currentrep = rs.getInt("currentrep");
            ret.totalrep = rs.getInt("totalrep");
            ret.gachexp = rs.getInt("gachexp");
            ret.makeMFC(rs.getInt("familyid"), rs.getInt("seniorid"), rs.getInt("junior1"), rs.getInt("junior2"));
            if (ret.guildid > 0) {
                ret.mgc = new MapleGuildCharacter(ret);
            }
            ret.buddylist = new BuddyList(rs.getByte("buddyCapacity"));
            ret.subcategory = rs.getByte("subcategory");
            ret.mount = new MapleMount(ret, 0, ret.job > 1000 && ret.job < 2000 ? 10001004 : (ret.job >= 2000 ? (ret.job == 2001 || (ret.job >= 2200 && ret.job <= 2218) ? 20011004 : (ret.job >= 3000 ? 30001004 : 20001004)) : 1004), (byte) 0, (byte) 1, 0);
            ret.rank = rs.getInt("rank");
            ret.rankMove = rs.getInt("rankMove");
            ret.jobRank = rs.getInt("jobRank");
            ret.jobRankMove = rs.getInt("jobRankMove");
            ret.marriageId = rs.getInt("marriageId");
            ret.charmessage = rs.getString("charmessage");
            ret.expression = rs.getInt("expression");
            ret.constellation = rs.getInt("constellation");
            ret.blood = rs.getInt("blood");
            ret.month = rs.getInt("month");
            ret.day = rs.getInt("day");
            ret.prefix = rs.getInt("prefix");
            if (channelserver) {
                MapleMapFactory mapFactory = ChannelServer.getInstance(client.getChannel()).getMapFactory();
                ret.map = mapFactory.getMap(ret.mapid);
                if (ret.map == null) { //char is on a map that doesn't exist warp it to henesys
                    ret.map = mapFactory.getMap(100000000);
                }
                MaplePortal portal = ret.map.getPortal(ret.initialSpawnPoint);
                if (portal == null) {
                    portal = ret.map.getPortal(0); // char is on a spawnpoint that doesn't exist - select the first spawnpoint instead
                    ret.initialSpawnPoint = 0;
                }
                ret.setPosition(portal.getPosition());

                int partyid = rs.getInt("party");
                if (partyid >= 0) {
                    MapleParty party = Party.getParty(partyid);
                    if (party != null && party.getMemberById(ret.id) != null) {
                        ret.party = party;
                    }
                }
                ret.bookCover = rs.getInt("monsterbookcover");
                ret.dojo = rs.getInt("dojo_pts");
                ret.dojoRecord = rs.getByte("dojoRecord");
                final String[] pets = rs.getString("pets").split(",");
                for (int i = 0; i < ret.petStore.length; i++) {
                    ret.petStore[i] = Byte.parseByte(pets[i]);
                }

            }
            rs.close();
            ps.close();

            boolean compensate_previousEvans = false;
            ps = con.prepareStatement("SELECT * FROM queststatus WHERE characterid = ?");
            ps.setInt(1, charid);
            rs = ps.executeQuery();
            pse = con.prepareStatement("SELECT * FROM queststatusmobs WHERE queststatusid = ?");

            while (rs.next()) {
                final int id = rs.getInt("quest");
                if (id == 170000) {
                    compensate_previousEvans = true;
                }
                final MapleQuest q = MapleQuest.getInstance(id);
                final MapleQuestStatus status = new MapleQuestStatus(q, rs.getByte("status"));
                final long cTime = rs.getLong("time");
                if (cTime > -1) {
                    status.setCompletionTime(cTime * 1000);
                }
                status.setForfeited(rs.getInt("forfeited"));
                status.setCustomData(rs.getString("customData"));
                ret.quests.put(q, status);
                pse.setInt(1, rs.getInt("queststatusid"));
                final ResultSet rsMobs = pse.executeQuery();

                while (rsMobs.next()) {
                    status.setMobKills(rsMobs.getInt("mob"), rsMobs.getInt("count"));
                }
                rsMobs.close();
            }
            rs.close();
            ps.close();
            pse.close();

            if (channelserver) {
                ret.CRand = new PlayerRandomStream();
                ret.monsterbook = MonsterBook.loadCards(charid);

                ps = con.prepareStatement("SELECT * FROM inventoryslot where characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();

                if (!rs.next()) {
                    throw new RuntimeException("No Inventory slot column found in SQL. [inventoryslot]");
                } else {
                    ret.getInventory(MapleInventoryType.EQUIP).setSlotLimit(rs.getByte("equip"));
                    ret.getInventory(MapleInventoryType.USE).setSlotLimit(rs.getByte("use"));
                    ret.getInventory(MapleInventoryType.SETUP).setSlotLimit(rs.getByte("setup"));
                    ret.getInventory(MapleInventoryType.ETC).setSlotLimit(rs.getByte("etc"));
                    ret.getInventory(MapleInventoryType.CASH).setSlotLimit(rs.getByte("cash"));
                }
                ps.close();
                rs.close();

                for (Pair<IItem, MapleInventoryType> mit : ItemLoader.INVENTORY.loadItems(false, charid).values()) {
                    ret.getInventory(mit.getRight()).addFromDB(mit.getLeft());
                    if (mit.getLeft().getPet() != null) {
                        ret.pets.add(mit.getLeft().getPet());
                    }
                }

                ps = con.prepareStatement("SELECT * FROM accounts WHERE id = ?");
                ps.setInt(1, ret.accountid);
                rs = ps.executeQuery();
                if (rs.next()) {
                    ret.getClient().setAccountName(rs.getString("name"));
                    ret.acash = rs.getInt("ACash");
                    ret.maplepoints = rs.getInt("mPoints");
                    ret.points = rs.getInt("points");
                    ret.vpoints = rs.getInt("vpoints");

                    if (rs.getTimestamp("lastlogon") != null) {
                        // 連續登入獎勵。The comparison is on Calendar's Sunday=1
                        // numbering, and is kept exactly as it was, wrap-around
                        // quirk included: Saturday is 7, 7 + 1 is 8, and no day
                        // is 8, so an account whose previous login was a Saturday
                        // never gets the 50 ACash. Fixing that is a gameplay
                        // change, not part of a java.time migration.
                        final ZoneId zone = ZoneId.systemDefault();
                        // SUNDAY_START.dayOfWeek() is Calendar.DAY_OF_WEEK's
                        // numbering exactly: Sunday 1 ... Saturday 7.
                        final TemporalField dayOfWeek = WeekFields.SUNDAY_START.dayOfWeek();
                        final int lastLogon = Instant.ofEpochMilli(rs.getTimestamp("lastlogon").getTime())
                                .atZone(zone).get(dayOfWeek);
                        if (lastLogon + 1 == ZonedDateTime.now(zone).get(dayOfWeek)) {
                            ret.acash += 50;
                        }
                    }
                    rs.close();
                    ps.close();

                    ps = con.prepareStatement("UPDATE accounts SET lastlogon = CURRENT_TIMESTAMP() WHERE id = ?");
                    ps.setInt(1, ret.accountid);
                    ps.executeUpdate();
                } else {
                    rs.close();
                }
                ps.close();

                ps = con.prepareStatement("SELECT * FROM questinfo WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();

                while (rs.next()) {
                    // I7：questinfo.customData 是 varchar(555) DEFAULT NULL，所以舊資料列
                    // 或手動改過的列讀出來可能是 null。這裡是 null 唯一進得來的入口，
                    // 在這裡正規化成空字串，之後整個 questinfo 就保證不含 null——
                    // getInfoQuest() 不會再把 null 丟給腳本（那些腳本接著就 .equals("")），
                    // Map 也才能是 ConcurrentHashMap。見 MapleCharacter.questinfo 的說明。
                    final String customData = rs.getString("customData");
                    ret.questinfo.put(rs.getInt("quest"), customData == null ? "" : customData);
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT skillid, skilllevel, masterlevel, expiration FROM skills WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                ISkill skil;
                while (rs.next()) {
                    skil = SkillFactory.getSkill(rs.getInt("skillid"));
                    if (skil != null && JobConstants.isApplicableSkill(rs.getInt("skillid"))) {
                        ret.skills.put(skil, new SkillEntry(rs.getByte("skilllevel"), rs.getByte("masterlevel"), rs.getLong("expiration")));
                    } else if (skil == null) { //doesnt. exist. e.g. bb
                        ret.remainingSp[JobConstants.getSkillBookForSkill(rs.getInt("skillid"))] += rs.getByte("skilllevel");
                    }
                }
                rs.close();
                ps.close();

                ret.expirationTask(false); //do it now

                // Bless of Fairy handling
                ps = con.prepareStatement("SELECT * FROM characters WHERE accountid = ? ORDER BY level DESC");
                ps.setInt(1, ret.accountid);
                rs = ps.executeQuery();
                byte maxlevel_ = 0;
                while (rs.next()) {
                    if (rs.getInt("id") != charid) { // Not this character
                        byte maxlevel = (byte) (rs.getShort("level") / 10);

                        if (maxlevel > 20) {
                            maxlevel = 20;
                        }
                        if (maxlevel > maxlevel_) {
                            maxlevel_ = maxlevel;
                            ret.BlessOfFairy_Origin = rs.getString("name");
                        }

                    } else if (charid < 17000 && !compensate_previousEvans && ret.job >= 2200 && ret.job <= 2218) { //compensate, watch max charid
                        for (int i = 0; i <= JobConstants.getSkillBook(ret.job); i++) {
                            ret.remainingSp[i] += 2; //2 that they missed. gg
                        }
                        ret.setQuestAdd(MapleQuest.getInstance(170000), (byte) 0, null); //set it so never again
                    }
                }
                ret.skills.put(SkillFactory.getSkill(JobConstants.getBOF_ForJob(ret.job)), new SkillEntry(maxlevel_, (byte) 0, -1));
                ps.close();
                rs.close();
                // END

                ps = con.prepareStatement("SELECT * FROM skillmacros WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                int position;
                while (rs.next()) {
                    position = rs.getInt("position");
                    SkillMacro macro = new SkillMacro(rs.getInt("skill1"), rs.getInt("skill2"), rs.getInt("skill3"), rs.getString("name"), rs.getInt("shout"), position);
                    ret.skillMacros[position] = macro;
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT `key`,`type`,`action` FROM keymap WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();

                final Map<Integer, Pair<Byte, Integer>> keyb = ret.keylayout.layout();
                while (rs.next()) {
                    keyb.put(Integer.valueOf(rs.getInt("key")), new Pair<>(rs.getByte("type"), rs.getInt("action")));
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT `locationtype`,`map` FROM savedlocations WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                while (rs.next()) {
                    ret.savedLocations[rs.getInt("locationtype")] = rs.getInt("map");
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT `characterid_to`,`when` FROM famelog WHERE characterid = ? AND DATEDIFF(NOW(),`when`) < 30");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                ret.lastfametime = 0;
                ret.lastmonthfameids = new ArrayList<>(31);
                while (rs.next()) {
                    ret.lastfametime = Math.max(ret.lastfametime, rs.getTimestamp("when").getTime());
                    ret.lastmonthfameids.add(Integer.valueOf(rs.getInt("characterid_to")));
                }
                rs.close();
                ps.close();

                ret.buddylist.loadFromDb(charid);
                ret.storage = MapleStorage.loadStorage(ret.accountid);
                ret.cs = new CashShop(ret.accountid, charid, ret.getJob());

                ps = con.prepareStatement("SELECT sn FROM wishlist WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                int i = 0;
                while (rs.next()) {
                    ret.wishlist[i] = rs.getInt("sn");
                    i++;
                }
                while (i < 10) {
                    ret.wishlist[i] = 0;
                    i++;
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT mapid FROM trocklocations WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                int r = 0;
                while (rs.next()) {
                    ret.rocks[r] = rs.getInt("mapid");
                    r++;
                }
                while (r < 10) {
                    ret.rocks[r] = 999999999;
                    r++;
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT mapid FROM regrocklocations WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                r = 0;
                while (rs.next()) {
                    ret.regrocks[r] = rs.getInt("mapid");
                    r++;
                }
                while (r < 5) {
                    ret.regrocks[r] = 999999999;
                    r++;
                }
                rs.close();
                ps.close();

                ps = con.prepareStatement("SELECT * FROM mountdata WHERE characterid = ?");
                ps.setInt(1, charid);
                rs = ps.executeQuery();
                if (!rs.next()) {
                    throw new RuntimeException("No mount data found on SQL column");
                }
                final IItem mount = ret.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -18);
                ret.mount = new MapleMount(ret, mount != null ? mount.getItemId() : 0, ret.job > 1000 && ret.job < 2000 ? 10001004 : (ret.job >= 2000 ? (ret.job == 2001 || ret.job >= 2200 ? 20011004 : (ret.job >= 3000 ? 30001004 : 20001004)) : 1004), rs.getByte("Fatigue"), rs.getByte("Level"), rs.getInt("Exp"));
                ps.close();
                rs.close();

                ret.stats.recalcLocalStats(true);
            } else { // Not channel server
                for (Pair<IItem, MapleInventoryType> mit : ItemLoader.INVENTORY.loadItems(true, charid).values()) {
                    ret.getInventory(mit.getRight()).addFromDB(mit.getLeft());
                }
            }
        } catch (SQLException ess) {
            // One record, not two: the trace and the "Failed to load character.."
            // line were always the same event. ERROR rather than the INFO the
            // stream would suggest - the load is abandoned here and a
            // half-populated MapleCharacter is still returned below.
            LOG.log(System.Logger.Level.ERROR, "Failed to load character..", ess);
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (rs != null) {
                    rs.close();
                }
            } catch (SQLException ignore) {
                // closing the statement/result set on the way out; nothing to do if that fails
            }
        }
        return ret;
    }

    public static void saveNewCharToDB(final MapleCharacter chr, final int type, final boolean db) {
        try (Connection con = DatabaseConnection.getConnection()) {

        PreparedStatement ps = null;
        PreparedStatement pse = null;
        ResultSet rs = null;
        try {
            con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            con.setAutoCommit(false);

            ps = con.prepareStatement("""
                    INSERT INTO characters (
                            level, fame, str, dex, luk, `int`, exp, hp, mp, maxhp, maxmp, sp, ap,
                            gm, skincolor, gender, job, hair, face, map, meso, hpApUsed, spawnpoint,
                            party, buddyCapacity, monsterbookcover, dojo_pts, dojoRecord, pets,
                            subcategory, marriageId, currentrep, totalrep, prefix, accountid, name,
                            world
                    ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """, DatabaseConnection.RETURN_GENERATED_KEYS);

            ps.setInt(1, 1); // Level
            ps.setShort(2, (short) 0); // Fame
            final PlayerStats stat = chr.stats;
            ps.setShort(3, stat.getStr()); // Str
            ps.setShort(4, stat.getDex()); // Dex
            ps.setShort(5, stat.getInt()); // Int
            ps.setShort(6, stat.getLuk()); // Luk
            ps.setInt(7, 0); // EXP
            ps.setShort(8, stat.getHp()); // HP
            ps.setShort(9, stat.getMp());
            ps.setShort(10, stat.getMaxHp()); // MP
            ps.setShort(11, stat.getMaxMp());
            ps.setString(12, "0,0,0,0,0,0,0,0,0,0"); // Remaining SP
            ps.setShort(13, (short) 0); // Remaining AP
            ps.setByte(14, (byte) 0); // GM Level
            ps.setByte(15, chr.skinColor);
            ps.setByte(16, chr.gender);
            ps.setShort(17, chr.job);
            ps.setInt(18, chr.hair);
            ps.setInt(19, chr.face);
            ps.setInt(20, type == 1 ? 0 : (type == 0 ? 130030000 : (type == 3 ? 900090000 : 914000000)));
            ps.setInt(21, chr.meso); // Meso
            ps.setShort(22, (short) 0); // HP ap used
            ps.setByte(23, (byte) 0); // Spawnpoint
            ps.setInt(24, -1); // Party
            ps.setByte(25, chr.buddylist.getCapacity()); // Buddylist
            ps.setInt(26, 0); // Monster book cover
            ps.setInt(27, 0); // Dojo
            ps.setInt(28, 0); // Dojo record
            ps.setString(29, "-1,-1,-1");
            ps.setInt(30, /*db ? 1 : */ 0); //for now
            ps.setInt(31, 0); //marriage ID
            ps.setInt(32, 0); //current reps
            ps.setInt(33, 0); //total reps
            ps.setInt(34, chr.prefix);
            ps.setInt(35, chr.getAccountID());
            ps.setString(36, chr.name);
           ps.setByte(37, chr.world);
            ps.executeUpdate();

            rs = ps.getGeneratedKeys();
            if (rs.next()) {
                chr.id = rs.getInt(1);
            } else {
                throw new DatabaseException("Inserting char failed.");
            }
            ps.close();
            rs.close();
            ps = con.prepareStatement("INSERT INTO queststatus (`queststatusid`, `characterid`, `quest`, `status`, `time`, `forfeited`, `customData`) VALUES (DEFAULT, ?, ?, ?, ?, ?, ?)", DatabaseConnection.RETURN_GENERATED_KEYS);
            pse = con.prepareStatement("INSERT INTO queststatusmobs VALUES (DEFAULT, ?, ?, ?)");
            ps.setInt(1, chr.id);
            for (final MapleQuestStatus q : chr.quests.values()) {
                ps.setInt(2, q.getQuest().getId());
                ps.setInt(3, q.getStatus());
                ps.setInt(4, (int) (q.getCompletionTime() / 1000));
                ps.setInt(5, q.getForfeited());
                ps.setString(6, q.getCustomData());
                ps.executeUpdate();
                rs = ps.getGeneratedKeys();
                rs.next();

                if (q.hasMobKills()) {
                    for (int mob : q.getMobKills().keySet()) {
                        pse.setInt(1, rs.getInt(1));
                        pse.setInt(2, mob);
                        pse.setInt(3, q.getMobKills(mob));
                        pse.executeUpdate();
                    }
                }
                rs.close();
            }
            ps.close();
            pse.close();

            ps = con.prepareStatement("INSERT INTO inventoryslot (characterid, `equip`, `use`, `setup`, `etc`, `cash`) VALUES (?, ?, ?, ?, ?, ?)");
            ps.setInt(1, chr.id);
            ps.setByte(2, (byte) 32); // Eq
            ps.setByte(3, (byte) 32); // Use
            ps.setByte(4, (byte) 32); // Setup
            ps.setByte(5, (byte) 32); // ETC
            ps.setByte(6, (byte) 60); // Cash
            ps.execute();
            ps.close();

            ps = con.prepareStatement("INSERT INTO mountdata (characterid, `Level`, `Exp`, `Fatigue`) VALUES (?, ?, ?, ?)");
            ps.setInt(1, chr.id);
            ps.setByte(2, (byte) 1);
            ps.setInt(3, 0);
            ps.setByte(4, (byte) 0);
            ps.execute();
            ps.close();

            List<Pair<IItem, MapleInventoryType>> listing = new ArrayList<>();
            for (final MapleInventory iv : chr.inventory) {
                for (final IItem item : iv.list()) {
                    listing.add(new Pair<>(item, iv.getType()));
                }
            }
            ItemLoader.INVENTORY.saveItems(listing, con, chr.id);

            /* // SEA 102
             final int[] array1 = {2, 3, 4, 5, 6, 7, 16, 17, 18, 19, 23, 25, 26, 27, 31, 34, 37, 38, 41, 44, 45, 46, 50, 57, 59, 60, 61, 62, 63, 64, 65, 8, 9, 24, 30};
             final int[] array2 = {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 5, 4, 5, 6, 6, 6, 6, 6, 6, 6, 4, 4, 4, 4};
             final int[] array3 = {10, 12, 13, 18, 6, 11, 8, 5, 0, 4, 1, 19, 14, 15, 3, 17, 9, 20, 22, 50, 51, 52, 7, 53, 100, 101, 102, 103, 104, 105, 106, 16, 23, 24, 2};
             */
            int[] array1 = {2, 3, 4, 5, 6, 7, 16, 17, 18, 19, 23, 25, 26, 27, 29, 31, 34, 35, 37, 38, 40, 41, 43, 44, 45, 46, 48, 50, 56, 57, 59, 60, 61, 62, 63, 64, 65};
            int[] array2 = {4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 5, 4, 4, 4, 4, 4, 4, 4, 4, 5, 5, 4, 4, 4, 5, 5, 6, 6, 6, 6, 6, 6, 6};
            int[] array3 = {10, 12, 13, 18, 24, 21, 8, 5, 0, 4, 1, 19, 14, 15, 52, 2, 17, 11, 3, 20, 16, 23, 9, 50, 51, 6, 22, 7, 53, 54, 100, 101, 102, 103, 104, 105, 106};

            ps = con.prepareStatement("INSERT INTO keymap (characterid, `key`, `type`, `action`) VALUES (?, ?, ?, ?)");
            ps.setInt(1, chr.id);
            for (int i = 0; i < array1.length; i++) {
                ps.setInt(2, array1[i]);
                ps.setInt(3, array2[i]);
                ps.setInt(4, array3[i]);
                ps.execute();
            }
            ps.close();

            con.commit();
        } catch (Exception e) {
            // One record: the trace and the message were always one event.
            LOG.log(System.Logger.Level.ERROR, "[charsave] Error saving character data", e);
            try {
                con.rollback();
            } catch (SQLException ex) {
                // A24：這裡本來回報的是 e（存檔失敗）而不是 ex（rollback 失敗）。
                // 存檔失敗上面那一行已經連堆疊完整記過一次了，所以舊寫法的淨效果是
                // 「同一份堆疊印兩次，而 rollback 為什麼失敗從來沒人知道」——這是最需要
                // 知道的那一個，因為 rollback 失敗代表交易狀態不明。
                // 原本的存檔例外掛成 suppressed，兩者留在同一筆紀錄裡，資訊一點沒少。
                ex.addSuppressed(e);
                LOG.log(System.Logger.Level.ERROR, "[charsave] Error Rolling Back", ex);
            }
        } finally {
            try {
                if (pse != null) {
                    pse.close();
                }
                if (ps != null) {
                    ps.close();
                }
                if (rs != null) {
                    rs.close();
                }
                con.setAutoCommit(true);
                con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            } catch (SQLException e) {
                // One record. ERROR: the connection goes back to the pool still
                // in a transaction, with the wrong isolation level.
                LOG.log(System.Logger.Level.ERROR, "[charsave] Error going back to autocommit mode", e);
            }
        }
        } catch (SQLException e) {
            // Thrown by returning the connection to the pool; the save itself
            // is already accounted for by the catch above. ERROR: the borrow was
            // not given back, which is a permit that never returns to the pool.
            // Terse on purpose - this one never printed a trace.
            LOG.log(System.Logger.Level.ERROR, "[charsave] Error releasing the connection: " + e);
        }
    }

    public static void saveToDB(final MapleCharacter chr, boolean dc, boolean fromcs) {
        if (chr.isClone()) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection()) {

        PreparedStatement ps = null;
        PreparedStatement pse = null;
        ResultSet rs = null;

        try {
            con.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            con.setAutoCommit(false);

            ps = con.prepareStatement("""
                    UPDATE characters SET
                            level = ?, fame = ?, str = ?, dex = ?, luk = ?, `int` = ?, exp = ?,
                            hp = ?, mp = ?, maxhp = ?, maxmp = ?, sp = ?, ap = ?, gm = ?,
                            skincolor = ?, gender = ?, job = ?, hair = ?, face = ?, map = ?,
                            meso = ?, hpApUsed = ?, spawnpoint = ?, party = ?, buddyCapacity = ?,
                            monsterbookcover = ?, dojo_pts = ?, dojoRecord = ?, pets = ?,
                            subcategory = ?, marriageId = ?, currentrep = ?, totalrep = ?,
                            charmessage = ?, expression = ?, constellation = ?, blood = ?,
                            month = ?, day = ?, beans = ?, prefix = ?, name = ?, gachexp = ?
                    WHERE id = ?
                    """);
            ps.setInt(1, chr.level);
            ps.setShort(2, chr.fame);
            ps.setShort(3, chr.stats.getStr());
            ps.setShort(4, chr.stats.getDex());
            ps.setShort(5, chr.stats.getLuk());
            ps.setShort(6, chr.stats.getInt());
            ps.setInt(7, chr.exp);
            ps.setShort(8, chr.stats.getHp() < 1 ? 50 : chr.stats.getHp());
            ps.setShort(9, chr.stats.getMp());
            ps.setShort(10, chr.stats.getMaxHp());
            ps.setShort(11, chr.stats.getMaxMp());
            final StringBuilder sps = new StringBuilder();
            for (int i = 0; i < chr.remainingSp.length; i++) {
                sps.append(chr.remainingSp[i]);
                sps.append(",");
            }
            final String sp = sps.toString();
            ps.setString(12, sp.substring(0, sp.length() - 1));
            ps.setShort(13, chr.remainingAp);
            ps.setByte(14, chr.gmLevel);
            ps.setByte(15, chr.skinColor);
            ps.setByte(16, chr.gender);
            ps.setShort(17, chr.job);
            ps.setInt(18, chr.hair);
            ps.setInt(19, chr.face);
            if (!fromcs && chr.map != null) {
                if (chr.map.getForcedReturnId() != 999999999) {
                    ps.setInt(20, chr.map.getForcedReturnId());
                } else {
                    ps.setInt(20, chr.stats.getHp() < 1 ? chr.map.getReturnMapId() : chr.map.getId());
                }
            } else {
                ps.setInt(20, chr.mapid);
            }
            ps.setInt(21, chr.meso);
            ps.setShort(22, chr.hpApUsed);
            if (chr.map == null) {
                ps.setByte(23, (byte) 0);
            } else {
                final MaplePortal closest = chr.map.findClosestSpawnpoint(chr.getPosition());
                ps.setByte(23, (byte) (closest != null ? closest.getId() : 0));
            }
            ps.setInt(24, chr.party != null ? chr.party.getId() : -1);
            ps.setShort(25, chr.buddylist.getCapacity());
            ps.setInt(26, chr.bookCover);
            ps.setInt(27, chr.dojo);
            ps.setInt(28, chr.dojoRecord);
            final StringBuilder petz = new StringBuilder();
            int petLength = 0;
            for (final MaplePet pet : chr.pets) {
                pet.saveToDb();
                if (pet.getSummoned()) {

                    petz.append(pet.getInventoryPosition());
                    petz.append(",");
                    petLength++;
                }
            }
            while (petLength < 3) {
                petz.append("-1,");
                petLength++;
            }
            final String petstring = petz.toString();
            ps.setString(29, petstring.substring(0, petstring.length() - 1));
            ps.setByte(30, chr.subcategory);
            ps.setInt(31, chr.marriageId);
            ps.setInt(32, chr.currentrep);
            ps.setInt(33, chr.totalrep);
            ps.setString(34, chr.charmessage);
            ps.setInt(35, chr.expression);
            ps.setInt(36, chr.constellation);
            ps.setInt(37, chr.blood);
            ps.setInt(38, chr.month);
            ps.setInt(39, chr.day);
            ps.setInt(40, chr.beans);
            ps.setInt(41, chr.prefix);
            ps.setString(42, chr.name);
        ps.setInt(43, chr.gachexp);
            ps.setInt(44, chr.id);

            if (ps.executeUpdate() < 1) {
                ps.close();
                throw new DatabaseException("Character not in database (" + chr.id + ")");
            }
            ps.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM skillmacros WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO skillmacros (characterid, skill1, skill2, skill3, name, shout, position) VALUES (?, ?, ?, ?, ?, ?, ?)");
            for (int i = 0; i < 5; i++) {
                final SkillMacro macro = chr.skillMacros[i];
                if (macro != null) {
                    ps.setInt(1, chr.id);
                    ps.setInt(2, macro.getSkill1());
                    ps.setInt(3, macro.getSkill2());
                    ps.setInt(4, macro.getSkill3());
                    ps.setString(5, macro.getName());
                    ps.setInt(6, macro.getShout());
                    ps.setInt(7, i);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM inventoryslot WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO inventoryslot (characterid, `equip`, `use`, `setup`, `etc`, `cash`) VALUES (?, ?, ?, ?, ?, ?)");
            ps.setInt(1, chr.id);
            ps.setByte(2, chr.getInventory(MapleInventoryType.EQUIP).getSlotLimit());
            ps.setByte(3, chr.getInventory(MapleInventoryType.USE).getSlotLimit());
            ps.setByte(4, chr.getInventory(MapleInventoryType.SETUP).getSlotLimit());
            ps.setByte(5, chr.getInventory(MapleInventoryType.ETC).getSlotLimit());
            ps.setByte(6, chr.getInventory(MapleInventoryType.CASH).getSlotLimit());
            ps.execute();
            ps.close();

            chr.saveInventory(con);

            chr.deleteWhereCharacterId(con, "DELETE FROM questinfo WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO questinfo (`characterid`, `quest`, `customData`) VALUES (?, ?, ?)");
            ps.setInt(1, chr.id);
            for (final Entry<Integer, String> q : chr.questinfo.entrySet()) {
                ps.setInt(2, q.getKey());
                ps.setString(3, q.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM queststatus WHERE characterid = ?");
            // queststatus 維持逐列 executeUpdate：每一列的 generated key 就是
            // queststatusmobs 的外來鍵，改成批次就拿不到逐列的 key。只有
            // queststatusmobs 收成批次 —— key 在 setInt 當下已經取出，累積到最後
            // 才送出不會改變任何一列的內容。
            ps = con.prepareStatement("INSERT INTO queststatus (`queststatusid`, `characterid`, `quest`, `status`, `time`, `forfeited`, `customData`) VALUES (DEFAULT, ?, ?, ?, ?, ?, ?)", DatabaseConnection.RETURN_GENERATED_KEYS);
            pse = con.prepareStatement("INSERT INTO queststatusmobs VALUES (DEFAULT, ?, ?, ?)");
            ps.setInt(1, chr.id);
            for (final MapleQuestStatus q : chr.quests.values()) {
                ps.setInt(2, q.getQuest().getId());
                ps.setInt(3, q.getStatus());
                ps.setInt(4, (int) (q.getCompletionTime() / 1000));
                ps.setInt(5, q.getForfeited());
                ps.setString(6, q.getCustomData());
                ps.executeUpdate();
                rs = ps.getGeneratedKeys();
                // rs.next() 的回傳值不能丟掉：沒有 generated key 時 rs.getInt(1)
                // 會丟 SQLException，被外層 catch 接住後整次存檔都會 rollback，
                // 玩家掉的是整隻角色這次的進度，而不是這一筆任務的怪物計數。
                if (!rs.next()) {
                    rs.close();
                    // WARNING, not ERROR: the save carries on (continue) and
                    // only this one quest's mob counts are skipped. The message
                    // is built on the !rs.next() branch, which a healthy save
                    // never takes, so the quest loop pays nothing for it.
                    LOG.log(System.Logger.Level.WARNING, MapleClient.getLogMessage(chr, "[charsave] queststatus 未回傳 generated key，略過 quest " + q.getQuest().getId() + " 的怪物計數"));
                    continue;
                }
                final int queststatusid = rs.getInt(1);

                if (q.hasMobKills()) {
                    for (int mob : q.getMobKills().keySet()) {
                        pse.setInt(1, queststatusid);
                        pse.setInt(2, mob);
                        pse.setInt(3, q.getMobKills(mob));
                        pse.addBatch();
                    }
                }
                rs.close();
            }
            ps.close();
            pse.executeBatch();
            pse.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM skills WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO skills (characterid, skillid, skilllevel, masterlevel, expiration) VALUES (?, ?, ?, ?, ?)");
            ps.setInt(1, chr.id);

            for (final Entry<ISkill, SkillEntry> skill : chr.skills.entrySet()) {
                if (JobConstants.isApplicableSkill(skill.getKey().getId())) { //do not save additional skills
                    ps.setInt(2, skill.getKey().getId());
                    ps.setByte(3, skill.getValue().skillevel);
                    ps.setByte(4, skill.getValue().masterlevel);
                    ps.setLong(5, skill.getValue().expiration);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.close();

            List<MapleCoolDownValueHolder> cd = chr.getCooldowns();
            if (dc && cd.size() > 0) {
                ps = con.prepareStatement("INSERT INTO skills_cooldowns (charid, SkillID, StartTime, length) VALUES (?, ?, ?, ?)");
                ps.setInt(1, chr.getId());
                for (final MapleCoolDownValueHolder cooling : cd) {
                    ps.setInt(2, cooling.skillId);
                    ps.setLong(3, cooling.startTime);
                    ps.setLong(4, cooling.length);
                    ps.addBatch();
                }
                ps.executeBatch();
                ps.close();
            }

            chr.deleteWhereCharacterId(con, "DELETE FROM savedlocations WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO savedlocations (characterid, `locationtype`, `map`) VALUES (?, ?, ?)");
            ps.setInt(1, chr.id);
            for (final SavedLocationType savedLocationType : SavedLocationType.values()) {
                if (chr.savedLocations[savedLocationType.getValue()] != -1) {
                    ps.setInt(2, savedLocationType.getValue());
                    ps.setInt(3, chr.savedLocations[savedLocationType.getValue()]);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM buddies WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO buddies (characterid, `buddyid`, `pending`) VALUES (?, ?, ?)");
            ps.setInt(1, chr.id);
            for (BuddylistEntry entry : chr.buddylist.getBuddies()) {
                ps.setInt(2, entry.getCharacterId());
                ps.setInt(3, entry.isVisible() ? 0 : 1);
                ps.addBatch();
            }
            ps.executeBatch();
            ps.close();

            ps = con.prepareStatement("UPDATE accounts SET `ACash` = ?, `mPoints` = ?, `points` = ?, `vpoints` = ? WHERE id = ?");
            ps.setInt(1, chr.acash);
            ps.setInt(2, chr.maplepoints);
            ps.setInt(3, chr.points);
            ps.setInt(4, chr.vpoints);
            ps.setInt(5, chr.client.getAccID());
            ps.execute();
            ps.close();

            if (chr.storage != null) {
                chr.storage.saveToDB();
            }
            if (chr.cs != null) {
                chr.cs.save();
            }
            PlayerNPC.updateByCharId(chr);
            chr.keylayout.saveKeys(chr.id);
            chr.mount.saveMount(chr.id);
            chr.monsterbook.saveCards(chr.id);

            chr.deleteWhereCharacterId(con, "DELETE FROM wishlist WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO wishlist(characterid, sn) VALUES(?, ?) ");
            // getWishlistSize() 數的是「非零的格數」，不是「最後一格的索引」。
            // 稀疏的願望清單 {0, 12345, ...} 會被算成 size = 1，舊寫法只存
            // wishlist[0] = 0：寫進一列垃圾 sn = 0，真正的 12345 則整筆消失。
            // 改成跟下面 trocklocations / regrocklocations 一樣，走完整個陣列、
            // 用哨兵值（這裡是 0，與 getWishlistSize() 的判斷一致）篩掉空格。
            for (int i = 0; i < chr.wishlist.length; i++) {
                if (chr.wishlist[i] > 0) {
                    ps.setInt(1, chr.getId());
                    ps.setInt(2, chr.wishlist[i]);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM trocklocations WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO trocklocations(characterid, mapid) VALUES(?, ?) ");
            for (int i = 0; i < chr.rocks.length; i++) {
                if (chr.rocks[i] != 999999999) {
                    ps.setInt(1, chr.getId());
                    ps.setInt(2, chr.rocks[i]);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.close();

            chr.deleteWhereCharacterId(con, "DELETE FROM regrocklocations WHERE characterid = ?");
            ps = con.prepareStatement("INSERT INTO regrocklocations(characterid, mapid) VALUES(?, ?) ");
            for (int i = 0; i < chr.regrocks.length; i++) {
                if (chr.regrocks[i] != 999999999) {
                    ps.setInt(1, chr.getId());
                    ps.setInt(2, chr.regrocks[i]);
                    ps.addBatch();
                }
            }
            ps.executeBatch();
            ps.close();

            con.commit();
        } catch (Exception e) {
            // One record: the trace and the context line were always one event,
            // so the message keeps its trailing e.toString() and the Throwable
            // carries the trace.
            LOG.log(System.Logger.Level.ERROR, MapleClient.getLogMessage(chr, "[charsave] Error saving character data") + e, e);
            try {
                con.rollback();
            } catch (SQLException ex) {
                // A24：同上，本來回報的是 e（存檔失敗）而且完全沒有堆疊。訊息尾端原本
                // 串的那個 e.toString() 也拿掉了——它現在以 suppressed 的形式跟在 ex
                // 後面，而且帶著自己的堆疊，比一行 toString 有用。
                ex.addSuppressed(e);
                LOG.log(System.Logger.Level.ERROR, MapleClient.getLogMessage(chr, "[charsave] Error Rolling Back"), ex);
            }
        } finally {
            try {
                if (ps != null) {
                    ps.close();
                }
                if (pse != null) {
                    pse.close();
                }
                if (rs != null) {
                    rs.close();
                }
                con.setAutoCommit(true);
                con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            } catch (SQLException e) {
                // ERROR: the connection goes back to the pool still in a
                // transaction, with the wrong isolation level. Terse, as it was.
                LOG.log(System.Logger.Level.ERROR, MapleClient.getLogMessage(chr, "[charsave] Error going back to autocommit mode") + e);
            }
        }
        } catch (SQLException e) {
            // Thrown by returning the connection to the pool; the save itself
            // is already accounted for by the catch above. ERROR: the borrow was
            // not given back. Terse, as it was.
            LOG.log(System.Logger.Level.ERROR, MapleClient.getLogMessage(chr, "[charsave] Error releasing the connection") + e);
        }
    }

    public static void deleteWhereCharacterId(Connection con, String sql, int id) throws SQLException {
        PreparedStatement ps = con.prepareStatement(sql);
        ps.setInt(1, id);
        ps.executeUpdate();
        ps.close();
    }

    public static void saveInventory(final MapleCharacter chr, final Connection con) throws SQLException {
        List<Pair<IItem, MapleInventoryType>> listing = new ArrayList<>();
        for (final MapleInventory iv : chr.inventory) {
            for (final IItem item : iv.list()) {
                listing.add(new Pair<>(item, iv.getType()));
            }
        }
        if (con != null) {
            ItemLoader.INVENTORY.saveItems(listing, con, chr.id);
        } else {
            ItemLoader.INVENTORY.saveItems(listing, chr.id);
        }
    }
}
