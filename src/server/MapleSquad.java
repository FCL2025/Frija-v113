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

    private volatile WeakReference<MapleCharacter> leader;
    private final String leaderName, toSay;
    /**
     * The roster and the ban list, name to job name.
     *
     * <p>Both stay {@link LinkedHashMap}s because insertion order is load
     * bearing: the leader is the first entry, {@link #getSquadMemberString}
     * numbers the menu rows from that order, and {@link #banMember} refuses row
     * 0 on the strength of it. So neither can become a {@code ConcurrentHashMap},
     * which would scramble it.
     *
     * <p>They were unguarded. Every member of a squad reaches them from their
     * own handler thread - join, leave and the leader's ban/accept menus are all
     * NPC scripts, and {@code EventInstanceManager} removes a member from a
     * timer thread when they leave the instance - so a {@code put} and a
     * {@code remove} could land on the same bucket at once.
     *
     * <p>{@link #memberLock} guards every read and every write of both maps. It
     * is a plain monitor rather than a {@code ReentrantReadWriteLock} on
     * purpose: a squad holds at most 30 entries and the operations happen at
     * NPC-menu rate, so there is nothing for a read lock to win, and a monitor
     * is reentrant in both directions where a read/write lock deadlocks the
     * moment a read-holding method wants to write.
     *
     * <p>Nothing that sends a packet happens inside it. Each mutator decides
     * under the lock what it changed and sends its {@code dropMessage} after
     * releasing it, and {@link #clear()} releases it before calling
     * {@code removeMapleSquad}, so {@code memberLock} never nests inside
     * {@code ChannelServer}'s {@code squadLock} or the other way round.
     */
    private final Map<String, String> members = new LinkedHashMap<>();
    private final Map<String, String> bannedMembers = new LinkedHashMap<>();
    private final Object memberLock = new Object();
    private final int ch;
    private final long startTime;
    private final int expiration;
    private final int beginMapId;
    private final MapleSquadType type;
    /**
     * Written by the squad NPC's thread and by the {@code Timer.ETC} task, read
     * by both plus whoever walks the boss map - volatile so a cleared squad
     * actually looks cleared to the next thread. The read-modify-write in
     * {@link #setStatus} is still not atomic; see the note there.
     */
    private volatile byte status = 0;
    private volatile ScheduledFuture<?> removal;
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
        synchronized (memberLock) {
            members.clear();
            bannedMembers.clear();
        }
        leader = null;
        // Identity-checked: a squad that lost the registration race clears
        // itself, and must not take the winner's slot down with it.
        ChannelServer.getInstance(ch).removeMapleSquad(type, this);
        this.status = 0;
    }

    public MapleCharacter getChar(String name) {
        return ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(name);
    }

    public long getTimeLeft() {
        return expiration - (System.currentTimeMillis() - startTime);
    }

    public void scheduleRemoval() {
        removal = Timer.ETC.schedule(() -> {
            if (status != 0 && leader != null && (getLeader() == null || status == 1)) { //leader itself = null means we're already cleared
                clear();
                copy();
            }
        }, expiration);
    }

    public String getLeaderName() {
        return leaderName;
    }

    /**
     * 這個頻道的候補佇列本身，{@code left} 是角色名、{@code right} 是排進來的
     * 時間戳（{@link #copy} 就是靠 {@code right} 挑等最久的那個）。
     *
     * <p><b>不要拿它做 {@code contains(角色名)}。</b>元素是
     * {@code Pair<String, Long>}，跟 {@code String} 永遠不相等，所以那樣寫
     * 恆為 {@code false}——本類別的 {@link #addMember} 和三支遠征隊 NPC
     * （{@code npc/2143004}、{@code npc/2161005}、{@code npc/9270047}）
     * 四處都犯過（A21）。要問「這個人在不在佇列裡」請用
     * {@link #isQueuedNextPlayer}。
     */
    public List<Pair<String, Long>> getAllNextPlayer() {
        return type.queue(ch);
    }

    /**
     * 這個頻道的候補佇列裡有沒有這個人。
     *
     * <p>比對範圍刻意和 {@link #getAllNextPlayer} 一致，只看
     * {@code type.queue(ch)}，也就是「這一個頻道」的佇列；
     * {@link #setNextPlayer} 另外還會掃全頻道，但那是它自己的防重複邏輯，
     * 不是這裡要回答的問題。比對用大小寫敏感的 {@code equals}，和
     * {@link #setNextPlayer}、{@link #copy} 對佇列的做法相同
     * （{@link #containsMember} 的忽略大小寫是 {@code members} 的規矩，
     * 兩邊本來就不一樣）。
     *
     * <p>不上鎖是對的：佇列是 {@code CopyOnWriteArrayList}，
     * {@link #setNextPlayer}、{@link #getNextPlayer} 和 {@link #copy} 也都
     * 直接走訪它。{@code memberLock} 只管 {@code members} /
     * {@code bannedMembers}，不要把它帶進來。答案在回傳的瞬間就可能過期
     * ——另一條 handler 執行緒隨時可能 {@code setNextPlayer}——所以呼叫端
     * 只能拿它做提示，不能拿它當「接下來那個動作一定會成功」的保證。
     */
    public boolean isQueuedNextPlayer(final String name) {
        for (Pair<String, Long> queued : type.queue(ch)) {
            if (queued.left.equals(name)) {
                return true;
            }
        }
        return false;
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

    /**
     * <b>這是個開關，不是「加入佇列」。</b>三條路：人已經在本頻道的佇列裡就
     * 把他移出去；不在本頻道、但在別的頻道的佇列裡就什麼都不做；兩者皆非才
     * 排進本頻道的佇列。
     *
     * <p>{@link #getNextPlayer} 印給玩家看的那段提示文字（「當下一個…或者
     * 移除…」）講的就是前兩條，所以移除那一半是設計的一部分，不是副作用。
     * 三支遠征隊 NPC 的 {@code case 3} 原本在呼叫前擋了一個恆為 false 的
     * {@code getAllNextPlayer().contains(name)}，等於那個擋沒作用、開關照常
     * 運作，只是訊息永遠印「You have reserved the spot.」；現在改成先用
     * {@link #isQueuedNextPlayer} 問一次再決定要印哪一句。
     */
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
            final boolean hasMembers;
            synchronized (memberLock) {
                hasMembers = !members.isEmpty();
            }
            // clear() is called with memberLock released: it broadcasts.
            if (hasMembers && getChar(leaderName) != null) {
                leader = new WeakReference<>(getChar(leaderName));
            } else {
                if (status != 0) {
                    clear();
                }
                return null;
            }
        }
        final WeakReference<MapleCharacter> ref = leader;
        return ref == null ? null : ref.get();
    }

    public boolean containsMember(MapleCharacter member) {
        return containsMember(member.getName());
    }

    /** Case-insensitive, as the loop it replaces was. */
    private boolean containsMember(final String name) {
        synchronized (memberLock) {
            for (String mmbr : members.keySet()) {
                if (mmbr.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> getMembers() {
        synchronized (memberLock) {
            return new LinkedList<>(members.keySet());
        }
    }

    public List<String> getBannedMembers() {
        synchronized (memberLock) {
            return new LinkedList<>(bannedMembers.keySet());
        }
    }

    public int getSquadSize() {
        synchronized (memberLock) {
            return members.size();
        }
    }

    public boolean isBanned(MapleCharacter member) {
        synchronized (memberLock) {
            return bannedMembers.containsKey(member.getName());
        }
    }

    public int addMember(MapleCharacter member, boolean join) {
        final MapleCharacter lead = getLeader();
        if (lead == null) {
            return -1;
        }
        final String name = member.getName();
        final String job = MapleCarnivalChallenge.getJobBasicNameById(member.getJob());
        if (join) {
            // 這裡原本是 getAllNextPlayer().contains(name)：拿 String 去比
            // List<Pair<String, Long>>，永遠是 false，所以這條規則從這份程式碼
            // 存在以來一次都沒生效過（A21）。規則本身是「已經在候補佇列裡的人
            // 不能再以隊員身分加入現役遠征隊」，寫成 isQueuedNextPlayer(name)
            // 就會真的生效——但**刻意不啟用**，因為在這棵樹上會把玩家鎖死：
            //
            // 1. 佇列只有 copy() 會清，而 copy() 只在 scheduleRemoval() 的到期
            //    任務裡被呼叫。EventInstanceManager.registerSquad 一旦
            //    setStatus((byte) 2)，setStatus 就把那個任務 cancel 掉了。
            //    也就是說「遠征隊真的打進去」這條主線永遠不會清佇列，而候補紀錄
            //    掛在 MapleSquadType 的 enum 欄位上，是 JVM 等級的，遠征隊解散、
            //    重開都還在，!clearsquads 也只清 ChannelServer 的 mapleSquads，
            //    碰不到它。
            // 2. 這裡回 -1，三支遠征隊 NPC 一律把 -1 顯示成「You are already
            //    part of the squad.」。所以一旦啟用，帶著陳舊候補紀錄的玩家會
            //    永遠加不進這個頻道的這一種遠征隊，而且看到的是一句和原因毫無
            //    關係的訊息。
            //
            // 要啟用請先處理 (1)（例如讓遠征隊結束時也清掉自己的候補紀錄），
            // 判斷式本身直接寫 if (isQueuedNextPlayer(name)) { return -1; } 即可。
            synchronized (memberLock) {
                if (containsMember(name)) {
                    return -1;
                }
                if (members.size() > 30) {
                    return 2;
                }
                members.put(name, job);
            }
            lead.dropMessage(6, name + " (" + job + ") 加入了遠征隊!");
            return 1;
        }
        synchronized (memberLock) {
            if (!containsMember(name)) {
                return -1;
            }
            members.remove(name);
        }
        lead.dropMessage(6, name + " (" + job + ") 離開了遠征隊.");
        return 1;
    }

    /**
     * Let a banned member back in by their row in the menu
     * {@link #getSquadMemberString}{@code (2)} drew.
     *
     * <p>The size check, the list build and the {@code get(pos)} are now one
     * step under {@link #memberLock}, so {@code pos} can no longer be validated
     * against one version of the ban list and resolved against another.
     *
     * <p>What that does <i>not</i> fix, and cannot be fixed here, is that
     * {@code pos} is an index into a menu the client drew earlier: see the note
     * on {@link #banMember}.
     */
    public void acceptMember(int pos) {
        final MapleCharacter toadd;
        synchronized (memberLock) {
            if (pos < 0 || pos >= bannedMembers.size()) {
                return;
            }
            final String name = new LinkedList<>(bannedMembers.keySet()).get(pos);
            // As before, an offline player is not un-banned at all. getChar is a
            // PlayerStorage map read - no packet, no database - so it is safe
            // under the lock, and resolving it here is what keeps the check and
            // the move atomic.
            toadd = name == null ? null : getChar(name);
            if (toadd == null) {
                return;
            }
            members.put(name, bannedMembers.get(name));
            bannedMembers.remove(name);
        }
        toadd.dropMessage(5, getLeaderName() + " 允許你重新回來遠征隊");
    }

    public void reAddMember(MapleCharacter chr) {
        final String job = MapleCarnivalChallenge.getJobBasicNameById(chr.getJob());
        synchronized (memberLock) {
            // remove then put, as before: it moves the entry to the end of the
            // insertion order rather than leaving it where it was.
            members.remove(chr.getName());
            members.put(chr.getName(), job);
        }
    }

    public void removeMember(MapleCharacter chr) {
        removeMember(chr.getName());
    }

    public void removeMember(String chr) {
        synchronized (memberLock) {
            members.remove(chr); // containsKey first was redundant, and was not atomic with it
        }
    }

    /**
     * Throw a member out by their row in the menu
     * {@link #getSquadMemberString}{@code (1)} drew. Row 0 is the leader and is
     * refused.
     *
     * <p>The three steps - size check, build the list, {@code get(pos)} - are
     * now one step under {@link #memberLock}. That removes the crash and the
     * wrong-list read: the index can no longer be checked against one roster
     * and resolved against another, shorter one.
     *
     * <p><b>It does not, and a lock cannot, answer the question underneath.</b>
     * {@code pos} arrives from an NPC selection: the client drew a numbered
     * list at the moment the leader opened the menu, and sends back the row
     * number when they click. If somebody left the squad in between, row 3 is
     * now a different player, and this bans that different player - silently,
     * and with a message that names them. Deciding what should happen instead
     * is a product call, not a refactor, and it is written up in the task notes
     * rather than answered here. Fixing it at all means the menu carrying the
     * member's name instead of their index, which changes
     * {@link #getSquadMemberString} and every squad NPC script with it.
     */
    public void banMember(int pos) {
        final MapleCharacter banned;
        synchronized (memberLock) {
            if (pos <= 0 || pos >= members.size()) { //may not ban leader
                return;
            }
            final String name = new LinkedList<>(members.keySet()).get(pos);
            // As before, a member who is not online on this channel is not banned.
            banned = name == null ? null : getChar(name);
            if (banned == null) {
                return;
            }
            bannedMembers.put(name, members.get(name));
            members.remove(name);
        }
        banned.dropMessage(5, getLeaderName() + " 從遠征隊中刪除您.");
    }

    /**
     * Note that the status write and the {@code removal} cancel are two steps,
     * so two threads calling this with {@code 2} at the same moment can both
     * see a non-null {@code removal} and both cancel it. Cancelling an already
     * cancelled {@code ScheduledFuture} is a no-op, so that is harmless today;
     * it is written down because it stops being harmless the moment anything
     * else is added here.
     */
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
        synchronized (memberLock) {
            return bannedMembers.size();
        }
    }

    /**
     * Builds one of the four squad menus. The whole build is under
     * {@link #memberLock} so the count in the header and the rows below it come
     * from the same roster, and nobody can be removed from {@code members}
     * halfway down the list and leave the numbering with a hole in it. It only
     * appends to a {@link StringBuilder}, so nothing here sends a packet while
     * the lock is held.
     */
    public String getSquadMemberString(byte type) {
        synchronized (memberLock) {
            return buildSquadMemberString(type);
        }
    }

    /** Caller holds {@link #memberLock}. */
    private String buildSquadMemberString(byte type) {
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

    /**
     * Job-name histogram of the current roster. {@code memberLock} is reentrant,
     * so {@link #buildSquadMemberString} case 3 calling this while already
     * holding it is fine.
     */
    public final Map<String, Integer> getJobs() {
        final Map<String, Integer> jobs = new LinkedHashMap<>();
        synchronized (memberLock) {
            for (Entry<String, String> chr : members.entrySet()) {
                if (jobs.containsKey(chr.getValue())) {
                    jobs.put(chr.getValue(), jobs.get(chr.getValue()) + 1);
                } else {
                    jobs.put(chr.getValue(), 1);
                }
            }
        }
        return jobs;
    }
}
