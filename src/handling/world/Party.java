package handling.world;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import client.MapleCharacter;
import database.DatabaseConnection;
import handling.channel.ChannelServer;
import tools.packet.ChatPacket;
import tools.packet.PartyPacket;

/**
 * 跨頻道的組隊狀態（E7 由 World 拆出）。
 */
public class Party {

    private static final System.Logger LOG = System.getLogger(Party.class.getName());

    private static final Map<Integer, MapleParty> parties = new HashMap<>();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final AtomicInteger runningPartyId = new AtomicInteger();

    static {
        try (Connection con = DatabaseConnection.getConnection();
                PreparedStatement ps = con.prepareStatement("SELECT MAX(party)+2 FROM characters");
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            runningPartyId.set(rs.getInt(1));
        } catch (SQLException e) {
            LOG.log(System.Logger.Level.ERROR, "Error reading the next party id from SQL", e);
        }
    }

    public static void partyChat(int partyid, String chattext, String namefrom) {
        lock.readLock().lock();
        try {
            MapleParty party = parties.get(partyid);
            if (party == null) {
                throw new IllegalArgumentException("no party with the specified partyid exists");
            }

            for (MaplePartyCharacter partychar : party.getMembers()) {
                int ch = Find.findChannel(partychar.getName());
                if (ch > 0) {
                    MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(partychar.getName());
                    if (chr != null && !chr.getName().equalsIgnoreCase(namefrom)) { //Extra check just in case
                        chr.getClient().send(ChatPacket.multiChat(namefrom, chattext, 1));
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void updateParty(int partyid, PartyOperation operation, MaplePartyCharacter target) {
        // Lookup + mutation + broadcast is one compound operation: several channel
        // threads can race to update the same party (join/leave/leader-change
        // arriving on different channels at once), so the whole sequence runs
        // under a single write lock instead of being split into independent
        // map operations that could interleave.
        lock.writeLock().lock();
        try {
            MapleParty party = parties.get(partyid);
            if (party == null) {
                return; //Don't update, just return. And definitely don't throw a damn exception.
                //throw new IllegalArgumentException("no party with the specified partyid exists");
            }
            switch (operation) {
                case JOIN:
                    party.addMember(target);
                    break;
                case EXPEL:
                case LEAVE:
                    party.removeMember(target);
                    break;
                case DISBAND:
                    parties.remove(partyid);
                    break;
                case SILENT_UPDATE:
                case LOG_ONOFF:
                    party.updateMember(target);
                    break;
                case CHANGE_LEADER:
                case CHANGE_LEADER_DC:
                    party.setLeader(target);
                    break;
                default:
                    throw new RuntimeException("Unhandeled updateParty operation " + operation.name());
            }

            for (MaplePartyCharacter partychar : party.getMembers()) {
                int ch = Find.findChannel(partychar.getName());
                if (ch > 0) {
                    MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(partychar.getName());
                    if (chr != null) {
                        if (operation == PartyOperation.DISBAND) {
                            chr.setParty(null);
                        } else {
                            chr.setParty(party);
                        }
                        chr.getClient().send(PartyPacket.updateParty(chr.getClient().getChannel(), party, operation, target));
                    }
                }
            }
            switch (operation) {
                case LEAVE:
                case EXPEL:
                    int ch = Find.findChannel(target.getName());
                    if (ch > 0) {
                        MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(target.getName());
                        if (chr != null) {
                            chr.getClient().send(PartyPacket.updateParty(chr.getClient().getChannel(), party, operation, target));
                            chr.setParty(null);
                        }
                    }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static MapleParty createParty(MaplePartyCharacter chrfor) {
        int partyid = runningPartyId.getAndIncrement();
        MapleParty party = new MapleParty(partyid, chrfor);
        lock.writeLock().lock();
        try {
            parties.put(party.getId(), party);
        } finally {
            lock.writeLock().unlock();
        }
        return party;
    }

    public static MapleParty getParty(int partyid) {
        lock.readLock().lock();
        try {
            return parties.get(partyid);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static MapleParty disbandParty(int partyid) {
        lock.writeLock().lock();
        try {
            return parties.remove(partyid);
        } finally {
            lock.writeLock().unlock();
        }
    }
}
