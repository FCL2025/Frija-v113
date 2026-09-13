package server;

import java.util.LinkedList;
import java.util.List;

import client.MapleClient;
import client.MapleCharacter;
import handling.channel.ChannelServer;
import handling.world.Find;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import server.Timer.EtcTimer;
import server.maps.MapleMap;
import tools.Pair;
import tools.packet.ChatPacket;
import tools.packet.MapPacket;

public class MapleSquad {

    public static enum MapleSquadType {

        bossbalrog(2), zak(2), chaoszak(3), horntail(2), chaosht(3), pinkbean(3), nmm_squad(2), vergamot(2), dunas(2), nibergen_squad(2), dunas2(2), core_blaze(2), aufheben(2), cwkpq(10), tokyo_2095(2), vonleon(3), scartar(2), cygnus(3);

        private MapleSquadType(int i) {
            this.i = i;
        }
        public int i;
        /*
         * These two are per-type, which means per-JVM: every channel's squads of
         * one type share them, and every player who talks to a squad NPC reaches
         * them from their own handler thread. Keyed by channel, and the per-channel
         * lists are read far more often than they are written.
         */
        private final Map<Integer, List<Pair<String, String>>> queuedPlayers = new ConcurrentHashMap<>();
        private final Map<Integer, List<Pair<String, Long>>> queue = new ConcurrentHashMap<>();

        /** This channel's waiting-squad queue, created on first use. */
        private List<Pair<String, Long>> queue(final int ch) {
            return queue.computeIfAbsent(ch, k -> new CopyOnWriteArrayList<>());
        }

        /** This channel's log of what happened to each queued squad. */
        private List<Pair<String, String>> queuedPlayers(final int ch) {
            return queuedPlayers.computeIfAbsent(ch, k -> new CopyOnWriteArrayList<>());
        }

        /** Every channel's queue, for the "is this player queued anywhere" scan. */
        private Collection<List<Pair<String, Long>>> allQueues() {
            return queue.values();
        }
    }

    private WeakReference<MapleCharacter> leader;
    private final String leaderName, toSay;
    private final Map<String, String> members = new LinkedHashMap<>();
    private final Map<String, String> bannedMembers = new LinkedHashMap<>();
    private final int ch;
    private final long startTime;
    private final int expiration;
    private final int beginMapId;
    private final MapleSquadType type;
    private byte status = 0;
    private ScheduledFuture<?> removal;
    private MapleClient c;

    public MapleSquad(final int ch, final String type, final MapleCharacter leader, final int expiration, final String toSay) {
        this.leader = new WeakReference<>(leader);
        this.members.put(leader.getName(), MapleCarnivalChallenge.getJobBasicNameById(leader.getJob()));
        this.leaderName = leader.getName();
        this.ch = ch;
        this.toSay = toSay;
        this.type = MapleSquadType.valueOf(type.toLowerCase());
        this.status = 1;
        this.beginMapId = leader.getMapId();
        leader.getMap().setSquad(this.type);
        this.type.queue(ch); // create this channel's two lists up front, as before
        this.type.queuedPlayers(ch);
        this.startTime = System.currentTimeMillis();
        this.expiration = expiration;
    }

    public void copy() {
        final List<Pair<String, Long>> waiting = type.queue(ch);
        final List<Pair<String, String>> outcome = type.queuedPlayers(ch);
        while (!waiting.isEmpty() && ChannelServer.getInstance(ch).getMapleSquad(type) == null) {
            // Pick the entry that has waited longest by identity, not by index:
            // another thread can remove one between the scan and the removal, and
            // then the index would point at somebody else.
            Pair<String, Long> next = null;
            long lowest = 0;
            for (Pair<String, Long> entry : waiting) {
                if (lowest == 0 || entry.right < lowest) {
                    next = entry;
                    lowest = entry.right;
                }
            }
            if (next == null || !waiting.remove(next)) {
                continue; // somebody else took it; the list is shorter, so re-check
            }
            final String nextPlayerId = next.left;
            final int theirCh = Find.findChannel(nextPlayerId);
            if (theirCh > 0) {
                final MapleCharacter lead = ChannelServer.getInstance(theirCh).getPlayerStorage().getCharacterByName(nextPlayerId);
                if (lead != null && lead.getMapId() == beginMapId && lead.getClient().getChannel() == ch) {
                    final MapleSquad squad = new MapleSquad(ch, type.name(), lead, expiration, toSay);
                    if (ChannelServer.getInstance(ch).addMapleSquad(squad, type.name())) {
                        getBeginMap().broadcastMessage(MapPacket.getClock(expiration / 1000));
                        getBeginMap().broadcastMessage(ChatPacket.serverNotice(6, nextPlayerId + toSay));
                        outcome.add(new Pair<>(nextPlayerId, "Success"));
                    } else {
                        squad.clear();
                        outcome.add(new Pair<>(nextPlayerId, "Skipped"));
                    }
                    break;
                } else {
                    if (lead != null) {
                        lead.dropMessage(6, "Your squad has been skipped due to you not being in the right channel and map.");
                    }
                    getBeginMap().broadcastMessage(ChatPacket.serverNotice(6, nextPlayerId + "'s squad has been skipped due to the player not being in the right channel and map."));
                    outcome.add(new Pair<>(nextPlayerId, "Not in map"));
                }
            } else {
                getBeginMap().broadcastMessage(ChatPacket.serverNotice(6, nextPlayerId + "'s squad has been skipped due to the player not being online."));
                outcome.add(new Pair<>(nextPlayerId, "Not online"));
            }
        }
    }

