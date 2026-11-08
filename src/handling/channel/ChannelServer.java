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
package handling.channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import client.MapleCharacter;
import handling.ByteArrayMaplePacket;
import handling.MaplePacket;
import handling.MapleServerHandler;
import handling.login.LoginServer;
import handling.mina.MapleAcceptor;
import handling.world.CheaterData;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import scripting.EventScriptManager;
import server.MapleSquad;
import server.MapleSquad.MapleSquadType;
import server.Timer;
import server.maps.MapleMapFactory;
import server.shops.HiredMerchant;
import server.life.PlayerNPC;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;
import server.ServerProperties;
import server.events.MapleCoconut;
import server.events.MapleEvent;
import server.events.MapleEventType;
import server.events.MapleFitness;
import server.events.MapleOla;
import server.events.MapleOxQuiz;
import server.events.MapleSnowball;
import tools.CollectionUtil;
import tools.ConcurrentEnumMap;
import tools.packet.ChatPacket;

public class ChannelServer {

    private static final System.Logger LOG = System.getLogger(ChannelServer.class.getName());

    public static long serverStartTime;
    private int expRate, mesoRate, dropRate, cashRate;
    // volatile: everything on this line and the next two can be changed by
    // server.LiveSettings - from a panel thread or the Settings.ini watcher -
    // while handler threads read it.
    private volatile short port = 8585;
    private static final short DEFAULT_PORT = 8585;
    private int channel, running_MerchantID = 0;
    private volatile int flags = 0;
    private volatile String serverMessage, ip, serverName;
    private volatile boolean shutdown = false, adminOnly = false;
    private boolean finishedShutdown = false, MegaphoneMuteState = false;
    private PlayerStorage players;
    private MapleServerHandler serverHandler;
    private volatile InetSocketAddress boundAddress;
    private final MapleMapFactory mapFactory;
    /** Replaced whole by {@link #applyEvents}; NPC scripts on any thread read it through {@link #getEventSM()}. */
    private volatile EventScriptManager eventSM;
    /** Serialises the event-list changes against each other; readers do not take it. */
    private final Object eventLock = new Object();
    /**
     * Every channel registers itself here from its own startup, and any thread
     * on any channel looks channels up through it - so it has to be concurrent.
     */
    private static final Map<Integer, ChannelServer> instances = new ConcurrentHashMap<>();
    private final Map<MapleSquadType, MapleSquad> mapleSquads = new ConcurrentEnumMap<>(MapleSquadType.class);
    private final Map<Integer, HiredMerchant> merchants = new HashMap<>();
    /**
     * Player NPCs are created and removed from any handler thread, and read by
     * every thread that walks a map - so this one is concurrent rather than
     * lock-guarded the way {@link #merchants} is.
     */
    private final Map<Integer, PlayerNPC> playerNPCs = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock merchLock = new ReentrantReadWriteLock(); //merchant
    private final ReentrantReadWriteLock squadLock = new ReentrantReadWriteLock(); //squad
    private int eventmap = -1;
    private final Map<MapleEventType, MapleEvent> events = new EnumMap<>(MapleEventType.class);
    private boolean debugMode = false;
    /** 自動存檔排程；{@code tms.AutoSaveMinutes} 為 0（預設）時一直是 null。 */
    private volatile ScheduledFuture<?> autoSaveTask;

    private ChannelServer(final int channel) {
        this.channel = channel;
        mapFactory = new MapleMapFactory();
        mapFactory.setChannel(channel);
    }

    public static Set<Integer> getAllInstance() {
        return new HashSet<>(instances.keySet());
    }

    public final void loadEvents() {
        if (events.size() != 0) {
            return;
        }
        events.put(MapleEventType.CokePlay, new MapleCoconut(channel, MapleEventType.CokePlay.mapids));
        events.put(MapleEventType.Coconut, new MapleCoconut(channel, MapleEventType.Coconut.mapids));
        events.put(MapleEventType.Fitness, new MapleFitness(channel, MapleEventType.Fitness.mapids));
        events.put(MapleEventType.OlaOla, new MapleOla(channel, MapleEventType.OlaOla.mapids));
        events.put(MapleEventType.OxQuiz, new MapleOxQuiz(channel, MapleEventType.OxQuiz.mapids));
        events.put(MapleEventType.Snowball, new MapleSnowball(channel, MapleEventType.Snowball.mapids));
    }

