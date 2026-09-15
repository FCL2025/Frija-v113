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
package handling.channel;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.locks.Lock;
import java.util.Collections;
import java.util.Collection;
import client.MapleCharacterUtil;
import client.MapleCharacter;
import handling.MaplePacket;
import handling.world.CharacterTransfer;
import handling.world.CheaterData;
import handling.world.Find;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import server.Timer;

public class PlayerStorage {

    private final ReentrantReadWriteLock mutex = new ReentrantReadWriteLock();
    private final Lock rL = mutex.readLock(), wL = mutex.writeLock();
    private final ReentrantReadWriteLock mutex2 = new ReentrantReadWriteLock();
    private final Lock rL2 = mutex2.readLock(), wL2 = mutex2.writeLock();
    private final Map<String, MapleCharacter> nameToChar = new HashMap<>();
    private final Map<Integer, MapleCharacter> idToChar = new HashMap<>();
    private final Map<Integer, CharacterTransfer> PendingCharacter = new HashMap<>();
    private final int channel;

    private PlayerStorage(int channel) {
        this.channel = channel;
    }

    /**
     * Builds a storage and arms its stale-pending-transfer sweep.
     *
     * <p>The sweep used to be scheduled from the constructor, which handed
     * {@code this} to a {@code Timer.PING} thread - through the inner
     * {@link PersistingTask} - before the object was finished
     * (-Xlint:this-escape). The constructor is private so that it is not
     * possible to build a storage and forget to arm it: a sweep that silently
     * never runs would leak every stale pending transfer. The first sweep
     * still lands 900000 ms after the storage is created, exactly as before.
     */
    public static PlayerStorage create(final int channel) {
        final PlayerStorage ret = new PlayerStorage(channel);
        // Prune once every 15 minutes
        Timer.PING.schedule(ret.new PersistingTask(), 900000);
        return ret;
    }

    public final Collection<MapleCharacter> getAllCharacters() {
        rL.lock();
        try {
            // A snapshot copy, not Collections.unmodifiableCollection(idToChar.values()):
            // that would only stop callers from mutating through the returned view, it
            // would still be backed live by idToChar, so a caller iterating it after this
            // method returns (i.e. after rL is released) could still race a concurrent
            // registerPlayer/deregisterPlayer and get a ConcurrentModificationException.
            return Collections.unmodifiableList(new ArrayList<>(idToChar.values()));
        } finally {
            rL.unlock();
        }
    }

    public final void registerPlayer(final MapleCharacter chr) {
        wL.lock();
        try {
            nameToChar.put(chr.getName().toLowerCase(), chr);
            idToChar.put(chr.getId(), chr);
        } finally {
            wL.unlock();
        }
        Find.register(chr.getId(), chr.getName(), channel);
    }

    public final void registerPendingPlayer(final CharacterTransfer chr, final int playerid) {
        wL2.lock();
        try {
            PendingCharacter.put(playerid, chr);//new Pair(System.currentTimeMillis(), chr));
        } finally {
            wL2.unlock();
        }
    }

    public final void deregisterPlayer(final MapleCharacter chr) {
        wL.lock();
        try {
            nameToChar.remove(chr.getName().toLowerCase());
            idToChar.remove(chr.getId());
        } finally {
            wL.unlock();
        }
        Find.forceDeregister(chr.getId(), chr.getName());
    }

    public final void deregisterPlayer(final int idz, final String namez) {
        wL.lock();
        try {
            nameToChar.remove(namez.toLowerCase());
            idToChar.remove(idz);
        } finally {
            wL.unlock();
        }
        Find.forceDeregister(idz, namez);
    }

    public final void deregisterPendingPlayer(final int charid) {
        wL2.lock();
        try {
            PendingCharacter.remove(charid);
        } finally {
            wL2.unlock();
        }
    }

    public final CharacterTransfer getPendingCharacter(final int charid) {
        final CharacterTransfer toreturn;
        rL2.lock();
        try {
            toreturn = PendingCharacter.get(charid);//.right;
        } finally {
            rL2.unlock();
        }
        if (toreturn != null) {
            deregisterPendingPlayer(charid);
        }
        return toreturn;
    }

