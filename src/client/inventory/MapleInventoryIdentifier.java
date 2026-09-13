/*
 This file is part of the ZeroFusion MapleStory Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc> 
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>
 ZeroFusion organized by "RMZero213" <RMZero213@hotmail.com>

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
package client.inventory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import database.DatabaseConnection;

public class MapleInventoryIdentifier {

    private static final System.Logger LOG = System.getLogger(MapleInventoryIdentifier.class.getName());

    private AtomicInteger runningUID;
    private ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private Lock readLock = rwl.readLock(), writeLock = rwl.writeLock();
    private static MapleInventoryIdentifier instance = new MapleInventoryIdentifier();

    // A singleton: only the static `instance` above may build one, and only
    // the static getInstance() is used from outside. Both that and the private
    // helpers below keep the constructor's getNextUniqueId() call non-virtual
    // (-Xlint:this-escape).
    private MapleInventoryIdentifier() {
        this.runningUID = new AtomicInteger(0);
        getNextUniqueId();
    }

    public static int getInstance() {
        return instance.getNextUniqueId();
    }

    private int getNextUniqueId() {
        if (grabRunningUID() <= 0) {
            setRunningUID(initUID());
        }
        incrementRunningUID();
        return grabRunningUID();
    }

    private int grabRunningUID() {
        readLock.lock();
        try {
            return runningUID.get(); //maybe we should really synchronize/do more here..
        } finally {
            readLock.unlock();
        }
    }

    private void incrementRunningUID() {
        setRunningUID(grabRunningUID() + 1);
    }

    private void setRunningUID(int rUID) {
        if (rUID < grabRunningUID()) { //dont go backwards.
            return;
        }
        writeLock.lock();
        try {
            runningUID.set(rUID); //maybe we should really synchronize/do more here..
        } finally {
            writeLock.unlock();
        }
    }

    private int initUID() {
        int ret = 0;
        if (grabRunningUID() > 0) {
            return grabRunningUID();
        }
        // The highest id already handed out by any of the four tables that
        // draw from this one sequence.
        final String[] queries = {
            "SELECT MAX(uniqueid) FROM inventoryitems",
            "SELECT MAX(petid) FROM pets",
            "SELECT MAX(ringid) FROM rings",
            "SELECT MAX(partnerringid) FROM rings"
        };
        try (Connection con = DatabaseConnection.getConnection()) {
            for (final String query : queries) {
                try (PreparedStatement ps = con.prepareStatement(query);
                        ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        ret = Math.max(ret, rs.getInt(1) + 1);
                    }
                }
            }
        } catch (Exception e) {
            // ERROR: the scan is abandoned and initUID() falls back to 0, so
            // the sequence restarts over ids that are already in use.
            LOG.log(System.Logger.Level.ERROR, "Error reading the highest unique item id", e);
        }
        return ret;
    }
}
