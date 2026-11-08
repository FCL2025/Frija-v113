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

import constants.GameConstants;
import constants.ItemConstants;
import constants.JobConstants;
import client.inventory.MapleInventoryType;
import client.inventory.MapleInventory;
import client.inventory.Item;
import client.inventory.MapleInventoryIdentifier;
import client.inventory.IItem;
import client.inventory.MapleMount;
import client.inventory.MaplePet;
import client.inventory.ItemFlag;
import client.inventory.MapleRing;
import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Deque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import client.anticheat.CheatTracker;
import constants.ServerConstants;
import database.DatabaseConnection;
import handling.MaplePacket;
import handling.channel.ChannelServer;
import handling.world.Broadcast;
import handling.world.CharacterTransfer;
import handling.world.Family;
import handling.world.Guild;
import handling.world.MapleMessenger;
import handling.world.MapleMessengerCharacter;
import handling.world.MapleParty;
import handling.world.MaplePartyCharacter;
import handling.world.Messenger;
import handling.world.Party;
import handling.world.PartyOperation;
import handling.world.PlayerBuffStorage;
import handling.world.PlayerBuffValueHolder;
import handling.world.World;
import handling.world.family.MapleFamily;
import handling.world.family.MapleFamilyBuff;
import handling.world.family.MapleFamilyBuff.MapleFamilyBuffEntry;
import handling.world.family.MapleFamilyCharacter;
import handling.world.guild.MapleGuild;
import handling.world.guild.MapleGuildCharacter;
import java.lang.ref.WeakReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import server.Timer;
import tools.MockIOSession;
import scripting.EventInstanceManager;
import scripting.NPCScriptManager;
import server.MaplePortal;
import server.MapleShop;
import server.MapleStatEffect;
import server.MapleStorage;
import server.MapleTrade;
import server.Randomizer;
import server.RandomRewards;
import server.MapleCarnivalParty;
import server.MapleItemInformationProvider;
import server.life.MapleMonster;
import server.maps.AbstractAnimatedMapleMapObject;
import server.maps.MapleDoor;
import server.maps.MapleMap;
import server.maps.MapleMapFactory;
import server.maps.MapleMapObject;
import server.maps.MapleMapObjectType;
import server.maps.MapleSummon;
import server.maps.FieldLimitType;
import server.maps.SavedLocationType;
import server.quest.MapleQuest;
import server.shops.IMaplePlayerShop;
import server.CashShop;
import tools.Pair;
import tools.packet.BuddyPacket;
import tools.packet.BuffPacket;
import tools.packet.CharacterPacket;
import tools.packet.ChatPacket;
import tools.packet.EventPacket;
import tools.packet.InventoryPacket;
import tools.packet.MTSCSPacket;
import tools.packet.MapPacket;
import tools.packet.MobPacket;
import tools.packet.MonsterCarnivalPacket;
import tools.packet.PartyPacket;
import tools.packet.PetPacket;
import tools.packet.PlayerShopPacket;
import tools.packet.QuestPacket;
import tools.packet.ServerPacket;
import tools.packet.UIPacket;
import server.MapleCarnivalChallenge;
import server.MapleInventoryManipulator;
import server.life.MobSkill;
import server.maps.Event_PyramidSubway;
import server.maps.MapleDragon;
import server.maps.MapleFoothold;
import server.movement.LifeMovementFragment;
import tools.ConcurrentEnumMap;
import tools.FileoutputUtil;

public class MapleCharacter extends AbstractAnimatedMapleMapObject {

    /** 命名與分級規則見 {@code tools.Logging}。 */
    private static final System.Logger LOG = System.getLogger(MapleCharacter.class.getName());

    // E4：持久化搬到同 package 的 CharacterRepository 之後，被它直接讀寫的欄位
    // 一律只放寬到 package-private（不是 public），沒被它碰到的維持 private。
    // 原本擠在同一行的宣告因此拆成兩行，讓放寬的範圍剛好是需要的那些、不多不少。
    String name, BlessOfFairy_Origin, charmessage;
    private String chalktext;
    long lastfametime;
    private long lastCombo, keydown_skill;
    byte dojoRecord, gmLevel, gender, initialSpawnPoint, skinColor, guildrank = 5, allianceRank = 5, world, subcategory; // Make this a quest record, TODO : Transfer it somehow with the current data
    private byte fairyExp = 10, numClones;
    short level, fame, hpApUsed, job, remainingAp;
    private short mulung_energy, combo, availableCP, totalCP;
    int accountid, id, meso, exp, hair, face, mapid, bookCover, dojo,
            guildid = 0, maplepoints, acash, points, vpoints,
            rank = 1, rankMove = 0, jobRank = 1, jobRankMove = 0, marriageId,
            currentrep, totalrep, gachexp,
            expression, constellation, blood, month, day, beans, prefix;
    // C10：exp 與 level 的「讀 → 算 → 寫」必須是一步。擊殺者的 handler thread 會經由
    // MapleMonster.killBy → MapleMap.killMonster 對「每一個攻擊者／每一個隊員」呼叫
    // gainExpMonster，所以同一隻角色的 exp 同時被好幾條執行緒加；沒有鎖時 exp += total
    // 是典型的掉更新。升級門檻的判斷也必須只有一條執行緒過得去——和 MapleMonster.damage
    // 用 damageLock 決定「誰的那一下把 HP 打過零」是同一個模式，否則兩條執行緒會各自
    // levelUp() 一次，玩家一次跳兩級。
    // 鎖底下只有純算術：levelUp() 會 broadcast、familyRep() 會借資料庫連線，兩者都在鎖外，
    // 這是 CLAUDE.md「鎖不可握過 broadcast 或資料庫借用」要求的。
    private final Object expLock = new Object();
    // C10：由 expLock 保護。levelUp() 不能在鎖底下跑（它 broadcast、更新隊伍公會家族），
    // 所以改用「這一刻已經有人在升級了」的旗標：第二條執行緒只把經驗值疊上去，由正在升級的
    // 那一條在 levelUp() 回來之後重新讀 exp、一併結算後面的級數（原本就有的 gainExp(0,…)
    // 遞迴就是在做這件事）。沒有這個旗標時兩條執行緒會各自 levelUp 一次，玩家憑同一份經驗
    // 白跳一級，而且第二次的 exp -= needed 會把經驗值扣成負的、再被尾端的 exp < 0 夾成滿格。
    // 單執行緒時這個旗標永遠是 false，行為和改之前逐字相同。
    private boolean levellingUp;
    // C10：meso / acash / maplepoints 同理，而且它們的「讀 → 檢查 → 寫」本來就是複合操作
    // （meso + gain < 0 的溢位守門、acash + quantity < 0 的上限守門）。
    // 跨執行緒的呼叫端：gainMeso 由交易確認方的執行緒同時改買賣雙方（MapleTrade.completeTrade）、
    // 由商人店主以外的買家執行緒加錢（PlayerInteractionHandler）；modifyCSPoints 由商城的
    // handler thread 與面板的 Panel-Worker-N（AccountsApi 改帳號貨幣）同時改。
    private final Object currencyLock = new Object();
    private int fallcounter = 0, chair, itemEffect, marriageItemId = 0,
            linkMid = 0, coconutteam = 0, followid = 0, battleshipHP = 0,
            challenge, guildContribution = 0, beansNum, beansRange;
    private boolean canSetBeansNum;
    private Point old = new Point(0, 0);
    private boolean smega, hidden, hasSummon = false;
    int[] wishlist, rocks, savedLocations, regrocks, remainingSp = new int[10];
    private AtomicInteger inst;
    // C4 併發審核：以下每個可變集合都逐一追過「誰讀、誰寫、跑在哪條執行緒」。
    // 會踩到別人角色物件的四條路徑是 MapleMap.broadcastMessage / MapleMap 對 characters
    // 的走訪、隊伍與公會的迭代、server.Timer 的排程工作，以及面板的 Panel-Worker-N。
    // 標「只有本人」的欄位是確認過只在自己的 Maple-Handler-N 上進出，故刻意不改。

    // 只在自己的 handler thread 上整串換掉參考（PlayerHandler 移動封包寫、AdminMapCommands 讀自己的），
    // list 內容從不就地修改，換頻/複製人也只是再指一次 → 不需要同步。
    private List<LifeMovementFragment> lastres;
    // 只有本人：PlayersHandler 的給人氣（chr 就是 c.getPlayer()）與換頻時的 CharacterTransfer。
    List<Integer> lastmonthfameids;
    // 共用：智慧之門到期時 Timer.BUFF 的 CancelEffectAction → cancelEffect() 會走訪並清空，
    // 本人的 handler thread 同時可能 addDoor；清單只有一兩筆且幾乎不寫 → CopyOnWriteArrayList。
    private List<MapleDoor> doors;
    // 共用：World.handleCooldowns 在 Timer.WORLD 上走訪、MapleMap.addPlayer 與
    // CharacterPacket/PetPacket 在「別人」的 handler thread 上走訪，本人只在自己的執行緒增刪。
    List<MaplePet> pets;
    // 固定長度陣列，元素只是參考覆寫（addClone/disposeClones），沒有結構性改動 → 不需要同步。
    private WeakReference<MapleCharacter>[] clones;
    // 共用：MapleMonster.switchController 由「攻擊者」的 handler thread 摘掉原控制者的怪，
    // MapleMap.updateMonsterController 由 Respawn（Timer.WORLD）指派，MapleMap.removePlayer
    // 還會拿 iterator 邊走邊 remove → 需要 iterator 支援移除的並行 Set。
    private Set<MapleMonster> controlled;
    // 共用，但早就上了讀寫鎖；getAndWriteLockVisibleMapObjects() 是把寫鎖交給呼叫端的設計，
    // 唯一的使用者 MapleMap.movePlayer 以 try/finally 配對 unlockWriteVisibleMapObjects()。
    private Set<MapleMapObject> visibleMapObjects;
    private ReentrantReadWriteLock visibleMapObjectsLock;
    // 共用：怪物死亡時 MapleMonster.killBy 會對「每一個攻擊者」呼叫 mobKilled()，
    // 也就是在擊殺者的 handler thread 上走訪別人的 quests；!saveall 還會在 GM 的執行緒上存檔走訪。
    // 原本是 LinkedHashMap，確認過寫封包與存檔都不依賴插入順序（客戶端與資料庫都以 quest id 為鍵）。
    Map<MapleQuest, MapleQuestStatus> quests;
    // C4 當時標為「不確定，維持原狀」，因為 customData 允許 null 而 ConcurrentHashMap 不收 null。
    // I7 把那個 null 語意查清楚了：**null 不是有意義的值，是一個沒人處理乾淨的洞**。
    // questinfo.customData 的資料庫欄位是 varchar(555) DEFAULT NULL，但全樹沒有任何一條寫入路徑
    // 會放 null 進來（updateInfoQuest 的三十幾個呼叫端一律傳字面字串）；null 只可能從舊資料列
    // 或手動改資料庫來。而讀出來之後的處理是不一致的：QuestInfoPacket 有 `== null ? ""` 的保護，
    // getInfoQuest() 沒有——它會把 null 原封不動回給腳本，而十幾支腳本接著就 .equals("") 或
    // .indexOf(…)，也就是 NPE。所以正確的決定是「在邊界把 null 正規化成空字串」，
    // 而不是讓 null 一路滲進來。正規化之後這個 Map 就可以是 ConcurrentHashMap。
    //
    // 跨執行緒走訪：CharacterRepository.saveToDB（!saveall／自動存檔／unlockAcc 都在別人的執行緒上）。
    // 插入順序不具語意（和 killedMobs 不同）：封包每一筆都自己帶 questid，客戶端以 id 為鍵；
    // 存檔的 INSERT 也有明確的 quest 欄位。所以這裡可以換容器，不必像 C11 那樣改用 monitor。
    Map<Integer, String> questinfo = new ConcurrentHashMap<>();
    // 共用（唯讀方向）：PlayerStats.recalcLocalStats 會在施放隊伍 buff 的那個玩家執行緒上
    // 讀被施放者的 skills，!saveall 也會在別人的執行緒上走訪；寫入只有本人。
    // 換 ConcurrentHashMap 後 get(null) 會丟 NPE，故 getSkillLevel/getMasterLevel 補了 null 判斷，
    // 行為與 LinkedHashMap.get(null) 回 null 完全一致。
    Map<ISkill, SkillEntry> skills = new ConcurrentHashMap<>();
    private Map<MapleBuffStat, MapleBuffStatValueHolder> effects = new ConcurrentEnumMap<>(MapleBuffStat.class);
    // 共用：別人進地圖時在他的 handler thread 上走訪（sendSpawnData）、MapleMap.removePlayer 也走訪，
    // 同時 Timer.BUFF 的 CancelEffectAction → deregisterBuffStats 會移除 → 需要並行 Map。
    private Map<Integer, MapleSummon> summons;
    // 共用：World.handleCooldowns 在 Timer.WORLD 上到期移除，Time Leap 由施放者的執行緒清掉
    // 隊友的冷卻（MapleStatEffect.applyTo），本人的 handler thread 則在放技能時加入。
    private Map<Integer, MapleCoolDownValueHolder> coolDowns = new ConcurrentHashMap<>();
    private Map<MapleDisease, MapleDiseaseValueHolder> diseases = new ConcurrentEnumMap<>(MapleDisease.class);
    CashShop cs;
    // 共用：怪物嘉年華的挑戰是由「挑戰者」的 handler thread 呼叫 owner.addCarnivalRequest()
    // 塞進對方的佇列（Libs/scripts/npc/2042000 等），EventInstanceManager 又會清空 →
    // 改用 ConcurrentLinkedDeque，且 clearCarnivalRequests() 改成 clear() 而不是換一個新物件
    // （換物件會讓正在加入的執行緒寫到被丟掉的舊佇列上）。
    private final Deque<MapleCarnivalChallenge> pendingCarnivalRequests = new ConcurrentLinkedDeque<>();
    private MapleCarnivalParty carnivalParty;
    BuddyList buddylist;
    MonsterBook monsterbook;
    private CheatTracker anticheat;
    MapleClient client;
    PlayerStats stats;
    PlayerRandomStream CRand;
    MapleMap map;
    private MapleShop shop;
    private MapleDragon dragon;
    private RockPaperScissors rps;
    MapleStorage storage;
    private MapleTrade trade;
    MapleMount mount;
    // 只有本人：成就功能整段被註解掉了，現在只剩換頻時 CharacterTransfer 的複製會碰它。
    List<Integer> finishedAchievements = new ArrayList<>();
    private MapleMessenger messenger;
    byte[] petStore;
    private IMaplePlayerShop playerShop;
    MapleParty party;
    private boolean invincible = false, canTalk = true, clone = false, followinitiator = false, followon = false;
    MapleGuildCharacter mgc;
    private MapleFamilyCharacter mfc;
    private EventInstanceManager eventInstance;
    MapleInventory[] inventory;
    SkillMacro[] skillMacros = new SkillMacro[5];
    MapleKeyLayout keylayout;
    private ScheduledFuture<?> beholderHealingSchedule, beholderBuffSchedule, BerserkSchedule,
            dragonBloodSchedule, fairySchedule, mapTimeLimitTask, fishing;
    private long nextConsume = 0, pqStartTime = 0;
    private Event_PyramidSubway pyramidSubway = null;
    // 只有本人：expirationTask() 兩個階段都在登入／換頻那條 handler thread 上跑
    //（CharacterRepository.loadCharFromDB 與 InterServerHandler），沒有第二條路徑。
    private List<Integer> pendingExpiration = null, pendingSkills = null;
    // 共用：addMoveMob() 在「移動怪物的人」的 handler thread 上，會反過來改同地圖每個人的
    // 這張表（chr.getMoveMobs().remove(...)），而且本身是先讀後寫的累加 → 並行 Map + merge。
    private final Map<Integer, Integer> movedMobs = new ConcurrentHashMap<>();
    private String teleportname = "";

    MapleCharacter(final boolean ChannelServer) {
        setStance(0);
        setPosition(new Point(0, 0));

        inventory = new MapleInventory[MapleInventoryType.values().length];
        for (MapleInventoryType type : MapleInventoryType.values()) {
            inventory[type.ordinal()] = new MapleInventory(type);
        }
        quests = new ConcurrentHashMap<>(); // Stupid erev quest.
        stats = new PlayerStats(this);
        for (int i = 0; i < remainingSp.length; i++) {
            remainingSp[i] = 0;
        }
        if (ChannelServer) {
            lastCombo = 0;
            mulung_energy = 0;
            combo = 0;
            keydown_skill = 0;
            smega = true;
            petStore = new byte[3];
            for (int i = 0; i < petStore.length; i++) {
                petStore[i] = (byte) -1;
            }
            wishlist = new int[10];
            rocks = new int[10];
            regrocks = new int[5];
            // 泛型陣列無法直接 new；陣列元素只由本類別以 WeakReference<MapleCharacter> 寫入，故轉型安全
            @SuppressWarnings("unchecked")
            final WeakReference<MapleCharacter>[] clonesArray = (WeakReference<MapleCharacter>[]) new WeakReference<?>[25];
            clones = clonesArray;
            for (int i = 0; i < clones.length; i++) {
                clones[i] = new WeakReference<>(null);
            }
            inst = new AtomicInteger();
            inst.set(0); // 1 = NPC/ Quest, 2 = Duey, 3 = Hired Merch store, 4 = Storage
            keylayout = new MapleKeyLayout();
            doors = new CopyOnWriteArrayList<>();
            controlled = ConcurrentHashMap.newKeySet();
            summons = new ConcurrentHashMap<>();
            visibleMapObjects = new LinkedHashSet<>();
            visibleMapObjectsLock = new ReentrantReadWriteLock();

            savedLocations = new int[SavedLocationType.values().length];
            for (int i = 0; i < SavedLocationType.values().length; i++) {
                savedLocations[i] = -1;
            }
            questinfo = new ConcurrentHashMap<>(); // I7，見欄位宣告處
            anticheat = new CheatTracker(this);
            pets = new CopyOnWriteArrayList<>();
        }
    }