    public final MapleCharacter getCharacterByName(final String name) {
        rL.lock();
        try {
            return nameToChar.get(name.toLowerCase());
        } finally {
            rL.unlock();
        }
    }

    /*
     public MapleCharacter getPendingCharacter(int id) {
     for (MapleCharacter chr : pendingCharacter) {
     if (chr.getId() == id) {
     return chr;
     }
     }
     return null;
     }*/
    public final MapleCharacter getCharacterById(final int id) {
        rL.lock();
        try {
            return idToChar.get(id);
        } finally {
            rL.unlock();
        }
    }

    public final int getConnectedClients() {
        rL.lock();
        try {
            return idToChar.size();
        } finally {
            rL.unlock();
        }
    }

    public final List<CheaterData> getCheaters() {
        final List<CheaterData> cheaters = new ArrayList<>();

        rL.lock();
        try {
            for (final MapleCharacter chr : nameToChar.values()) {
                if (chr.getCheatTracker().getPoints() > 0) {
                    cheaters.add(new CheaterData(chr.getCheatTracker().getPoints(), MapleCharacterUtil.makeMapleReadable(chr.getName()) + " (" + chr.getCheatTracker().getPoints() + ") " + chr.getCheatTracker().getSummary()));
                }
            }
        } finally {
            rL.unlock();
        }
        return cheaters;
    }

    public final void disconnectAll() {
        disconnectAll(false);
    }

    public final void disconnectAll(final boolean checkGM) {
        wL.lock();
        try {
            final Iterator<MapleCharacter> itr = nameToChar.values().iterator();
            MapleCharacter chr;
            while (itr.hasNext()) {
                chr = itr.next();

                if (!chr.isGM() || !checkGM) {
                    chr.getClient().disconnect(false, false, true);
                    chr.getClient().getSession().close();
                    Find.forceDeregister(chr.getId(), chr.getName());
                    itr.remove();
                }
            }
        } finally {
            wL.unlock();
        }
    }

    public final String getOnlinePlayers(final boolean byGM) {
        final StringBuilder sb = new StringBuilder();

        if (byGM) {
            rL.lock();
            try {
                for (final MapleCharacter chr : nameToChar.values()) {
                    sb.append(MapleCharacterUtil.makeMapleReadable(chr.getName()));
                    sb.append(", ");
                }
            } finally {
                rL.unlock();
            }
        } else {
            rL.lock();
            try {
                for (final MapleCharacter chr : nameToChar.values()) {
                    if (!chr.isGM()) {
                        sb.append(MapleCharacterUtil.makeMapleReadable(chr.getName()));
                        sb.append(", ");
                    }
                }
            } finally {
                rL.unlock();
            }
        }
        return sb.toString();
    }

    public final void broadcastPacket(final MaplePacket data) {
        rL.lock();
        try {
            for (final MapleCharacter chr : nameToChar.values()) {
                chr.getClient().send(data);
            }
        } finally {
            rL.unlock();
        }
    }

    public final void broadcastSmegaPacket(final MaplePacket data) {
        rL.lock();
        try {
            for (final MapleCharacter chr : nameToChar.values()) {
                if (chr.getClient().isLoggedIn() && chr.getSmega()) {
                    chr.getClient().send(data);
                }
            }
        } finally {
            rL.unlock();
        }
    }

    public final void broadcastGMPacket(final MaplePacket data) {
        rL.lock();
        try {
            for (final MapleCharacter chr : nameToChar.values()) {
                if (chr.getClient().isLoggedIn() && chr.isGM()) {
                    chr.getClient().send(data);
                }
            }
        } finally {
            rL.unlock();
        }
    }

    public class PersistingTask implements Runnable {

        @Override
        public void run() {
            wL2.lock();
            try {
                final long currenttime = System.currentTimeMillis();
                final Iterator<Map.Entry<Integer, CharacterTransfer>> itr = PendingCharacter.entrySet().iterator();

                while (itr.hasNext()) {
                    if (currenttime - itr.next().getValue().TranferTime > 40000) { // 40 sec
                        itr.remove();
                    }
                }
                Timer.PING.schedule(new PersistingTask(), 900000);
            } finally {
                wL2.unlock();
            }
        }
    }
}
