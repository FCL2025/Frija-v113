package server;

import client.SkillFactory;
import handling.MapleServerHandler;
import handling.channel.ChannelServer;
import handling.channel.MapleGuildRanking;
import handling.login.LoginServer;
import handling.cashshop.CashShopServer;
import handling.login.LoginInformationProvider;
import handling.world.World;
import java.sql.Connection;
import java.sql.SQLException;
import database.DatabaseConnection;
import handling.world.family.MapleFamilyBuff;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import scripting.ScriptEncodingCheck;
import server.Timer.BuffTimer;
import server.Timer.CheatTimer;
import server.Timer.CloneTimer;
import server.Timer.EtcTimer;
import server.Timer.EventTimer;
import server.Timer.MapTimer;
import server.Timer.MobTimer;
import server.Timer.WorldTimer;
import server.events.MapleOxQuizFactory;
import server.life.MapleLifeFactory;
import server.panel.PanelServer;
import server.quest.MapleQuest;
import tools.Encodings;
import tools.FileoutputUtil;

public class Start {

    /**
     * The pilot for the tree's one logging mechanism - see {@code tools.Logging}
     * for how a logger is named, which level replaces which of the four things
     * this server used to report with, and what must not be converted.
     *
     * <p>Everything here stays {@code INFO} and therefore stays on the console
     * exactly as it printed before: the startup banner is read by the person
     * waiting for the server to come up, and {@code tools.Logging.Console}
     * writes an {@code INFO} record to {@code System.out} as the bare message,
     * in {@code Encodings.NATIVE}, with nothing prefixed.
     */
    private static final System.Logger LOG = System.getLogger(Start.class.getName());

    /** How many bad scripts the startup scan lists before it summarises the rest. */
    private static final int MAX_BAD_SCRIPTS_PRINTED = 30;

    /** When the phase currently being timed began. */
    private static long phaseStart = System.nanoTime();

    /**
     * Prints how long the phase that just finished took, and starts the clock
     * for the next one. Cheap enough to leave on: a startup that suddenly gets
     * slower says so on the next run instead of having to be bisected.
     *
     * <p>The labels are Chinese, so the column is padded by how wide the name
     * prints rather than by how many characters it has - {@code %-20s} would
     * count a full-width character as one column and stagger every line.
     */
    private static void phase(final String name) {
        final long now = System.nanoTime();
        int width = 0;
        for (int i = 0; i < name.length(); i++) {
            width += name.charAt(i) > 0x7F ? 2 : 1;
        }
        final StringBuilder pad = new StringBuilder();
        for (int i = width; i < 20; i++) {
            pad.append(' ');
        }
        LOG.log(System.Logger.Level.INFO,
                "[啟動] " + name + pad + String.format("%6d", (now - phaseStart) / 1000000L) + " ms");
        phaseStart = now;
    }

    /**
     * Names the scripts that are not BIG5, because nothing else will.
     *
     * <p>A script saved as UTF-8 is read as an empty file and the NPC it
     * belongs to simply stops answering - no exception the player or the
     * administrator ever sees. Two batches of them (nine portal scripts, eight
     * NPC scripts) lived in this tree for years that way. The scan costs about
     * 0.4 s for ~2,450 files, so it runs on every start; it never blocks or
     * fails one, and it says nothing at all when every script is fine.
     *
     * <p>This is deliberately {@code scanEncoding()} and not
     * {@link ScriptEncodingCheck#scan()}: the latter also hands every script
     * to Nashorn, which finds a second family of silently-dead scripts (six of
     * them in this tree) but costs 2.5-4 s and a few hundred MB of short-lived
     * allocation. That is more than half of this server's whole startup, for a
     * fault that cannot appear between two runs of the same source tree, so
     * the compile half lives on {@code !checkscripts} and
     * {@code GET /api/scripts/encoding} instead.
     */
    private static void checkScriptEncoding() {
        try {
            final ScriptEncodingCheck.Result result = ScriptEncodingCheck.scanEncoding();
            if (result.ok()) {
                return;
            }
            // One record, not one per line: the level prefix would otherwise
            // repeat down the block, and another thread could log into the
            // middle of it.
            final String nl = System.lineSeparator();
            final StringBuilder sb = new StringBuilder(256);
            sb.append("[腳本] ").append(result.total()).append(" 支腳本中有 ").append(result.badCount())
                    .append(" 支不是 BIG5，將不會生效：");
            int shown = 0;
            for (final String path : result.bad()) {
                if (shown >= MAX_BAD_SCRIPTS_PRINTED) {
                    sb.append(nl).append("      …以及其他 ").append(result.badCount() - shown)
                            .append(" 支（完整清單請用 !checkscripts 或管理面板 /api/scripts/encoding）");
                    break;
                }
                sb.append(nl).append("      ").append(path);
                shown++;
            }
            LOG.log(System.Logger.Level.WARNING, sb.toString());
        } catch (final Exception e) {
            // Deliberately terse: this one always reported e.toString() and
            // never a trace, so the Throwable is not attached.
            LOG.log(System.Logger.Level.WARNING, "[腳本] 編碼檢查失敗（不影響啟動）：" + e);
        }
    }

