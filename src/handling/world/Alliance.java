package handling.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import handling.MaplePacket;
import handling.world.guild.MapleGuild;
import handling.world.guild.MapleGuildAlliance;
import tools.packet.ChatPacket;
import tools.packet.GuildPacket;

/**
 * 跨頻道的公會聯盟狀態（E7 由 World 拆出）。
 */
public class Alliance {

    private static final Map<Integer, MapleGuildAlliance> alliances = new LinkedHashMap<>();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    static {
        System.out.println("Loading GuildAlliances :::");
        Collection<MapleGuildAlliance> allGuilds = MapleGuildAlliance.loadAll();
        for (MapleGuildAlliance g : allGuilds) {
            alliances.put(g.getId(), g);
        }
    }

    // Forces the JVM to run this class's static initializer (the DB load
    // above) right now, from World.init(), instead of lazily whenever
    // something first calls into Alliance.
    public static void init() {
    }

    public static MapleGuildAlliance getAlliance(final int allianceid) {
        lock.readLock().lock();
        try {
            MapleGuildAlliance ret = alliances.get(allianceid);
            if (ret != null) {
                return ret;
            }
        } finally {
            lock.readLock().unlock();
        }
        // Re-check after taking the write lock, same reasoning as Guild.getGuild:
        // otherwise two racing callers can each load and insert their own
        // MapleGuildAlliance for the same id, and the second put silently wins.
        lock.writeLock().lock();
        try {
            MapleGuildAlliance ret = alliances.get(allianceid);
            if (ret != null) {
                return ret;
            }
            ret = new MapleGuildAlliance(allianceid);
            if (ret.getId() <= 0) { //failed to load
                return null;
            }
            alliances.put(allianceid, ret);
            return ret;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static int getAllianceLeader(final int allianceid) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.getLeaderId();
        }
        return 0;
    }