    public MapleMap getBeginMap() {
        return ChannelServer.getInstance(ch).getMapFactory().getMap(beginMapId);
    }

    public void clear() {
        if (removal != null) {
            getBeginMap().broadcastMessage(MapPacket.stopClock());
            removal.cancel(false);
            removal = null;
        }
        members.clear();
        bannedMembers.clear();
        leader = null;
        ChannelServer.getInstance(ch).removeMapleSquad(type);
        this.status = 0;
    }

    public MapleCharacter getChar(String name) {
        return ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(name);
    }

    public long getTimeLeft() {
        return expiration - (System.currentTimeMillis() - startTime);
    }

    public void scheduleRemoval() {
        removal = EtcTimer.getInstance().schedule(() -> {
            if (status != 0 && leader != null && (getLeader() == null || status == 1)) { //leader itself = null means we're already cleared
                clear();
                copy();
            }
        }, expiration);
    }

    public String getLeaderName() {
        return leaderName;
    }

    public List<Pair<String, Long>> getAllNextPlayer() {
        return type.queue(ch);
    }

    public String getNextPlayer() {
        final List<Pair<String, Long>> waiting = type.queue(ch);
        StringBuilder sb = new StringBuilder("\n排隊成員 : ");
        sb.append("#b").append(waiting.size()).append(" #k ").append("與遠征隊名單 : \n\r ");
        int i = 0;
        for (Pair<String, Long> chr : waiting) {
            i++;
            sb.append(i).append(" : ").append(chr.left);
            sb.append(" \n\r ");
        }
        sb.append("你是否想要 #e當下一個#n 在遠征隊排隊中　或者 #e移除#n 在遠征隊? 如果你想的話...");
        return sb.toString();
    }

    public void setNextPlayer(String i) {
        final List<Pair<String, Long>> waiting = type.queue(ch);
        Pair<String, Long> toRemove = null;
        for (Pair<String, Long> s : waiting) {
            if (s.left.equals(i)) {
                toRemove = s;
                break;
            }
        }
        if (toRemove != null) {
            waiting.remove(toRemove);
            return;
        }
        for (List<Pair<String, Long>> v : type.allQueues()) {
            for (Pair<String, Long> s : v) {
                if (s.left.equals(i)) {
                    return;
                }
            }
        }
        waiting.add(new Pair<>(i, System.currentTimeMillis()));
    }

    public MapleCharacter getLeader() {
        if (leader == null || leader.get() == null) {
            if (members.size() > 0 && getChar(leaderName) != null) {
                leader = new WeakReference<>(getChar(leaderName));
            } else {
                if (status != 0) {
                    clear();
                }
                return null;
            }
        }
        return leader.get();
    }

    public boolean containsMember(MapleCharacter member) {
        for (String mmbr : members.keySet()) {
            if (mmbr.equalsIgnoreCase(member.getName())) {
                return true;
            }
        }
        return false;
    }

    public List<String> getMembers() {
        return new LinkedList<>(members.keySet());
    }

    public List<String> getBannedMembers() {
        return new LinkedList<>(bannedMembers.keySet());
    }

    public int getSquadSize() {
        return members.size();
    }

    public boolean isBanned(MapleCharacter member) {
        return bannedMembers.containsKey(member.getName());
    }

    public int addMember(MapleCharacter member, boolean join) {
    if (getLeader() == null) {
        return -1;
    }
        final String job = MapleCarnivalChallenge.getJobBasicNameById(member.getJob());
        if (join) {
            if (!containsMember(member) && !getAllNextPlayer().contains(member.getName())) {
                if (members.size() <= 30) {
                    members.put(member.getName(), job);
                    getLeader().dropMessage(6, member.getName() + " (" + job + ") 加入了遠征隊!");
                    return 1;
                }
                return 2;
            }
            return -1;
        } else {
            if (containsMember(member)) {
                members.remove(member.getName());
                getLeader().dropMessage(6, member.getName() + " (" + job + ") 離開了遠征隊.");
                return 1;
            }
            return -1;
        }
    }