    public final static void main(final String args[]) {
        FileoutputUtil.ensureLogDirs();
        if (Boolean.parseBoolean(ServerProperties.getProperty("tms.Admin"))) {
            LOG.log(System.Logger.Level.INFO, "[!!! Admin Only Mode Active !!!]");
        }
        if (Boolean.parseBoolean(ServerProperties.getProperty("tms.AutoRegister"))) {
            LOG.log(System.Logger.Level.INFO, "Loading Autoregister mode :::");
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE accounts SET loggedin = 0")) {
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new RuntimeException("[EXCEPTION] Please check if the SQL server is active.", ex);
        }
        phase("資料庫連線");

        World.init();
        WorldTimer.getInstance().start();
        EtcTimer.getInstance().start();
        MapTimer.getInstance().start();
        MobTimer.getInstance().start();
        CloneTimer.getInstance().start();
        EventTimer.getInstance().start();
        BuffTimer.getInstance().start();
        phase("世界與計時器");

        LoginInformationProvider.getInstance();
        MapleQuest.initQuests();
        MapleLifeFactory.loadQuestCounts();
        MapleItemInformationProvider.getInstance().load();
        RandomRewards.getInstance();
        SkillFactory.getSkill(99999999);
        MapleOxQuizFactory.getInstance().initialize();
        MapleCarnivalFactory.getInstance();
        phase("遊戲資料檔");

        checkScriptEncoding();
        phase("腳本編碼檢查");

        MapleGuildRanking.getInstance().getRank();
        MapleFamilyBuff.getBuffEntry();
        MapleServerHandler.registerMBean();
        RankingWorker.getInstance().run();
        phase("排行榜與家族");

        CashItemFactory.getInstance().initialize();
        phase("購物商城道具");

        LoginServer.run_startup_configurations();
        phase("登入伺服器");

        ChannelServer.startChannel_Main();
        phase("頻道伺服器");

        LOG.log(System.Logger.Level.INFO, "[購物商城伺服器啟動中]");
        CashShopServer.run_startup_configurations();
        LOG.log(System.Logger.Level.INFO, "[購物商城伺服器啟動完成]");
        CheatTimer.getInstance().register(AutobanManager.getInstance(), 60000);
        Runtime.getRuntime().addShutdownHook(new Thread(new Shutdown()));
        try {
            SpeedRunner.getInstance().loadSpeedRuns();
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.ERROR, "讀取速通紀錄失敗，排行榜可能不完整", e);
        }
        World.registerRespawn();
        LoginServer.setOn();
        phase("購物商城伺服器");

        // Last, so that the first request it answers sees a fully started
        // server. It never throws: a port clash prints and the game goes on.
        PanelServer.start();
        phase("管理面板");

        // Uptime rather than a nanoTime taken in main(): it counts from the
        // moment the JVM was launched, which is what the person who ran
        // Launcher.bat actually waited for.
        final long startupMillis = ManagementFactory.getRuntimeMXBean().getUptime();
        PanelServer.setStartupMillis(startupMillis);
        LOG.log(System.Logger.Level.INFO, "Loaded Complete ::: 啟動完成於 "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "，共花費 "
                + String.format("%.2f", startupMillis / 1000.0) + " 秒");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in, Encodings.NATIVE));
    }

    public static class Shutdown implements Runnable {

        @Override
        public void run() {
            new Thread(ShutdownServer.getInstance()).start();
        }
    }
}
