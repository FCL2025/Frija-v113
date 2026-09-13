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
package handling.cashshop;

import java.net.InetSocketAddress;

import handling.MapleServerHandler;
import handling.channel.PlayerStorage;
import handling.mina.MapleAcceptor;
import server.MTSStorage;
import server.ServerProperties;

public class CashShopServer {

    private static final System.Logger LOG = System.getLogger(CashShopServer.class.getName());

    private static volatile String ip;
    private static volatile InetSocketAddress InetSocketadd;
    private final static int PORT = 8596;
    private static volatile PlayerStorage players, playersMTS;
    private static volatile boolean finishedShutdown = false;

    public static final void run_startup_configurations() {
        ip = ServerProperties.getProperty("tms.IP") + ":" + PORT;

        players = PlayerStorage.create(-10);
        playersMTS = PlayerStorage.create(-20);

        try {
            InetSocketadd = MapleAcceptor.bind(PORT, new MapleServerHandler(-1, true));
            LOG.log(System.Logger.Level.INFO, "Shop    1: Listening on port " + PORT);
        } catch (final Exception e) {
            // One record where there were two prints: the message and the trace
            // belong to the same event, and the startup is abandoned right after.
            LOG.log(System.Logger.Level.ERROR, "Binding to port " + PORT + " failed", e);
            throw new RuntimeException("Binding failed.", e);
        }
    }

    public static final String getIP() {
        return ip;
    }

    public static final PlayerStorage getPlayerStorage() {
        return players;
    }

    public static final PlayerStorage getPlayerStorageMTS() {
        return playersMTS;
    }

    public static final void shutdown() {
        if (finishedShutdown) {
            return;
        }
        LOG.log(System.Logger.Level.INFO, "Saving all connected clients (CS)...");
        players.disconnectAll();
        playersMTS.disconnectAll();
        MTSStorage.getInstance().saveBuyNow(true);
        LOG.log(System.Logger.Level.INFO, "Shutting down CS...");
        MapleAcceptor.unbind(InetSocketadd);
        finishedShutdown = true;
    }

    public static boolean isShutdown() {
        return finishedShutdown;
    }
}
