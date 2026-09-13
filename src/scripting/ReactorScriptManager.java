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
package scripting;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import client.MapleClient;
import database.DatabaseConnection;
import server.maps.ReactorDropEntry;
import server.maps.MapleReactor;
import tools.FileoutputUtil;

public class ReactorScriptManager extends AbstractScriptManager {

    private static final ReactorScriptManager instance = new ReactorScriptManager();
    /**
     * Any Maple-Handler-N thread that hits a reactor fills this in, and
     * {@code !reloaddrops} or a drop edit in the panel empties it from a
     * Panel-Worker-N thread. Concurrent for that reason alone - the lists in it
     * are built and finished before they ever go into the map.
     */
    private final Map<Integer, List<ReactorDropEntry>> drops = new ConcurrentHashMap<>();

    public static final ReactorScriptManager getInstance() {
        return instance;
    }

    public final void act(final MapleClient c, final MapleReactor reactor) {
        try {
            final ScriptScope iv = getInvocable("reactor/" + reactor.getReactorId() + ".js", c);

            if (iv == null) {
                return;
            }
            ReactorActionManager rm = new ReactorActionManager(c, reactor);

            iv.put("rm", rm);
            iv.invokeFunction("act");
        } catch (Exception e) {
            System.err.println("Error executing reactor script. ReactorID: " + reactor.getReactorId() + ", ReactorName: " + reactor.getName() + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing reactor script. ReactorID: " + reactor.getReactorId() + ", ReactorName: " + reactor.getName() + ":" + e);
        }
    }

    public final List<ReactorDropEntry> getDrops(final int rid) {
        List<ReactorDropEntry> ret = drops.get(rid);
        if (ret != null) {
            return ret;
        }
        ret = new LinkedList<>();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM reactordrops WHERE reactorid = ?")) {
            ps.setInt(1, rid);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ret.add(new ReactorDropEntry(rs.getInt("itemid"), rs.getInt("chance"), rs.getInt("questid")));
                }
            }
        } catch (final SQLException e) {
            System.err.println("Could not retrieve drops for reactor " + rid + e);
            return ret;
        }
        // Miss-then-put, not computeIfAbsent: the query above borrows a
        // connection and that must not happen under the map's bin lock. A race
        // costs one extra query and stores an equal list.
        drops.put(rid, ret);
        return ret;
    }

    public final void clearDrops() {
        drops.clear();
    }
}