    public static MapleCharacter getDefault(final MapleClient client, final int type) {
        MapleCharacter ret = new MapleCharacter(false);
        ret.client = client;
        ret.map = null;
        ret.exp = 0;
        ret.gmLevel = 0;
        ret.job = (short) (type == 1 ? 0 : (type == 0 ? 1000 : (type == 3 ? 2001 : (type == 4 ? 3000 : 2000))));
        ret.beans = 0;
        ret.meso = 0;
        ret.level = 1;
        ret.remainingAp = 0;
        ret.fame = 0;
        ret.accountid = client.getAccID();
        ret.buddylist = new BuddyList((byte) 20);

        ret.stats.str = 13;
        ret.stats.dex = 4;
        ret.stats.int_ = 4;
        ret.stats.luk = 4;
        ret.stats.maxhp = 50;
        ret.stats.hp = 50;
        ret.stats.maxmp = 50;
        ret.stats.mp = 50;
        ret.prefix = 0;
        ret.gachexp = 0;

        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
            ps.setInt(1, ret.accountid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ret.client.setAccountName(rs.getString("name"));
                    ret.acash = rs.getInt("ACash");
                    ret.maplepoints = rs.getInt("mPoints");
                    ret.points = rs.getInt("points");
                    ret.vpoints = rs.getInt("vpoints");
                }
            }
        } catch (SQLException e) {
            // ERROR: abandoned, so the new character keeps the zeroed account
            // name and currency defaults. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error getting character default" + e);
        }
        return ret;
    }

    public final static MapleCharacter reconstructChr(final CharacterTransfer ct, final MapleClient client, final boolean isChannel) {
        final MapleCharacter ret = new MapleCharacter(true); // Always true, it's change channel
        ret.client = client;
        if (!isChannel) {
            ret.client.setChannel(ct.channel);
        }
        ret.id = ct.characterid;
        ret.name = ct.name;
        ret.level = ct.level;
        ret.fame = ct.fame;

        ret.CRand = new PlayerRandomStream();

        ret.stats.str = ct.str;
        ret.stats.dex = ct.dex;
        ret.stats.int_ = ct.int_;
        ret.stats.luk = ct.luk;
        ret.stats.maxhp = ct.maxhp;
        ret.stats.maxmp = ct.maxmp;
        ret.stats.hp = ct.hp;
        ret.stats.mp = ct.mp;

        ret.chalktext = ct.chalkboard;
        ret.exp = ct.exp;
        ret.hpApUsed = ct.hpApUsed;
        ret.remainingSp = ct.remainingSp;
        ret.remainingAp = ct.remainingAp;
        ret.beans = ct.beans;
        ret.meso = ct.meso;
        ret.gmLevel = ct.gmLevel;
        ret.skinColor = ct.skinColor;
        ret.gender = ct.gender;
        ret.job = ct.job;
        ret.hair = ct.hair;
        ret.face = ct.face;
        ret.accountid = ct.accountid;
        ret.mapid = ct.mapid;
        ret.initialSpawnPoint = ct.initialSpawnPoint;
        ret.world = ct.world;
        ret.bookCover = ct.mBookCover;
        ret.dojo = ct.dojo;
        ret.dojoRecord = ct.dojoRecord;
        ret.guildid = ct.guildid;
        ret.guildrank = ct.guildrank;
        ret.allianceRank = ct.alliancerank;
        ret.points = ct.points;
        ret.vpoints = ct.vpoints;
        ret.fairyExp = ct.fairyExp;
        ret.marriageId = ct.marriageId;
        ret.currentrep = ct.currentrep;
        ret.totalrep = ct.totalrep;
        ret.charmessage = ct.charmessage;
        ret.expression = ct.expression;
        ret.constellation = ct.constellation;
        ret.blood = ct.blood;
        ret.month = ct.month;
        ret.day = ct.day;
        ret.gachexp = ct.gachexp;
        // E10：CharacterTransfer 一直有抄 battleshipHP，只是這裡沒有讀回來。漏掉的
        // 後果不是崩潰而是白吃的補血：Loggedin 稍後用 PlayerBuffStorage 把
        // MONSTER_RIDING 補回去時，registerEffect:832 那個「battleshipHP <= 0 就
        // 填滿」的 quick hack 會命中，於是換一次頻道戰艦耐久就滿血。
        ret.battleshipHP = ct.battleshipHP;
        ret.makeMFC(ct.familyid, ct.seniorid, ct.junior1, ct.junior2);
        if (ret.guildid > 0) {
            ret.mgc = new MapleGuildCharacter(ret);
        }
        ret.buddylist = new BuddyList(ct.buddysize);
        ret.subcategory = ct.subcategory;
        ret.prefix = ct.prefix;

        if (isChannel) {
            final MapleMapFactory mapFactory = ChannelServer.getInstance(client.getChannel()).getMapFactory();
            ret.map = mapFactory.getMap(ret.mapid);
            if (ret.map == null) { //char is on a map that doesn't exist warp it to henesys
                ret.map = mapFactory.getMap(100000000);
            } else {
                if (ret.map.getForcedReturnId() != 999999999) {
                    ret.map = ret.map.getForcedReturnMap();
                }
            }
            MaplePortal portal = ret.map.getPortal(ret.initialSpawnPoint);
            if (portal == null) {
                portal = ret.map.getPortal(0); // char is on a spawnpoint that doesn't exist - select the first spawnpoint instead
                ret.initialSpawnPoint = 0;
            }
            ret.setPosition(portal.getPosition());

            final int messengerid = ct.messengerid;
            if (messengerid > 0) {
                ret.messenger = Messenger.getMessenger(messengerid);
            }
        } else {

            ret.messenger = null;
        }
        int partyid = ct.partyid;
        if (partyid >= 0) {
            MapleParty party = Party.getParty(partyid);
            if (party != null && party.getMemberById(ret.id) != null) {
                ret.party = party;
            }
        }

        MapleQuestStatus queststatus;
        MapleQuestStatus queststatus_from;
        MapleQuest quest;
        for (final Map.Entry<Integer, Object> qs : ct.Quest.entrySet()) {
            quest = MapleQuest.getInstance(qs.getKey());
            queststatus_from = (MapleQuestStatus) qs.getValue();

            queststatus = new MapleQuestStatus(quest, queststatus_from.getStatus());
            queststatus.setForfeited(queststatus_from.getForfeited());
            queststatus.setCustomData(queststatus_from.getCustomData());
            queststatus.setCompletionTime(queststatus_from.getCompletionTime());

            if (queststatus_from.getMobKills() != null) {
                for (final Map.Entry<Integer, Integer> mobkills : queststatus_from.getMobKills().entrySet()) {
                    queststatus.setMobKills(mobkills.getKey(), mobkills.getValue());
                }
            }
            ret.quests.put(quest, queststatus);
        }
        for (final Map.Entry<Integer, SkillEntry> qs : ct.Skills.entrySet()) {
            ret.skills.put(SkillFactory.getSkill(qs.getKey()), qs.getValue());
        }
        for (final Integer zz : ct.finishedAchievements) {
            ret.finishedAchievements.add(zz);
        }
        ret.monsterbook = new MonsterBook(ct.mbook);
        ret.inventory = (MapleInventory[]) ct.inventorys;
        ret.BlessOfFairy_Origin = ct.BlessOfFairy;
        ret.skillMacros = (SkillMacro[]) ct.skillmacro;
        ret.petStore = ct.petStore;
        ret.keylayout = new MapleKeyLayout(ct.keymap);
        ret.questinfo = ct.InfoQuest;
        ret.savedLocations = ct.savedlocation;
        ret.wishlist = ct.wishlist;
        ret.rocks = ct.rocks;
        ret.regrocks = ct.regrocks;
        ret.buddylist.loadFromTransfer(ct.buddies);
        // ret.lastfametime
        // ret.lastmonthfameids
        ret.keydown_skill = 0; // Keydown skill can't be brought over
        ret.lastfametime = ct.lastfametime;
        ret.lastmonthfameids = ct.famedcharacters;
        ret.storage = (MapleStorage) ct.storage;
        ret.cs = (CashShop) ct.cs;
        client.setAccountName(ct.accountname);
        ret.acash = ct.ACash;
        ret.maplepoints = ct.MaplePoints;
        ret.numClones = ct.clonez;
        ret.mount = new MapleMount(ret, ct.mount_itemid, JobConstants.isKOC(ret.job) ? 10001004 : (JobConstants.isAran(ret.job) ? 20001004 : (JobConstants.isEvan(ret.job) ? 20011004 : 1004)), ct.mount_Fatigue, ct.mount_level, ct.mount_exp);

        ret.stats.recalcLocalStats(true);

        return ret;
    }

    // ---------------------------------------------------------------------
    // 持久化：實作已經搬到同 package 的 CharacterRepository（E4 第一刀）。
    // 這裡留下的是原封不動的轉呼叫，讓既有的呼叫端一行都不用改。
    // ---------------------------------------------------------------------

    public static MapleCharacter loadCharFromDB(int charid, MapleClient client, boolean channelserver) {
        return CharacterRepository.loadCharFromDB(charid, client, channelserver);
    }

    public static void saveNewCharToDB(final MapleCharacter chr, final int type, final boolean db) {
        CharacterRepository.saveNewCharToDB(chr, type, db);
    }

    public void saveToDB(boolean dc, boolean fromcs) {
        CharacterRepository.saveToDB(this, dc, fromcs);
    }

    void deleteWhereCharacterId(Connection con, String sql) throws SQLException {
        deleteWhereCharacterId(con, sql, id);
    }

    public static void deleteWhereCharacterId(Connection con, String sql, int id) throws SQLException {
        CharacterRepository.deleteWhereCharacterId(con, sql, id);
    }

    public void saveInventory(final Connection con) throws SQLException {
        CharacterRepository.saveInventory(this, con);
    }

    public final PlayerStats getStat() {
        return stats;
    }

    public final PlayerRandomStream cRand() {
        return CRand;
    }

    public final void questInfoPacket(final tools.data.output.MaplePacketLittleEndianWriter mplew) {
        mplew.writeShort(questinfo.size());

        for (final Entry<Integer, String> q : questinfo.entrySet()) {
            mplew.writeShort(q.getKey());
            mplew.writeMapleAsciiString(q.getValue() == null ? "" : q.getValue());
        }
    }

    public final void updateInfoQuest(final int questid, final String data) {
        // I7：把 null 擋在入口。全樹三十幾個呼叫端目前一律傳字面字串，但這是維持
        // 「questinfo 不含 null」這個不變量的地方——少了它，ConcurrentHashMap 會 NPE。
        final String value = data == null ? "" : data;
        questinfo.put(questid, value);
        client.send(QuestPacket.updateInfoQuest(questid, value));
    }

    public final String getInfoQuest(final int questid) {
        // I7：本來是 containsKey 再 get 的兩段式，而且「有這個 key、值卻是 null」時會
        // 把 null 原封不動回給呼叫端——十幾支腳本接著就 .equals("") 或 .indexOf(…)。
        // 正規化之後不可能有 null，getOrDefault 也順便把兩段式收成一步。
        return questinfo.getOrDefault(questid, "");
    }

    public final int getNumQuest() {
        int i = 0;
        for (final MapleQuestStatus q : quests.values()) {
            if (q.getStatus() == 2 && !(q.isCustom())) {
                i++;
            }
        }
        return i;
    }

    public final byte getQuestStatus(final int quest) {
        return getQuest(MapleQuest.getInstance(quest)).getStatus();
    }

    public final MapleQuestStatus getQuest(final MapleQuest quest) {
        if (!quests.containsKey(quest)) {
            return new MapleQuestStatus(quest, (byte) 0);
        }
        return quests.get(quest);
    }

    public final void setQuestAdd(final MapleQuest quest, final byte status, final String customData) {
        // C4：containsKey + put 併成 computeIfAbsent，一樣是「沒有才建」，
        // 但不會兩條執行緒各建一份、其中一份的進度寫完就被蓋掉。
        quests.computeIfAbsent(quest, k -> {
            final MapleQuestStatus stat = new MapleQuestStatus(k, status);
            stat.setCustomData(customData);
            return stat;
        });
    }

    public final MapleQuestStatus getQuestNAdd(final MapleQuest quest) {
        // C4：原本是 containsKey → put → return，兩條執行緒同時進來會各拿到一份
        // MapleQuestStatus，其中一份的進度寫完就被丟掉。computeIfAbsent 保證只有一份。
        return quests.computeIfAbsent(quest, k -> new MapleQuestStatus(k, (byte) 0));
    }

    public final MapleQuestStatus getQuestNoAdd(final MapleQuest quest) {
        return quests.get(quest);
    }

    public final void updateQuest(final MapleQuestStatus quest) {
        updateQuest(quest, false);
    }

    public final void updateQuest(final MapleQuestStatus quest, final boolean update) {
        quests.put(quest.getQuest(), quest);
        if (!(quest.isCustom())) {
            client.send(QuestPacket.updateQuest(quest));
            if (quest.getStatus() == 1 && !update) {
                client.send(QuestPacket.updateQuestInfo(this, quest.getQuest().getId(), quest.getNpc(), (byte) 8));
            }
        }
    }

    public final Map<Integer, String> getInfoQuest_Map() {
        return questinfo;
    }

    public final Map<MapleQuest, MapleQuestStatus> getQuest_Map() {
        return quests;
    }

    public boolean isActiveBuffedValue(int skillid) {
        LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skillid) {
                return true;
            }
        }
        return false;
    }

    public Integer getBuffedValue(MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        return mbsvh == null ? null : Integer.valueOf(mbsvh.value);
    }

    public final Integer getBuffedSkill_X(final MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        if (mbsvh == null) {
            return null;
        }
        return mbsvh.effect.getX();
    }

    public final Integer getBuffedSkill_Y(final MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        if (mbsvh == null) {
            return null;
        }
        return mbsvh.effect.getY();
    }

    public boolean isBuffFrom(MapleBuffStat stat, ISkill skill) {
        final MapleBuffStatValueHolder mbsvh = effects.get(stat);
        if (mbsvh == null) {
            return false;
        }
        return mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skill.getId();
    }

    public int getBuffSource(MapleBuffStat stat) {
        final MapleBuffStatValueHolder mbsvh = effects.get(stat);
        return mbsvh == null ? -1 : mbsvh.effect.getSourceId();
    }

    public int getItemQuantity(int itemid, boolean checkEquipped) {
        int possesed = inventory[ItemConstants.getInventoryType(itemid).ordinal()].countById(itemid);
        if (checkEquipped) {
            possesed += inventory[MapleInventoryType.EQUIPPED.ordinal()].countById(itemid);
        }
        return possesed;
    }

    public void setBuffedValue(MapleBuffStat effect, int value) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        if (mbsvh == null) {
            return;
        }
        mbsvh.value = value;
    }

    public Long getBuffedStarttime(MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        return mbsvh == null ? null : Long.valueOf(mbsvh.startTime);
    }

    public MapleStatEffect getStatForBuff(MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        return mbsvh == null ? null : mbsvh.effect;
    }

    private void prepareDragonBlood(final MapleStatEffect bloodEffect) {
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
        }
        dragonBloodSchedule = Timer.BUFF.register(() -> {
            if (stats.getHp() - bloodEffect.getX() > 1) {
                cancelBuffStats(MapleBuffStat.DRAGONBLOOD);
            } else {
                addHP(-bloodEffect.getX());
                client.send(CharacterPacket.showOwnBuffEffect(bloodEffect.getSourceId(), 5));
                map.broadcastMessage(MapleCharacter.this, CharacterPacket.showBuffeffect(getId(), bloodEffect.getSourceId(), 5), false);
            }
        }, 4000, 4000);
    }

    public void startMapTimeLimitTask(int time, final MapleMap to) {
        client.send(MapPacket.getClock(time));

        time *= 1000;
        mapTimeLimitTask = Timer.MAP.register(() -> {
            changeMap(to, to.getPortal(0));
        }, time, time);
    }

    public void startFishingTask(final boolean VIP) {
        final int time = GameConstants.getFishingTime(VIP, isGM());
        cancelFishingTask();

        fishing = Timer.ETC.register(() -> {
            final boolean expMulti = haveItem(2300001, 1, false, true);
            if (!expMulti && !haveItem(2300000, 1, false, true)) {
                cancelFishingTask();
                return;
            }
            MapleInventoryManipulator.removeById(client, MapleInventoryType.USE, expMulti ? 2300001 : 2300000, 1, false, false);

            final int randval = RandomRewards.getInstance().getFishingReward();

            switch (randval) {
                case 0: // Meso
                    final int money = Randomizer.rand(expMulti ? 15 : 10, expMulti ? 7500 : 5000);
                    gainMeso(money, true);
                    break;
                case 1: // EXP
                    final int experi = Randomizer.nextInt(Math.abs(GameConstants.getExpNeededForLevel(level) / 400) + 1);
                    gainExp(expMulti ? (experi * 3 / 2) : experi, true, false, true);
                    break;
                default:
                    MapleInventoryManipulator.addById(client, randval, (short) 1);
                    break;
            }
            map.broadcastMessage(UIPacket.fishingCaught(id));
        }, time, time);
    }

    public void cancelMapTimeLimitTask() {
        if (mapTimeLimitTask != null) {
            mapTimeLimitTask.cancel(false);
        }
    }

    public void cancelFishingTask() {
        if (fishing != null) {
            fishing.cancel(false);
        }
    }

    public void registerEffect(MapleStatEffect effect, long starttime, ScheduledFuture<?> schedule) {
        registerEffect(effect, starttime, schedule, effect.getStatups());
    }

    public void registerEffect(MapleStatEffect effect, long starttime, ScheduledFuture<?> schedule, List<Pair<MapleBuffStat, Integer>> statups) {
        if (effect.isHide()) {
            this.hidden = true;
            map.broadcastMessage(this, CharacterPacket.removePlayerFromMap(getId()), false);
        } else if (effect.isDragonBlood()) {
            prepareDragonBlood(effect);
        } else if (effect.isBerserk()) {
            checkBerserk();
        } else if (effect.isMonsterRiding_()) {
            getMount().startSchedule();
        } else if (effect.isBeholder()) {
            prepareBeholderEffect();
        }
        int clonez = 0;
        for (Pair<MapleBuffStat, Integer> statup : statups) {
            if (statup.getLeft() == MapleBuffStat.ILLUSION) {
                clonez = statup.getRight();
            }
            int value = statup.getRight().intValue();
            if (statup.getLeft() == MapleBuffStat.MONSTER_RIDING && effect.getSourceId() == 5221006) {
                if (battleshipHP <= 0) {//quick hack
                    battleshipHP = value; //copy this as well
                }
            }
            effects.put(statup.getLeft(), new MapleBuffStatValueHolder(effect, starttime, schedule, value));
        }
        if (clonez > 0) {
            int cloneSize = Math.max(getNumClones(), getCloneSize());
            if (clonez > cloneSize) { //how many clones to summon
                for (int i = 0; i < clonez - cloneSize; i++) { //1-1=0
                    cloneLook();
                }
            }
        }
        stats.recalcLocalStats();
    }

    public List<MapleBuffStat> getBuffStats(final MapleStatEffect effect, final long startTime) {
        final List<MapleBuffStat> bstats = new ArrayList<>();
        // C12：不能寫 new EnumMap<>(effects)。EnumMap 的複製建構子對「來源也是 EnumMap」
        // 走的是直接複製對方 vals/size 欄位的快速路徑，完全不經過 ConcurrentEnumMap 覆寫的
        // 方法，讀鎖形同不存在，會拿到 size 與陣列對不上的複本。entrySet() 現在本身就是
        // 鎖內做好的不可變快照，直接走訪它即可。
        for (Entry<MapleBuffStat, MapleBuffStatValueHolder> stateffect : effects.entrySet()) {
            final MapleBuffStatValueHolder mbsvh = stateffect.getValue();
            if (mbsvh.effect.sameSource(effect) && (startTime == -1 || startTime == mbsvh.startTime)) {
                bstats.add(stateffect.getKey());
            }
        }
        return bstats;
    }

    private boolean deregisterBuffStats(List<MapleBuffStat> stats) {
        boolean clonez = false;
        List<MapleBuffStatValueHolder> effectsToCancel = new ArrayList<>(stats.size());
        for (MapleBuffStat stat : stats) {
            final MapleBuffStatValueHolder mbsvh = effects.remove(stat);
            if (mbsvh != null) {
                boolean addMbsvh = true;
                for (MapleBuffStatValueHolder contained : effectsToCancel) {
                    if (mbsvh.startTime == contained.startTime && contained.effect == mbsvh.effect) {
                        addMbsvh = false;
                    }
                }
                if (addMbsvh) {
                    effectsToCancel.add(mbsvh);
                }
                if (stat == MapleBuffStat.SUMMON || stat == MapleBuffStat.PUPPET || stat == MapleBuffStat.REAPER) {
                    final int summonId = mbsvh.effect.getSourceId();
                    final MapleSummon summon = summons.get(summonId);
                    if (summon != null) {
                        map.broadcastMessage(MapPacket.removeSummon(summon, true));
                        map.removeMapObject(summon);
                        removeVisibleMapObject(summon);
                        summons.remove(summonId);
                        if (summon.getSkill() == 1321007) {
                            if (beholderHealingSchedule != null) {
                                beholderHealingSchedule.cancel(false);
                                beholderHealingSchedule = null;
                            }
                            if (beholderBuffSchedule != null) {
                                beholderBuffSchedule.cancel(false);
                                beholderBuffSchedule = null;
                            }
                        }
                    }
                } else if (stat == MapleBuffStat.DRAGONBLOOD) {
                    if (dragonBloodSchedule != null) {
                        dragonBloodSchedule.cancel(false);
                        dragonBloodSchedule = null;
                    }
                } else if (stat == MapleBuffStat.ILLUSION) {
                    disposeClones();
                    clonez = true;
                }
            }
        }
        for (MapleBuffStatValueHolder cancelEffectCancelTasks : effectsToCancel) {
            if (getBuffStats(cancelEffectCancelTasks.effect, cancelEffectCancelTasks.startTime).size() == 0) {
                if (cancelEffectCancelTasks.schedule != null) {
                    cancelEffectCancelTasks.schedule.cancel(false);
                }
            }
        }
        return clonez;
    }

    /**
     * @param effect
     * @param overwrite when overwrite is set no data is sent and all the
     * Buffstats in the StatEffect are deregistered
     * @param startTime
     */
    public void cancelEffect(final MapleStatEffect effect, final boolean overwrite, final long startTime) {
        cancelEffect(effect, overwrite, startTime, effect.getStatups());
    }

    public void cancelEffect(final MapleStatEffect effect, final boolean overwrite, final long startTime, List<Pair<MapleBuffStat, Integer>> statups) {
        List<MapleBuffStat> buffstats;
        if (!overwrite) {
            buffstats = getBuffStats(effect, startTime);
        } else {
            buffstats = new ArrayList<>(statups.size());
            for (Pair<MapleBuffStat, Integer> statup : statups) {
                buffstats.add(statup.getLeft());
            }
        }
        if (buffstats.size() <= 0) {
            return;
        }
        final boolean clonez = deregisterBuffStats(buffstats);
        if (effect.isMagicDoor()) {
            // remove for all on maps
            if (!getDoors().isEmpty()) {
                MapleDoor door = getDoors().iterator().next();
                for (MapleCharacter chr : door.getTarget().getCharacters()) {
                    door.sendDestroyData(chr.client);
            }
                for (MapleCharacter chr : door.getTown().getCharacters()) {
                    door.sendDestroyData(chr.client);
                }
            for (MapleDoor destroyDoor : getDoors()) {
                    door.getTarget().removeMapObject(destroyDoor);
                    door.getTown().removeMapObject(destroyDoor);
                }
                 removeDoor();
                silentPartyUpdate();
            }
        } else if (effect.isMonsterRiding_()) {
            getMount().cancelSchedule();
        } else if (effect.isMonsterRiding()) {
            cancelEffectFromBuffStat(MapleBuffStat.MECH_CHANGE);
        } else if (effect.isAranCombo()) {
            combo = 0;
        }
        // check if we are still logged in o.o
        if (!overwrite) {
            cancelPlayerBuffs(buffstats);
            if (effect.isHide() && client.getChannelServer().getPlayerStorage().getCharacterById(this.getId()) != null) { //Wow this is so fking hacky...
                this.hidden = false;
                map.broadcastMessage(this, CharacterPacket.spawnPlayerMapobject(this), false);

                for (final MaplePet pet : pets) {
                    if (pet.getSummoned()) {
                        map.broadcastMessage(this, PetPacket.showPet(this, pet, false, false), false);
                    }
                }
                for (final WeakReference<MapleCharacter> chr : clones) {
                    if (chr.get() != null) {
                        map.broadcastMessage(chr.get(), CharacterPacket.spawnPlayerMapobject(chr.get()), false);
                    }
                }
            }
        }
        if (!clonez) {
            for (WeakReference<MapleCharacter> chr : clones) {
                if (chr.get() != null) {
                    chr.get().cancelEffect(effect, overwrite, startTime);
                }
            }
        }
    }

    public void cancelBuffStats(MapleBuffStat... stat) {
        List<MapleBuffStat> buffStatList = Arrays.asList(stat);
        deregisterBuffStats(buffStatList);
        cancelPlayerBuffs(buffStatList);
    }

    public void cancelEffectFromBuffStat(MapleBuffStat stat) {
        if (effects.get(stat) != null) {
            cancelEffect(effects.get(stat).effect, false, -1);
        }
    }

    private void cancelPlayerBuffs(List<MapleBuffStat> buffstats) {
        boolean write = client.getChannelServer().getPlayerStorage().getCharacterById(getId()) != null;
        if (buffstats.contains(MapleBuffStat.HOMING_BEACON)) {
            if (write) {
                client.send(BuffPacket.cancelHoming());
            }
        } else {
            if (write) {
                stats.recalcLocalStats();
            }
            client.send(BuffPacket.cancelBuff(buffstats));
            map.broadcastMessage(this, BuffPacket.cancelForeignBuff(getId(), buffstats), false);
        }
    }

    public void dispel() {
        if (!isHidden()) {
            final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
            for (MapleBuffStatValueHolder mbsvh : allBuffs) {
                if (mbsvh.effect.isSkill() && mbsvh.schedule != null && !mbsvh.effect.isMorph()) {
                    cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                }
            }
        }
    }

    public void dispelSkill(int skillid) {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (skillid == 0) {
                if (mbsvh.effect.isSkill() && (mbsvh.effect.getSourceId() == 4331003 || mbsvh.effect.getSourceId() == 4331002 || mbsvh.effect.getSourceId() == 4341002 || mbsvh.effect.getSourceId() == 22131001 || mbsvh.effect.getSourceId() == 1321007 || mbsvh.effect.getSourceId() == 2121005 || mbsvh.effect.getSourceId() == 2221005 || mbsvh.effect.getSourceId() == 2311006 || mbsvh.effect.getSourceId() == 2321003 || mbsvh.effect.getSourceId() == 3111002 || mbsvh.effect.getSourceId() == 3111005 || mbsvh.effect.getSourceId() == 3211002 || mbsvh.effect.getSourceId() == 3211005 || mbsvh.effect.getSourceId() == 4111002)) {
                    cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                    break;
                }
            } else {
                if (mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skillid) {
                    cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                    break;
                }
            }
        }
    }

    public void dispelBuff(int skillid) {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.getSourceId() == skillid) {
                cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                break;
            }
        }
    }

    public void cancelAllBuffs_() {
        effects.clear();
        cancelAllTimers();
    }
    
    public void cancelAllTimers() {
        cancelFishingTask();
        cancelMapTimeLimitTask();

        if (fairySchedule != null) {
            fairySchedule.cancel(false);
        }
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
            dragonBloodSchedule = null;
        }
        if (BerserkSchedule != null) {
            BerserkSchedule.cancel(false);
            BerserkSchedule = null;
        }
        if (beholderHealingSchedule != null) {
            beholderHealingSchedule.cancel(false);
            beholderHealingSchedule = null;
        }
        if (beholderBuffSchedule != null) {
            beholderBuffSchedule.cancel(false);
            beholderBuffSchedule = null;
        }
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
            dragonBloodSchedule = null;
        }
    }

    public void cancelAllBuffs() {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            cancelEffect(mbsvh.effect, false, mbsvh.startTime);
        }
    }

    public void cancelMorphs() {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            switch (mbsvh.effect.getSourceId()) {
                case 5101007:
                case 5111005:
                case 5121003:
                case 15111002:
                case 13111005:
                    return; // Since we can't have more than 1, save up on loops
                default:
                    if (mbsvh.effect.isMorph()) {
                        cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                        continue;
                    }
            }
        }
    }

    public int getMorphState() {
        LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.isMorph()) {
                return mbsvh.effect.getSourceId();
            }
        }
        return -1;
    }

    public void silentGiveBuffs(List<PlayerBuffValueHolder> buffs) {
        if (buffs == null) {
            return;
        }
        for (PlayerBuffValueHolder mbsvh : buffs) {
            mbsvh.effect.silentApplyBuff(this, mbsvh.startTime);
        }
    }

    public List<PlayerBuffValueHolder> getAllBuffs() {
        List<PlayerBuffValueHolder> ret = new ArrayList<>();
        LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            ret.add(new PlayerBuffValueHolder(mbsvh.startTime, mbsvh.effect));
        }
        return ret;
    }

    public void cancelMagicDoor() {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.isMagicDoor()) {
                cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                break;
            }
        }
    }

    public int getSkillLevel(int skillid) {
        return getSkillLevel(SkillFactory.getSkill(skillid));
    }

    public final void handleEnergyCharge(final int skillid, final int targets) {
        final ISkill echskill = SkillFactory.getSkill(skillid);
        final byte skilllevel = getSkillLevel(echskill);
        if (skilllevel > 0) {
            final MapleStatEffect echeff = echskill.getEffect(skilllevel);
            if (targets > 0) {
                if (getBuffedValue(MapleBuffStat.ENERGY_CHARGE) == null) {
                    echeff.applyEnergyBuff(this, true); // Infinity time
                } else {
                    Integer energyLevel = getBuffedValue(MapleBuffStat.ENERGY_CHARGE);
                    //TODO: bar going down
                    if (energyLevel < 10000) {
                        energyLevel += (echeff.getX() * targets);

                        client.send(CharacterPacket.showOwnBuffEffect(skillid, 2));
                        map.broadcastMessage(this, CharacterPacket.showBuffeffect(id, skillid, 2), false);

                        if (energyLevel >= 10000) {
                            energyLevel = 10000;
                        }
                        client.send(BuffPacket.giveEnergyChargeTest(energyLevel, echeff.getDuration() / 1000));
                        setBuffedValue(MapleBuffStat.ENERGY_CHARGE, Integer.valueOf(energyLevel));
                    } else if (energyLevel == 10000) {
                        echeff.applyEnergyBuff(this, false); // One with time
                        setBuffedValue(MapleBuffStat.ENERGY_CHARGE, Integer.valueOf(10001));
                    }
                }
            }
        }
    }

    public final void handleBattleshipHP(int damage) {
        if (isActiveBuffedValue(5221006)) {
            battleshipHP -= damage;
            if (battleshipHP <= 0) {
                battleshipHP = 0;
                final MapleStatEffect effect = getStatForBuff(MapleBuffStat.MONSTER_RIDING);
                client.send(CharacterPacket.skillCooldown(5221006, effect.getCooldown()));
                addCooldown(5221006, System.currentTimeMillis(), effect.getCooldown() * 1000);
                dispelSkill(5221006);
            }
        }
    }

    public final void handleOrbgain() {
        int orbcount = getBuffedValue(MapleBuffStat.COMBO);
        ISkill combo;
        ISkill advcombo;

        switch (getJob()) {
            case 1110, 1111, 1112 -> {
                combo = SkillFactory.getSkill(11111001);
                advcombo = SkillFactory.getSkill(11110005);
            }
            default -> {
                combo = SkillFactory.getSkill(1111002);
                advcombo = SkillFactory.getSkill(1120003);
            }
        }

        MapleStatEffect ceffect = null;
        int advComboSkillLevel = getSkillLevel(advcombo);
        if (advComboSkillLevel > 0) {
            ceffect = advcombo.getEffect(advComboSkillLevel);
        } else if (getSkillLevel(combo) > 0) {
            ceffect = combo.getEffect(getSkillLevel(combo));
        } else {
            return;
        }

        if (orbcount < ceffect.getX() + 1) {
            int neworbcount = orbcount + 1;
            if (advComboSkillLevel > 0 && ceffect.makeChanceResult()) {
                if (neworbcount < ceffect.getX() + 1) {
                    neworbcount++;
                }
            }
            List<Pair<MapleBuffStat, Integer>> stat = Collections.singletonList(new Pair<>(MapleBuffStat.COMBO, neworbcount));
            setBuffedValue(MapleBuffStat.COMBO, neworbcount);
            int duration = ceffect.getDuration();
            duration += (int) ((getBuffedStarttime(MapleBuffStat.COMBO) - System.currentTimeMillis()));

            client.send(BuffPacket.giveBuff(combo.getId(), duration, stat, ceffect));
            map.broadcastMessage(this, BuffPacket.giveForeignBuff(getId(), stat, ceffect), false);
        }
    }

    public void handleOrbconsume() {
        ISkill combo;

        switch (getJob()) {
            case 1110, 1111 -> combo = SkillFactory.getSkill(11111001);
            default -> combo = SkillFactory.getSkill(1111002);
        }
        if (getSkillLevel(combo) <= 0) {
            return;
        }
        MapleStatEffect ceffect = getStatForBuff(MapleBuffStat.COMBO);
        if (ceffect == null) {
            return;
        }
        List<Pair<MapleBuffStat, Integer>> stat = Collections.singletonList(new Pair<>(MapleBuffStat.COMBO, 1));
        setBuffedValue(MapleBuffStat.COMBO, 1);
        int duration = ceffect.getDuration();
        duration += (int) ((getBuffedStarttime(MapleBuffStat.COMBO) - System.currentTimeMillis()));

        client.send(BuffPacket.giveBuff(combo.getId(), duration, stat, ceffect));
        map.broadcastMessage(this, BuffPacket.giveForeignBuff(getId(), stat, ceffect), false);
    }

    public void silentEnforceMaxHpMp() {
        stats.setMp(stats.getMp());
        stats.setHp(stats.getHp(), true);
    }

    public void enforceMaxHpMp() {
        List<Pair<MapleStat, Integer>> statups = new ArrayList<>(2);
        if (stats.getMp() > stats.getCurrentMaxMp()) {
            stats.setMp(stats.getMp());
            statups.add(new Pair<>(MapleStat.MP, Integer.valueOf(stats.getMp())));
        }
        if (stats.getHp() > stats.getCurrentMaxHp()) {
            stats.setHp(stats.getHp());
            statups.add(new Pair<>(MapleStat.HP, Integer.valueOf(stats.getHp())));
        }
        if (statups.size() > 0) {
            client.send(CharacterPacket.updatePlayerStats(statups, getJob()));
        }
    }

    public MapleMap getMap() {
        return map;
    }

    public MonsterBook getMonsterBook() {
        return monsterbook;
    }

    public void setMap(MapleMap newmap) {
        this.map = newmap;
    }

    public void setMap(int PmapId) {
        this.mapid = PmapId;
    }

    public int getMapId() {
        if (map != null) {
            return map.getId();
        }
        return mapid;
    }

    public byte getInitialSpawnpoint() {
        return initialSpawnPoint;
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public final String getBlessOfFairyOrigin() {
        return this.BlessOfFairy_Origin;
    }

    public final short getLevel() {
        return level;
    }

    public final short getFame() {
        return fame;
    }

    public final int getDojo() {
        return dojo;
    }

    public final int getDojoRecord() {
        return dojoRecord;
    }

    public final int getFallCounter() {
        return fallcounter;
    }

    public final MapleClient getClient() {
        return client;
    }

    public final void setClient(final MapleClient client) {
        this.client = client;
    }

    public int getExp() {
        synchronized (expLock) {
            return exp;
        }
    }

    public short getRemainingAp() {
        return remainingAp;
    }

    public int getRemainingSp() {
        return remainingSp[JobConstants.getSkillBook(job)]; //default
    }

    public int getRemainingSp(final int skillbook) {
        return remainingSp[skillbook];
    }

    public int[] getRemainingSps() {
        return remainingSp;
    }

    public int getRemainingSpSize() {
        int ret = 0;
        for (int i = 0; i < remainingSp.length; i++) {
            if (remainingSp[i] > 0) {
                ret++;
            }
        }
        return ret;
    }

    public short getHpApUsed() {
        return hpApUsed;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHpApUsed(short hpApUsed) {
        this.hpApUsed = hpApUsed;
    }

    public byte getSkinColor() {
        return skinColor;
    }

    public void setSkinColor(byte skinColor) {
        this.skinColor = skinColor;
    }

    public short getJob() {
        return job;
    }

    public byte getGender() {
        return gender;
    }

    public int getHair() {
        return hair;
    }

    public int getFace() {
        return face;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setExp(int exp) {
        synchronized (expLock) {
            this.exp = exp;
        }
    }

    public void setHair(int hair) {
        this.hair = hair;
    }

    public void setFace(int face) {
        this.face = face;
    }

    public void setFame(short fame) {
        this.fame = fame;
    }

    public void setDojo(final int dojo) {
        this.dojo = dojo;
    }

    public void setDojoRecord(final boolean reset) {
        if (reset) {
            dojo = 0;
            dojoRecord = 0;
        } else {
            dojoRecord++;
        }
    }

    public void setFallCounter(int fallcounter) {
        this.fallcounter = fallcounter;
    }

    public Point getOldPosition() {
        return old;
    }

    public void setOldPosition(Point x) {
        this.old = x;
    }

    public void setRemainingAp(short remainingAp) {
        this.remainingAp = remainingAp;
    }

    public void setRemainingSp(int remainingSp) {
        this.remainingSp[JobConstants.getSkillBook(job)] = remainingSp; //default
    }

    public void setRemainingSp(int remainingSp, final int skillbook) {
        this.remainingSp[skillbook] = remainingSp;
    }

    public void setGender(byte gender) {
        this.gender = gender;
    }

    public void setInvincible(boolean invinc) {
        invincible = invinc;
    }

    public boolean isInvincible() {
        return invincible;
    }

    public CheatTracker getCheatTracker() {
        return anticheat;
    }

    public BuddyList getBuddylist() {
        return buddylist;
    }

    public void addFame(int famechange) {
        this.fame = (short) (this.fame + famechange); // 名聲欄位是 16 bit：PlayersHandler 已把 |fame| 夾在 30000 內，封包也是 writeShort
        /*if (this.fame >= 50) {
         finishAchievement(7);
         }*/
    }

    public void changeMapBanish(final int mapid, final String portal, final String msg) {
        dropMessage(5, msg);
        final MapleMap map = client.getChannelServer().getMapFactory().getMap(mapid);
        changeMap(map, map.getPortal(portal));
    }

    public void changeMap(final MapleMap to, final Point pos) {
        changeMapInternal(to, pos, MapPacket.getWarpToMap(to, 0x81, this), null);
    }

    public void changeMap(final MapleMap to, final MaplePortal pto) {
        changeMapInternal(to, pto.getPosition(), MapPacket.getWarpToMap(to, pto.getId(), this), null);
    }

    public void changeMapPortal(final MapleMap to, final MaplePortal pto) {
        changeMapInternal(to, pto.getPosition(), MapPacket.getWarpToMap(to, pto.getId(), this), pto);
    }

    private void changeMapInternal(final MapleMap to, final Point pos, MaplePacket warpPacket, final MaplePortal pto) {
        if (to == null) {
            return;
        }
        final int nowmapid = map.getId();
        if (eventInstance != null) {
            eventInstance.changedMap(this, to.getId());
        }
        final boolean pyramid = pyramidSubway != null;
        if (map.getId() == nowmapid) {
            client.send(warpPacket);

            map.removePlayer(this);
            if (!isClone() && client.getChannelServer().getPlayerStorage().getCharacterById(getId()) != null) {
                map = to;
                setPosition(pos);
                to.addPlayer(this);
                stats.relocHeal();
            }
        }
        if (pyramid && pyramidSubway != null) { //checks if they had pyramid before AND after changing
            pyramidSubway.onChangeMap(this, to.getId());
        }
    }

    public void leaveMap() {
        // D9：不在這裡清 controlled。控制權是 MapleMap.removePlayer 一隻一隻交還出去的，
        // 而它交還完才呼叫 leaveMap()，所以這裡再清一次只會讓那一步無怪可還。
        //controlled.clear();
        visibleMapObjectsLock.writeLock().lock();
        try {
            visibleMapObjects.clear();
        } finally {
            visibleMapObjectsLock.writeLock().unlock();
        }
        if (chair != 0) {
            cancelFishingTask();
            chair = 0;
        }
        cancelMapTimeLimitTask();
    }

    public void changeJob(int newJob) {
        try {
            final boolean isEv = JobConstants.isEvan(job) || JobConstants.isResist(job);
            this.job = (short) newJob;
            if (newJob != 0 && newJob != 1000 && newJob != 2000 && newJob != 2001 && newJob != 3000) {
                if (isEv) {
                    remainingSp[JobConstants.getSkillBook(newJob)] += 5;
                    client.send(UIPacket.getSPMsg((byte) 5, (short) newJob));
                } else {
                    remainingSp[JobConstants.getSkillBook(newJob)]++;
                    if (newJob % 10 >= 2) {
                        remainingSp[JobConstants.getSkillBook(newJob)] += 2;
                    }
                }
            }
            if (newJob > 0 && !isGM()) {
                resetStatsByJob(true);
                if (!JobConstants.isEvan(newJob)) {
                    if (getLevel() > (newJob == 200 ? 8 : 10) && newJob % 100 == 0 && (newJob % 1000) / 100 > 0) { //first job
                        remainingSp[JobConstants.getSkillBook(newJob)] += 3 * (getLevel() - (newJob == 200 ? 8 : 10));
                    }
                } else if (newJob == 2200) {
                    MapleQuest.getInstance(22100).forceStart(this, 0, null);
                    MapleQuest.getInstance(22100).forceComplete(this, 0);
                    expandInventory((byte) 1, 4);
                    expandInventory((byte) 2, 4);
                    expandInventory((byte) 3, 4);
                    expandInventory((byte) 4, 4);
                    client.send(UIPacket.getEvanTutorial("UI/tutorial/evan/14/0"));
                    dropMessage(5, "The baby Dragon hatched and appears to have something to tell you. Click the baby Dragon to start a conversation.");
                }
            }
            client.send(CharacterPacket.updateSp(this, false, isEv));
            updateSingleStat(MapleStat.JOB, newJob);

            int maxhp = stats.getMaxHp(), maxmp = stats.getMaxMp();

            switch (job) {
                case 100: // Warrior
                case 1100: // Soul Master
                case 2100: // Aran
                case 3200:
                    maxhp += Randomizer.rand(200, 250);
                    break;
                case 200: // Magician
                case 2200: //evan
                case 2210: //evan
                    maxmp += Randomizer.rand(100, 150);
                    break;
                case 300: // Bowman
                case 400: // Thief
                case 500: // Pirate
                case 3300:
                case 3500:
                    maxhp += Randomizer.rand(100, 150);
                    maxmp += Randomizer.rand(25, 50);
                    break;
                case 110: // Fighter
                    maxhp += Randomizer.rand(300, 350);
                    break;
                case 120: // Page
                case 130: // Spearman
                case 510: // 打手
                case 512: // 拳霸
                case 1110: // Soul Master
                case 2110: // Aran
                case 3210:
                    maxhp += Randomizer.rand(300, 350);
                    break;
                case 210: // FP
                case 220: // IL
                case 230: // Cleric
                    maxmp += Randomizer.rand(400, 450);
                    break;
                case 310: // Bowman
                case 312: // Bowman
                case 320: // Crossbowman
                case 322: // Bowman
                case 410: // Assasin
                case 412: // Assasin
                case 420: // Bandit
                case 422: // Assasin
                case 430: // Semi Dualer
                case 520: // 槍手
                case 522: // 槍神
                case 1310: // Wind Breaker
                case 1410: // Night Walker
                case 3310:
                case 3510:
                    maxhp += Randomizer.rand(300, 350);
                    maxhp += Randomizer.rand(150, 200);
                    break;
                case 900: // GM
                case 800: // Manager
                    maxhp += 30000;
                    maxhp += 30000;
                    break;
            }
            if (maxhp >= 30000) {
                maxhp = 30000;
            }
            if (maxmp >= 30000) {
                maxmp = 30000;
            }
            stats.setMaxHp((short) maxhp);
            stats.setMaxMp((short) maxmp);
            stats.setHp((short) maxhp);
            stats.setMp((short) maxmp);
            List<Pair<MapleStat, Integer>> statup = new ArrayList<>(4);
            statup.add(new Pair<>(MapleStat.MAXHP, Integer.valueOf(maxhp)));
            statup.add(new Pair<>(MapleStat.MAXMP, Integer.valueOf(maxmp)));
            statup.add(new Pair<>(MapleStat.HP, Integer.valueOf(maxhp)));
            statup.add(new Pair<>(MapleStat.MP, Integer.valueOf(maxmp)));
            stats.recalcLocalStats();
            client.send(CharacterPacket.updatePlayerStats(statup, getJob()));
            map.broadcastMessage(this, CharacterPacket.showForeignEffect(getId(), 8), false);
            silentPartyUpdate();
            guildUpdate();
            familyUpdate();
            if (dragon != null) {
                map.broadcastMessage(MapPacket.removeDragon(this.id));
                map.removeMapObject(dragon);
                dragon = null;
            }
            baseSkills();
            if (newJob >= 2200 && newJob <= 2218) { //make new
                if (getBuffedValue(MapleBuffStat.MONSTER_RIDING) != null) {
                    cancelBuffStats(MapleBuffStat.MONSTER_RIDING);
                }
                makeDragon();
                map.spawnDragon(dragon);
                map.updateMapObjectVisibility(this, dragon);
            }
        } catch (Exception e) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, e); //all jobs throw errors :(
        }
    }

    public void baseSkills() {
        if (JobConstants.getJobNumber(job) >= 3) { //third job.
            List<Integer> skills = SkillFactory.getSkillsByJob(job);
            if (skills != null) {
                for (int i : skills) {
                    final ISkill skil = SkillFactory.getSkill(i);
                    if (skil != null && !skil.isInvisible() && skil.isFourthJob() && getSkillLevel(skil) <= 0 && getMasterLevel(skil) <= 0 && skil.getMasterLevel() > 0) {
                        changeSkillLevel(skil, (byte) 0, (byte) skil.getMasterLevel()); //usually 10 master
                    }
                }
            }
        }
    }

    public void makeDragon() {
        dragon = new MapleDragon(this);
    }

    public MapleDragon getDragon() {
        return dragon;
    }

    public void gainAp(short ap) {
        this.remainingAp += ap;
        updateSingleStat(MapleStat.AVAILABLEAP, this.remainingAp);
    }

    public void gainSP(int sp) {
        this.remainingSp[JobConstants.getSkillBook(job)] += sp; //default
        client.send(CharacterPacket.updateSp(this, false));
        client.send(UIPacket.getSPMsg((byte) sp, job));
    }

    public void gainSP(int sp, final int skillbook) {
        this.remainingSp[skillbook] += sp; //default
        client.send(CharacterPacket.updateSp(this, false));
        client.send(UIPacket.getSPMsg((byte) sp, job));
    }

    public void resetAPSP() {
        for (int i = 0; i < remainingSp.length; i++) {
            this.remainingSp[i] = 0;
        }
        client.send(CharacterPacket.updateSp(this, false));
        gainAp((short) -this.remainingAp);
    }

    public void changeSkillLevel(final ISkill skill, byte newLevel, byte newMasterlevel) { //1 month
        if (skill == null) {
            return;
        }
        changeSkillLevel(skill, newLevel, newMasterlevel, skill.isTimeLimited() ? (System.currentTimeMillis() + (30L * 24L * 60L * 60L * 1000L)) : -1);
    }

    public void changeSkillLevel(final ISkill skill, byte newLevel, byte newMasterlevel, long expiration) {
        if (skill == null || (!JobConstants.isApplicableSkill(skill.getId()) && !JobConstants.isApplicableSkill_(skill.getId()))) {
            return;
        }
        client.send(CharacterPacket.updateSkill(skill.getId(), newLevel, newMasterlevel, expiration));
        if (newLevel == 0 && newMasterlevel == 0) {
            if (skills.containsKey(skill)) {
                skills.remove(skill);
            } else {
                return; //nothing happen
            }
        } else {
            skills.put(skill, new SkillEntry(newLevel, newMasterlevel, expiration));
        }
        if (JobConstants.isRecoveryIncSkill(skill.getId())) {
            stats.relocHeal();
        } else if (JobConstants.isElementAmp_Skill(skill.getId())) {
            stats.recalcLocalStats();
        }

    }

    public void changeSkillLevel_Skip(final ISkill skill, byte newLevel, byte newMasterlevel) {
        if (skill == null) {
            return;
        }
        client.send(CharacterPacket.updateSkill(skill.getId(), newLevel, newMasterlevel, -1L));
        if (newLevel == 0 && newMasterlevel == 0) {
            if (skills.containsKey(skill)) {
                skills.remove(skill);
            } else {
                return; //nothing happen
            }
        } else {
            skills.put(skill, new SkillEntry(newLevel, newMasterlevel, -1L));
        }

    }

    public void playerDead() {
        final MapleStatEffect statss = getStatForBuff(MapleBuffStat.SOUL_STONE);
        if (statss != null) {
            dropMessage(5, "You have been revived by Soul Stone.");
            getStat().setHp(((getStat().getMaxHp() / 100) * statss.getX()));
            setStance(0);
            changeMap(getMap(), getMap().getPortal(0));
            return;
        }
        if (getEventInstance() != null) {
            getEventInstance().playerKilled(this);
        }
        dispelSkill(0);
        cancelEffectFromBuffStat(MapleBuffStat.MORPH);
        cancelEffectFromBuffStat(MapleBuffStat.MONSTER_RIDING);
        cancelEffectFromBuffStat(MapleBuffStat.SUMMON);
        cancelEffectFromBuffStat(MapleBuffStat.REAPER);
        cancelEffectFromBuffStat(MapleBuffStat.PUPPET);
        checkFollow();
        if (job != 0 && job != 1000 && job != 2000 && job != 2001 && job != 3000) {
            int charms = getItemQuantity(5130000, false);
            if (charms > 0) {
                MapleInventoryManipulator.removeById(client, MapleInventoryType.CASH, 5130000, 1, true, false);

                charms--;
                if (charms > 0xFF) {
                    charms = 0xFF;
                }
                client.send(MTSCSPacket.useCharm((byte) charms, (byte) 0));
            } else {
                float diepercentage = 0.0f;
                int expforlevel = GameConstants.getExpNeededForLevel(level);
                if (map.isTown() || FieldLimitType.RegularExpLoss.check(map.getFieldLimit())) {
                    diepercentage = 0.01f;
                } else {
                    float v8 = 0.0f;
                    if (this.job / 100 == 3) {
                        v8 = 0.08f;
                    } else {
                        v8 = 0.2f;
                    }
                    diepercentage = (float) (v8 / this.stats.getLuk() + 0.05);
                }
                // C10：死亡扣經驗也是「讀 → 算 → 寫」，和擊殺者執行緒的 gainExpMonster
                // 互斥，否則死亡瞬間拿到的那一筆經驗會整筆消失。
                synchronized (expLock) {
                    int v10 = (int) (exp - (long) ((double) expforlevel * diepercentage));
                    if (v10 < 0) {
                        v10 = 0;
                    }
                    this.exp = v10;
                }
            }
        }
        this.updateSingleStat(MapleStat.EXP, getExp());
        if (!stats.checkEquipDurabilitys(this, -100)) { //i guess this is how it works ?
            dropMessage(5, "An item has run out of durability but has no inventory room to go to.");
        } //lol
        if (pyramidSubway != null) {
            stats.setHp((short) 50);
            pyramidSubway.fail(this);
        }
    }

    public void updatePartyMemberHP() {
        if (party != null) {
            final int channel = client.getChannel();
            for (MaplePartyCharacter partychar : party.getMembers()) {
                if (partychar.getMapid() == getMapId() && partychar.getChannel() == channel) {
                    final MapleCharacter other = client.getChannelServer().getPlayerStorage().getCharacterByName(partychar.getName());
                    if (other != null) {
                        other.getClient().send(PartyPacket.updatePartyMemberHP(getId(), stats.getHp(), stats.getCurrentMaxHp()));
                    }
                }
            }
        }
    }

    public void receivePartyMemberHP() {
        if (party == null) {
            return;
        }
        int channel = client.getChannel();
        for (MaplePartyCharacter partychar : party.getMembers()) {
            if (partychar.getMapid() == getMapId() && partychar.getChannel() == channel) {
                MapleCharacter other = client.getChannelServer().getPlayerStorage().getCharacterByName(partychar.getName());
                if (other != null) {
                    client.send(PartyPacket.updatePartyMemberHP(other.getId(), other.getStat().getHp(), other.getStat().getCurrentMaxHp()));
                }
            }
        }
    }

    public void healHP(int delta) {
        addHP(delta);
    }

    public void healMP(int delta) {
        addMP(delta);
    }

    /**
     * Convenience function which adds the supplied parameter to the current hp
     * then directly does a updateSingleStat.
     *
     * @see MapleCharacter#setHp(int)
     * @param delta
     */
    public void addHP(int delta) {
        if (stats.setHp(stats.getHp() + delta)) {
            updateSingleStat(MapleStat.HP, stats.getHp());
        }
    }

    /**
     * Convenience function which adds the supplied parameter to the current mp
     * then directly does a updateSingleStat.
     *
     * @see MapleCharacter#setMp(int)
     * @param delta
     */
    public void addMP(int delta) {
        if (stats.setMp(stats.getMp() + delta)) {
            updateSingleStat(MapleStat.MP, stats.getMp());
        }
    }

    public void addMPHP(int hpDiff, int mpDiff) {
        List<Pair<MapleStat, Integer>> statups = new ArrayList<>();

        if (stats.setHp(stats.getHp() + hpDiff)) {
            statups.add(new Pair<>(MapleStat.HP, Integer.valueOf(stats.getHp())));
        }
        if (stats.setMp(stats.getMp() + mpDiff)) {
            statups.add(new Pair<>(MapleStat.MP, Integer.valueOf(stats.getMp())));
        }
        if (statups.size() > 0) {
            client.send(CharacterPacket.updatePlayerStats(statups, getJob()));
        }
    }

    public void updateSingleStat(MapleStat stat, int newval) {
        updateSingleStat(stat, newval, false);
    }

    /**
     * Updates a single stat of this MapleCharacter for the client. This method
     * only creates and sends an update packet, it does not update the stat
     * stored in this MapleCharacter instance.
     *
     * @param stat
     * @param newval
     * @param itemReaction
     */
    public void updateSingleStat(MapleStat stat, int newval, boolean itemReaction) {
        if (stat == MapleStat.AVAILABLESP) {
            client.send(CharacterPacket.updateSp(this, itemReaction, false));
            return;
        }
        Pair<MapleStat, Integer> statpair = new Pair<>(stat, Integer.valueOf(newval));
        client.send(CharacterPacket.updatePlayerStats(Collections.singletonList(statpair), itemReaction, getJob()));
    }
    public void gainExp(final int total, final boolean show, final boolean inChat, final boolean white ) {
        gainExp(total,show,inChat,white,true);
    }
    
    public void gainExp(final int total, final boolean show, final boolean inChat, final boolean white , final boolean OneLevel) {
        try {
            // C10：經驗值的加總與「這一次是不是該升級」在 expLock 底下一次算完。
            // leveled 的條件和改之前逐字等價（原本是加之前判斷 exp + total >= needed，
            // 這裡是加之後判斷 exp >= needed），只多了 !levellingUp 這一個並行守門。
            // levelUp()（會 broadcast、更新隊伍公會家族）與 familyRep()（會借資料庫連線）
            // 一律在鎖外做。
            final int prevexp;
            int needed;
            final boolean maxLevel;
            final boolean leveled;
            synchronized (expLock) {
                prevexp = exp;
                needed = GameConstants.getExpNeededForLevel(level);
                maxLevel = level >= 200 || (JobConstants.isKOC(job) && level >= 120);
                if (maxLevel) {
                    if (exp + total > needed) {
                        exp = needed;
                    } else {
                        exp += total;
                    }
                    leveled = false;
                } else {
                    exp += total;
                    leveled = exp >= needed && !levellingUp;
                    if (leveled) {
                        levellingUp = true;
                    }
                }
            }
            if (leveled) {
                final boolean overflow;
                try {
                    levelUp();
                } finally {
                    synchronized (expLock) {
                        levellingUp = false;
                    }
                }
                synchronized (expLock) {
                    needed = GameConstants.getExpNeededForLevel(level);
                    overflow = exp > needed;
                    if (overflow && OneLevel) {
                        exp = needed;
                    }
                }
                if (overflow && !OneLevel) {
                    gainExp(0, false, false, false , false);
                }
            }
            if (!maxLevel && total > 0) {
                familyRep(prevexp, needed, leveled);
            }
            if (total != 0) {
                final int cur;
                synchronized (expLock) {
                    if (exp < 0) { // After adding, and negative
                        if (total > 0) {
                            exp = needed;
                        } else if (total < 0) {
                            exp = 0;
                        }
                    }
                    cur = exp;
                }
                updateSingleStat(MapleStat.EXP, cur);
                if (show) { // still show the expgain even if it's not there
                    client.send(CharacterPacket.gainEXP_Others(total, inChat, white));
                }
                if (total > 0) {
                    stats.checkEquipLevels(this, total); //gms like
                }
            }
        } catch (Exception e) {
            FileoutputUtil.outputFileError(FileoutputUtil.ScriptEx_Log, e); //all jobs throw errors :(
        }
    }

    public void familyRep(int prevexp, int needed, boolean leveled) {
        if (mfc != null) {
            int onepercent = needed / 100;
            int percentrep = (prevexp / onepercent + getExp() / onepercent);
            if (leveled) {
                percentrep = 100 - percentrep + (level / 2);
            }
            if (percentrep > 0) {
                int sensen = Family.setRep(mfc.getFamilyId(), mfc.getSeniorId(), percentrep, level);
                if (sensen > 0) {
                    Family.setRep(mfc.getFamilyId(), sensen, percentrep / 2, level); //and we stop here
                }
            }
        }
    }

    public void gainExpMonster(final int gain, final boolean show, final boolean white, final byte pty, int Class_Bonus_EXP, int Equipment_Bonus_EXP, int Premium_Bonus_EXP) {
        int total = gain + Class_Bonus_EXP + Equipment_Bonus_EXP + Premium_Bonus_EXP;
        int partyinc = 0;
        if (pty > 1) {
            partyinc = (int) (((float) (gain / 20.0)) * (pty + 1));
            total += partyinc;
        }

        if (gain > 0 && total < gain) { //just in case
            total = Integer.MAX_VALUE;
        }
        // C10：這裡是掉更新最密集的入口——MapleMonster.killBy 在「擊殺者」的執行緒上對
        // 每一個攻擊者、每一個隊員呼叫這個方法，所以同一隻角色的 exp 真的會同時被好幾條
        // 執行緒加。算術與升級判定在 expLock 底下一次做完；levelUp() 會 broadcast、
        // familyRep() 會借資料庫連線，兩者都留在鎖外。levellingUp 旗標讓同時進來的第二條
        // 執行緒只把經驗值疊上去而不重複升級（見欄位宣告處的說明）。
        final int addend = total;
        final int prevexp;
        int needed;
        final boolean maxLevel;
        final boolean leveled;
        synchronized (expLock) {
            prevexp = exp;
            needed = GameConstants.getExpNeededForLevel(level);
            maxLevel = level >= 200 || (JobConstants.isKOC(job) && level >= 120);
            if (maxLevel) {
                if (exp + addend > needed) {
                    exp = needed;
                } else {
                    exp += addend;
                }
                leveled = false;
            } else {
                exp += addend;
                leveled = exp >= needed && !levellingUp;
                if (leveled) {
                    levellingUp = true;
                }
            }
        }
        if (leveled) {
            try {
                levelUp();
            } finally {
                synchronized (expLock) {
                    levellingUp = false;
                }
            }
            synchronized (expLock) {
                needed = GameConstants.getExpNeededForLevel(level);
                if (exp > needed) {
                    exp = needed;
                }
            }
        }
        if (!maxLevel && total > 0) {
            familyRep(prevexp, needed, leveled);
        }
        if (gain != 0) {
            final int cur;
            synchronized (expLock) {
                if (exp < 0) { // After adding, and negative
                    if (gain > 0) {
                        exp = GameConstants.getExpNeededForLevel(level);
                    } else if (gain < 0) {
                        exp = 0;
                    }
                }
                cur = exp;
            }
            updateSingleStat(MapleStat.EXP, cur);
            if (show) { // still show the expgain even if it's not there
                client.send(CharacterPacket.gainEXP_Monster(gain, white, partyinc, Class_Bonus_EXP, Equipment_Bonus_EXP, Premium_Bonus_EXP));
            }
            stats.checkEquipLevels(this, total);
        }
    }

    public void forceReAddItem_NoUpdate(IItem item, MapleInventoryType type) {
        getInventory(type).removeSlot(item.getPosition());
        getInventory(type).addFromDB(item);
    }

    public void forceReAddItem(IItem item, MapleInventoryType type) { //used for stuff like durability, item exp/level, probably owner?
        forceReAddItem_NoUpdate(item, type);
        if (type != MapleInventoryType.UNDEFINED) {
            client.send(InventoryPacket.updateSpecialItemUse(item, type == MapleInventoryType.EQUIPPED ? (byte) 1 : type.getType()));
        }
    }

    public void forceReAddItem_Flag(IItem item, MapleInventoryType type) { //used for flags
        forceReAddItem_NoUpdate(item, type);
        if (type != MapleInventoryType.UNDEFINED) {
            client.send(InventoryPacket.updateSpecialItemUse_(item, type == MapleInventoryType.EQUIPPED ? (byte) 1 : type.getType()));
        }
    }

    public void silentPartyUpdate() {
        if (party != null) {
            Party.updateParty(party.getId(), PartyOperation.SILENT_UPDATE, new MaplePartyCharacter(this));
        }
    }

    public boolean isGM() {
        return gmLevel > 0;
    }

    public boolean isAdmin() {
        return gmLevel >= 2;
    }

    public int getGMLevel() {
        return gmLevel;
    }
     public boolean isPlayer() {
        return gmLevel == 0;
    }
    public boolean hasGmLevel(int level) {
        return gmLevel >= level;
    }

    public final MapleInventory getInventory(MapleInventoryType type) {
        return inventory[type.ordinal()];
    }

    public final MapleInventory[] getInventorys() {
        return inventory;
    }

    public final void expirationTask() {
        expirationTask(true);
    }

    public final void expirationTask(boolean pending) {
        if (pending) {
            if (pendingExpiration != null) {
                for (Integer z : pendingExpiration) {
                    client.send(MTSCSPacket.itemExpired(z.intValue()));
                }
            }
            pendingExpiration = null;
            if (pendingSkills != null) {
                for (Integer z : pendingSkills) {
                    client.send(CharacterPacket.updateSkill(z, 0, 0, -1));
                    client.send(ChatPacket.serverNotice(5, "[" + SkillFactory.getSkillName(z) + "] skill has expired and will not be available for use."));
                }
            } //not real msg
            pendingSkills = null;
            return;
        }
        long expiration;
        final List<Integer> ret = new ArrayList<>();
        final long currenttime = System.currentTimeMillis();
        final List<Pair<MapleInventoryType, IItem>> toberemove = new ArrayList<>(); // This is here to prevent deadlock.
        final List<IItem> tobeunlock = new ArrayList<>(); // This is here to prevent deadlock.

        for (final MapleInventoryType inv : MapleInventoryType.values()) {
            for (final IItem item : getInventory(inv)) {
                expiration = item.getExpiration();

                if (expiration != -1 && !ItemConstants.isPet(item.getItemId()) && currenttime > expiration) {
                    if (ItemFlag.LOCK.check(item.getFlag())) {
                        tobeunlock.add(item);
                    } else if (currenttime > expiration) {
                        toberemove.add(new Pair<>(inv, item));
                    }
                } else if (item.getItemId() == 5000054 && item.getPet() != null && item.getPet().getSecondsLeft() <= 0) {
                    toberemove.add(new Pair<>(inv, item));
                }
            }
        }
        IItem item;
        for (final Pair<MapleInventoryType, IItem> itemz : toberemove) {
            item = itemz.getRight();
            ret.add(item.getItemId());
            getInventory(itemz.getLeft()).removeItem(item.getPosition(), item.getQuantity(), false);
        }
        for (final IItem itemz : tobeunlock) {
            itemz.setExpiration(-1);
            itemz.setFlag((byte) (itemz.getFlag() - ItemFlag.LOCK.getValue()));
        }
        this.pendingExpiration = ret;

        final List<Integer> skilz = new ArrayList<>();
        final List<ISkill> toberem = new ArrayList<>();
        for (Entry<ISkill, SkillEntry> skil : skills.entrySet()) {
            if (skil.getValue().expiration != -1 && currenttime > skil.getValue().expiration) {
                toberem.add(skil.getKey());
            }
        }
        for (ISkill skil : toberem) {
            skilz.add(skil.getId());
            this.skills.remove(skil);
        }
        this.pendingSkills = skilz;
    }

    public MapleShop getShop() {
        return shop;
    }

    public void setShop(MapleShop shop) {
        this.shop = shop;
    }

    public int getMeso() {
        synchronized (currencyLock) {
            return meso;
        }
    }

    public final int[] getSavedLocations() {
        return savedLocations;
    }

    public int getSavedLocation(SavedLocationType type) {
        return savedLocations[type.getValue()];
    }

    public void saveLocation(SavedLocationType type) {
        savedLocations[type.getValue()] = getMapId();
    }

    public void saveLocation(SavedLocationType type, int mapz) {
        savedLocations[type.getValue()] = mapz;
    }

    public void clearSavedLocation(SavedLocationType type) {
        savedLocations[type.getValue()] = -1;
    }

    public void gainMeso(int gain, boolean show) {
        gainMeso(gain, show, false, false);
    }

    public void gainMeso(int gain, boolean show, boolean enableActions) {
        gainMeso(gain, show, enableActions, false);
    }

    public void gainMeso(int gain, boolean show, boolean enableActions, boolean inChat) {
        // C10：守門與加總必須是一步，否則兩條執行緒可以各自通過 meso + gain < 0 的檢查後
        // 都寫回自己算出來的值（掉更新），或是在溢位邊界上兩邊都以為自己沒有溢位。
        // 送封包在鎖外，而且送的是「這一次呼叫算出來的」值而不是再讀一次欄位。
        final boolean refused;
        final int newMeso;
        synchronized (currencyLock) {
            refused = meso + gain < 0;
            if (!refused) {
                meso += gain;
            }
            newMeso = meso;
        }
        if (refused) {
            client.send(CharacterPacket.enableActions());
            return;
        }
        updateSingleStat(MapleStat.MESO, newMeso, enableActions);
        if (show) {
            client.send(CharacterPacket.showMesoGain(gain, inChat));
        }
    }

    public void controlMonster(MapleMonster monster, boolean aggro) {
        if (clone) {
            return;
        }
        monster.setController(this);
        controlled.add(monster);
        client.send(MobPacket.controlMonster(monster, false, aggro));
    }

    public void stopControllingMonster(MapleMonster monster) {
        if (clone) {
            return;
        }
        // C4：contains + remove 併成一步，理由同 removeCooldown。
        if (monster != null) {
            controlled.remove(monster);
        }
    }

    public void checkMonsterAggro(MapleMonster monster) {
        if (clone || monster == null) {
            return;
        }
        if (monster.getController() == this) {
            monster.setControllerHasAggro(true);
        } else {
            monster.switchController(this, true);
        }
    }

    public Set<MapleMonster> getControlled() {
        return controlled;
    }

    public int getControlledSize() {
        return controlled.size();
    }

    public int getAccountID() {
        return accountid;
    }

    public void mobKilled(final int id, final int skillID) {
        for (MapleQuestStatus q : quests.values()) {
            if (q.getStatus() != 1 || !q.hasMobKills()) {
                continue;
            }
            if (q.mobKilled(id, skillID)) {
                client.send(QuestPacket.updateQuestMobKills(q));
                if (q.getQuest().canComplete(this, null)) {
                    client.send(QuestPacket.getShowQuestCompletion(q.getQuest().getId()));
                }
            }
        }
    }

    public final List<MapleQuestStatus> getStartedQuests() {
        List<MapleQuestStatus> ret = new LinkedList<>();
        for (MapleQuestStatus q : quests.values()) {
            if (q.getStatus() == 1 && !(q.isCustom())) {
                ret.add(q);
            }
        }
        return ret;
    }

    public final List<MapleQuestStatus> getCompletedQuests() {
        List<MapleQuestStatus> ret = new LinkedList<>();
        for (MapleQuestStatus q : quests.values()) {
            if (q.getStatus() == 2 && !(q.isCustom())) {
                ret.add(q);
            }
        }
        return ret;
    }

    public Map<ISkill, SkillEntry> getSkills() {
        return Collections.unmodifiableMap(skills);
    }

    public byte getSkillLevel(final ISkill skill) {
        // C4：skills 換成 ConcurrentHashMap 之後 get(null) 會丟 NPE；
        // SkillFactory.getSkill() 對不存在的技能 id 會回 null，呼叫端不一定先擋。
        // 原本 LinkedHashMap.get(null) 回 null，等同走到下面的 return 0。
        if (skill == null) {
            return 0;
        }
        final SkillEntry ret = skills.get(skill);
        if (ret == null || ret.skillevel <= 0) {
            return 0;
        }
        return (byte) Math.min(skill.getMaxLevel(), ret.skillevel + (skill.isBeginnerSkill() ? 0 : stats.incAllskill));
    }

    public byte getMasterLevel(final int skill) {
        return getMasterLevel(SkillFactory.getSkill(skill));
    }

    public byte getMasterLevel(final ISkill skill) {
        // C4：null 判斷的理由同 getSkillLevel。
        if (skill == null) {
            return 0;
        }
        final SkillEntry ret = skills.get(skill);
        if (ret == null) {
            return 0;
        }
        return ret.masterlevel;
    }

    public void levelUp() {
        if (JobConstants.isKOC(job)) {
            if (level <= 70) {
                remainingAp += 6;
            } else {
                remainingAp += 5;
            }
        } else {
            remainingAp += 5;
        }
        int maxhp = stats.getMaxHp();
        int maxmp = stats.getMaxMp();

        if (job == 0 || job == 1000 || job == 2000 || job == 2001 || job == 3000) { // Beginner
            maxhp += Randomizer.rand(12, 16);
            maxmp += Randomizer.rand(10, 12);
        } else if (job >= 100 && job <= 132) { // Warrior
            final ISkill improvingMaxHP = SkillFactory.getSkill(1000001);
            final int slevel = getSkillLevel(improvingMaxHP);
            if (slevel > 0) {
                maxhp += improvingMaxHP.getEffect(slevel).getX();
            }
            maxhp += Randomizer.rand(24, 28);
            maxmp += Randomizer.rand(4, 6);
        } else if (job >= 200 && job <= 232) { // Magician
            final ISkill improvingMaxMP = SkillFactory.getSkill(2000001);
            final int slevel = getSkillLevel(improvingMaxMP);
            if (slevel > 0) {
                maxmp += improvingMaxMP.getEffect(slevel).getX() * 2;
            }
            maxhp += Randomizer.rand(10, 14);
            maxmp += Randomizer.rand(22, 24);
        } else if (job >= 3200 && job <= 3212) { //battle mages get their own little neat thing
            maxhp += Randomizer.rand(20, 24);
            maxmp += Randomizer.rand(42, 44);
        } else if ((job >= 300 && job <= 322) || (job >= 400 && job <= 434) || (job >= 1300 && job <= 1311) || (job >= 1400 && job <= 1411) || (job >= 3300 && job <= 3312)) { // Bowman, Thief, Wind Breaker and Night Walker
            maxhp += Randomizer.rand(20, 24);
            maxmp += Randomizer.rand(14, 16);
        } else if ((job >= 500 && job <= 522) || (job >= 3500 && job <= 3512)) { // Pirate
            final ISkill improvingMaxHP = SkillFactory.getSkill(5100000);
            final int slevel = getSkillLevel(improvingMaxHP);
            if (slevel > 0) {
                maxhp += improvingMaxHP.getEffect(slevel).getX();
            }
            maxhp += Randomizer.rand(22, 26);
            maxmp += Randomizer.rand(18, 22);
        } else if (job >= 1100 && job <= 1111) { // Soul Master
            final ISkill improvingMaxHP = SkillFactory.getSkill(11000000);
            final int slevel = getSkillLevel(improvingMaxHP);
            if (slevel > 0) {
                maxhp += improvingMaxHP.getEffect(slevel).getX();
            }
            maxhp += Randomizer.rand(24, 28);
            maxmp += Randomizer.rand(4, 6);
        } else if (job >= 1200 && job <= 1211) { // Flame Wizard
            final ISkill improvingMaxMP = SkillFactory.getSkill(12000000);
            final int slevel = getSkillLevel(improvingMaxMP);
            if (slevel > 0) {
                maxmp += improvingMaxMP.getEffect(slevel).getX() * 2;
            }
            maxhp += Randomizer.rand(10, 14);
            maxmp += Randomizer.rand(22, 24);
        } else if (job >= 1500 && job <= 1512) { // Pirate
            final ISkill improvingMaxHP = SkillFactory.getSkill(15100000);
            final int slevel = getSkillLevel(improvingMaxHP);
            if (slevel > 0) {
                maxhp += improvingMaxHP.getEffect(slevel).getX();
            }
            maxhp += Randomizer.rand(22, 26);
            maxmp += Randomizer.rand(18, 22);
        } else if (job >= 2100 && job <= 2112) { // Aran
            maxhp += Randomizer.rand(50, 52);
            maxmp += Randomizer.rand(4, 6);
        } else if (job >= 2200 && job <= 2218) { // Evan
            maxhp += Randomizer.rand(12, 16);
            maxmp += Randomizer.rand(50, 52);
        } else { // GameMaster
            maxhp += Randomizer.rand(50, 100);
            maxmp += Randomizer.rand(50, 100);
        }
        maxmp += stats.getTotalInt() / 10;
        // C10：扣掉這一級的經驗值再進位，必須和 gainExp/gainExpMonster 的加總互斥，
        // 否則同時進來的那一筆經驗會被算在錯的級距上。底下的 broadcast 與公會／家族更新
        // 一律在鎖外。
        synchronized (expLock) {
            exp -= GameConstants.getExpNeededForLevel(level);
            level += 1;
        }
        int level = getLevel();

        // 成就系統
        /*if (level == 30) {
         finishAchievement(2);
         }
         if (level == 70) {
         finishAchievement(3);
         }
         if (level == 120) {
         finishAchievement(4);
         }
         if (level == 200) {
         finishAchievement(5);
         }*/
        if (level == 200 && !isGM()) {
            final StringBuilder sb = new StringBuilder("[恭喜] ");
            final IItem medal = getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -21);
            if (medal != null) { // Medal
                sb.append("<");
                sb.append(MapleItemInformationProvider.getInstance().getName(medal.getItemId()));
                sb.append("> ");
            }
            sb.append(getName());
            sb.append(" 達到了 200 等 是我們的英雄!");
            Broadcast.broadcastMessage(ChatPacket.serverNotice(6, sb.toString()).getBytes());
        }
        maxhp = (short) Math.min(30000, Math.abs(maxhp));
        maxmp = (short) Math.min(30000, Math.abs(maxmp));

        final List<Pair<MapleStat, Integer>> statup = new ArrayList<>(8);

        statup.add(new Pair<>(MapleStat.MAXHP, maxhp));
        statup.add(new Pair<>(MapleStat.MAXMP, maxmp));
        statup.add(new Pair<>(MapleStat.HP, maxhp));
        statup.add(new Pair<>(MapleStat.MP, maxmp));
        statup.add(new Pair<>(MapleStat.EXP, exp));
        statup.add(new Pair<>(MapleStat.LEVEL, level));

        if (isGM() || (job != 0 && job != 1000 && job != 2000 && job != 2001 && job != 3000)) { // Not Beginner, Nobless and Legend
            remainingSp[JobConstants.getSkillBook(this.job)] += 3;
            client.send(CharacterPacket.updateSp(this, false));
        } else {
            if (level <= 10) {
                stats.setStr((short) (stats.getStr() + remainingAp));
                remainingAp = 0;

                statup.add(new Pair<>(MapleStat.STR, (int) stats.getStr()));
            }
        }

        statup.add(new Pair<>(MapleStat.AVAILABLEAP, (int) remainingAp));

        stats.setMaxHp((short) maxhp);
        stats.setMaxMp((short) maxmp);
        stats.setHp((short) maxhp);
        stats.setMp((short) maxmp);
        client.send(CharacterPacket.updatePlayerStats(statup, getJob()));
        map.broadcastMessage(this, CharacterPacket.showForeignEffect(getId(), 0), false);
        stats.recalcLocalStats();
        silentPartyUpdate();
        guildUpdate();
        familyUpdate();
        if (JobConstants.isAran(job)) {
            switch (level) {
                case 30 ->
                    client.send(MapPacket.startMapEffect("You have reached level 30! To job advance, go back to Lirin of Rien.", 5120000, true));
                case 70 ->
                    client.send(MapPacket.startMapEffect("You have reached level 70! To job advance, talk to your job instructor in El Nath.", 5120000, true));
                case 120 ->
                    client.send(MapPacket.startMapEffect("You have reached level 120! To job advance, talk to your job instructor in Leafre.", 5120000, true));
            }
        }
        if (JobConstants.isKOC(job) && level == 70) {
            client.send(MapPacket.startMapEffect("You have reached level 70! To job advance, talk to your job instructor in Erev.", 5120000, true));
        }
        if (JobConstants.isEvan(job)) {
            switch (level) {
                case 9 ->
                    client.send(MapPacket.startMapEffect("請確保您完成所有的任務需要達到10級之前，否則你將無法繼續.", 5120000, true));
                case 10, 20, 30, 40, 50, 60, 80, 100, 120, 160 -> {
                    if (job < 2218) {
                        changeJob(job == 2001 ? 2200 : (job == 2200 ? 2210 : (job + 1))); //automatic
                    }
                }
            }
        }
    }

    public void changeKeybinding(int key, byte type, int action) {
        if (type != 0) {
            keylayout.layout().put(Integer.valueOf(key), new Pair<>(type, action));
        } else {
            keylayout.layout().remove(Integer.valueOf(key));
        }
    }

    public void sendMacros() {
        for (int i = 0; i < 5; i++) {
            if (skillMacros[i] != null) {
                client.send(CharacterPacket.getMacros(skillMacros));
                break;
            }
        }
    }

    public void updateMacros(int position, SkillMacro updateMacro) {
        skillMacros[position] = updateMacro;
    }

    public final SkillMacro[] getMacros() {
        return skillMacros;
    }

    /**
     * @param duration the instant the ban lifts; written straight into
     * {@code accounts.tempban}, which {@code MapleClient} reads back at login.
     */
    public void tempban(String reason, Instant duration, int greason, boolean IPMac) {
        if (IPMac) {
            client.banMacs();
        }

        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("INSERT INTO ipbans VALUES (DEFAULT, ?)")) {
                ps.setString(1, client.getSession().getRemoteAddress().toString().split(":")[0]);
                ps.execute();
            }

            client.getSession().close();

            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET tempban = ?, banreason = ?, greason = ? WHERE id = ?")) {
                // Millisecond resolution on purpose: the column has no
                // fractional-seconds precision, and Timestamp.from(Instant)
                // would hand the driver nanoseconds it has to round away.
                Timestamp TS = new Timestamp(duration.toEpochMilli());
                ps.setTimestamp(1, TS);
                ps.setString(2, reason);
                ps.setInt(3, greason);
                ps.setInt(4, accountid);
                ps.execute();
            }
        } catch (SQLException ex) {
            // ERROR: the temp ban may be only half-written - the session is
            // already closed above but the tempban columns can be untouched, so
            // the account comes straight back. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while tempbanning" + ex);
        }

    }

    public final boolean ban(String reason, boolean IPMac, boolean autoban, boolean hellban) {
        if (lastmonthfameids == null) {
            throw new RuntimeException("Trying to ban a non-loaded character (testhack)");
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("UPDATE accounts SET banned = ?, banreason = ? WHERE id = ?")) {
                ps.setInt(1, autoban ? 2 : 1);
                ps.setString(2, reason);
                ps.setInt(3, accountid);
                ps.execute();
            }

            if (IPMac) {
                client.banMacs();
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO ipbans VALUES (DEFAULT, ?)")) {
                    ps.setString(1, client.getSessionIPAddress());
                    ps.execute();
                }

                if (hellban) {
                    try (PreparedStatement psa = con.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
                        psa.setInt(1, accountid);
                        try (ResultSet rsa = psa.executeQuery()) {
                            if (rsa.next()) {
                                try (PreparedStatement pss = con.prepareStatement("UPDATE accounts SET banned = ?, banreason = ? WHERE email = ? OR SessionIP = ?")) {
                                    pss.setInt(1, autoban ? 2 : 1);
                                    pss.setString(2, reason);
                                    pss.setString(3, rsa.getString("email"));
                                    pss.setString(4, client.getSessionIPAddress());
                                    pss.execute();
                                }
                            }
                        }
                    }
                }
            }
        } catch (SQLException ex) {
            // ERROR: the ban is abandoned, false goes back to the caller and the
            // session below is never closed. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while banning" + ex);
            return false;
        }
        client.getSession().close();
        return true;
    }

    public static boolean ban(String id, String reason, boolean accountId, int gmlevel, boolean hellban) {
        try (Connection con = DatabaseConnection.getConnection()) {
            if (id.matches("/[0-9]{1,3}\\..*")) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO ipbans VALUES (DEFAULT, ?)")) {
                    ps.setString(1, id);
                    ps.execute();
                    return true;
                }
            }
            final PreparedStatement ps = accountId
                    ? con.prepareStatement("SELECT id FROM accounts WHERE name = ?")
                    : con.prepareStatement("SELECT accountid FROM characters WHERE name = ?");
            try (ps) {
                boolean ret = false;
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int z = rs.getInt(1);
                        try (PreparedStatement psb = con.prepareStatement("UPDATE accounts SET banned = 1, banreason = ? WHERE id = ? AND gm < ?")) {
                            psb.setString(1, reason);
                            psb.setInt(2, z);
                            psb.setInt(3, gmlevel);
                            psb.execute();
                        }

                        if (gmlevel > 100) { //admin ban
                            try (PreparedStatement psa = con.prepareStatement("SELECT * FROM accounts WHERE id = ?")) {
                                psa.setInt(1, z);
                                try (ResultSet rsa = psa.executeQuery()) {
                                    if (rsa.next()) {
                                        String sessionIP = rsa.getString("sessionIP");
                                        if (sessionIP != null && sessionIP.matches("/[0-9]{1,3}\\..*")) {
                                            try (PreparedStatement psz = con.prepareStatement("INSERT INTO ipbans VALUES (DEFAULT, ?)")) {
                                                psz.setString(1, sessionIP);
                                                psz.execute();
                                            }
                                        }
                                        if (rsa.getString("macs") != null) {
                                            String[] macData = rsa.getString("macs").split(", ");
                                            if (macData.length > 0) {
                                                MapleClient.banMacs(macData);
                                            }
                                        }
                                        if (hellban) {
                                            try (PreparedStatement pss = con.prepareStatement("UPDATE accounts SET banned = 1, banreason = ? WHERE email = ?" + (sessionIP == null ? "" : " OR SessionIP = ?"))) {
                                                pss.setString(1, reason);
                                                pss.setString(2, rsa.getString("email"));
                                                if (sessionIP != null) {
                                                    pss.setString(3, sessionIP);
                                                }
                                                pss.execute();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        ret = true;
                    }
                }
                return ret;
            }
        } catch (SQLException ex) {
            // ERROR: the ban is abandoned and false goes back to the caller.
            // Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while banning" + ex);
        }
        return false;
    }

    /**
     * Oid of players is always = the cid
     */
    @Override
    public int getObjectId() {
        return getId();
    }

    /**
     * Throws unsupported operation exception, oid of players is read only
     */
    @Override
    public void setObjectId(int id) {
        throw new UnsupportedOperationException();
    }

    public MapleStorage getStorage() {
        return storage;
    }

    public void addVisibleMapObject(MapleMapObject mo) {
        if (clone) {
            return;
        }
        visibleMapObjectsLock.writeLock().lock();
        try {
            visibleMapObjects.add(mo);
        } finally {
            visibleMapObjectsLock.writeLock().unlock();
        }
    }

    public void removeVisibleMapObject(MapleMapObject mo) {
        if (clone) {
            return;
        }
        visibleMapObjectsLock.writeLock().lock();
        try {
            visibleMapObjects.remove(mo);
        } finally {
            visibleMapObjectsLock.writeLock().unlock();
        }
    }

    public boolean isMapObjectVisible(MapleMapObject mo) {
        visibleMapObjectsLock.readLock().lock();
        try {
            return !clone && visibleMapObjects.contains(mo);
        } finally {
            visibleMapObjectsLock.readLock().unlock();
        }
    }

    public Collection<MapleMapObject> getAndWriteLockVisibleMapObjects() {
        visibleMapObjectsLock.writeLock().lock();
        return visibleMapObjects;
    }

    public void unlockWriteVisibleMapObjects() {
        visibleMapObjectsLock.writeLock().unlock();
    }

    public boolean isAlive() {
        return stats.getHp() > 0;
    }

    @Override
    public void sendDestroyData(MapleClient client) {
        client.send(CharacterPacket.removePlayerFromMap(this.getObjectId()));
        for (final WeakReference<MapleCharacter> chr : clones) {
            if (chr.get() != null) {
                chr.get().sendDestroyData(client);
            }
        }
    }

    @Override
    public void sendSpawnData(MapleClient client) {
        if (client.getPlayer().allowedToTarget(this)) {
            client.send(CharacterPacket.spawnPlayerMapobject(this));

            for (final MaplePet pet : pets) {
                if (pet.getSummoned()) {
                    client.send(PetPacket.showPet(this, pet, false, false));
                    client.send(PetPacket.petComplete(this, pet));
                }
            }
            for (final WeakReference<MapleCharacter> chr : clones) {
                if (chr.get() != null) {
                    chr.get().sendSpawnData(client);
                }
            }
            if (dragon != null) {
                client.send(MapPacket.spawnDragon(dragon));
            }
            if (summons != null) {
                for (final MapleSummon summon : summons.values()) {
                    client.send(MapPacket.spawnSummon(summon, false));
                }
            }
            if (followid > 0) {
                client.send(CharacterPacket.followEffect(followinitiator ? id : followid, followinitiator ? followid : id, null));
            }
        }
    }

    public final void equipChanged() {
        map.broadcastMessage(this, CharacterPacket.updateCharLook(this), false);
        stats.recalcLocalStats();
        if (getMessenger() != null) {
            Messenger.updateMessenger(getMessenger().getId(), getName(), client.getChannel());
        }
    }

    public final MaplePet getPet(final int index) {
        byte count = 0;
        for (final MaplePet pet : pets) {
            if (pet.getSummoned()) {
                if (count == index) {
                    return pet;
                }
                count++;
            }
        }
        return null;
    }

    public void removePetCS(MaplePet pet) {
        pets.remove(pet);
    }

    public void addPet(final MaplePet pet) {
        if (pets.contains(pet)) {
            pets.remove(pet);
        }
        pets.add(pet);
        // So that the pet will be at the last
        // Pet index logic :(
    }

    public void removePet(MaplePet pet, boolean shiftLeft) {
        pet.setSummoned(0);
        /*	int slot = -1;
         for (int i = 0; i < 3; i++) {
         if (pets[i] != null) {
         if (pets[i].getUniqueId() == pet.getUniqueId()) {
         pets[i] = null;
         slot = i;
         break;
         }
         }
         }
         if (shiftLeft) {
         if (slot > -1) {
         for (int i = slot; i < 3; i++) {
         if (i != 2) {
         pets[i] = pets[i + 1];
         } else {
         pets[i] = null;
         }
         }
         }
         }*/
    }

    public final byte getPetIndex(final MaplePet petz) {
        byte count = 0;
        for (final MaplePet pet : pets) {
            if (pet.getSummoned()) {
                if (pet == petz) {
                    return count;
                }
                count++;
            }
        }
        return -1;
    }

    public final byte getPetIndex(final int petId) {
        byte count = 0;
        for (final MaplePet pet : pets) {
            if (pet.getSummoned()) {
                if (pet.getUniqueId() == petId) {
                    return count;
                }
                count++;
            }
        }
        return -1;
    }

    public final byte getPetById(final int petId) {
        byte count = 0;
        for (final MaplePet pet : pets) {
            if (pet.getSummoned()) {
                if (pet.getPetItemId() == petId) {
                    return count;
                }
                count++;
            }
        }
        return -1;
    }

    public final List<MaplePet> getPets() {
        return pets;
    }

    public final void unequipAllPets() {
        for (final MaplePet pet : pets) {
            if (pet != null) {
                unequipPet(pet, true, false);
            }
        }
    }

    public void unequipPet(MaplePet pet, boolean shiftLeft, boolean hunger) {
        if (pet.getSummoned()) {
            pet.saveToDb();
            map.broadcastMessage(this, PetPacket.showPet(this, pet, true, hunger), true);
            removePet(pet, shiftLeft);
            client.send(PetPacket.petStatUpdate(this));
            client.send(CharacterPacket.enableActions());
        }
    }

    public final long getLastFameTime() {
        return lastfametime;
    }

    public final List<Integer> getFamedCharacters() {
        return lastmonthfameids;
    }

    /**
     * E10：成就列表。遊戲端拿成就的整套 API（setAchievementFinished /
     * finishAchievement / getFinishedAchievements）在本樹裡是整段註解掉的，但
     * 這個欄位本身並不是死的——CharacterRepository.loadCharFromDB 會照 accountid
     * 把 achievements 整個帳號載進來，saveToDB 又會「先 DELETE 整個帳號再把這份
     * 清單寫回去」。所以它必須跟著換頻走，否則換頻之後的第一次存檔會把整個帳號
     * 的成就清空。這個 getter 是為了 CharacterTransfer（在 handling.world，碰不到
     * package-private 欄位）而留的。
     */
    public final List<Integer> getFinishedAchievements() {
        return finishedAchievements;
    }

    public FameStatus canGiveFame(MapleCharacter from) {
        if (lastfametime >= System.currentTimeMillis() - 60 * 60 * 24 * 1000) {
            return FameStatus.NOT_TODAY;
        } else if (from == null || lastmonthfameids == null || lastmonthfameids.contains(Integer.valueOf(from.getId()))) {
            return FameStatus.NOT_THIS_MONTH;
        }
        return FameStatus.OK;
    }

    public void hasGivenFame(MapleCharacter to) {
        lastfametime = System.currentTimeMillis();
        lastmonthfameids.add(Integer.valueOf(to.getId()));
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO famelog (characterid, characterid_to) VALUES (?, ?)")) {
            ps.setInt(1, getId());
            ps.setInt(2, to.getId());
            ps.execute();
        } catch (SQLException e) {
            // ERROR: lastfametime and lastmonthfameids were already updated in
            // memory above, so the famelog row is simply missing. Terse.
            LOG.log(System.Logger.Level.ERROR, "ERROR writing famelog for char " + getName() + " to " + to.getName() + e);
        }
    }

    public final MapleKeyLayout getKeyLayout() {
        return this.keylayout;
    }

    public MapleParty getParty() {
        return party;
    }

    public int getPartyId() {
        return (party != null ? party.getId() : -1);
    }

    public byte getWorld() {
        return world;
    }

    public void setWorld(byte world) {
        this.world = world;
    }

    public void setParty(MapleParty party) {
        this.party = party;
    }

    public MapleTrade getTrade() {
        return trade;
    }

    public void setTrade(MapleTrade trade) {
        this.trade = trade;
    }

    public EventInstanceManager getEventInstance() {
        return eventInstance;
    }

    public void setEventInstance(EventInstanceManager eventInstance) {
        this.eventInstance = eventInstance;
    }

    public void addDoor(MapleDoor door) {
        doors.add(door);
    }

    public void clearDoors() {
        doors.clear();
    }

    public List<MapleDoor> getDoors() {
        return new ArrayList<>(doors);
    }

    public void setSmega() {
        if (smega) {
            smega = false;
            dropMessage(5, "You have set megaphone to disabled mode");
        } else {
            smega = true;
            dropMessage(5, "You have set megaphone to enabled mode");
        }
    }

    public boolean getSmega() {
        return smega;
    }

    public Map<Integer, MapleSummon> getSummons() {
        return summons;
    }

    public int getChair() {
        return chair;
    }

    public int getItemEffect() {
        return itemEffect;
    }

    public void setChair(int chair) {
        this.chair = chair;
        stats.relocHeal();
    }

    public void setItemEffect(int itemEffect) {
        this.itemEffect = itemEffect;
    }

    @Override
    public MapleMapObjectType getType() {
        return MapleMapObjectType.PLAYER;
    }

    public int getFamilyId() {
        if (mfc == null) {
            return 0;
        }
        return mfc.getFamilyId();
    }

    public int getSeniorId() {
        if (mfc == null) {
            return 0;
        }
        return mfc.getSeniorId();
    }

    public int getJunior1() {
        if (mfc == null) {
            return 0;
        }
        return mfc.getJunior1();
    }

    public int getJunior2() {
        if (mfc == null) {
            return 0;
        }
        return mfc.getJunior2();
    }

    public int getCurrentRep() {
        return currentrep;
    }

    public int getTotalRep() {
        return totalrep;
    }

    public void setCurrentRep(int _rank) {
        currentrep = _rank;
        if (mfc != null) {
            mfc.setCurrentRep(_rank);
        }
    }

    public void setTotalRep(int _rank) {
        totalrep = _rank;
        if (mfc != null) {
            mfc.setTotalRep(_rank);
        }
    }

    public int getGuildId() {
        return guildid;
    }

    public byte getGuildRank() {
        return guildrank;
    }

    public void setGuildId(int _id) {
        guildid = _id;
        if (guildid > 0) {
            if (mgc == null) {
                mgc = new MapleGuildCharacter(this);

            } else {
                mgc.setGuildId(guildid);
            }
        } else {
            mgc = null;
        }
    }

    public void setGuildRank(byte _rank) {
        guildrank = _rank;
        if (mgc != null) {
            mgc.setGuildRank(_rank);
        }
    }

    public MapleGuildCharacter getMGC() {
        return mgc;
    }

    public void setAllianceRank(byte rank) {
        allianceRank = rank;
        if (mgc != null) {
            mgc.setAllianceRank(rank);
        }
    }

    public byte getAllianceRank() {
        return allianceRank;
    }

    public MapleGuild getGuild() {
        if (getGuildId() <= 0) {
            return null;
        }
        return Guild.getGuild(getGuildId());
    }

    public void guildUpdate() {
        if (guildid <= 0) {
            return;
        }
        mgc.setLevel(level);
        mgc.setJobId(job);
        Guild.memberLevelJobUpdate(mgc);
    }

    public void saveGuildStatus() {
        MapleGuild.setOfflineGuildStatus(guildid, guildrank, allianceRank, id);
    }

    public void familyUpdate() {
        if (mfc == null) {
            return;
        }
        Family.memberFamilyUpdate(mfc, this);
    }

    public void saveFamilyStatus() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE characters SET familyid = ?, seniorid = ?, junior1 = ?, junior2 = ? WHERE id = ?")) {
            if (mfc == null) {
                ps.setInt(1, 0);
                ps.setInt(2, 0);
                ps.setInt(3, 0);
                ps.setInt(4, 0);
            } else {
                ps.setInt(1, mfc.getFamilyId());
                ps.setInt(2, mfc.getSeniorId());
                ps.setInt(3, mfc.getJunior1());
                ps.setInt(4, mfc.getJunior2());
            }
            ps.setInt(5, id);
            ps.execute();
        } catch (SQLException se) {
            // One record, not two: the message and the trace were always the
            // same event. ERROR rather than the INFO the stream would suggest -
            // the family columns were not written.
            LOG.log(System.Logger.Level.ERROR, "SQLException: " + se.getLocalizedMessage(), se);
        }
    }

    public void modifyCSPoints(int type, int quantity) {
        modifyCSPoints(type, quantity, false);
    }

    public void dropMessage(String message) {
        dropMessage(6, message);
    }

    public void modifyCSPoints(int type, int quantity, boolean show) {
        // C10：上限守門與加總必須是一步。商城的 handler thread（MTSCSPacket / CashShopOperation）
        // 與面板的 Panel-Worker-N（AccountsApi 改帳號貨幣）會同時進來，中間夾一個「讀 → 判斷 →
        // 寫」就會掉更新。dropMessage 會送封包，所以拒絕訊息也搬到鎖外送。
        final String refusal;
        synchronized (currencyLock) {
            refusal = switch (type) {
                case 1 -> {
                    if (acash + quantity < 0) {
                        yield "You have gained the max cash. No cash will be awarded.";
                    }
                    acash += quantity;
                    yield null;
                }
                case 2 -> {
                    if (maplepoints + quantity < 0) {
                        yield "You have gained the max maple points. No cash will be awarded.";
                    }
                    maplepoints += quantity;
                    yield null;
                }
                default -> null;
            };
        }
        if (refusal != null) {
            if (show) {
                dropMessage(-1, refusal);
            }
            return;
        }
        if (show && quantity != 0) {
            dropMessage(-1, "你" + (quantity > 0 ? "獲得了 " : "失去 ") + quantity + (type == 1 ? " CASH" : " 楓葉點數"));
            // D9：點數增減時的特效，刻意只留上面那行文字訊息。showSpecialEffect 仍在，解開即可用。
            //client.send(CharacterPacket.showSpecialEffect(19));
        }
    }

    public int getCSPoints(int type) {
        synchronized (currencyLock) {
            return switch (type) {
                case 1 -> acash;
                case 2 -> maplepoints;
                default -> 0;
            };
        }
    }

    /**
     * C10：面板要的是「設成這個值」，不是「加這個差額」。
     *
     * <p>{@code AccountsApi} 本來寫的是
     * {@code chr.modifyCSPoints(1, 新值 - chr.getCSPoints(1))}——讀一次、算差額、再寫一次，
     * 中間玩家在商城買一件東西，差額就是照著過期的值算出來的，資料庫那一列和線上角色物件
     * 會停在兩個不同的數字上。面板的語意本來就是絕對值（它在這之前已經把同一個值 UPDATE
     * 進 {@code accounts} 了），所以這裡直接指派，讓兩邊由建構方式保證一致。
     *
     * <p>不做 {@code modifyCSPoints} 的上限守門：那個守門是給「加值」用的溢位保護，
     * 而這裡的值來自面板、已經是要寫進資料庫的那一個。
     */
    public void setCSPoints(int type, int value) {
        synchronized (currencyLock) {
            switch (type) {
                case 1 ->
                    acash = value;
                case 2 ->
                    maplepoints = value;
                default -> {
                }
            }
        }
    }

    public final boolean hasEquipped(int itemid) {
        return inventory[MapleInventoryType.EQUIPPED.ordinal()].countById(itemid) >= 1;
    }

    public final boolean haveItem(int itemid, int quantity, boolean checkEquipped, boolean greaterOrEquals) {
        final MapleInventoryType type = ItemConstants.getInventoryType(itemid);
        int possesed = inventory[type.ordinal()].countById(itemid);
        if (checkEquipped && type == MapleInventoryType.EQUIP) {
            possesed += inventory[MapleInventoryType.EQUIPPED.ordinal()].countById(itemid);
        }
        if (greaterOrEquals) {
            return possesed >= quantity;
        } else {
            return possesed == quantity;
        }
    }

    public final boolean haveItem(int itemid, int quantity) {
        return haveItem(itemid, quantity, true, true);
    }

    public final boolean haveItem(int itemid) {
        return haveItem(itemid, 1, true, true);
    }

    public static enum FameStatus {

        OK, NOT_TODAY, NOT_THIS_MONTH
    }

    public byte getBuddyCapacity() {
        return buddylist.getCapacity();
    }

    public void setBuddyCapacity(byte capacity) {
        buddylist.setCapacity(capacity);
        client.send(BuddyPacket.updateBuddyCapacity(capacity));
    }

    public MapleMessenger getMessenger() {
        return messenger;
    }

    public void setMessenger(MapleMessenger messenger) {
        this.messenger = messenger;
    }

    public void addCooldown(int skillId, long startTime, long length) {
        coolDowns.put(Integer.valueOf(skillId), new MapleCoolDownValueHolder(skillId, startTime, length));
    }

    public void removeCooldown(int skillId) {
        // C4：containsKey + remove 併成一步。remove() 本身就是「有才刪、沒有就沒事」，
        // 語意不變，但不會再有 Timer.WORLD 的到期掃描與 Time Leap 之間的先讀後寫空窗。
        coolDowns.remove(Integer.valueOf(skillId));
    }

    public boolean skillisCooling(int skillId) {
        return coolDowns.containsKey(Integer.valueOf(skillId));
    }

    public void giveCoolDowns(final int skillid, long starttime, long length) {
        addCooldown(skillid, starttime, length);
    }

    public void giveCoolDowns(final List<MapleCoolDownValueHolder> cooldowns) {
        int time;
        if (cooldowns != null) {
            for (MapleCoolDownValueHolder cooldown : cooldowns) {
                coolDowns.put(cooldown.skillId, cooldown);
            }
        } else {
            try (Connection con = DatabaseConnection.getConnection()) {
                try (PreparedStatement ps = con.prepareStatement("SELECT SkillID,StartTime,length FROM skills_cooldowns WHERE charid = ?")) {
                    ps.setInt(1, getId());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            if (rs.getLong("length") + rs.getLong("StartTime") - System.currentTimeMillis() <= 0) {
                                continue;
                            }
                            giveCoolDowns(rs.getInt("SkillID"), rs.getLong("StartTime"), rs.getLong("length"));
                        }
                    }
                }
                deleteWhereCharacterId(con, "DELETE FROM skills_cooldowns WHERE charid = ?");

            } catch (SQLException e) {
                // ERROR: abandoned, so the player logs in with some or all of
                // their cooldowns gone - and the DELETE that clears the rows may
                // not have run either. Kept as terse as it was: this one never
                // reported the exception at all.
                LOG.log(System.Logger.Level.ERROR, "Error while retriving cooldown from SQL storage");
            }
        }
    }

    public List<MapleCoolDownValueHolder> getCooldowns() {
        return new ArrayList<>(coolDowns.values());
    }

    public final List<MapleDiseaseValueHolder> getAllDiseases() {
        return new ArrayList<>(diseases.values());
    }

    public final boolean hasDisease(final MapleDisease dis) {
        // C12：keySet() 現在會在鎖內複製一份快照，拿它做一次 contains 等於白配置一個 Set。
        // containsKey 同樣在讀鎖底下、語意完全相同、零配置。
        return diseases.containsKey(dis);
    }

    public void giveDebuff(final MapleDisease disease, MobSkill skill) {
        giveDebuff(disease, skill.getX(), skill.getDuration(), skill.getSkillId(), skill.getSkillLevel());
    }

    public void giveDebuff(final MapleDisease disease, int x, long duration, int skillid, int level) {
        final List<Pair<MapleDisease, Integer>> debuff = Collections.singletonList(new Pair<>(disease, Integer.valueOf(x)));

        if (!hasDisease(disease) && diseases.size() < 2) {
            if (!(disease == MapleDisease.SEDUCE || disease == MapleDisease.STUN)) {
                if (isActiveBuffedValue(2321005)) {
                    return;
                }
            }

            diseases.put(disease, new MapleDiseaseValueHolder(disease, System.currentTimeMillis(), duration));
            client.send(BuffPacket.giveDebuff(debuff, skillid, level, (int) duration));
            map.broadcastMessage(this, BuffPacket.giveForeignDebuff(id, debuff, skillid, level), false);
        }
    }

    public final void giveSilentDebuff(final List<MapleDiseaseValueHolder> ld) {
        if (ld != null) {
            for (final MapleDiseaseValueHolder disease : ld) {
                diseases.put(disease.disease, disease);
            }
        }
    }

    public void dispelDebuff(MapleDisease debuff) {
        if (hasDisease(debuff)) {
            long mask = debuff.getValue();
            boolean first = debuff.isFirst();
            client.send(BuffPacket.cancelDebuff(mask, first));
            map.broadcastMessage(this, BuffPacket.cancelForeignDebuff(id, mask, first), false);

            diseases.remove(debuff);
        }
    }

    public void dispelDebuffs() {
        dispelDebuff(MapleDisease.CURSE);
        dispelDebuff(MapleDisease.DARKNESS);
        dispelDebuff(MapleDisease.POISON);
        dispelDebuff(MapleDisease.SEAL);
        dispelDebuff(MapleDisease.WEAKEN);
    }

    public void cancelAllDebuffs() {
        diseases.clear();
    }

    public void setLevel(final short level) {
        this.level = (short) (level - 1);
    }

    public void sendNote(String to, String msg) {
        sendNote(to, msg, 0);
    }

    public void sendNote(String to, String msg, int fame) {
        MapleCharacterUtil.sendNote(to, getName(), msg, fame);
    }

    public void showNote() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM notes WHERE `to`=?", ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE)) {
            ps.setString(1, getName());
            try (ResultSet rs = ps.executeQuery()) {
                rs.last();
                int count = rs.getRow();
                rs.first();
                client.send(MTSCSPacket.showNotes(rs, count));
            }
        } catch (SQLException e) {
            // ERROR: abandoned, so the note window is never sent. Terse.
            LOG.log(System.Logger.Level.ERROR, "Unable to show note" + e);
        }
    }

    public void deleteNote(int id, int fame) {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT gift FROM notes WHERE `id`=?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (rs.getInt("gift") == fame && fame > 0) { //not exploited! hurray
                            addFame(fame);
                            updateSingleStat(MapleStat.FAME, getFame());
                            client.send(CharacterPacket.getShowFameGain(fame));
                        }
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM notes WHERE `id`=?")) {
                ps.setInt(1, id);
                ps.execute();
            }
        } catch (SQLException e) {
            // ERROR: abandoned. The fame may already have been granted above
            // while the note row survives, so it can be claimed again. Terse.
            LOG.log(System.Logger.Level.ERROR, "Unable to delete note" + e);
        }
    }

    public void mulung_EnergyModify(boolean inc) {
        if (inc) {
            if (mulung_energy + 100 > 10000) {
                mulung_energy = 10000;
            } else {
                mulung_energy += 100;
            }
        } else {
            mulung_energy = 0;
        }
        client.send(EventPacket.mulungEnergy(mulung_energy));
    }

    public void writeMulungEnergy() {
        client.send(EventPacket.mulungEnergy(mulung_energy));
    }

    public void writeEnergy(String type, String inc) {
        client.send(EventPacket.sendPyramidEnergy(type, inc));
    }

    public void writeStatus(String type, String inc) {
        client.send(EventPacket.sendGhostStatus(type, inc));
    }

    public void writePoint(String type, String inc) {
        client.send(EventPacket.sendGhostPoint(type, inc));
    }

    public final short getCombo() {
        return combo;
    }

    public void setCombo(final short combo) {
        this.combo = combo;
    }

    public final long getLastCombo() {
        return lastCombo;
    }

    public void setLastCombo(final long combo) {
        this.lastCombo = combo;
    }

    public final long getKeyDownSkill_Time() {
        return keydown_skill;
    }

    public void setKeyDownSkill_Time(final long keydown_skill) {
        this.keydown_skill = keydown_skill;
    }

    public void checkBerserk() {
        if (BerserkSchedule != null) {
            BerserkSchedule.cancel(false);
            BerserkSchedule = null;
        }

        final ISkill BerserkX = SkillFactory.getSkill(1320006);
        final int skilllevel = getSkillLevel(BerserkX);
        if (skilllevel >= 1) {
            final MapleStatEffect ampStat = BerserkX.getEffect(skilllevel);
            stats.Berserk = stats.getHp() * 100 / stats.getMaxHp() <= ampStat.getX();
            client.send(CharacterPacket.showOwnBuffEffect(1320006, 1, (byte) (stats.Berserk ? 1 : 0)));
            map.broadcastMessage(this, CharacterPacket.showBuffeffect(getId(), 1320006, 1, (byte) (stats.Berserk ? 1 : 0)), false);

            BerserkSchedule = Timer.BUFF.schedule(() -> {
                checkBerserk();
            }, 10000);
        }
    }

    private void prepareBeholderEffect() {
        if (beholderHealingSchedule != null) {
            beholderHealingSchedule.cancel(false);
        }
        if (beholderBuffSchedule != null) {
            beholderBuffSchedule.cancel(false);
        }
        ISkill bHealing = SkillFactory.getSkill(1320008);
        final int bHealingLvl = getSkillLevel(bHealing);
        final int berserkLvl = getSkillLevel(SkillFactory.getSkill(1320006));

        if (bHealingLvl > 0) {
            final MapleStatEffect healEffect = bHealing.getEffect(bHealingLvl);
            int healInterval = healEffect.getX() * 15000;
            beholderHealingSchedule = Timer.BUFF.register(() -> {
                int remhppercentage = (int) Math.ceil((getStat().getHp() * 100.0) / getStat().getMaxHp());
                if (berserkLvl == 0 || remhppercentage >= berserkLvl + 10) {
                    addHP(healEffect.getHp());
                }
                client.send(CharacterPacket.showOwnBuffEffect(1321007, 2));
                map.broadcastMessage(MapPacket.summonSkill(getId(), 1321007, 5));
                map.broadcastMessage(MapleCharacter.this, CharacterPacket.showBuffeffect(getId(), 1321007, 2), false);
            }, healInterval, healInterval);
        }
        ISkill bBuff = SkillFactory.getSkill(1320009);
        final int bBuffLvl = getSkillLevel(bBuff);
        if (bBuffLvl > 0) {
            final MapleStatEffect buffEffect = bBuff.getEffect(bBuffLvl);
            int buffInterval = buffEffect.getX() * 10000;
            beholderBuffSchedule = Timer.BUFF.register(() -> {
                buffEffect.applyTo(MapleCharacter.this);
                client.send(CharacterPacket.showOwnBuffEffect(1321007, 2));
                map.broadcastMessage(MapPacket.summonSkill(getId(), 1321007, Randomizer.nextInt(3) + 6));
                map.broadcastMessage(MapleCharacter.this, CharacterPacket.showBuffeffect(getId(), 1321007, 2), false);
            }, buffInterval, buffInterval);
        }
    }

    public void setChalkboard(String text) {
        this.chalktext = text;
        map.broadcastMessage(MTSCSPacket.useChalkboard(getId(), text));
    }

    public String getChalkboard() {
        return chalktext;
    }

    public MapleMount getMount() {
        return mount;
    }

    public int[] getWishlist() {
        return wishlist;
    }

    public void clearWishlist() {
        for (int i = 0; i < 10; i++) {
            wishlist[i] = 0;
        }
    }

    public int getWishlistSize() {
        int ret = 0;
        for (int i = 0; i < 10; i++) {
            if (wishlist[i] > 0) {
                ret++;
            }
        }
        return ret;
    }

    public void setWishlist(int[] wl) {
        this.wishlist = wl;
    }

    public int[] getRocks() {
        return rocks;
    }

    public int getRockSize() {
        int ret = 0;
        for (int i = 0; i < 10; i++) {
            if (rocks[i] != 999999999) {
                ret++;
            }
        }
        return ret;
    }

    public void deleteFromRocks(int map) {
        for (int i = 0; i < 10; i++) {
            if (rocks[i] == map) {
                rocks[i] = 999999999;
                break;
            }
        }
    }

    public void addRockMap() {
        if (getRockSize() >= 10) {
            return;
        }
        rocks[getRockSize()] = getMapId();
    }

    public boolean isRockMap(int id) {
        for (int i = 0; i < 10; i++) {
            if (rocks[i] == id) {
                return true;
            }
        }
        return false;
    }

    public int[] getRegRocks() {
        return regrocks;
    }

    public int getRegRockSize() {
        int ret = 0;
        for (int i = 0; i < 5; i++) {
            if (regrocks[i] != 999999999) {
                ret++;
            }
        }
        return ret;
    }

    public void deleteFromRegRocks(int map) {
        for (int i = 0; i < 5; i++) {
            if (regrocks[i] == map) {
                regrocks[i] = 999999999;
                break;
            }
        }
    }

    public void addRegRockMap() {
        if (getRegRockSize() >= 5) {
            return;
        }
        regrocks[getRegRockSize()] = getMapId();
    }

    public boolean isRegRockMap(int id) {
        for (int i = 0; i < 5; i++) {
            if (regrocks[i] == id) {
                return true;
            }
        }
        return false;
    }

    public List<LifeMovementFragment> getLastRes() {
        return lastres;
    }

    public void setLastRes(List<LifeMovementFragment> lastres) {
        this.lastres = lastres;
    }

    public void setMonsterBookCover(int bookCover) {
        this.bookCover = bookCover;
    }

    public int getMonsterBookCover() {
        return bookCover;
    }

               public int getBossLog(String bossid) {
        try (Connection con1 = DatabaseConnection.getConnection();
             PreparedStatement ps = con1.prepareStatement("select count(*) from bosslog where characterid = ? and bossid = ? and lastattempt >= subtime(current_timestamp, '1 0:0:0.0')")) {
            int ret_count = 0;
            ps.setInt(1, id);
            ps.setString(2, bossid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    ret_count = rs.getInt(1);
                } else {
                    ret_count = -1;
                }
            }
            return ret_count;
        } catch (Exception Ex) {
            return -1;
        }
    }

    public void setBossLog(String bossid) {
        try (Connection con1 = DatabaseConnection.getConnection();
             PreparedStatement ps = con1.prepareStatement("insert into bosslog (characterid, bossid) values (?,?)")) {
            ps.setInt(1, id);
            ps.setString(2, bossid);
            ps.executeUpdate();
        } catch (Exception Ex) {
            // ERROR: the bosslog row was not written, so the NPC scripts that
            // read it will let this boss be run again. Terse on purpose.
            LOG.log(System.Logger.Level.ERROR, "Error while insert bosslog for char " + getName() + ": " + Ex);
        }
    }
    public void dropMessage(int type, String message) {
        if (type == -1) {
            client.send(UIPacket.getTopMsg(message));
        } else if (type == -2) {
            client.send(PlayerShopPacket.shopChat(message, 0)); //0 or what
        } else {
            client.send(ChatPacket.serverNotice(type, message));
        }
    }

    public IMaplePlayerShop getPlayerShop() {
        return playerShop;
    }

    public void setPlayerShop(IMaplePlayerShop playerShop) {
        this.playerShop = playerShop;
    }

    public int getConversation() {
        return inst.get();
    }

    public void setConversation(int inst) {
        this.inst.set(inst);
    }

    public MapleCarnivalParty getCarnivalParty() {
        return carnivalParty;
    }

    public void setCarnivalParty(MapleCarnivalParty party) {
        carnivalParty = party;
    }

    public void addCP(int ammount) {
        totalCP = (short) (totalCP + ammount); // CP 上線是 writeShort，本來就只有 16 bit
        availableCP = (short) (availableCP + ammount);
    }

    public void useCP(int ammount) {
        availableCP = (short) (availableCP - ammount);
    }

    public int getAvailableCP() {
        return availableCP;
    }

    public int getTotalCP() {
        return totalCP;
    }

    public void resetCP() {
        totalCP = 0;
        availableCP = 0;
    }

    public void addCarnivalRequest(MapleCarnivalChallenge request) {
        pendingCarnivalRequests.add(request);
    }

    public final MapleCarnivalChallenge getNextCarnivalRequest() {
        return pendingCarnivalRequests.pollLast();
    }

    public void clearCarnivalRequests() {
        // C4：原本是換一個新的 LinkedList；正在 addCarnivalRequest 的挑戰者執行緒
        // 可能還抓著舊的參考，挑戰就寫進被丟掉的佇列裡。改成原地清空。
        pendingCarnivalRequests.clear();
    }

    public void startMonsterCarnival(final int enemyavailable, final int enemytotal) {
        client.send(MonsterCarnivalPacket.startMonsterCarnival(this, enemyavailable, enemytotal));
    }

    public void cpUpdate(final boolean party, final int available, final int total, final int team) {
        client.send(MonsterCarnivalPacket.cpUpdate(party, available, total, team));
    }

    public void playerDiedCPQ(final String name, final int lostCP, final int team) {
        client.send(MonsterCarnivalPacket.playerDiedMessage(name, lostCP, team));
    }

    /*public void setAchievementFinished(int id) {
     if (!finishedAchievements.contains(id)) {
     finishedAchievements.add(id);
     }
     }

     public boolean achievementFinished(int achievementid) {
     return finishedAchievements.contains(achievementid);
     }

     public void finishAchievement(int id) {
     if (!achievementFinished(id)) {
     if (isAlive() && !isClone()) {
     MapleAchievements.getInstance().getById(id).finishAchievement(this);
     }
     }
     }

     public List<Integer> getFinishedAchievements() {
     return finishedAchievements;
     }

     public void modifyAchievementCSPoints(int type, int quantity) {
     switch (type) {
     case 1:
     acash += quantity;
     break;
     case 2:
     maplepoints += quantity;
     break;
     }
     }*/
    public boolean getCanTalk() {
        return this.canTalk;
    }

    public void canTalk(boolean talk) {
        this.canTalk = talk;
    }

    public int getEXPMod() {
        return stats.expMod;
    }

    public int getDropMod() {
        return stats.dropMod;
    }

    public int getCashMod() {
        return stats.cashMod;
    }

    public void setPoints(int p) {
        this.points = p;
        /*if (this.points >= 1) {
         finishAchievement(1);
         }*/
    }

    public int getPoints() {
        return points;
    }

    public void setVPoints(int p) {
        this.vpoints = p;
    }

    public int getVPoints() {
        return vpoints;
    }

    public CashShop getCashInventory() {
        return cs;
    }

    public void removeAll(int id) {
        MapleInventoryType type = ItemConstants.getInventoryType(id);
        int possessed = getInventory(type).countById(id);

        if (possessed > 0) {
            MapleInventoryManipulator.removeById(getClient(), type, id, possessed, true, false);
            getClient().send(InventoryPacket.getShowItemGain(id, (short) -possessed, true));
        }
        /*if (type == MapleInventoryType.EQUIP) { //check equipped
         type = MapleInventoryType.EQUIPPED;
         possessed = getInventory(type).countById(id);

         if (possessed > 0) {
         MapleInventoryManipulator.removeById(getClient(), type, id, possessed, true, false);
         getClient().send(InventoryPacket.getShowItemGain(id, (short)-possessed, true));
         }
         }*/
    }

    //TODO: more than one crush/friendship ring at a time
    public Pair<List<MapleRing>, List<MapleRing>> getRings(boolean equip) {
        MapleInventory iv = getInventory(MapleInventoryType.EQUIPPED);
        Collection<IItem> equippedC = iv.list();
        List<Item> equipped = new ArrayList<>(equippedC.size());
        for (IItem item : equippedC) {
            equipped.add((Item) item);
        }
        Collections.sort(equipped);
        List<MapleRing> crings = new ArrayList<>();
        List<MapleRing> frings = new ArrayList<>();
        MapleRing ring;
        for (Item item : equipped) {
            if (item.getRing() != null) {
                ring = item.getRing();
                ring.setEquipped(true);
                if (ItemConstants.isFriendshipRing(item.getItemId()) || ItemConstants.isCrushRing(item.getItemId())) {
                    if (equip) {
                        if (ItemConstants.isCrushRing(item.getItemId())) {
                            crings.add(ring);
                        } else if (ItemConstants.isFriendshipRing(item.getItemId())) {
                            frings.add(ring);
                        }
                    } else {
                        if (crings.size() == 0 && ItemConstants.isCrushRing(item.getItemId())) {
                            crings.add(ring);
                        } else if (frings.size() == 0 && ItemConstants.isFriendshipRing(item.getItemId())) {
                            frings.add(ring);
                        } //for 3rd person the actual slot doesnt matter, so we'll use this to have both shirt/ring same?
                        //however there seems to be something else behind this, will have to sniff someone with shirt and ring, or more conveniently 3-4 of those
                    }
                }
            }
        }
        if (equip) {
            iv = getInventory(MapleInventoryType.EQUIP);
            for (IItem item : iv.list()) {
                if (item.getRing() != null && ItemConstants.isCrushRing(item.getItemId())) {
                    ring = item.getRing();
                    ring.setEquipped(false);
                    if (ItemConstants.isFriendshipRing(item.getItemId())) {
                        frings.add(ring);
                    } else if (ItemConstants.isCrushRing(item.getItemId())) {
                        crings.add(ring);
                    }
                }
            }
        }
        Collections.sort(frings, new MapleRing.RingComparator());
        Collections.sort(crings, new MapleRing.RingComparator());
        return new Pair<>(crings, frings);
    }

    public int getFH() {
        MapleFoothold fh = getMap().getFootholds().findBelow(getPosition());
        if (fh != null) {
            return fh.getId();
        }
        return 0;
    }

    public void startFairySchedule(boolean exp) {
        startFairySchedule(exp, false);
    }

    public void startFairySchedule(boolean exp, boolean equipped) {
        cancelFairySchedule(exp);
        if (fairyExp < 30 && stats.equippedFairy) {
            if (equipped) {
                dropMessage(5, "The Fairy Pendant's experience points will increase to " + (fairyExp + 10) + "% after one hour.");
            }
            fairySchedule = Timer.ETC.schedule(() -> {
                if (fairyExp < 30 && stats.equippedFairy) {
                    fairyExp += 10;
                    dropMessage(5, "The Fairy Pendant's EXP was boosted to " + fairyExp + "%.");
                    startFairySchedule(false, true);
                } else {
                    cancelFairySchedule(!stats.equippedFairy);
                }
            }, 60 * 60 * 1000);
        } else {
            cancelFairySchedule(!stats.equippedFairy);
        }
    }

    public void cancelFairySchedule(boolean exp) {
        if (fairySchedule != null) {
            fairySchedule.cancel(false);
            fairySchedule = null;
        }
        if (exp) {
            this.fairyExp = 10;
        }
    }

    public byte getFairyExp() {
        return fairyExp;
    }

    public int getCoconutTeam() {
        return coconutteam;
    }

    public void setCoconutTeam(int team) {
        coconutteam = team;
    }

    public void spawnPet(byte slot) {
        spawnPet(slot, false, true);
    }

    public void spawnPet(byte slot, boolean lead) {
        spawnPet(slot, lead, true);
    }

    public void spawnPet(byte slot, boolean lead, boolean broadcast) {
        final IItem item = getInventory(MapleInventoryType.CASH).getItem(slot);
        if (item == null || item.getItemId() > 5000100 || item.getItemId() < 5000000) {
            return;
        }
        switch (item.getItemId()) {
            case 5000047:
            case 5000028: {
                final MaplePet pet = MaplePet.createPet(item.getItemId() + 1, MapleInventoryIdentifier.getInstance());
                if (pet != null) {
                    MapleInventoryManipulator.addById(client, item.getItemId() + 1, (short) 1, item.getOwner(), pet, 45);
                    MapleInventoryManipulator.removeFromSlot(client, MapleInventoryType.CASH, slot, (short) 1, false);
                }
                break;
            }
            default: {
                final MaplePet pet = item.getPet();
                if (pet != null && (item.getItemId() != 5000054 || pet.getSecondsLeft() > 0) && (item.getExpiration() == -1 || item.getExpiration() > System.currentTimeMillis())) {
                    if (pet.getSummoned()) { // Already summoned, let's keep it
                        unequipPet(pet, true, false);
                    } else {
                        int leadid = 8;
                        
                        if (JobConstants.isKOC(getJob())) {
                            leadid = 10000018;
                        } else if (JobConstants.isAran(getJob())) {
                            leadid = 20000024;
                        } else if (JobConstants.isEvan(getJob())) {
                            leadid = 20010024;
                        } else if (JobConstants.isResist(getJob())) {
                            leadid = 30000024;
                        }
                        if (getSkillLevel(SkillFactory.getSkill(leadid)) == 0 && getPet(0) != null) {
                            unequipPet(getPet(0), false, false);
                        } else if (lead && getSkillLevel(SkillFactory.getSkill(leadid)) > 0) { // Follow the Lead
                        }
                        final Point pos = getPosition();
                        pet.setPos(pos);
                        try {
                            pet.setFh(getMap().getFootholds().findBelow(pos).getId());
                        } catch (NullPointerException e) {
                            pet.setFh(0); //lol, it can be fixed by movement
                        }
                        pet.setStance(0);
                        pet.setSummoned(1);

                        addPet(pet);

                        if (lead && getSkillLevel(SkillFactory.getSkill(leadid)) > 0) { // Follow the Lead
                            int count = 0 ;
                            for (final MaplePet p : pets) {
                                if (p.getSummoned()) {
                                    break;
                                }
                                count++;
                            }
                            int pet_count = 0;
                            for (final MaplePet p : pets) {
                                if (p == pet) {
                                    break;
                                }
                                pet_count++;
                            }
                            // Leftover developer trace, kept at INFO so it still
                            // prints exactly as it did. Not a hot path: this runs
                            // once per pet summon, on a player action.
                            LOG.log(System.Logger.Level.INFO, "Count = " + count + " Pet_count = " + pet_count);
                            Collections.swap(pets, count, pet_count);
                        }
                        
                        if (broadcast) {
                            getMap().broadcastMessage(this, PetPacket.showPet(this, pet, false, false), true);
                            client.send(PetPacket.petComplete(this, pet));
                            client.send(PetPacket.petStatUpdate(this));
                        }
                    }
                }
                break;
            }
        }
        client.send(PetPacket.emptyStatUpdate());
    }

    public void addMoveMob(int mobid) {
        // C4：原本是 containsKey → get → +1 → put，再 get 一次來比 30，中間任何一步
        // 都可能被別人的執行緒插進來（下面那個迴圈就是在改別人的這張表），計數會少算。
        // merge 一次做完並回傳新值，單執行緒下的結果與原本逐字相同：
        // 不存在時放 1（1 > 30 為假，本來也不會進 if），存在時 +1 後比較。
        final int moved = movedMobs.merge(mobid, 1, Integer::sum);
        if (moved > 30) { //trying to move not null monster = broadcast dead
            for (MapleCharacter chr : getMap().getCharactersThreadsafe()) { //also broadcast to others
                if (chr.getMoveMobs().containsKey(mobid)) { //they also tried to move this mob
                    chr.getClient().send(MobPacket.killMonster(mobid, 1));
                    chr.getMoveMobs().remove(mobid);
                }
            }
        }
    }

    public Map<Integer, Integer> getMoveMobs() {
        return movedMobs;
    }

    public int getLinkMid() {
        return linkMid;
    }

    public void setLinkMid(int lm) {
        this.linkMid = lm;
    }

    public boolean isClone() {
        return clone;
    }

    public void setClone(boolean c) {
        this.clone = c;
    }

    public WeakReference<MapleCharacter>[] getClones() {
        return clones;
    }

    public MapleCharacter cloneLooks() {
        MapleClient cs = new MapleClient(null, null, new MockIOSession());

        final int minus = (getId() + Randomizer.nextInt(getId())); // really randomize it, dont want it to fail

        MapleCharacter ret = new MapleCharacter(true);
        ret.id = minus;
        ret.client = cs;
        ret.exp = 0;
        ret.meso = 0;
        ret.beans = beans;
        ret.blood = blood;
        ret.month = month;
        ret.day = day;
        ret.charmessage = charmessage;
        ret.expression = expression;
        ret.constellation = constellation;
        ret.remainingAp = 0;
        ret.fame = 0;
        ret.accountid = client.getAccID();
        ret.name = name;
        ret.level = level;
        ret.fame = fame;
        ret.job = job;
        ret.hair = hair;
        ret.face = face;
        ret.skinColor = skinColor;
        ret.bookCover = bookCover;
        ret.monsterbook = monsterbook;
        ret.mount = mount;
        ret.CRand = new PlayerRandomStream();
        ret.gmLevel = gmLevel;
        ret.gender = gender;
        ret.mapid = map.getId();
        ret.map = map;
        ret.setStance(getStance());
        ret.chair = chair;
        ret.itemEffect = itemEffect;
        ret.guildid = guildid;
        ret.currentrep = currentrep;
        ret.totalrep = totalrep;
        ret.stats = stats;
        // C12：同上——putAll 對 EnumMap 來源也走那條繞過讀鎖的快速路徑，所以逐筆從
        // entrySet() 的快照複製。分身只有一隻、buff 數量是個位數，成本可以忽略。
        for (Entry<MapleBuffStat, MapleBuffStatValueHolder> buff : effects.entrySet()) {
            ret.effects.put(buff.getKey(), buff.getValue());
        }
        if (ret.effects.get(MapleBuffStat.ILLUSION) != null) {
            ret.effects.remove(MapleBuffStat.ILLUSION);
        }
        if (ret.effects.get(MapleBuffStat.SUMMON) != null) {
            ret.effects.remove(MapleBuffStat.SUMMON);
        }
        if (ret.effects.get(MapleBuffStat.REAPER) != null) {
            ret.effects.remove(MapleBuffStat.REAPER);
        }
        if (ret.effects.get(MapleBuffStat.PUPPET) != null) {
            ret.effects.remove(MapleBuffStat.PUPPET);
        }
        ret.guildrank = guildrank;
        ret.allianceRank = allianceRank;
        ret.hidden = hidden;
        ret.setPosition(new Point(getPosition()));
        for (IItem equip : getInventory(MapleInventoryType.EQUIPPED)) {
            ret.getInventory(MapleInventoryType.EQUIPPED).addFromDB(equip);
        }
        ret.skillMacros = skillMacros;
        ret.keylayout = keylayout;
        ret.questinfo = questinfo;
        ret.savedLocations = savedLocations;
        ret.wishlist = wishlist;
        ret.rocks = rocks;
        ret.regrocks = regrocks;
        ret.buddylist = buddylist;
        ret.keydown_skill = 0;
        ret.lastmonthfameids = lastmonthfameids;
        ret.lastfametime = lastfametime;
        ret.storage = storage;
        ret.cs = this.cs;
        ret.client.setAccountName(client.getAccountName());
        ret.acash = acash;
        ret.maplepoints = maplepoints;
        ret.clone = true;
        ret.client.setChannel(this.client.getChannel());
        while (map.getCharacterById(ret.id) != null || client.getChannelServer().getPlayerStorage().getCharacterById(ret.id) != null) {
            ret.id++;
        }
        ret.client.setPlayer(ret);
        return ret;
    }

    public final void cloneLook() {
        if (clone) {
            return;
        }
        for (int i = 0; i < clones.length; i++) {
            if (clones[i].get() == null) {
                final MapleCharacter newp = cloneLooks();
                map.addPlayer(newp);
                map.broadcastMessage(CharacterPacket.updateCharLook(newp));
                map.movePlayer(newp, getPosition());
                clones[i] = new WeakReference<>(newp);
                return;
            }
        }
    }

    public final void disposeClones() {
        numClones = 0;
        for (int i = 0; i < clones.length; i++) {
            if (clones[i].get() != null) {
                map.removePlayer(clones[i].get());
                clones[i].get().getClient().disconnect(false, false);
                clones[i] = new WeakReference<>(null);
                numClones++;
            }
        }
    }

    public final int getCloneSize() {
        int z = 0;
        for (int i = 0; i < clones.length; i++) {
            if (clones[i].get() != null) {
                z++;
            }
        }
        return z;
    }

    public void spawnClones() {
        if (numClones == 0 && stats.hasClone) {
            cloneLook(); //once and never again
        }
        for (int i = 0; i < numClones; i++) {
            cloneLook();
        }
        numClones = 0;
    }

    public byte getNumClones() {
        return numClones;
    }

    public void setDragon(MapleDragon d) {
        this.dragon = d;
    }

    public final void spawnSavedPets() {
        for (int i = 0; i < petStore.length; i++) {
            if (petStore[i] > -1) {
                spawnPet(petStore[i], false, false);
            }
        }
        client.send(PetPacket.petStatUpdate(this));
        petStore = new byte[]{-1, -1, -1};
    }

    public final byte[] getPetStores() {
        return petStore;
    }

    public void resetStats(final int str, final int dex, final int int_, final int luk) {
        List<Pair<MapleStat, Integer>> stat = new ArrayList<>(2);
        int total = stats.getStr() + stats.getDex() + stats.getLuk() + stats.getInt() + getRemainingAp();

        total -= str;
        stats.setStr((short) str);

        total -= dex;
        stats.setDex((short) dex);

        total -= int_;
        stats.setInt((short) int_);

        total -= luk;
        stats.setLuk((short) luk);

        setRemainingAp((short) total);

        stat.add(new Pair<>(MapleStat.STR, str));
        stat.add(new Pair<>(MapleStat.DEX, dex));
        stat.add(new Pair<>(MapleStat.INT, int_));
        stat.add(new Pair<>(MapleStat.LUK, luk));
        stat.add(new Pair<>(MapleStat.AVAILABLEAP, total));
        client.send(CharacterPacket.updatePlayerStats(stat, false, getJob()));
    }

    public Event_PyramidSubway getPyramidSubway() {
        return pyramidSubway;
    }

    public void setPyramidSubway(Event_PyramidSubway ps) {
        this.pyramidSubway = ps;
    }

    public byte getSubcategory() {
        if (job >= 430 && job <= 434) {
            return 1; //dont set it
        }
        return subcategory;
    }

    public int itemQuantity(final int itemid) {
        return getInventory(ItemConstants.getInventoryType(itemid)).countById(itemid);
    }

    public void setRPS(RockPaperScissors rps) {
        this.rps = rps;
    }

    public RockPaperScissors getRPS() {
        return rps;
    }

    public long getNextConsume() {
        return nextConsume;
    }

    public void setNextConsume(long nc) {
        this.nextConsume = nc;
    }

    public int getRank() {
        return rank;
    }

    public int getRankMove() {
        return rankMove;
    }

    public int getJobRank() {
        return jobRank;
    }

    public int getJobRankMove() {
        return jobRankMove;
    }

    public void changeChannel(final int channel) {
        final ChannelServer toch = ChannelServer.getInstance(channel);

        if (channel == client.getChannel() || toch == null || toch.isShutdown()) {
            client.send(ServerPacket.serverBlocked(1));
            return;
        }
        changeRemoval();

        final ChannelServer ch = ChannelServer.getInstance(client.getChannel());
        if (getMessenger() != null) {
            Messenger.silentLeaveMessenger(getMessenger().getId(), new MapleMessengerCharacter(this));
        }
        PlayerBuffStorage.addBuffsToStorage(getId(), getAllBuffs());
        PlayerBuffStorage.addCooldownsToStorage(getId(), getCooldowns());
        PlayerBuffStorage.addDiseaseToStorage(getId(), getAllDiseases());
        World.channelChange_Data(new CharacterTransfer(this), getId(), channel);
        ch.removePlayer(this);
        client.updateLoginState(MapleClient.CHANGE_CHANNEL, client.getSessionIPAddress());

        client.send(ServerPacket.getChannelChange(Integer.parseInt(toch.getIP().split(":")[1])));
        saveToDB(false, false);
        getMap().removePlayer(this);
        client.setPlayer(null);
        client.setReceiving(false);
    }

    public void expandInventory(byte type, int amount) {
        final MapleInventory inv = getInventory(MapleInventoryType.getByType(type));
        inv.addSlot((byte) amount);
        client.send(InventoryPacket.getSlotUpdate(type, inv.getSlotLimit()));
    }

    public boolean allowedToTarget(MapleCharacter other) {
        return other != null && (!other.isHidden() || getGMLevel() >= other.getGMLevel());
    }

    public int getFollowId() {
        return followid;
    }

    public void setFollowId(int fi) {
        this.followid = fi;
        if (fi == 0) {
            this.followinitiator = false;
            this.followon = false;
        }
    }

    public void setFollowInitiator(boolean fi) {
        this.followinitiator = fi;
    }

    public void setFollowOn(boolean fi) {
        this.followon = fi;
    }

    public boolean isFollowOn() {
        return followon;
    }

    public boolean isFollowInitiator() {
        return followinitiator;
    }

    public void checkFollow() {
        if (followon) {
            map.broadcastMessage(CharacterPacket.followEffect(id, 0, null));
            map.broadcastMessage(CharacterPacket.followEffect(followid, 0, null));
            MapleCharacter tt = map.getCharacterById(followid);
            client.send(CharacterPacket.getFollowMessage("Follow canceled."));
            if (tt != null) {
                tt.setFollowId(0);
                tt.getClient().send(CharacterPacket.getFollowMessage("Follow canceled."));
            }
            setFollowId(0);
        }
    }

    public int getMarriageId() {
        return marriageId;
    }

    public void setMarriageId(final int mi) {
        this.marriageId = mi;
    }

    public int getMarriageItemId() {
        return marriageItemId;
    }

    public void setMarriageItemId(final int mi) {
        this.marriageItemId = mi;
    }

    public boolean isStaff() {
        return this.gmLevel > ServerConstants.PlayerGMRank.NORMAL.getLevel();
    }

    // TODO: gvup, vic, lose, draw, VR
    public boolean startPartyQuest(final int questid) {
        boolean ret = false;
        if (!quests.containsKey(MapleQuest.getInstance(questid)) || !questinfo.containsKey(questid)) {
            final MapleQuestStatus status = getQuestNAdd(MapleQuest.getInstance(questid));
            status.setStatus((byte) 1);
            updateQuest(status);
            switch (questid) {
                case 1300:
                case 1301:
                case 1302: //carnival, ariants.
                    updateInfoQuest(questid, "min=0;sec=0;date=0000-00-00;have=0;rank=F;try=0;cmp=0;CR=0;VR=0;gvup=0;vic=0;lose=0;draw=0");
                    break;
                case 1204: //herb town pq
                    updateInfoQuest(questid, "min=0;sec=0;date=0000-00-00;have0=0;have1=0;have2=0;have3=0;rank=F;try=0;cmp=0;CR=0;VR=0");
                    break;
                case 1206: //ellin pq
                    updateInfoQuest(questid, "min=0;sec=0;date=0000-00-00;have0=0;have1=0;rank=F;try=0;cmp=0;CR=0;VR=0");
                    break;
                default:
                    updateInfoQuest(questid, "min=0;sec=0;date=0000-00-00;have=0;rank=F;try=0;cmp=0;CR=0;VR=0");
                    break;
            }
            ret = true;
        } //started the quest.
        return ret;
    }

    public String getOneInfo(final int questid, final String key) {
        if (!questinfo.containsKey(questid) || key == null) {
            return null;
        }
        final String[] split = questinfo.get(questid).split(";");
        for (String x : split) {
            final String[] split2 = x.split("="); //should be only 2
            if (split2.length == 2 && split2[0].equals(key)) {
                return split2[1];
            }
        }
        return null;
    }

    public void updateOneInfo(final int questid, final String key, final String value) {
        if (!questinfo.containsKey(questid) || key == null || value == null) {
            return;
        }
        final String[] split = questinfo.get(questid).split(";");
        boolean changed = false;
        final StringBuilder newQuest = new StringBuilder();
        for (String x : split) {
            final String[] split2 = x.split("="); //should be only 2
            if (split2.length != 2) {
                continue;
            }
            if (split2[0].equals(key)) {
                newQuest.append(key).append("=").append(value);
            } else {
                newQuest.append(x);
            }
            newQuest.append(";");
            changed = true;
        }

        updateInfoQuest(questid, changed ? newQuest.toString().substring(0, newQuest.toString().length() - 1) : newQuest.toString());
    }

    public void recalcPartyQuestRank(final int questid) {
        if (!startPartyQuest(questid)) {
            final String oldRank = getOneInfo(questid, "rank");
            if (oldRank == null || oldRank.equals("S")) {
                return;
            }
            final String[] split = questinfo.get(questid).split(";");
            String newRank = null;
            if (oldRank.equals("A")) {
                newRank = "S";
            } else if (oldRank.equals("B")) {
                newRank = "A";
            } else if (oldRank.equals("C")) {
                newRank = "B";
            } else if (oldRank.equals("D")) {
                newRank = "C";
            } else if (oldRank.equals("F")) {
                newRank = "D";
            } else {
                return;
            }
            final List<Pair<String, Pair<String, Integer>>> questInfo = MapleQuest.getInstance(questid).getInfoByRank(newRank);
            for (Pair<String, Pair<String, Integer>> q : questInfo) {
                boolean found = false;
                final String val = getOneInfo(questid, q.right.left);
                if (val == null) {
                    return;
                }
                int vall = 0;
                try {
                    vall = Integer.parseInt(val);
                } catch (NumberFormatException e) {
                    return;
                }
                if (q.left.equals("less")) {
                    found = vall < q.right.right;
                } else if (q.left.equals("more")) {
                    found = vall > q.right.right;
                } else if (q.left.equals("equal")) {
                    found = vall == q.right.right;
                }
                if (!found) {
                    return;
                }
            }
            //perfectly safe
            updateOneInfo(questid, "rank", newRank);
        }
    }

    public void tryPartyQuest(final int questid) {
        try {
            startPartyQuest(questid);
            pqStartTime = System.currentTimeMillis();
            updateOneInfo(questid, "try", String.valueOf(Integer.parseInt(getOneInfo(questid, "try")) + 1));
        } catch (Exception e) {
            // One record, not two. ERROR rather than the INFO the stream would
            // suggest: the attempt counter was abandoned part-way, so the
            // party-quest record for this character is now inconsistent.
            LOG.log(System.Logger.Level.ERROR, "tryPartyQuest error", e);
        }
    }

    public void endPartyQuest(final int questid) {
        try {
            startPartyQuest(questid);
            if (pqStartTime > 0) {
                final long changeTime = System.currentTimeMillis() - pqStartTime;
                final int mins = (int) (changeTime / 1000 / 60), secs = (int) (changeTime / 1000 % 60);
                final int mins2 = Integer.parseInt(getOneInfo(questid, "min")), secs2 = Integer.parseInt(getOneInfo(questid, "sec"));
                if (mins2 <= 0 || mins < mins2) {
                    updateOneInfo(questid, "min", String.valueOf(mins));
                    updateOneInfo(questid, "sec", String.valueOf(secs));
                    updateOneInfo(questid, "date", FileoutputUtil.currentReadable_Date());
                }
                final int newCmp = Integer.parseInt(getOneInfo(questid, "cmp")) + 1;
                updateOneInfo(questid, "cmp", String.valueOf(newCmp));
                updateOneInfo(questid, "CR", String.valueOf((int) Math.ceil((newCmp * 100.0) / Integer.parseInt(getOneInfo(questid, "try")))));
                recalcPartyQuestRank(questid);
                pqStartTime = 0;
            }
        } catch (Exception e) {
            // One record, not two. ERROR for the same reason as tryPartyQuest:
            // the record is left half-updated and pqStartTime is not cleared.
            LOG.log(System.Logger.Level.ERROR, "endPartyQuest error", e);
        }

    }

    public void havePartyQuest(final int itemId) {
        int questid = 0, index = -1;
        switch (itemId) {
            case 1002798:
                questid = 1200; //henesys
                break;
            case 1072369:
                questid = 1201; //kerning
                break;
            case 1022073:
                questid = 1202; //ludi
                break;
            case 1082232:
                questid = 1203; //orbis
                break;
            case 1002571:
            case 1002572:
            case 1002573:
            case 1002574:
                questid = 1204; //herbtown
                index = itemId - 1002571;
                break;
            case 1122010:
                questid = 1205; //magatia
                break;
            case 1032061:
            case 1032060:
                questid = 1206; //ellin
                index = itemId - 1032060;
                break;
            case 3010018:
                questid = 1300; //ariant
                break;
            case 1122007:
                questid = 1301; //carnival
                break;
            case 1122058:
                questid = 1302; //carnival2
                break;
            default:
                return;
        }
        startPartyQuest(questid);
        updateOneInfo(questid, "have" + (index == -1 ? "" : index), "1");
    }

    public void resetStatsByJob(boolean beginnerJob) {
        int baseJob = (beginnerJob ? (job % 1000) : (job % 1000 / 100 * 100)); //1112 -> 112 -> 1 -> 100
        if (baseJob == 100) { //first job = warrior
            resetStats(25, 4, 4, 4);
        } else if (baseJob == 200) {
            resetStats(4, 4, 20, 4);
        } else if (baseJob == 300 || baseJob == 400) {
            resetStats(4, 25, 4, 4);
        } else if (baseJob == 500) {
            resetStats(4, 20, 4, 4);
        }
    }

    public boolean hasSummon() {
        return hasSummon;
    }

    public void setHasSummon(boolean summ) {
        this.hasSummon = summ;
    }

    public void removeDoor() {
        final MapleDoor door = getDoors().iterator().next();
        for (final MapleCharacter chr : door.getTarget().getCharactersThreadsafe()) {
            door.sendDestroyData(chr.getClient());
        }
        for (final MapleCharacter chr : door.getTown().getCharactersThreadsafe()) {
            door.sendDestroyData(chr.getClient());
        }
        for (final MapleDoor destroyDoor : getDoors()) {
            door.getTarget().removeMapObject(destroyDoor);
            door.getTown().removeMapObject(destroyDoor);
        }
        clearDoors();
    }

    public void changeRemoval() {
        changeRemoval(false);
    }

    public void changeRemoval(boolean dc) {
        if (getTrade() != null) {
            MapleTrade.cancelTrade(getTrade(), client);
        }
        if (getCheatTracker() != null) {
            getCheatTracker().dispose();
        }
        if (!dc) {
            cancelEffectFromBuffStat(MapleBuffStat.MONSTER_RIDING);
            cancelEffectFromBuffStat(MapleBuffStat.SUMMON);
            cancelEffectFromBuffStat(MapleBuffStat.REAPER);
            cancelEffectFromBuffStat(MapleBuffStat.PUPPET);
        }
        if (getPyramidSubway() != null) {
            getPyramidSubway().dispose(this);
        }
        if (playerShop != null && !dc) {
            playerShop.removeVisitor(this);
            if (playerShop.isOwner(this)) {
                playerShop.setOpen(true);
            }
        }
        if (!getDoors().isEmpty()) {
            removeDoor();
        }
        disposeClones();
        NPCScriptManager.getInstance().dispose(client);
    }

    public void updateTick(int newTick) {
        anticheat.updateTick(newTick);
    }

    public boolean canUseFamilyBuff(MapleFamilyBuffEntry buff) {
        final MapleQuestStatus stat = getQuestNAdd(MapleQuest.getInstance(buff.questID));
        if (stat.getCustomData() == null) {
            stat.setCustomData("0");
        }
        return Long.parseLong(stat.getCustomData()) + (24 * 3600000) < System.currentTimeMillis();
    }

    public void useFamilyBuff(MapleFamilyBuffEntry buff) {
        final MapleQuestStatus stat = getQuestNAdd(MapleQuest.getInstance(buff.questID));
        stat.setCustomData(String.valueOf(System.currentTimeMillis()));
    }

    public List<Pair<Integer, Integer>> usedBuffs() {
        //assume count = 1
        List<Pair<Integer, Integer>> used = new ArrayList<>();
        for (MapleFamilyBuffEntry buff : MapleFamilyBuff.getBuffEntry()) {
            if (!canUseFamilyBuff(buff)) {
                used.add(new Pair<>(buff.index, buff.count));
            }
        }
        return used;
    }

    public String getTeleportName() {
        return teleportname;
    }

    public void setTeleportName(final String tname) {
        teleportname = tname;
    }

    public int getNoJuniors() {
        if (mfc == null) {
            return 0;
        }
        return mfc.getNoJuniors();
    }

    public MapleFamilyCharacter getMFC() {
        return mfc;
    }

    public void makeMFC(final int familyid, final int seniorid, final int junior1, final int junior2) {
        if (familyid > 0) {
            MapleFamily f = Family.getFamily(familyid);
            if (f == null) {
                mfc = null;
            } else {
                mfc = f.getMFC(id);
                if (mfc == null) {
                    mfc = f.addFamilyMemberInfo(this, seniorid, junior1, junior2);
                }
                if (mfc.getSeniorId() != seniorid) {
                    mfc.setSeniorId(seniorid);
                }
                if (mfc.getJunior1() != junior1) {
                    mfc.setJunior1(junior1);
                }
                if (mfc.getJunior2() != junior2) {
                    mfc.setJunior2(junior2);
                }
            }
        } else {
            mfc = null;
        }
    }

    public void setFamily(final int newf, final int news, final int newj1, final int newj2) {
        if (mfc == null || newf != mfc.getFamilyId() || news != mfc.getSeniorId() || newj1 != mfc.getJunior1() || newj2 != mfc.getJunior2()) {
            makeMFC(newf, news, newj1, newj2);
        }
    }

    public int maxBattleshipHP(int skillid) {
        return (getSkillLevel(skillid) * 5000) + ((getLevel() - 120) * 3000);
    }

    public int currentBattleshipHP() {
        return battleshipHP;
    }

    public void sendEnglishQuiz(String msg) {
        client.send(EventPacket.englishQuizMsg(msg));
    }
    
    public int getGachExp() {
        return gachexp;
    }

    public void setGachExp(int ge) {
        this.gachexp = ge;
    }

    public void fakeRelog() {
        client.send(CharacterPacket.getCharInfo(this));
        final MapleMap mapp = getMap();
        mapp.removePlayer(this);
        mapp.addPlayer(this);
    }

    public String getcharmessage() {
        return charmessage;
    }

    public void setcharmessage(String s) {
        charmessage /*+*/ = s;
    }

    public int getexpression() {
        return expression;
    }

    public void setexpression(int s) {
        expression /*+*/ = s;
    }

    public int getconstellation() {
        return constellation;
    }

    public void setconstellation(int s) {
        constellation /*+*/ = s;
    }

    public int getblood() {
        return blood;
    }

    public void setblood(int s) {
        blood /*+*/ = s;
    }

    public int getmonth() {
        return month;
    }

    public void setmonth(int s) {
        month /*+*/ = s;
    }

    public int getday() {
        return day;
    }

    public void setday(int s) {
        day /*+*/ = s;
    }

    public int getTeam() {
        return coconutteam;
    }

    public int getBeans() {
        return beans;
    }

    public void gainBeans(int s) {
        beans += s;
    }

    public void setBeans(int s) {
        beans = s;
    }

    public int getBeansNum() {
        return beansNum;
    }

    /**
     * @param beansNum the beansNum to set
     */
    public void setBeansNum(int beansNum) {
        beansNum = beansNum;
    }

    /**
     * @return the beansRange
     */
    public int getBeansRange() {
        return beansRange;
    }

    /**
     * @param beansRange the beansRange to set
     */
    public void setBeansRange(int beansRange) {
        beansRange = beansRange;
    }

    /**
     * @return the canSetBeansNum
     */
    public boolean isCanSetBeansNum() {
        return canSetBeansNum;
    }

    /**
     * @param canSetBeansNum the canSetBeansNum to set
     */
    public void setCanSetBeansNum(boolean canSetBeansNum) {
        canSetBeansNum = canSetBeansNum;
    }

    public boolean haveGM() {
        return gmLevel >= 2 && gmLevel <= 3;
    }

    public void setprefix(int prefix) {
        this.prefix = prefix;
    }

    public int getPrefix() {
        return prefix;
    }
}

