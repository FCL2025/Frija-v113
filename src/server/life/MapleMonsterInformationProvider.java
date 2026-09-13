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
package server.life;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import database.DatabaseConnection;

public class MapleMonsterInformationProvider {

    private static final System.Logger LOG = System.getLogger(MapleMonsterInformationProvider.class.getName());

    private static final MapleMonsterInformationProvider instance = new MapleMonsterInformationProvider();
    /**
     * Every mob death on any Maple-Handler-N thread reads this, and
     * {@code !reloaddrops} or a drop edit in the panel empties it from a
     * Panel-Worker-N thread, so it has to be concurrent. The per-monster lists
     * are still filled in outside the map - see {@link #retrieveDrop}.
     */
    private final Map<Integer, List<MonsterDropEntry>> drops = new ConcurrentHashMap<>();
    /**
     * The global drop table is not looked up per monster, so it is a whole list
     * that a reload <em>replaces</em> rather than empties and refills: a reader
     * on another thread sees either the old table or the new one, never the
     * empty middle of a reload, and never a list being appended to while it
     * walks it. The list itself is never mutated once published.
     */
    private volatile List<MonsterGlobalDropEntry> globaldrops = new ArrayList<>();

    protected MapleMonsterInformationProvider() {
        retrieveGlobal();
    }

    public static final MapleMonsterInformationProvider getInstance() {
        return instance;
    }

    public final List<MonsterGlobalDropEntry> getGlobalDrop() {
        return globaldrops;
    }

    private final void retrieveGlobal() {
        final List<MonsterGlobalDropEntry> fresh = new ArrayList<>();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM drop_data_global WHERE chance > 0");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                fresh.add(
                        new MonsterGlobalDropEntry(
                                rs.getInt("itemid"),
                                rs.getInt("chance"),
                                rs.getInt("continent"),
                                rs.getByte("dropType"),
                                rs.getInt("minimum_quantity"),
                                rs.getInt("maximum_quantity"),
                                rs.getShort("questid")));
            }
        } catch (SQLException e) {
            // WARNING, and terse on purpose: this one only ever concatenated
            // the exception, and the comment below says the half-read table is
            // published regardless - the server carries on with it.
            LOG.log(System.Logger.Level.WARNING, "Error retrieving drop" + e);
        }
        // Published whether or not the query got through, because a half-read
        // table is what the old code left behind too.
        globaldrops = fresh;
    }

    public final List<MonsterDropEntry> retrieveDrop(final int monsterId) {
        final List<MonsterDropEntry> cached = drops.get(monsterId);
        if (cached != null) {
            return cached;
        }
        final List<MonsterDropEntry> ret = new LinkedList<>();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM drop_data WHERE dropperid = ?")) {
            ps.setInt(1, monsterId);
            try (ResultSet rs = ps.executeQuery()) {
                int itemid;
                int chance;
                while (rs.next()) {
                    itemid = rs.getInt("itemid");
                    chance = rs.getInt("chance");
                    /*
                    if (GameConstants.getInventoryType(itemid) == MapleInventoryType.EQUIP) {
                        chance *= 10; //in GMS/SEA it was raised
                    }
                    */
                    ret.add(new MonsterDropEntry(
                            itemid,
                            chance,
                            rs.getInt("minimum_quantity"),
                            rs.getInt("maximum_quantity"),
                            rs.getShort("questid")));
                }
            }
        } catch (SQLException e) {
            return ret;
        }
        // Deliberately a miss-then-put rather than computeIfAbsent: the query
        // above borrows a connection, and computeIfAbsent would hold the map's
        // bin lock across it. Two threads that miss on the same monster both
        // read the table and the second one wins, which costs one query.
        drops.put(monsterId, ret);
        return ret;
    }

    public final void clearDrops() {
        drops.clear();
        retrieveGlobal();
    }
}
