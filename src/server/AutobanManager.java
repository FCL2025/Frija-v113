package server;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import client.MapleClient;
import handling.world.Broadcast;
import java.util.concurrent.locks.ReentrantLock;
import tools.packet.ChatPacket;

public class AutobanManager implements Runnable {

    private static class ExpirationEntry implements Comparable<ExpirationEntry> {

        public long time;
        public int acc;
        public int points;

        public ExpirationEntry(long time, int acc, int points) {
            this.time = time;
            this.acc = acc;
            this.points = points;
        }

        /**
         * These live in a {@link TreeSet}, which means the ordering has to be
         * a total order consistent with {@link #equals} or entries vanish. The
         * old {@code (int) (time - o.time)} was neither: it overflowed for
         * timestamps more than 24 days apart, and it called every two entries
         * scheduled in the same millisecond equal, so the second one was
         * silently dropped instead of being stored.
         */
        @Override
        public int compareTo(final AutobanManager.ExpirationEntry o) {
            return ORDER.compare(this, o);
        }

        @Override
        public boolean equals(final Object oth) {
            return oth instanceof AutobanManager.ExpirationEntry ee
                    && time == ee.time && points == ee.points && acc == ee.acc;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(time) * 31 * 31 + acc * 31 + points;
        }
    }

    private static final Comparator<ExpirationEntry> ORDER =
            Comparator.comparingLong((ExpirationEntry e) -> e.time)
                    .thenComparingInt(e -> e.acc)
                    .thenComparingInt(e -> e.points);
    private Map<Integer, Integer> points = new HashMap<>();
    private Map<Integer, List<String>> reasons = new HashMap<>();
    private Set<ExpirationEntry> expirations = new TreeSet<>();
    private static final int AUTOBAN_POINTS = 5000;
    private static AutobanManager instance = new AutobanManager();
    private final ReentrantLock lock = new ReentrantLock(true);

    public static final AutobanManager getInstance() {
        return instance;
    }

    public final void autoban(final MapleClient c, final String reason) {
        if (c.getPlayer().isGM() || c.getPlayer().isClone()) {
            c.getPlayer().dropMessage(5, "[WARNING] A/b triggled : " + reason);
            return;
        }
        addPoints(c, AUTOBAN_POINTS, 0, reason);
    }

    public final void addPoints(final MapleClient c, final int points, final long expiration, final String reason) {
        lock.lock();
        try {
            List<String> reasonList;
            final int acc = c.getPlayer().getAccountID();

            if (this.points.containsKey(acc)) {
                final int SavedPoints = this.points.get(acc);
                if (SavedPoints >= AUTOBAN_POINTS) { // Already auto ban'd.
                    return;
                }
                this.points.put(acc, SavedPoints + points); // Add
                reasonList = this.reasons.get(acc);
                reasonList.add(reason);
            } else {
                this.points.put(acc, points);
                reasonList = new LinkedList<>();
                reasonList.add(reason);
                this.reasons.put(acc, reasonList);
            }

            if (this.points.get(acc) >= AUTOBAN_POINTS) { // See if it's sufficient to auto ban
                if (c.getPlayer().isGM() || c.getPlayer().isClone()) {
                    c.getPlayer().dropMessage(5, "[WARNING] A/b triggled : " + reason);
                    return;
                }
                final StringBuilder sb = new StringBuilder("a/b ");
                sb.append(c.getPlayer().getName());
                sb.append(" (IP ");
                sb.append(c.getSession().getRemoteAddress().toString());
                sb.append("): ");
                for (final String s : reasons.get(acc)) {
                    sb.append(s);
                    sb.append(", ");
                }
                Broadcast.broadcastMessage(ChatPacket.serverNotice(0, "'" + c.getPlayer().getName() + "'使用外掛程式遭到系統封鎖").getBytes());
                c.getPlayer().ban(sb.toString(), false, true, false);
                c.disconnect(true, false);
            } else {
                if (expiration > 0) {
                    expirations.add(new ExpirationEntry(System.currentTimeMillis() + expiration, acc, points));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Decays points whose expiry has passed. Scheduled once a minute onto
     * {@code Timer.CHEAT}.
     *
     * <p>This never ran until the timer pools were made self-starting, which is
     * why two bugs in it went unnoticed: it walked {@code expirations} without
     * taking the lock that every writer takes, and it subtracted an entry's
     * points without ever removing the entry - so once live it would have
     * re-subtracted the same points every minute forever and driven every
     * account's score to negative infinity, quietly disabling the autoban
     * entirely. It now removes what it decays, under the lock.
     */
    public final void run() {
        final long now = System.currentTimeMillis();
        lock.lock();
        try {
            // 這裡不能用 removeIf：本體除了移除之外還要把點數扣回 points，
            // 而且靠 return 在遇到第一筆未到期的項目時提前結束（集合是照時間排序的）；
            // removeIf 一定走完整個集合，語意不同。
            final Iterator<ExpirationEntry> it = expirations.iterator();
            while (it.hasNext()) {
                final ExpirationEntry e = it.next();
                // The set is sorted by time, so the first entry still in the
                // future ends the sweep.
                if (e.time > now) {
                    return;
                }
                final Integer current = points.get(e.acc);
                if (current != null) {
                    points.put(e.acc, current - e.points);
                }
                it.remove();
            }
        } finally {
            lock.unlock();
        }
    }
}
