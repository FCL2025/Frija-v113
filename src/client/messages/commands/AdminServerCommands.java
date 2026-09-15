package client.messages.commands;

import client.MapleCharacter;
import constants.ServerConstants.PlayerGMRank;
import client.MapleClient;
import handling.MaplePacket;
import handling.MapleServerHandler;
import handling.RecvPacketOpcode;
import handling.SendPacketOpcode;
import handling.channel.ChannelServer;
import handling.world.Broadcast;
import handling.world.World;
import java.util.ArrayList;
import java.util.Collection;
import scripting.EventInstanceManager;
import scripting.EventManager;
import scripting.PortalScriptManager;
import scripting.ReactorScriptManager;
import scripting.ScriptCache;
import scripting.ScriptEncodingCheck;
import server.MapleShopFactory;
import server.MapleSquad;
import server.ShutdownServer;
import server.Timer;
import server.events.MapleEvent;
import server.events.MapleEventType;
import server.life.MapleMonsterInformationProvider;
import server.quest.MapleQuest;
import tools.StringUtil;
import java.util.concurrent.ScheduledFuture;
import tools.packet.ChatPacket;

/**
 * ADMIN 指令：伺服器層級的公告、重載、關機、頻道倍率與活動。由 AdminCommand 拆出。
 *
 * @author Emilyx3
 */
public class AdminServerCommands {

    private static final System.Logger LOG = System.getLogger(AdminServerCommands.class.getName());

    public static PlayerGMRank getPlayerLevelRequired() {
        return PlayerGMRank.ADMIN;
    }

