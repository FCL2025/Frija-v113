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
        System.out.println("[啟動] " + name + pad + String.format("%6d", (now - phaseStart) / 1000000L) + " ms");
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
            System.out.println("[腳本] " + result.total() + " 支腳本中有 " + result.badCount()
                    + " 支不是 BIG5，將不會生效：");
            int shown = 0;
            for (final String path : result.bad()) {
                if (shown >= MAX_BAD_SCRIPTS_PRINTED) {
                    System.out.println("      …以及其他 " + (result.badCount() - shown)
                            + " 支（完整清單請用 !checkscripts 或管理面板 /api/scripts/encoding）");
                    break;
                }
                System.out.println("      " + path);
                shown++;
            }
        } catch (final Exception e) {
            System.out.println("[腳本] 編碼檢查失敗（不影響啟動）：" + e);
        }
    }

    public final static void main(final String args[]) {
        FileoutputUtil.ensureLogDirs();
        if (Boolean.parseBoolean(ServerProperties.getProperty("tms.Admin"))) {
            System.out.println("[!!! Admin Only Mode Active !!!]");
        }
        if (Boolean.parseBoolean(ServerProperties.getProperty("tms.AutoRegister"))) {
            System.out.println("Loading Autoregister mode :::");
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

        System.out.println("[購物商城伺服器啟動中]");
        CashShopServer.run_startup_configurations();
        System.out.println("[購物商城伺服器啟動完成]");
        CheatTimer.getInstance().register(AutobanManager.getInstance(), 60000);
        Runtime.getRuntime().addShutdownHook(new Thread(new Shutdown()));
        try {
            SpeedRunner.getInstance().loadSpeedRuns();
        } catch (SQLException e) {
            e.printStackTrace();
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
        System.out.println("Loaded Complete ::: 啟動完成於 "
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
