package server;

import java.sql.SQLException;
import java.util.List;

import database.DatabaseConnection;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.world.Alliance;
import handling.world.Family;
import handling.world.Guild;
import handling.world.World;
import server.panel.PanelServer;

/**
 * The body of the JVM shutdown hook: stop accepting, save the world, then let
 * go of the database.
 *
 * <p>The order used to be wrong in a way that lost writes. {@code closeAll()}
 * ran while the channel shutdowns it had just kicked off were still saving
 * characters on other threads, and the timer pools - seven of the nine, the
 * other two never having been started - were stopped afterwards, so scheduled
 * saves could still be firing into a closed database. Everything that writes
 * now finishes first, and the pool is the last thing to go.
 */
public class ShutdownServer implements Runnable {

    private static final ShutdownServer instance = new ShutdownServer();
    /** How long to let in-flight character saves finish before pulling the plug. */
    private static final long DRAIN_MILLIS = 5000;

    public static volatile boolean running = false;

    public static ShutdownServer getInstance() {
        return instance;
    }

    @Override
    public void run() {
        synchronized (this) {
            if (running) { //Run once!
                return;
            }
            running = true;
        }
        World.isShutDown = true;

        // 0. The panel first, before anything below starts tearing down what
        //    its routes read - and well before step 6, which a request that
        //    arrived during the drain would otherwise run into.
        PanelServer.stop();

        // 1. Stop accepting anything new.
        try {
            for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                cs.setShutdown();
            }
            LoginServer.shutdown();
        } catch (final Exception e) {
            e.printStackTrace();
        }

        // 2. Disconnect and save every player, channel by channel. Over a
        //    snapshot: shutting a channel down deregisters it.
        for (final int channel : List.copyOf(ChannelServer.getAllInstance())) {
            try {
                ChannelServer.getInstance(channel).shutdown(this);
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }

        // 3. Flush the cross-channel state that only lives in memory.
        try {
            Guild.save();
            Alliance.save();
            Family.save();
        } catch (final Exception e) {
            System.err.println("THROW" + e);
        }

        // 4. No more scheduled work - including the two pools the old
        //    hand-written list here never knew about.
        Timer.stopAll();

        // 5. Give whatever is still mid-save a moment to land.
        try {
            Thread.sleep(DRAIN_MILLIS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 6. Only now is nothing left that could want a connection.
        try {
            DatabaseConnection.closeAll();
        } catch (final SQLException e) {
            System.err.println("THROW" + e);
        }

        System.exit(0); //not sure if this is really needed for ChannelServer
    }
}
