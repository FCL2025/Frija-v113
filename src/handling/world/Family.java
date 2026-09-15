package handling.world;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import client.MapleCharacter;
import handling.MaplePacket;
import handling.world.family.MapleFamily;
import handling.world.family.MapleFamilyCharacter;

/**
 * 跨頻道的家族狀態（E7 由 World 拆出）。
 */
public class Family {

    private static final System.Logger LOG = System.getLogger(Family.class.getName());

    private static final Map<Integer, MapleFamily> families = new LinkedHashMap<>();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    static {
        LOG.log(System.Logger.Level.INFO, "Loading Families :::");
        Collection<MapleFamily> allGuilds = MapleFamily.loadAll();
        for (MapleFamily g : allGuilds) {
            if (g.isProper()) {
                families.put(g.getId(), g);
            }
        }
    }

    // Forces the JVM to run this class's static initializer (the DB load
    // above) right now, from World.init(), instead of lazily whenever
    // something first calls into Family.
    public static void init() {
    }

    public static MapleFamily getFamily(int id) {
        lock.readLock().lock();
        try {
            MapleFamily ret = families.get(id);
            if (ret != null) {
                return ret;
            }
        } finally {
            lock.readLock().unlock();
        }
        // Re-check after taking the write lock, same reasoning as Guild.getGuild:
        // otherwise two racing callers can each load and insert their own
        // MapleFamily for the same id, and the second put silently wins.
        lock.writeLock().lock();
        try {
            MapleFamily ret = families.get(id);
            if (ret != null) {
                return ret;
            }
            ret = new MapleFamily(id);
            if (ret.getId() <= 0 || !ret.isProper()) { //failed to load
                return null;
            }
            families.put(id, ret);
            return ret;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void memberFamilyUpdate(MapleFamilyCharacter mfc, MapleCharacter mc) {
        MapleFamily f = getFamily(mfc.getFamilyId());
        if (f != null) {
            f.memberLevelJobUpdate(mc);
        }
    }

    public static void setFamilyMemberOnline(MapleFamilyCharacter mfc, boolean bOnline, int channel) {
        MapleFamily f = getFamily(mfc.getFamilyId());
        if (f != null) {
            f.setOnline(mfc.getId(), bOnline, channel);
        }
    }

    public static int setRep(int fid, int cid, int addrep, int oldLevel) {
        MapleFamily f = getFamily(fid);
        if (f != null) {
            return f.setRep(cid, addrep, oldLevel);
        }
        return 0;
    }

    public static void save() {
        LOG.log(System.Logger.Level.INFO, "Saving families...");
        lock.writeLock().lock();
        try {
            for (MapleFamily a : families.values()) {
                a.writeToDB(false);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void setFamily(int familyid, int seniorid, int junior1, int junior2, int currentrep, int totalrep, int cid) {
        int ch = Find.findChannel(cid);
        if (ch == -1) {
            return;
        }
        MapleCharacter mc = World.getStorage(ch).getCharacterById(cid);
        if (mc == null) {
            return;
        }
        boolean bDifferent = mc.getFamilyId() != familyid || mc.getSeniorId() != seniorid || mc.getJunior1() != junior1 || mc.getJunior2() != junior2;
        mc.setFamily(familyid, seniorid, junior1, junior2);
        mc.setCurrentRep(currentrep);
        mc.setTotalRep(totalrep);
        if (bDifferent) {
            mc.saveFamilyStatus();
        }
    }

    public static void familyPacket(int gid, MaplePacket message, int cid) {
        MapleFamily f = getFamily(gid);
        if (f != null) {
            f.broadcast(message, -1, f.getMFC(cid).getPedigree());
        }
    }

    public static void disbandFamily(int gid) {
        MapleFamily g = getFamily(gid);
        lock.writeLock().lock();
        try {
            if (g != null) {
                g.disbandFamily();
                families.remove(gid);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
