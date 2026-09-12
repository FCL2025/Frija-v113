package server;

import client.SkillFactory;
import constants.ServerConstants;
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
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;
import server.Timer.*;
import server.events.MapleOxQuizFactory;
import server.life.MapleLifeFactory;
import server.panel.PanelServer;
import server.quest.MapleQuest;
import tools.Encodings;
import tools.FileoutputUtil;

public class Start {

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
//        ItemMakerFactory.getInstance();
        MapleItemInformationProvider.getInstance().load();
        RandomRewards.getInstance();
        SkillFactory.getSkill(99999999);
        MapleOxQuizFactory.getInstance().initialize();
        MapleCarnivalFactory.getInstance();
        phase("遊戲資料檔");

        MapleGuildRanking.getInstance().getRank();
        MapleFamilyBuff.getBuffEntry();
        MapleServerHandler.registerMBean();
        RankingWorker.getInstance().run();
        // MTSStorage.load();
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