    public final void run_startup_configurations() {
        setChannel(channel); //instances.put
        try {
            expRate = Integer.parseInt(ServerProperties.getProperty("tms.Exp"));
            mesoRate = Integer.parseInt(ServerProperties.getProperty("tms.Meso"));
            dropRate = Integer.parseInt(ServerProperties.getProperty("tms.Drop"));
            cashRate = Integer.parseInt(ServerProperties.getProperty("tms.Cash"));
            serverMessage = ServerProperties.getProperty("tms.ServerMessage");
            serverName = ServerProperties.getProperty("tms.ServerName");
            flags = Integer.parseInt(ServerProperties.getProperty("tms.WFlags", "0"));
            adminOnly = Boolean.parseBoolean(ServerProperties.getProperty("tms.Admin", "false"));
            eventSM = new EventScriptManager(this, eventList());
            port = (short) configuredPort(channel);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ip = ServerProperties.getProperty("tms.IP") + ":" + port;

        players = PlayerStorage.create(channel);
        loadEvents();

        try {
            this.serverHandler = new MapleServerHandler(channel, false);
            this.boundAddress = MapleAcceptor.bind(port, serverHandler);
            LOG.log(System.Logger.Level.INFO, "Channel " + channel + ": Listening on port " + port + "");
            eventSM.init();
            startAutoSave();
        } catch (IOException e) {
            // ERROR, not INFO: the channel never bound, so it is not serving
            // anyone. Terse on purpose - this one only ever concatenated the
            // exception, never a trace, so the Throwable is not attached.
            LOG.log(System.Logger.Level.ERROR, "Binding to port " + port + " failed (ch: " + getChannel() + ")" + e);
        }
    }

    public final void shutdown(Object threadToNotify) {
        if (finishedShutdown) {
            return;
        }
        broadcastPacket(ChatPacket.serverNotice(0, "這個頻道正在關閉中."));
        // dc all clients by hand so we get sessionClosed...
        shutdown = true;

        // 先停掉自動存檔：關機流程自己會走 disconnectAll() 存一次，沒必要讓排程在
        // 玩家一個個被踢下線的過程中再插一輪進來搶連線。
        stopAutoSave();

        LOG.log(System.Logger.Level.INFO, "Channel " + channel + ", Saving hired merchants...");

        closeAllMerchant();

        LOG.log(System.Logger.Level.INFO, "Channel " + channel + ", Saving characters...");

        getPlayerStorage().disconnectAll();

        LOG.log(System.Logger.Level.INFO, "Channel " + channel + ", Unbinding...");

        MapleAcceptor.unbind(boundAddress);
        boundAddress = null;

        //temporary while we dont have !addchannel
        instances.remove(channel);
        LoginServer.removeChannel(channel);
        setFinishShutdown();
        // 保留作為紀錄：shutdown(Object) 的 threadToNotify 參數原本是要在關閉完成後喚醒呼叫端，
        // 這段被關掉之後參數就沒人用了；現在呼叫端改看 setFinishShutdown()。
//        if (threadToNotify != null) {
//            synchronized (threadToNotify) {
//                threadToNotify.notify();
//            }
//        }
    }

    public final void unbind() {
        MapleAcceptor.unbind(boundAddress);
    }

    public final boolean hasFinishedShutdown() {
        return finishedShutdown;
    }

    public final MapleMapFactory getMapFactory() {
        return mapFactory;
    }

    public static final ChannelServer newInstance(final int channel) {
        return new ChannelServer(channel);
    }

    public static final ChannelServer getInstance(final int channel) {
        return instances.get(channel);
    }

    public final void addPlayer(final MapleCharacter chr) {
        getPlayerStorage().registerPlayer(chr);
        chr.getClient().send(ChatPacket.serverMessage(serverMessage));
    }

    public final PlayerStorage getPlayerStorage() {
        if (players == null) { //wth
            players = PlayerStorage.create(channel); //wthhhh
        }
        return players;
    }

    public final void removePlayer(final MapleCharacter chr) {
        getPlayerStorage().deregisterPlayer(chr);

    }

    public final void removePlayer(final int idz, final String namez) {
        getPlayerStorage().deregisterPlayer(idz, namez);

    }

    public final String getServerMessage() {
        return serverMessage;
    }

    public final void setServerMessage(final String newMessage) {
        serverMessage = newMessage;
        broadcastPacket(ChatPacket.serverMessage(serverMessage));
    }

    public final void broadcastPacket(final MaplePacket data) {
        getPlayerStorage().broadcastPacket(data);
    }

    public final void broadcastSmegaPacket(final MaplePacket data) {
        getPlayerStorage().broadcastSmegaPacket(data);
    }

    public final void broadcastGMPacket(final MaplePacket data) {
        getPlayerStorage().broadcastGMPacket(data);
    }

    public final int getExpRate() {
        return expRate;
    }
    
    public final void setExpRate(final int expRate) {
        this.expRate = expRate;
    }

    public final int getCashRate() {
        return cashRate;
    }

    public final void setCashRate(final int cashRate) {
        this.cashRate = cashRate;
    }

    public final int getChannel() {
        return channel;
    }

    public final void setChannel(final int channel) {
        instances.put(channel, this);
        LoginServer.addChannel(channel);
    }

    public static final Collection<ChannelServer> getAllInstances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    public final String getIP() {
        return ip;
    }

    public final boolean isShutdown() {
        return shutdown;
    }

    public final int getLoadedMaps() {
        return mapFactory.getLoadedMaps();
    }

    public final EventScriptManager getEventSM() {
        return eventSM;
    }

    /**
     * Recompiles every {@code tms.Events} script for this channel.
     *
     * <p>This used to read the property {@code "K．"} - a mojibake remnant of
     * {@code tms.Events} - which does not exist, so {@code split} threw an NPE.
     * Worse, {@code cancel()} had already run, so {@code !reloadevents} left
     * the channel with no event scripts at all until the next restart. The
     * new manager is built before the old one is cancelled for the same
     * reason: if compiling fails, the channel keeps what it had.
     */
    public final void reloadEvents() {
        final String[] names = eventList();
        applyEvents(names, new HashSet<>(Arrays.asList(names)));
    }

    /**
     * Brings this channel's running events in line with {@code names} without
     * touching the ones that stay: events new to the list are compiled and
     * started, events no longer in it - and every name in {@code recompile} -
     * are stopped, and everything else keeps running as the same object, open
     * instances and players included. {@link #reloadEvents()} is the special
     * case that recompiles all of them.
     *
     * <p>The new manager is built before anything is stopped, so a compile
     * failure leaves the channel with what it had. The swap happens between
     * stopping the old entries and starting the new ones so that a recompiled
     * event never has two copies scheduling at once.
     *
     * @return {@code [started, stopped]}
     */
    public final List<List<String>> applyEvents(final String[] names, final Set<String> recompile) {
        synchronized (eventLock) {
            final EventScriptManager old = eventSM;
            final EventScriptManager next = EventScriptManager.derive(this, old, names, recompile);
            final List<String> stopped = old.cancelDropped(next);
            eventSM = next;
            next.initFresh();
            return List.of(next.getFreshNames(), stopped);
        }
    }

    /** {@code tms.Events} as a list, blanks and stray spaces removed. */
    public static String[] eventList() {
        final String raw = ServerProperties.getProperty("tms.Events", "");
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /** {@code tms.Port<n>}, falling back to {@code 8585 + n} when it is absent or blank. */
    public static int configuredPort(final int channel) {
        final String configured = ServerProperties.getProperty("tms.Port" + channel);
        if (configured != null && !configured.isBlank()) {
            return Integer.parseInt(configured.trim());
        }
        return DEFAULT_PORT + channel;
    }

    /**
     * Moves this channel to another port. Players already on it stay
     * connected; the next one to log in or change channel is sent the new
     * port. A port that cannot be bound throws and leaves the old one serving.
     */
    public final synchronized void rebind(final int newPort) throws IOException {
        if (newPort == port || shutdown) {
            return;
        }
        boundAddress = MapleAcceptor.rebind(boundAddress, newPort, serverHandler);
        port = (short) newPort;
        ip = ServerProperties.getProperty("tms.IP") + ":" + newPort;
        LOG.log(System.Logger.Level.INFO, "Channel " + channel + ": Listening on port " + newPort + "");
    }

    public final void setTempFlag(final int flags) {
        this.flags = flags;
    }

    public final void setAdminOnly(final boolean adminOnly) {
        this.adminOnly = adminOnly;
    }

    public final int getMesoRate() {
        return mesoRate;
    }

    public final void setMesoRate(final int mesoRate) {
        this.mesoRate = mesoRate;
    }

    public final int getDropRate() {
        return dropRate;
    }

    public final void setDropRate(final int dropRate) {
        this.dropRate = dropRate;
    }

    public static final void startChannel_Main() {
        serverStartTime = System.currentTimeMillis();

        for (int i = 0; i < Integer.parseInt(ServerProperties.getProperty("tms.Count", "0")); i++) {
            newInstance(i + 1).run_startup_configurations();
        }
    }

    public static final void startChannel(final int channel)
    {
        serverStartTime = System.currentTimeMillis();
        for (int i = 0; i < Integer.parseInt(ServerProperties.getProperty("tms.Count", "0")); i++) {
            if(channel == i + 1) {
                newInstance(i + 1).run_startup_configurations();
                break;
            }
        }
    }

    /**
     * A snapshot of this channel's squads, not a live view.
     *
     * <p>It used to hand back {@code unmodifiableMap(mapleSquads)}, which is a
     * wrapper around the live map: {@link ConcurrentEnumMap} locks each call
     * but the caller then iterates outside that lock, so
     * {@code new ArrayList<>(getAllSquads().values())} in {@code !clearsquads}
     * could walk straight into a {@code ConcurrentModificationException} if a
     * squad was registered or expired mid-copy. Copying under
     * {@link #squadLock} - which every mutator below now takes - gives the
     * caller a map that cannot change under it.
     */
    public Map<MapleSquadType, MapleSquad> getAllSquads() {
        final Map<MapleSquadType, MapleSquad> snapshot;
        squadLock.readLock().lock();
        try {
            snapshot = new EnumMap<>(mapleSquads);
        } finally {
            squadLock.readLock().unlock();
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public final MapleSquad getMapleSquad(final String type) {
        return getMapleSquad(MapleSquadType.valueOf(type.toLowerCase()));
    }

    public final MapleSquad getMapleSquad(final MapleSquadType type) {
        // A single get, which ConcurrentEnumMap already takes its own read lock
        // for; squadLock is only needed where a test and a set have to be one
        // step. Callers that act on the answer must expect it to be stale by the
        // time they do - addMapleSquad is the only thing that may decide a race.
        return mapleSquads.get(type);
    }

    /**
     * Claim this channel's slot for one squad type, or report that somebody
     * else got there first.
     *
     * <p>This was {@code containsKey} followed by {@code put}. Both calls lock -
     * {@code mapleSquads} is a {@link ConcurrentEnumMap} - but the *pair* did
     * not, and {@code Map.putIfAbsent}'s default get-then-put, which
     * {@code ConcurrentEnumMap} does not override, would have been no better.
     * Two leaders talking to the same squad NPC on two handler threads could
     * both be told they had won the slot, and the return value is not
     * advisory: {@link MapleSquad#copy()} and
     * {@code NPCConversationManager.registerSquad} both throw a squad away on
     * {@code false}, so a lost race that reports {@code true} leaves two live
     * squads believing they own one boss map.
     *
     * <p>{@link #squadLock} makes the test and the set one step. Nothing inside
     * it broadcasts or borrows a database connection, which is what CLAUDE.md
     * forbids holding a lock across: {@code scheduleRemoval()} only hands a task
     * to {@code Timer.ETC} and has been moved out of the lock anyway, and both
     * callers send their {@code broadcastMessage} pair after this method has
     * already returned. The window between the {@code put} and
     * {@code scheduleRemoval()} - a squad visible with a null {@code removal} -
     * is exactly the window the old code had, since {@code put} released the
     * map's internal lock before returning too.
     */
    public final boolean addMapleSquad(final MapleSquad squad, final String type) {
        final MapleSquadType types = MapleSquadType.valueOf(type.toLowerCase());
        squadLock.writeLock().lock();
        try {
            if (mapleSquads.containsKey(types)) {
                return false;
            }
            mapleSquads.put(types, squad);
        } finally {
            squadLock.writeLock().unlock();
        }
        squad.scheduleRemoval();
        return true;
    }

    /**
     * Give up this channel's slot for one squad type - but only if {@code squad}
     * is still the squad holding it.
     *
     * <p>The identity check is the other half of the race above, and it is not
     * decoration. Both callers of {@link #addMapleSquad} build their squad
     * first and register it second, and both call {@code clear()} on it when
     * registration fails. {@code clear()} ends in here, and the old
     * remove-by-key removed whatever was in the slot - which, on a lost race,
     * is the squad that had just won it. The loser evicted the winner.
     *
     * <p>The only caller is {@link MapleSquad#clear()}, so the one-argument form
     * this replaced had no other users to keep.
     */
    public final boolean removeMapleSquad(final MapleSquadType types, final MapleSquad squad) {
        if (types == null) {
            return false;
        }
        squadLock.writeLock().lock();
        try {
            if (mapleSquads.get(types) != squad) {
                return false;
            }
            return mapleSquads.remove(types) != null;
        } finally {
            squadLock.writeLock().unlock();
        }
    }

    public final void closeAllMerchant() {
        merchLock.writeLock().lock();
        try {
            // 這裡不能用 removeIf：迴圈本體除了移除之外還要 closeShop(true, false)，
            // 那會存檔、取消排程、廣播拆店，是實實在在的副作用，放進 predicate 會很糟。
            // 第二個參數刻意是 false —— closeShop 若自己再呼叫 removeMerchant，
            // 就會在走訪途中動到 merchants，這個 iterator 才是唯一的移除者。
            final Iterator<HiredMerchant> merchants_ = merchants.values().iterator();
            while (merchants_.hasNext()) {
                merchants_.next().closeShop(true, false);
                merchants_.remove();
            }
        } finally {
            merchLock.writeLock().unlock();
        }
    }

    public final int addMerchant(final HiredMerchant hMerchant) {
        merchLock.writeLock().lock();

        int runningmer = 0;
        try {
            runningmer = running_MerchantID;
            merchants.put(running_MerchantID, hMerchant);
            running_MerchantID++;
        } finally {
            merchLock.writeLock().unlock();
        }
        return runningmer;
    }

    public final void removeMerchant(final HiredMerchant hMerchant) {
        merchLock.writeLock().lock();

        try {
            merchants.remove(hMerchant.getStoreId());
        } finally {
            merchLock.writeLock().unlock();
        }
    }

    public final boolean containsMerchant(final int accid) {
        boolean contains = false;

        merchLock.readLock().lock();
        try {
            for (HiredMerchant hm : merchants.values()) {
                if (hm.getOwnerAccId() == accid) {
                    contains = true;
                    break;
                }
            }
        } finally {
            merchLock.readLock().unlock();
        }
        return contains;
    }

    public final List<HiredMerchant> searchMerchant(final int itemSearch) {
        final List<HiredMerchant> list = new LinkedList<>();
        merchLock.readLock().lock();
        try {
            for (HiredMerchant hm : merchants.values()) {
                if (hm.searchItem(itemSearch).size() > 0) {
                    list.add(hm);
                }
            }
        } finally {
            merchLock.readLock().unlock();
        }
        return list;
    }

    public final void toggleMegaphoneMuteState() {
        this.MegaphoneMuteState = !this.MegaphoneMuteState;
    }

    public final boolean getMegaphoneMuteState() {
        return MegaphoneMuteState;
    }

    public int getEvent() {
        return eventmap;
    }

    public final void setEvent(final int ze) {
        this.eventmap = ze;
    }

    public MapleEvent getEvent(final MapleEventType t) {
        return events.get(t);
    }

    public final Collection<PlayerNPC> getAllPlayerNPC() {
        return playerNPCs.values();
    }

    public final PlayerNPC getPlayerNPC(final int id) {
        return playerNPCs.get(id);
    }

    public final void addPlayerNPC(final PlayerNPC npc) {
        // remove() instead of containsKey() + remove(): only the thread that
        // actually took the old entry out may touch the map object.
        if (playerNPCs.remove(npc.getId()) != null) {
            getMapFactory().getMap(npc.getMapId()).removeMapObject(npc);
        }
        playerNPCs.put(npc.getId(), npc);
        getMapFactory().getMap(npc.getMapId()).addMapObject(npc);
    }

    public final void removePlayerNPC(final PlayerNPC npc) {
        if (playerNPCs.remove(npc.getId()) != null) {
            getMapFactory().getMap(npc.getMapId()).removeMapObject(npc);
        }
    }

    public final String getServerName() {
        return serverName;
    }

    public final void setServerName(final String sn) {
        this.serverName = sn;
    }

    public final int getPort() {
        return port;
    }

    public static final Set<Integer> getChannelServer() {
        return new HashSet<>(instances.keySet());
    }

    public final void setShutdown() {
        this.shutdown = true;
        LOG.log(System.Logger.Level.INFO, "Channel " + channel + " has set to shutdown.");
    }

    public final void setFinishShutdown() {
        this.finishedShutdown = true;
        LOG.log(System.Logger.Level.INFO, "Channel " + channel + " has finished shutdown.");
    }

    public final boolean isAdminOnly() {
        return adminOnly;
    }

    public final static int getChannelCount() {
        return instances.size();
    }

    public final MapleServerHandler getServerHandler() {
        return serverHandler;
    }

    public final int getTempFlag() {
        return flags;
    }

    /**
     * What the login screen lists. A channel that is shutting down - one being
     * retired by {@link #setChannelCount}, or every channel during a server
     * shutdown - is left out, so nobody picks a channel that is going away.
     */
    public static Map<Integer, Integer> getChannelLoad() {
        Map<Integer, Integer> ret = new HashMap<>();
        for (ChannelServer cs : instances.values()) {
            if (!cs.isShutdown()) {
                ret.put(cs.getChannel(), cs.getConnectedClients());
            }
        }
        return ret;
    }

    public int getConnectedClients() {
        return getPlayerStorage().getConnectedClients();
    }

    public List<CheaterData> getCheaters() {
        List<CheaterData> cheaters = getPlayerStorage().getCheaters();

        Collections.sort(cheaters);
        return CollectionUtil.copyFirst(cheaters, 20);
    }

    public void broadcastMessage(byte[] message) {
        broadcastPacket(new ByteArrayMaplePacket(message));
    }

    public void broadcastSmega(byte[] message) {
        broadcastSmegaPacket(new ByteArrayMaplePacket(message));
    }

    public void broadcastGMMessage(byte[] message) {
        broadcastGMPacket(new ByteArrayMaplePacket(message));
    }

    /**
     * 把這個頻道上線中的角色逐一寫回資料庫，回傳實際存了幾個。
     *
     * <p><b>為什麼這樣是安全的</b>：{@code getAllCharacters()} 回傳的是快照（複製完
     * 就放掉 {@code PlayerStorage} 的讀鎖），所以整個迴圈跑完都不持有任何鎖——這正是
     * CLAUDE.md「不要在借資料庫連線時握著鎖」那條規則要的形狀。迴圈是循序的：一次只
     * 借一條連線、存完還回去再借下一條，所以 N 個頻道同時跑最多也只佔用 N 條連線
     * （{@code tms.DbPoolSize} 預設 64），不會把連線池吃乾。
     *
     * <p>快照的代價是：存檔途中登出的角色會被多存一次（{@code saveToDB} 是冪等的
     * UPDATE，無害），存檔途中登入的角色這一輪會被跳過，等下一輪。
     *
     * <p>{@code saveToDB(false, false)} 的第二個參數是 {@code fromcs}：傳 false 才會
     * 寫入角色「現在所在地圖」，傳 true 寫的是 {@code chr.mapid} 這個只在換頻道／進
     * 商城時才更新的欄位。頻道上的角色一律用 false。
     */
    public int saveAll() {
        int ppl = 0;
        for (MapleCharacter chr : this.players.getAllCharacters()) {
            ++ppl;
            chr.saveToDB(false, false);
        }
        LOG.log(System.Logger.Level.INFO, "[存檔] 已經將頻道 " + this.channel + " 的 " + ppl + " 個玩家保存到數據中.");
        return ppl;
    }

    /**
     * 依 {@code tms.AutoSaveMinutes} 掛上定期存檔，0（預設）代表不啟用。
     *
     * <p>這個機制以前只存在於 {@code Libs/scripts/event/AutoSave.js}：它靠
     * {@code tms.Events} 被載入、用 {@code em.scheduleAtTimestamp} 排在每個小時的
     * 10 分，間隔寫死也不可設定，而且 {@code tms.Events} 裡寫的是 {@code autoSave}、
     * 檔名卻是 {@code AutoSave.js}——在 Windows 上大小寫不敏感所以剛好載得到，換到
     * 大小寫敏感的檔案系統就會被「找不到就靜靜跳過」的規則吃掉，自動存檔隨之消失而
     * 且沒有任何訊息。這裡把同一件事搬回 Java，並且預設關閉，以免和還在跑的
     * AutoSave.js 疊成一分鐘內存兩次。
     *
     * <p>用 {@code Timer.ETC} 而不是 {@code Timer.WORLD}：{@code Timer.WORLD} 上目前只有
     * 每 3 秒一次的 {@code Respawn}，而存檔是會阻塞在資料庫上的長工作，塞進去會排擠
     * 怪物重生；{@code Timer.ETC} 本來就是放釣魚、精靈吊墜、雇傭商人到期這類雜項長尾
     * 工作的池子。
     */
    private void startAutoSave() {
        final int minutes = autoSaveMinutes();
        if (minutes <= 0) {
            return;
        }
        final long period = minutes * 60000L;
        // 第一次延遲一個完整週期：伺服器剛起來時沒有人在線上，立刻存一次沒有意義。
        autoSaveTask = Timer.ETC.register(this::saveAll, period, period);
        LOG.log(System.Logger.Level.INFO, "Channel " + channel + ": 自動存檔每 " + minutes + " 分鐘一次.");
    }

    private void stopAutoSave() {
        final ScheduledFuture<?> task = autoSaveTask;
        if (task != null) {
            autoSaveTask = null;
            // false：正在寫資料庫的那一輪讓它寫完，不要從中間打斷。
            task.cancel(false);
        }
    }

    /**
     * 重新讀 {@code tms.AutoSaveMinutes} 並換上新的排程：舊排程以
     * {@code cancel(false)} 取消（正在存的那一輪照樣存完），新的間隔從現在起算。
     * 關閉中的頻道不再掛排程。
     */
    public final synchronized void restartAutoSave() {
        stopAutoSave();
        if (!shutdown) {
            startAutoSave();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 頻道數的即時調整                                                     */
    /* ------------------------------------------------------------------ */

    /** How long {@link #retire} waits for players to leave before closing their sessions outright. */
    private static final long RETIRE_GRACE_MILLIS = 10_000;
    /** How long it waits in all before it stops waiting. */
    private static final long RETIRE_GIVE_UP_MILLIS = 30_000;
    private static final long RETIRE_POLL_MILLIS = 1_000;

    /**
     * Grows or shrinks the set of running channels to {@code target}, while
     * the server runs.
     *
     * <p>Growing starts each missing channel exactly the way startup does -
     * maps, event scripts, port, auto-save. Shrinking retires the
     * highest-numbered channels (so the remaining ones stay 1..target, which
     * the "next channel" logic in {@code InventoryHandler} assumes): see
     * {@link #retire}. Retirement finishes on a timer, so this returns before
     * the channel is fully gone; a channel that is still retiring cannot be
     * started again until it is.
     *
     * @return a one-line summary for the operator
     * @throws IllegalStateException when a channel that would have to be
     * started is still retiring, or a new one cannot bind its port
     */
    public static synchronized String setChannelCount(final int target) {
        if (target < 1) {
            throw new IllegalArgumentException("頻道數至少要是 1");
        }
        int current = 0;
        for (final ChannelServer cs : instances.values()) {
            if (!cs.isShutdown()) {
                current = Math.max(current, cs.getChannel());
            }
        }
        if (target > current) {
            for (int ch = current + 1; ch <= target; ch++) {
                if (instances.containsKey(ch)) {
                    throw new IllegalStateException("頻道 " + ch + " 還在關閉中，等它關完（最多約 "
                            + RETIRE_GIVE_UP_MILLIS / 1000 + " 秒）再加回來");
                }
            }
            final List<Integer> started = new ArrayList<>();
            for (int ch = current + 1; ch <= target; ch++) {
                final ChannelServer cs = newInstance(ch);
                String failure = null;
                try {
                    cs.run_startup_configurations();
                    if (cs.boundAddress == null) {
                        // run_startup_configurations has already logged why.
                        failure = "無法綁定 port " + cs.port;
                    }
                } catch (final RuntimeException e) {
                    failure = String.valueOf(e.getCause() == null ? e : e.getCause());
                }
                if (failure != null) {
                    // Undo the registration run_startup_configurations makes
                    // first thing, so the login screen does not list it.
                    cs.shutdown = true;
                    cs.stopAutoSave();
                    cs.getPlayerStorage().close();
                    instances.remove(ch);
                    LoginServer.removeChannel(ch);
                    throw new IllegalStateException("頻道 " + ch + " 開啟失敗：" + failure
                            + (started.isEmpty() ? "" : "（已開啟 " + started + "）"));
                }
                started.add(ch);
            }
            return "已開啟頻道 " + started;
        }
        if (target < current) {
            final List<Integer> retiring = new ArrayList<>();
            for (int ch = target + 1; ch <= current; ch++) {
                final ChannelServer cs = instances.get(ch);
                if (cs != null && !cs.isShutdown()) {
                    cs.retire(1);
                    retiring.add(ch);
                }
            }
            return "正在關閉頻道 " + retiring + "，線上玩家會被移到頻道 1";
        }
        return "頻道數不變（" + current + "）";
    }

    /**
     * The channel a client should be sent to when it asked for
     * {@code wanted}: that one if it is serving, otherwise the lowest-numbered
     * one that is, or null if none is.
     *
     * <p>A channel can now go away under a client that picked it - from the
     * login screen a moment before, or before it walked into the cash shop -
     * and every caller below used to dereference {@code getInstance()} blind.
     */
    public static ChannelServer servingOrFallback(final int wanted) {
        final ChannelServer cs = instances.get(wanted);
        if (cs != null && !cs.isShutdown()) {
            return cs;
        }
        ChannelServer best = null;
        for (final ChannelServer other : instances.values()) {
            if (!other.isShutdown() && (best == null || other.getChannel() < best.getChannel())) {
                best = other;
            }
        }
        return best;
    }

    /**
     * Takes this one channel out of service while every other part of the
     * server keeps running.
     *
     * <p>Unlike {@link #shutdown(Object)}, which is the whole-server path and
     * deliberately skips the party / guild / buddy clean-up because everything
     * is going down anyway, the players here are handed to another channel the
     * same way a player's own channel change does it; anyone still here after
     * {@link #RETIRE_GRACE_MILLIS} has the session closed, which runs the full
     * disconnect clean-up. Marking the channel shut down first is what takes
     * it off the login screen and refuses channel changes into it.
     */
    private void retire(final int fallback) {
        shutdown = true;
        LoginServer.removeChannel(channel);
        stopAutoSave();
        LOG.log(System.Logger.Level.INFO, "Channel " + channel + ": 關閉中，線上玩家移往頻道 " + fallback + ".");
        broadcastPacket(ChatPacket.serverNotice(0, "頻道 " + channel + " 即將關閉，你會被移到頻道 " + fallback + "。"));
        closeAllMerchant();
        for (final MapleCharacter chr : getPlayerStorage().getAllCharacters()) {
            try {
                chr.changeChannel(fallback);
            } catch (final RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Channel " + channel + ": 無法把 " + chr.getName()
                        + " 移到頻道 " + fallback + "，改為中斷連線：" + e);
                chr.getClient().getSession().close();
            }
        }
        Timer.ETC.schedule(() -> finishRetire(System.currentTimeMillis()), RETIRE_POLL_MILLIS);
    }

    private void finishRetire(final long since) {
        final long waited = System.currentTimeMillis() - since;
        if (getPlayerStorage().getConnectedClients() > 0 && waited < RETIRE_GIVE_UP_MILLIS) {
            if (waited >= RETIRE_GRACE_MILLIS) {
                for (final MapleCharacter chr : getPlayerStorage().getAllCharacters()) {
                    chr.getClient().getSession().close();
                }
            }
            Timer.ETC.schedule(() -> finishRetire(since), RETIRE_POLL_MILLIS);
            return;
        }
        // Closes whatever sessions are still on the port. Their disconnect
        // clean-up runs on their own threads and looks this channel up by
        // number, so the instance stays registered for a few more seconds.
        MapleAcceptor.unbind(boundAddress);
        boundAddress = null;
        synchronized (eventLock) {
            eventSM.cancel();
        }
        Timer.ETC.schedule(() -> {
            getPlayerStorage().close();
            instances.remove(channel);
            LoginServer.removeChannel(channel);
            setFinishShutdown();
        }, 5_000);
    }

    private static int autoSaveMinutes() {
        final String configured = ServerProperties.getProperty("tms.AutoSaveMinutes", "0");
        if (configured == null || configured.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (final NumberFormatException e) {
            // WARNING：伺服器照跑，只是自動存檔維持關閉——而「靜靜地沒有自動存檔」
            // 正是這個項目要修掉的毛病，所以要講出來。
            LOG.log(System.Logger.Level.WARNING, "tms.AutoSaveMinutes 不是數字（" + configured + "），自動存檔維持關閉.");
            return 0;
        }
    }
 }

