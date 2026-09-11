package server;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import tools.FileoutputUtil;

/**
 * The named scheduled thread pools the whole server schedules onto. Nothing in
 * the tree should ever start a bare {@link Thread}: pick the pool that matches
 * the work and {@link #schedule} onto it, so the work is named, bounded, and
 * shut down with everything else.
 *
 * <p>Every pool used to be created as {@code new ScheduledThreadPoolExecutor(3)}
 * and then told {@code setCorePoolSize(4)} and {@code setMaximumPoolSize(8)}.
 * The last of those does nothing at all - a scheduled executor's queue is an
 * unbounded {@code DelayedWorkQueue}, so it never grows past the core size and
 * the maximum is ignored - which meant every pool was silently a four-thread
 * pool no matter what the numbers said. The size is now one deliberate number,
 * scaled to the machine and overridable with {@code tms.TimerThreads}.
 */
public abstract class Timer {

    public static class WorldTimer extends Timer {

        private static final WorldTimer instance = new WorldTimer();

        private WorldTimer() {
            name = "Worldtimer";
        }

        public static WorldTimer getInstance() {
            return instance;
        }
    }

    public static class MapTimer extends Timer {

        private static final MapTimer instance = new MapTimer();

        private MapTimer() {
            name = "Maptimer";
        }

        public static MapTimer getInstance() {
            return instance;
        }
    }

    public static class BuffTimer extends Timer {

        private static final BuffTimer instance = new BuffTimer();

        private BuffTimer() {
            name = "Bufftimer";
        }

        public static BuffTimer getInstance() {
            return instance;
        }
    }

    public static class EventTimer extends Timer {

        private static final EventTimer instance = new EventTimer();

        private EventTimer() {
            name = "Eventtimer";
        }

        public static EventTimer getInstance() {
            return instance;
        }
    }

    public static class CloneTimer extends Timer {

        private static final CloneTimer instance = new CloneTimer();

        private CloneTimer() {
            name = "Clonetimer";
        }

        public static CloneTimer getInstance() {
            return instance;
        }
    }

    public static class EtcTimer extends Timer {

        private static final EtcTimer instance = new EtcTimer();

        private EtcTimer() {
            name = "Etctimer";
        }

        public static EtcTimer getInstance() {
            return instance;
        }
    }

    public static class MobTimer extends Timer {

        private static final MobTimer instance = new MobTimer();

        private MobTimer() {
            name = "Mobtimer";
        }

        public static MobTimer getInstance() {
            return instance;
        }
    }

    public static class CheatTimer extends Timer {

        private static final CheatTimer instance = new CheatTimer();

        private CheatTimer() {
            name = "Cheattimer";
        }

        public static CheatTimer getInstance() {
            return instance;
        }
    }

    public static class PingTimer extends Timer {

        private static final PingTimer instance = new PingTimer();

        private PingTimer() {
            name = "Pingtimer";
        }

        public static PingTimer getInstance() {
            return instance;
        }
    }

    /**
     * Every pool that has ever been started, so that {@link #stopAll()} can
     * reach the ones a hand-written shutdown list would forget. The old
     * {@code ShutdownServer} stopped seven of the nine by name and left
     * {@code CheatTimer} and {@code PingTimer} running.
     */
    private static final Collection<Timer> STARTED = new CopyOnWriteArrayList<>();

    private static final String THREADS_PROPERTY = "tms.TimerThreads";
    private static final int DEFAULT_THREADS =
            Math.max(4, Runtime.getRuntime().availableProcessors());

    private volatile ScheduledExecutorService ses;
    protected String file, name;

    public void start() {
        if (isRunning()) {
            return;
        }
        synchronized (this) {
            if (isRunning()) {
                return;
            }
            file = "Logs/Log_" + name + "_Except.rtf";

            final ScheduledThreadPoolExecutor stpe =
                    new ScheduledThreadPoolExecutor(poolSize(), namedThreadFactory(name));
            // Nothing here is worth blocking a shutdown on, and periodic tasks
            // that outlive the shutdown just log into a closing world.
            stpe.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            stpe.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            stpe.setRemoveOnCancelPolicy(true);
            stpe.setKeepAliveTime(10, TimeUnit.MINUTES);
            stpe.allowCoreThreadTimeOut(true);
            ses = stpe;
            STARTED.add(this);
        }
    }

    public boolean isRunning() {
        final ScheduledExecutorService current = ses;
        return current != null && !current.isShutdown();
    }

    public void stop() {
        final ScheduledExecutorService current = ses;
        if (current == null) {
            return;
        }
        current.shutdown();
        try {
            if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
                final List<Runnable> abandoned = current.shutdownNow();
                if (!abandoned.isEmpty()) {
                    System.out.println(name + ": abandoned " + abandoned.size()
                            + " task(s) that did not finish in time.");
                }
            }
        } catch (final InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
        STARTED.remove(this);
    }

    /** Stops every pool that was ever started. Used by the shutdown hook. */
    public static void stopAll() {
        for (final Timer timer : STARTED) {
            timer.stop();
        }
    }

    public ScheduledFuture<?> register(final Runnable r, final long repeatTime, final long delay) {
        final ScheduledExecutorService current = pool();
        if (current == null) {
            return null;
        }
        return current.scheduleAtFixedRate(logging(r), delay, repeatTime, TimeUnit.MILLISECONDS);
    }

    public ScheduledFuture<?> register(final Runnable r, final long repeatTime) {
        return register(r, repeatTime, 0);
    }

    public ScheduledFuture<?> schedule(final Runnable r, final long delay) {
        final ScheduledExecutorService current = pool();
        if (current == null) {
            return null;
        }
        return current.schedule(logging(r), delay, TimeUnit.MILLISECONDS);
    }

    /**
     * The pool, started on demand.
     *
     * <p>This used to return null whenever {@link #start()} had not been called
     * for this particular pool, and the schedule was then dropped in silence.
     * {@code Start.main} started seven of the nine, so four subsystems were
     * dead code that looked live: the ping-timeout disconnect in
     * {@code MapleClient}, the idle-login task in {@code LoginWorker}, the
     * {@code PlayerStorage.PersistingTask} sweep of stale pending transfers,
     * and the {@code AutobanManager} point decay. Starting on first use is what
     * makes a schedule mean what it says.
     */
    private ScheduledExecutorService pool() {
        if (!isRunning()) {
            start();
        }
        return ses;
    }

    public ScheduledFuture<?> scheduleAtTimestamp(final Runnable r, final long timestamp) {
        return schedule(r, timestamp - System.currentTimeMillis());
    }

    /**
     * A task that throws out of {@code run} silently kills a repeating
     * schedule - {@code scheduleAtFixedRate} cancels the whole series on the
     * first exception. Swallowing it into the pool's log file is what has kept
     * the periodic work alive, so every task goes through here.
     */
    private Runnable logging(final Runnable r) {
        final String logFile = file;
        return () -> {
            try {
                r.run();
            } catch (final Throwable t) {
                FileoutputUtil.outputFileError(logFile, t);
            }
        };
    }

    private static int poolSize() {
        final String configured = ServerProperties.getProperty(THREADS_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(configured.trim()));
            } catch (final NumberFormatException e) {
                // Fall through to the default rather than refuse to start.
            }
        }
        return DEFAULT_THREADS;
    }

    private static ThreadFactory namedThreadFactory(final String poolName) {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return r -> {
            final Thread t = new Thread(r, poolName + "-Worker-" + threadNumber.getAndIncrement());
            // A stuck timer task must not keep a shut-down JVM alive.
            t.setDaemon(true);
            return t;
        };
    }
}