    public static class Shutdown_01 extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            for (handling.channel.ChannelServer cserv : handling.channel.ChannelServer.getAllInstances()) {
                cserv.closeAllMerchant();
            }
            c.getPlayer().dropMessage(6, "精靈商人儲存完畢.");
            return 1;

        }
    }

    public static class Shutdown_02 extends CommandExecute {

        private static Thread t = null;

        @Override
        public int execute(MapleClient c, String[] splitted) {
            c.getPlayer().dropMessage(6, "關閉中...");
            if (t == null || !t.isAlive()) {
                t = new Thread(server.ShutdownServer.getInstance());
                t.start();
            } else {
                c.getPlayer().dropMessage(6, "已在執行中...");
            }
            return 1;
        }
    }

    public static class ShutdownTime extends CommandExecute {

        private static ScheduledFuture<?> ts = null;
        private int minutesLeft = 0;
       private static Thread t = null;

        public int execute(MapleClient c, String[] splitted) {
            minutesLeft = Integer.parseInt(splitted[1]);
             c.getPlayer().dropMessage(6, "伺服器即將在" + minutesLeft + "分鐘後關閉. 請盡速關閉精靈商人 並下線.");
            if (ts == null && (t == null || !t.isAlive())) {
                t = new Thread(ShutdownServer.getInstance());
                ts = Timer.EVENT.register(() -> {
                    if (minutesLeft == 0) {
        ShutdownServer.getInstance();
                        t.start();
                        ts.cancel(false);
        return;
                    }
                    Broadcast.broadcastMessage(ChatPacket.serverNotice(0, "伺服器即將在 " + minutesLeft + "分鐘後關閉. 請盡速關閉精靈商人 並下線.").getBytes());;
                    LOG.log(System.Logger.Level.INFO, "伺服器即將在 " + minutesLeft + "分鐘後關閉.");
                    minutesLeft--;
                }, 60000);
            } else {
                c.getPlayer().dropMessage(6, "好吧真拿你沒辦法..伺服器關閉時間修改...請等待關閉完畢..請勿強制關閉服務器..否則後果自負!");
            }
            return 1;
        }
    }

    public static class saveall extends CommandExecute {
        /**
         * 計數以前是這個指令類別的 private 欄位。{@code CommandProcessor} 反射時每個
         * 指令類別只 new 一個實例並存進 map 重複使用，所以那個欄位從來不歸零——第二次
         * 打 {@code !saveall} 印出來的是累計值——而且兩個 GM 同時打還會互相加到對方的
         * 數字上。改成區域變數，一次呼叫一份計數。
         *
         * <p>迴圈本身也收斂到 {@link ChannelServer#saveAll()}：那是自動存檔走的同一條
         * 路，以前這裡自己抄了一份，而且第二個參數傳的是 {@code fromcs = true}，會把
         * 角色位置寫成 {@code chr.mapid}——那個欄位只在換頻道／進商城時更新，對頻道上
         * 的角色來說就是「登入時的地圖」。GM 看到的訊息不變。
         */
        @Override
        public int execute(MapleClient c, String[] splitted) {
            int p = 0;
            for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                p += cserv.saveAll();
            }
            c.getPlayer().dropMessage("[保存] "+p+"個玩家數據保存到數據中.");
            return 0;
        }
    }

    public static class ReloadIPMonitor extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleServerHandler.reloadLoggedIPs();
            return 1;
        }

    }

    public static class Say extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                sb.append(c.getPlayer().getName());
                sb.append("] ");
                sb.append(StringUtil.joinStringFrom(splitted, 1));
                Broadcast.broadcastMessage(ChatPacket.serverNotice(6, sb.toString()).getBytes());
            } else {
                c.getPlayer().dropMessage(6, "指令規則: !say <message>");
                return 0;
            }
            return 1;
        }
    }

    public static class StartAutoEvent extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            final EventManager em = c.getChannelServer().getEventSM().getEventManager("AutomatedEvent");
            if (em != null) {
                em.scheduleRandomEvent();
            }
            return 1;
        }
    }

    public static class SetEvent extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleEvent.onStartEvent(c.getPlayer());
            return 1;
        }
    }

    public static class StartEvent extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (c.getChannelServer().getEvent() == c.getPlayer().getMapId()) {
                MapleEvent.setEvent(c.getChannelServer(), false);
                c.getPlayer().dropMessage(5, "Started the event and closed off");
                return 1;
            } else {
                c.getPlayer().dropMessage(5, "!scheduleevent must've been done first, and you must be in the event map.");
                return 0;
            }
        }
    }

    public static class ScheduleEvent extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            final MapleEventType type = MapleEventType.getByString(splitted[1]);
            if (type == null) {
                final StringBuilder sb = new StringBuilder("Wrong 指令規則: ");
                for (MapleEventType t : MapleEventType.values()) {
                    sb.append(t.name()).append(",");
                }
                c.getPlayer().dropMessage(5, sb.toString().substring(0, sb.toString().length() - 1));
            }
            final String msg = MapleEvent.scheduleEvent(type, c.getChannelServer());
            if (msg.length() > 0) {
                c.getPlayer().dropMessage(5, msg);
                return 0;
            }
            return 1;
        }
    }

    public static class SpeakMega extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleCharacter victim = c.getChannelServer().getPlayerStorage().getCharacterByName(splitted[1]);
            Broadcast.broadcastSmega(ChatPacket.serverNotice(3, victim == null ? c.getChannel() : victim.getClient().getChannel(), victim == null ? splitted[1] : victim.getName() + " : " + StringUtil.joinStringFrom(splitted, 2), true).getBytes());
            return 1;
        }
    }

    public static class Speak extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleCharacter victim = c.getChannelServer().getPlayerStorage().getCharacterByName(splitted[1]);
            if (victim == null) {
                c.getPlayer().dropMessage(5, "找不到 '" + splitted[1]);
                return 0;
            } else {
                victim.getMap().broadcastMessage(ChatPacket.getChatText(victim.getId(), StringUtil.joinStringFrom(splitted, 2), victim.isGM(), 0));
            }
            return 1;
        }
    }

    public static class SpeakMap extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            for (MapleCharacter victim : c.getPlayer().getMap().getCharactersThreadsafe()) {
                if (victim.getId() != c.getPlayer().getId()) {
                    victim.getMap().broadcastMessage(ChatPacket.getChatText(victim.getId(), StringUtil.joinStringFrom(splitted, 1), victim.isGM(), 0));
                }
            }
            return 1;
        }
    }

    public static class SpeakChn extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            for (MapleCharacter victim : c.getChannelServer().getPlayerStorage().getAllCharacters()) {
                if (victim.getId() != c.getPlayer().getId()) {
                    victim.getMap().broadcastMessage(ChatPacket.getChatText(victim.getId(), StringUtil.joinStringFrom(splitted, 1), victim.isGM(), 0));
                }
            }
            return 1;
        }
    }

    public static class SpeakWorld extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                for (MapleCharacter victim : cserv.getPlayerStorage().getAllCharacters()) {
                    if (victim.getId() != c.getPlayer().getId()) {
                        victim.getMap().broadcastMessage(ChatPacket.getChatText(victim.getId(), StringUtil.joinStringFrom(splitted, 1), victim.isGM(), 0));
                    }
                }
            }
            return 1;
        }
    }

    public static class SendAllNote extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {

            if (splitted.length >= 1) {
                String text = StringUtil.joinStringFrom(splitted, 1);
                for (MapleCharacter mch : c.getChannelServer().getPlayerStorage().getAllCharacters()) {
                    c.getPlayer().sendNote(mch.getName(), text);
                }
            } else {
                c.getPlayer().dropMessage(6, "Use it like this, !sendallnote <text>");
                return 0;
            }
            return 1;
        }
    }

    public static class TMegaphone extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            World.toggleMegaphoneMuteState();
            c.getPlayer().dropMessage(6, "Megaphone state : " + (c.getChannelServer().getMegaphoneMuteState() ? "Enabled" : "Disabled"));
            return 1;
        }
    }

    public static class ExpRate extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length > 1) {
                final int rate = Integer.parseInt(splitted[1]);
                if (splitted.length > 2 && splitted[2].equalsIgnoreCase("all")) {
                    for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                        cserv.setExpRate(rate);
                    }
                } else {
                    c.getChannelServer().setExpRate(rate);
                }
                c.getPlayer().dropMessage(6, "Exprate has been changed to " + rate + "x");
            } else {
                c.getPlayer().dropMessage(6, "Syntax: !exprate <number> [all]");
            }
            return 1;
        }
    }

    public static class DropRate extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length > 1) {
                final int rate = Integer.parseInt(splitted[1]);
                if (splitted.length > 2 && splitted[2].equalsIgnoreCase("all")) {
                    for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                        cserv.setDropRate(rate);
                    }
                } else {
                    c.getChannelServer().setDropRate(rate);
                }
                c.getPlayer().dropMessage(6, "Drop Rate has been changed to " + rate + "x");
            } else {
                c.getPlayer().dropMessage(6, "Syntax: !droprate <number> [all]");
            }
            return 1;
        }
    }

    public static class MesoRate extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length > 1) {
                final int rate = Integer.parseInt(splitted[1]);
                if (splitted.length > 2 && splitted[2].equalsIgnoreCase("all")) {
                    for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                        cserv.setMesoRate(rate);
                    }
                } else {
                    c.getChannelServer().setMesoRate(rate);
                }
                c.getPlayer().dropMessage(6, "Meso Rate has been changed to " + rate + "x");
            } else {
                c.getPlayer().dropMessage(6, "Syntax: !mesorate <number> [all]");
            }
            return 1;
        }
    }

    public static class CashRate extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (splitted.length > 1) {
                final int rate = Integer.parseInt(splitted[1]);
                if (splitted.length > 2 && splitted[2].equalsIgnoreCase("all")) {
                    for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                        cserv.setCashRate(rate);
                    }
                } else {
                    c.getChannelServer().setCashRate(rate);
                }
                c.getPlayer().dropMessage(6, "Cash Rate has been changed to " + rate + "x");
            } else {
                c.getPlayer().dropMessage(6, "Syntax: !cashrate <number> [all]");
            }
            return 1;
        }
    }

    // 刻意保留：!listsquads 這個指令被停用了（CommandProcessor 只反射沒被註解掉的內部類別，
    // 所以整個類別註解起來就等於移除指令）。內容本身仍然成立——它用到的 MapleSquad 方法都還在——
    // 只是要復活的話得補上 import java.util.Map.Entry。
    /*    public static class ListSquads extends CommandExecute {

     @Override
     public int execute(MapleClient c, String[] splitted) {
     for (Entry<String, MapleSquad> squads : c.getChannelServer().getAllSquads().entrySet()) {
     c.getPlayer().dropMessage(5, "TYPE: " + squads.getKey() + ", Leader: " + squads.getValue().getLeader().getName() + ", status: " + squads.getValue().getStatus() + ", numMembers: " + squads.getValue().getSquadSize() + ", numBanned: " + squads.getValue().getBannedMemberSize());
     }
     return 1;
     }
     }*/
    public static class ClearSquads extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            final Collection<MapleSquad> squadz = new ArrayList<>(c.getChannelServer().getAllSquads().values());
            for (MapleSquad squads : squadz) {
                squads.clear();
            }
            return 1;
        }
    }

    public static class SetInstanceProperty extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            EventManager em = c.getChannelServer().getEventSM().getEventManager(splitted[1]);
            if (em == null || em.getInstances().size() <= 0) {
                c.getPlayer().dropMessage(5, "none");
            } else {
                em.setProperty(splitted[2], splitted[3]);
                for (EventInstanceManager eim : em.getInstances()) {
                    eim.setProperty(splitted[2], splitted[3]);
                }
            }
            return 1;
        }
    }

    public static class ListInstanceProperty extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            EventManager em = c.getChannelServer().getEventSM().getEventManager(splitted[1]);
            if (em == null || em.getInstances().size() <= 0) {
                c.getPlayer().dropMessage(5, "none");
            } else {
                for (EventInstanceManager eim : em.getInstances()) {
                    c.getPlayer().dropMessage(5, "Event " + eim.getName() + ", eventManager: " + em.getName() + " iprops: " + eim.getProperty(splitted[2]) + ", eprops: " + em.getProperty(splitted[2]));
                }
            }
            return 1;
        }
    }

    public static class ListInstances extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            EventManager em = c.getChannelServer().getEventSM().getEventManager(StringUtil.joinStringFrom(splitted, 1));
            if (em == null || em.getInstances().size() <= 0) {
                c.getPlayer().dropMessage(5, "none");
            } else {
                for (EventInstanceManager eim : em.getInstances()) {
                    c.getPlayer().dropMessage(5, "Event " + eim.getName() + ", charSize: " + eim.getPlayers().size() + ", dcedSize: " + eim.getDisconnected().size() + ", mobSize: " + eim.getMobs().size() + ", eventManager: " + em.getName() + ", timeLeft: " + eim.getTimeLeft() + ", iprops: " + eim.getProperties().toString() + ", eprops: " + em.getProperties().toString());
                }
            }
            return 1;
        }
    }

    public static class LeaveInstance extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (c.getPlayer().getEventInstance() == null) {
                c.getPlayer().dropMessage(5, "You are not in one");
            } else {
                c.getPlayer().getEventInstance().unregisterPlayer(c.getPlayer());
            }
            return 1;
        }
    }

    public static class StartInstance extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (c.getPlayer().getEventInstance() != null) {
                c.getPlayer().dropMessage(5, "You are in one");
            } else if (splitted.length > 2) {
                EventManager em = c.getChannelServer().getEventSM().getEventManager(splitted[1]);
                if (em == null || em.getInstance(splitted[2]) == null) {
                    c.getPlayer().dropMessage(5, "Not exist");
                } else {
                    em.getInstance(splitted[2]).registerPlayer(c.getPlayer());
                }
            } else {
                c.getPlayer().dropMessage(5, "!startinstance [eventmanager] [eventinstance]");
            }
            return 1;

        }
    }

    public static class EventInstance extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            if (c.getPlayer().getEventInstance() == null) {
                c.getPlayer().dropMessage(5, "none");
            } else {
                EventInstanceManager eim = c.getPlayer().getEventInstance();
                c.getPlayer().dropMessage(5, "Event " + eim.getName() + ", charSize: " + eim.getPlayers().size() + ", dcedSize: " + eim.getDisconnected().size() + ", mobSize: " + eim.getMobs().size() + ", eventManager: " + eim.getEventManager().getName() + ", timeLeft: " + eim.getTimeLeft() + ", iprops: " + eim.getProperties().toString() + ", eprops: " + eim.getEventManager().getProperties().toString());
            }
            return 1;
        }
    }

    public static class Notice extends CommandExecute {

        private static int getNoticeType(String typestring) {
            if (typestring.equals("n")) {
                return 0;
            } else if (typestring.equals("p")) {
                return 1;
            } else if (typestring.equals("l")) {
                return 2;
            } else if (typestring.equals("nv")) {
                return 5;
            } else if (typestring.equals("v")) {
                return 5;
            } else if (typestring.equals("b")) {
                return 6;
            }
            return -1;
        }

        @Override
        public int execute(MapleClient c, String[] splitted) {
            int joinmod = 1;
            int range = -1;
            if (splitted[1].equals("m")) {
                range = 0;
            } else if (splitted[1].equals("c")) {
                range = 1;
            } else if (splitted[1].equals("w")) {
                range = 2;
            }

            int tfrom = 2;
            if (range == -1) {
                range = 2;
                tfrom = 1;
            }
            int type = getNoticeType(splitted[tfrom]);
            if (type == -1) {
                type = 0;
                joinmod = 0;
            }
            StringBuilder sb = new StringBuilder();
            if (splitted[tfrom].equals("nv")) {
                sb.append("[Notice]");
            } else {
                sb.append("");
            }
            joinmod += tfrom;
            sb.append(StringUtil.joinStringFrom(splitted, joinmod));

            MaplePacket packet = ChatPacket.serverNotice(type, sb.toString());
            if (range == 0) {
                c.getPlayer().getMap().broadcastMessage(packet);
            } else if (range == 1) {
                ChannelServer.getInstance(c.getChannel()).broadcastPacket(packet);
            } else if (range == 2) {
                Broadcast.broadcastMessage(packet.getBytes());
            }
            return 1;
        }
    }

    public static class Yellow extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            int range = -1;
            if (splitted[1].equals("m")) {
                range = 0;
            } else if (splitted[1].equals("c")) {
                range = 1;
            } else if (splitted[1].equals("w")) {
                range = 2;
            }
            if (range == -1) {
                range = 2;
            }
            MaplePacket packet = ChatPacket.yellowChat((splitted[0].equals("!y") ? ("[" + c.getPlayer().getName() + "] ") : "") + StringUtil.joinStringFrom(splitted, 2));
            if (range == 0) {
                c.getPlayer().getMap().broadcastMessage(packet);
            } else if (range == 1) {
                ChannelServer.getInstance(c.getChannel()).broadcastPacket(packet);
            } else if (range == 2) {
                Broadcast.broadcastMessage(packet.getBytes());
            }
            return 1;
        }
    }

    public static class Y extends Yellow {
    }

    public static class ReloadOps extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            SendPacketOpcode.reloadValues();
            RecvPacketOpcode.reloadValues();
            return 1;
        }
    }

    public static class ReloadDrops extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleMonsterInformationProvider.getInstance().clearDrops();
            ReactorScriptManager.getInstance().clearDrops();
            return 1;
        }
    }

    public static class ReloadPortal extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            PortalScriptManager.getInstance().clearScripts();
            return 1;
        }
    }

    public static class ReloadShops extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleShopFactory.getInstance().clear();
            return 1;
        }
    }

    public static class ReloadEvents extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            // Before the channels rebuild their EventScriptManagers, not
            // after: they compile through ScriptCache, so a stale entry left
            // here would be handed straight back to the new managers and the
            // edited file would never take effect.
            ScriptCache.clearKind("event");
            for (ChannelServer instance : ChannelServer.getAllInstances()) {
                instance.reloadEvents();
            }
            return 1;
        }
    }

    public static class ReloadQuests extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            MapleQuest.clearQuests();
            // The WZ quest data and the quest scripts are two different
            // things, but nobody typing this means only one of them, and
            // before the scripts were cached the file was re-read on every
            // conversation anyway.
            ScriptCache.clearKind("quest");
            return 1;
        }
    }

    /**
     * Drops every compiled script, so the next player to run one re-reads the
     * file. This is what {@code !reloadportal} and {@code !reloadevents} do for
     * their own kind; NPC, quest and reactor scripts have no reload of their
     * own because they never needed one - until they were cached, every
     * conversation read the file off disk.
     *
     * <p>It does not touch a live conversation: those are per-MapleClient
     * scopes owned by the handler thread of the player they belong to, so a
     * player who is mid-dialogue finishes it in the old script and gets the
     * new one on the next one (or at once, with {@code @解卡}).
     */
    public static class ReloadScripts extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            PortalScriptManager.getInstance().clearScripts();
            final int dropped = ScriptCache.clear();
            c.getPlayer().dropMessage(6, "[腳本] 已清掉 " + dropped + " 支已編譯腳本，下次執行時重讀檔案。");
            return 1;
        }
    }

    /**
     * Re-runs the script health scan, and it is a superset of the one the
     * server does at startup: startup only strict-decodes BIG5 (~0.4 s), this
     * also hands every script to Nashorn for compilation (~4 s in total).
     * Both failures look the same in game - a script that is not BIG5 is read
     * as an empty file, and one that will not compile never defines its entry
     * point, so either way the NPC or quest just stops answering and
     * {@code Log_Script_Except.rtf} only says {@code No such function action}.
     * This is the way to find out that is what happened.
     *
     * <p>The sweep blocks this handler thread for those ~4 s, which is
     * harmless: MINA's {@code ExecutorFilter} runs one task per session at a
     * time, so only the GM who typed the command waits.
     */
    public static class CheckScripts extends CommandExecute {

        private static final int MAX_LISTED = 15;

        @Override
        public int execute(MapleClient c, String[] splitted) {
            final ScriptEncodingCheck.Result result = ScriptEncodingCheck.scan();
            if (result.ok()) {
                c.getPlayer().dropMessage(6, "[腳本] " + result.total() + " 支腳本都讀得到也編得過，沒有問題。");
                return 1;
            }
            c.getPlayer().dropMessage(6, "[腳本] " + result.total() + " 支腳本中有 " + result.badCount()
                    + " 支不會生效（不是 BIG5 " + result.notBig5().size()
                    + " 支、編不過 " + result.notCompiling().size() + " 支）：");
            int shown = 0;
            for (String path : result.bad()) {
                if (shown >= MAX_LISTED) {
                    c.getPlayer().dropMessage(6, "…以及其他 " + (result.badCount() - shown) + " 支（完整清單見伺服器 console 或管理面板）");
                    break;
                }
                c.getPlayer().dropMessage(6, path);
                shown++;
            }
            return 1;
        }
    }

    public static class ServerMessage extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            Collection<ChannelServer> cservs = ChannelServer.getAllInstances();
            String outputMessage = StringUtil.joinStringFrom(splitted, 1);
            for (ChannelServer cserv : cservs) {
                cserv.setServerMessage(outputMessage);
            }
            return 1;
        }
    }

    public static class ReloadMap extends CommandExecute {

        @Override
        public int execute(MapleClient c, String[] splitted) {
            final int mapId = Integer.parseInt(splitted[1]);
            for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                if (cserv.getMapFactory().isMapLoaded(mapId) && cserv.getMapFactory().getMap(mapId).getCharactersSize() > 0) {
                    c.getPlayer().dropMessage(5, "There exists characters on channel " + cserv.getChannel());
                    return 0;
                }
            }
            for (ChannelServer cserv : ChannelServer.getAllInstances()) {
                if (cserv.getMapFactory().isMapLoaded(mapId)) {
                    cserv.getMapFactory().removeMap(mapId);
                }
            }
            return 1;
        }
    }
}
