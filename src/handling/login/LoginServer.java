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
package handling.login;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import handling.MapleServerHandler;
import handling.mina.MapleAcceptor;
import server.ServerProperties;

public class LoginServer {

    public static final int PORT = 8484;
    private static InetSocketAddress InetSocketadd;
    /**
     * Per-channel population, written by every channel's own thread and read by
     * the login threads answering SERVERSTATUS_REQUEST - so, concurrent.
     */
    private static volatile Map<Integer, Integer> load = new ConcurrentHashMap<>();
    private static volatile String serverName, eventMessage;
    private static volatile byte flag;
    private static volatile int maxCharacters, userLimit, usersOn = 0;
    private static volatile boolean finishedShutdown = true, adminOnly = false;

    public static final void addChannel(final int channel) {
        load.put(channel, 0);
    }

    public static final void removeChannel(final int channel) {
        load.remove(channel);
    }

    public static final void run_startup_configurations() {
        userLimit = Integer.parseInt(ServerProperties.getProperty("tms.UserLimit"));
        serverName = ServerProperties.getProperty("tms.ServerName");
        eventMessage = ServerProperties.getProperty("tms.EventMessage");
        flag = Byte.parseByte(ServerProperties.getProperty("tms.Flag"));
        adminOnly = Boolean.parseBoolean(ServerProperties.getProperty("tms.Admin", "false"));
        maxCharacters = Integer.parseInt(ServerProperties.getProperty("tms.MaxCharacters"));

        try {
            InetSocketadd = MapleAcceptor.bind(PORT, new MapleServerHandler(-1, false));
            System.out.println("Login   1: Listening on port " + PORT);
        } catch (IOException e) {
            System.err.println("Binding to port " + PORT + " failed" + e);
        }
    }

    public static final void shutdown() {
        if (finishedShutdown) {
            return;
        }
        System.out.println("Shutting down login...");
        MapleAcceptor.unbind(InetSocketadd);
        finishedShutdown = true; //nothing. lol
    }

    public static final String getServerName() {
        return serverName;
    }

    public static final String getEventMessage() {
        return eventMessage;
    }

    public static final byte getFlag() {
        return flag;
    }

    public static final int getMaxCharacters() {
        return maxCharacters;
    }

    public static final Map<Integer, Integer> getLoad() {
        return load;
    }

    public static void setLoad(final Map<Integer, Integer> load_, final int usersOn_) {
        load = new ConcurrentHashMap<>(load_);
        usersOn = usersOn_;
    }

    public static final void setEventMessage(final String newMessage) {
        eventMessage = newMessage;
    }

    public static final void setFlag(final byte newflag) {
        flag = newflag;
    }

    public static final int getUserLimit() {
        return userLimit;
    }

    public static final int getUsersOn() {
        return usersOn;
    }

    public static final void setUserLimit(final int newLimit) {
        userLimit = newLimit;
    }

    public static final int getNumberOfSessions() {
        return MapleAcceptor.getManagedSessionCount(InetSocketadd);
    }

    public static final boolean isAdminOnly() {
        return adminOnly;
    }

    public static final boolean isShutdown() {
        return finishedShutdown;
    }

    public static final void setOn() {
        finishedShutdown = false;
    }
}
