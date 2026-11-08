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
package client.anticheat;

import database.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import server.Timer;

/**
 * Writes {@link CheatingOffenseEntry}s to the {@code cheatlog} table, once
 * every 61 seconds on {@link Timer#CHEAT}.
 *
 * <p>E17: the INSERT/UPDATE below had been commented out since before this
 * fork existed, so every offence {@code CheatTracker} recorded was collected
 * here and then silently thrown away by {@code toPersist.clear()}. The table
 * and its two views ({@code readable_cheatlog},
 * {@code readable_last_hour_cheatlog}) were always there for it; the schema
 * matches what this writes.
 *
 * <p>One row per {@link CheatingOffenseEntry}: the first flush inserts it and
 * keeps the generated id in the entry, later flushes update that row. Inserts
 * therefore stay one statement per row (a batched statement may not ask for
 * generated keys - see CLAUDE.md), updates are batched.
 *
 * <p>Threading: entries are mutated on the offending player's handler thread
 * and read here on a timer thread; {@code CheatingOffenseEntry}'s mutable
 * fields are volatile for exactly that reason. A count read slightly behind
 * is harmless - the next {@code registerOffense} queues the entry again.
 */
public class CheatingOffensePersister {

    private static final System.Logger LOG = System.getLogger(CheatingOffensePersister.class.getName());

    /** {@code cheatlog.param} is TINYTEXT (255 bytes) in a big5 table: two bytes per CJK char. */
    private static final int MAX_PARAM_CHARS = 120;

    private final static CheatingOffensePersister instance = new CheatingOffensePersister();
    private final Set<CheatingOffenseEntry> toPersist = new LinkedHashSet<>();
    private final Lock mutex = new ReentrantLock();

    private CheatingOffensePersister() {
        Timer.CHEAT.register(new PersistingTask(), 61000);
    }

    public static CheatingOffensePersister getInstance() {
        return instance;
    }

    public void persistEntry(CheatingOffenseEntry coe) {
        mutex.lock();
        try {
            toPersist.remove(coe); //equal/hashCode h4x
            toPersist.add(coe);
        } finally {
            mutex.unlock();
        }
    }

    public class PersistingTask implements Runnable {

        @Override
        public void run() {
            final CheatingOffenseEntry[] offenses;
            mutex.lock();
            try {
                offenses = toPersist.toArray(new CheatingOffenseEntry[0]);
                toPersist.clear();
            } finally {
                mutex.unlock();
            }
            if (offenses.length == 0) {
                return;
            }
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement insertps = con.prepareStatement("INSERT INTO cheatlog (characterid, offense, count, lastoffensetime, param) VALUES (?, ?, ?, ?, ?)", DatabaseConnection.RETURN_GENERATED_KEYS);
                 PreparedStatement updateps = con.prepareStatement("UPDATE cheatlog SET count = ?, lastoffensetime = ?, param = ? WHERE id = ?")) {
                boolean updates = false;
                for (CheatingOffenseEntry offense : offenses) {
                    final String param = clip(offense.getParam());
                    final Timestamp last = new Timestamp(offense.getLastOffenseTime());
                    if (offense.getDbId() == -1) {
                        insertps.setInt(1, offense.getChrfor());
                        insertps.setString(2, offense.getOffense().name());
                        insertps.setInt(3, offense.getCount());
                        insertps.setTimestamp(4, last);
                        insertps.setString(5, param);
                        insertps.executeUpdate();
                        try (ResultSet rs = insertps.getGeneratedKeys()) {
                            if (rs.next()) {
                                offense.setDbId(rs.getInt(1));
                            }
                        }
                    } else {
                        updateps.setInt(1, offense.getCount());
                        updateps.setTimestamp(2, last);
                        updateps.setString(3, param);
                        updateps.setInt(4, offense.getDbId());
                        updateps.addBatch();
                        updates = true;
                    }
                }
                if (updates) {
                    updateps.executeBatch();
                }
            } catch (SQLException e) {
                // ERROR: abandoned - this round's offences are not written. Entries
                // that offend again are queued again; the rest are lost.
                LOG.log(System.Logger.Level.ERROR, "error persisting cheatlog", e);
            }
        }
    }

    private static String clip(final String param) {
        if (param == null) {
            return "";
        }
        return param.length() <= MAX_PARAM_CHARS ? param : param.substring(0, MAX_PARAM_CHARS);
    }
}