    public static void updateAllianceRanks(final int allianceid, final String[] ranks) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            mga.setRank(ranks);
        }
    }

    public static void updateAllianceNotice(final int allianceid, final String notice) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            mga.setNotice(notice);
        }
    }

    public static boolean canInvite(final int allianceid) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.getCapacity() > mga.getNoGuilds();
        }
        return false;
    }

    public static boolean changeAllianceLeader(final int allianceid, final int cid) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.setLeaderId(cid);
        }
        return false;
    }

    public static boolean changeAllianceRank(final int allianceid, final int cid, final int change) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.changeAllianceRank(cid, change);
        }
        return false;
    }

    public static boolean changeAllianceCapacity(final int allianceid) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.setCapacity();
        }
        return false;
    }

    public static boolean disbandAlliance(final int allianceid) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.disband();
        }
        return false;
    }

    public static boolean addGuildToAlliance(final int allianceid, final int gid) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.addGuild(gid);
        }
        return false;
    }

    public static boolean removeGuildFromAlliance(final int allianceid, final int gid, final boolean expelled) {
        final MapleGuildAlliance mga = getAlliance(allianceid);
        if (mga != null) {
            return mga.removeGuild(gid, expelled);
        }
        return false;
    }

    public static void sendGuild(final int allianceid) {
        final MapleGuildAlliance alliance = getAlliance(allianceid);
        if (alliance != null) {
            sendGuild(GuildPacket.getAllianceUpdate(alliance), -1, allianceid);
            sendGuild(GuildPacket.getGuildAlliance(alliance), -1, allianceid);
        }
    }

    public static void sendGuild(final MaplePacket packet, final int exceptionId, final int allianceid) {
        final MapleGuildAlliance alliance = getAlliance(allianceid);
        if (alliance != null) {
            for (int i = 0; i < alliance.getNoGuilds(); i++) {
                int gid = alliance.getGuildId(i);
                if (gid > 0 && gid != exceptionId) {
                    Guild.guildPacket(gid, packet);
                }
            }
        }
    }

    public static boolean createAlliance(final String alliancename, final int cid, final int cid2, final int gid, final int gid2) {
        final int allianceid = MapleGuildAlliance.createToDb(cid, alliancename, gid, gid2);
        if (allianceid <= 0) {
            return false;
        }
        final MapleGuild g = Guild.getGuild(gid), g_ = Guild.getGuild(gid2);
        g.setAllianceId(allianceid);
        g_.setAllianceId(allianceid);
        g.changeARank(true);
        g_.changeARank(false);

        final MapleGuildAlliance alliance = getAlliance(allianceid);

        sendGuild(GuildPacket.createGuildAlliance(alliance), -1, allianceid);
        sendGuild(GuildPacket.getAllianceInfo(alliance), -1, allianceid);
        sendGuild(GuildPacket.getGuildAlliance(alliance), -1, allianceid);
        sendGuild(GuildPacket.changeAlliance(alliance, true), -1, allianceid);
        return true;
    }

    public static void allianceChat(final int gid, final String name, final int cid, final String msg) {
        final MapleGuild g = Guild.getGuild(gid);
        if (g != null) {
            final MapleGuildAlliance ga = getAlliance(g.getAllianceId());
            if (ga != null) {
                for (int i = 0; i < ga.getNoGuilds(); i++) {
                    final MapleGuild g_ = Guild.getGuild(ga.getGuildId(i));
                    if (g_ != null) {
                        g_.allianceChat(name, cid, msg);
                    }
                }
            }
        }
    }

    public static void setNewAlliance(final int gid, final int allianceid) {
        final MapleGuildAlliance alliance = getAlliance(allianceid);
        final MapleGuild guild = Guild.getGuild(gid);
        if (alliance != null && guild != null) {
            for (int i = 0; i < alliance.getNoGuilds(); i++) {
                if (gid == alliance.getGuildId(i)) {
                    guild.setAllianceId(allianceid);
                    guild.broadcast(GuildPacket.getAllianceInfo(alliance));
                    guild.broadcast(GuildPacket.getGuildAlliance(alliance));
                    guild.broadcast(GuildPacket.changeAlliance(alliance, true));
                    guild.changeARank();
                    guild.writeToDB(false);
                } else {
                    final MapleGuild g_ = Guild.getGuild(alliance.getGuildId(i));
                    if (g_ != null) {
                        g_.broadcast(GuildPacket.addGuildToAlliance(alliance, guild));
                        g_.broadcast(GuildPacket.changeGuildInAlliance(alliance, guild, true));
                    }
                }
            }
        }
    }

    public static void setOldAlliance(final int gid, final boolean expelled, final int allianceid) {
        final MapleGuildAlliance alliance = getAlliance(allianceid);
        final MapleGuild g_ = Guild.getGuild(gid);
        if (alliance != null) {
            for (int i = 0; i < alliance.getNoGuilds(); i++) {
                final MapleGuild guild = Guild.getGuild(alliance.getGuildId(i));
                if (guild == null) {
                    if (gid != alliance.getGuildId(i)) {
                        alliance.removeGuild(gid, false);
                    }
                    continue; //just skip
                }
                if (g_ == null || gid == alliance.getGuildId(i)) {
                    guild.changeARank(5);
                    guild.setAllianceId(0);
                    guild.broadcast(GuildPacket.disbandAlliance(allianceid));
                } else if (g_ != null) {
                    guild.broadcast(ChatPacket.serverNotice(5, "[" + g_.getName() + "] Guild has left the alliance."));
                    guild.broadcast(GuildPacket.changeGuildInAlliance(alliance, g_, false));
                    guild.broadcast(GuildPacket.removeGuildFromAlliance(alliance, g_, expelled));
                }

            }
        }

        if (gid == -1) {
            lock.writeLock().lock();
            try {
                alliances.remove(allianceid);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    public static List<MaplePacket> getAllianceInfo(final int allianceid, final boolean start) {
        List<MaplePacket> ret = new ArrayList<>();
        final MapleGuildAlliance alliance = getAlliance(allianceid);
        if (alliance != null) {
            if (start) {
                ret.add(GuildPacket.getAllianceInfo(alliance));
                ret.add(GuildPacket.getGuildAlliance(alliance));
            }
            ret.add(GuildPacket.getAllianceUpdate(alliance));
        }
        return ret;
    }

    public static void save() {
        System.out.println("Saving alliances...");
        lock.writeLock().lock();
        try {
            for (MapleGuildAlliance a : alliances.values()) {
                a.saveToDb();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