    public void acceptMember(int pos) {
        if (pos < 0 || pos >= bannedMembers.size()) {
            return;
        }
        final List<String> membersAsList = getBannedMembers();
        final String toadd = membersAsList.get(pos);
        if (toadd != null && getChar(toadd) != null) {
            members.put(toadd, bannedMembers.get(toadd));
            bannedMembers.remove(toadd);

            getChar(toadd).dropMessage(5, getLeaderName() + " 允許你重新回來遠征隊");
        }
    }

    public void reAddMember(MapleCharacter chr) {
        removeMember(chr);
        members.put(chr.getName(), MapleCarnivalChallenge.getJobBasicNameById(chr.getJob()));
    }

    public void removeMember(MapleCharacter chr) {
        if (members.containsKey(chr.getName())) {
            members.remove(chr.getName());
        }
    }

    public void removeMember(String chr) {
        if (members.containsKey(chr)) {
            members.remove(chr);
        }
    }

    public void banMember(int pos) {
        if (pos <= 0 || pos >= members.size()) { //may not ban leader
            return;
        }
        final List<String> membersAsList = getMembers();
        final String toban = membersAsList.get(pos);
        if (toban != null && getChar(toban) != null) {
            bannedMembers.put(toban, members.get(toban));
            members.remove(toban);

            getChar(toban).dropMessage(5, getLeaderName() + " 從遠征隊中刪除您.");
        }
    }

    public void setStatus(byte status) {
        this.status = status;
        if (status == 2 && removal != null) {
            removal.cancel(false);
            removal = null;
        }
    }

    public int getStatus() {
        return status;
    }

    public int getBannedMemberSize() {
        return bannedMembers.size();
    }

    public String getSquadMemberString(byte type) {
        switch (type) {
            case 0: {
                StringBuilder sb = new StringBuilder("遠征隊名單 : ");
                sb.append("#b").append(members.size()).append(" #k ").append("與成員名單 : \n\r ");
                int i = 0;
                for (Entry<String, String> chr : members.entrySet()) {
                    i++;
                    sb.append(i).append(" : ").append(chr.getKey()).append(" (").append(chr.getValue()).append(") ");
                    if (i == 1) {
                        sb.append("(遠征隊領袖)");
                    }
                    sb.append(" \n\r ");
                }
                while (i < 30) {
                    i++;
                    sb.append(i).append(" : ").append(" \n\r ");
                }
                return sb.toString();
            }
            case 1: {
                StringBuilder sb = new StringBuilder("遠征隊名單 : ");
                sb.append("#b").append(members.size()).append(" #n ").append("與成員名單 : \n\r ");
                int i = 0, selection = 0;
                for (Entry<String, String> chr : members.entrySet()) {
                    i++;
                    sb.append("#b#L").append(selection).append("#");
                    selection++;
                    sb.append(i).append(" : ").append(chr.getKey()).append(" (").append(chr.getValue()).append(") ");
                    if (i == 1) {
                        sb.append("(遠征隊領袖)");
                    }
                    sb.append("#l").append(" \n\r ");
                }
                while (i < 30) {
                    i++;
                    sb.append(i).append(" : ").append(" \n\r ");
                }
                return sb.toString();
            }
            case 2: {
                StringBuilder sb = new StringBuilder("遠征隊名單 : ");
                sb.append("#b").append(members.size()).append(" #n ").append("與成員名單 : \n\r ");
                int i = 0, selection = 0;
                for (Entry<String, String> chr : bannedMembers.entrySet()) {
                    i++;
                    sb.append("#b#L").append(selection).append("#");
                    selection++;
                    sb.append(i).append(" : ").append(chr.getKey()).append(" (").append(chr.getValue()).append(") ");
                    sb.append("#l").append(" \n\r ");
                }
                while (i < 30) {
                    i++;
                    sb.append(i).append(" : ").append(" \n\r ");
                }
                return sb.toString();
            }
            case 3: { //CWKPQ
                StringBuilder sb = new StringBuilder("職業 : ");
                final Map<String, Integer> jobs = getJobs();
                for (Entry<String, Integer> chr : jobs.entrySet()) {
                    sb.append("\r\n").append(chr.getKey()).append(" : ").append(chr.getValue());
                }
                return sb.toString();
            }
        }
        return null;
    }

    public final MapleSquadType getType() {
        return type;
    }

    public final Map<String, Integer> getJobs() {
        final Map<String, Integer> jobs = new LinkedHashMap<>();
        for (Entry<String, String> chr : members.entrySet()) {
            if (jobs.containsKey(chr.getValue())) {
                jobs.put(chr.getValue(), jobs.get(chr.getValue()) + 1);
            } else {
                jobs.put(chr.getValue(), 1);
            }
        }
        return jobs;
    }
}
