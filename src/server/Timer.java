package server;

import java.util.List;
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
 * <p>These were nine nested classes, each one a {@code static class XxxTimer
 * extends Timer} holding a private singleton and a {@code getInstance()} that
 * returned it - about a hundred lines that differed only in the pool's name.
 * That is what an enum is, so this is one now, and a call site says
 * {@code Timer.ETC.schedule(...)} instead of {@code EtcTimer.getInstance()
 * .schedule(...)}. It also removes the {@code CopyOnWriteArrayList} that
 * {@link #stopAll()} used to walk: the list of pools is {@link #values()}.
 *
 * <p>Every pool used to be created as {@code new ScheduledThreadPoolExecutor(3)}
 * and then told {@code setCorePoolSize(4)} and {@code setMaximumPoolSize(8)}.
 * The last of those does nothing at all - a scheduled executor's queue is an
 * unbounded {@code DelayedWorkQueue}, so it never grows past the core size and
 * the maximum is ignored - which meant every pool was silently a four-thread
 * pool no matter what the numbers said. The size is now one deliberate number,
 * scaled to the machine and overridable with {@code tms.TimerThreads}.
 */
public enum Timer {

    WORLD("WorldTimer"),
    MAP("MapTimer"),
    BUFF("BuffTimer"),
    EVENT("EventTimer"),
    CLONE("CloneTimer"),
    ETC("EtcTimer"),
    MOB("MobTimer"),
    CHEAT("CheatTimer"),
    PING("PingTimer");

    private static final System.Logger LOG = System.getLogger(Timer.class.getName());

    private static final String THREADS_PROPERTY = "tms.TimerThreads";
    private static final int DEFAULT_THREADS =
            Math.max(4, Runtime.getRuntime().availableProcessors());

    /**
     * Names the pool's threads and its log file, and through the file its
     * logger.
     *
     * <p>The camel case is load-bearing, and it used to be wrong. These names
     * were spelled {@code "Maptimer"} with a lower-case t, so the log file came
     * out as {@code Log_Maptimer_Except.rtf} and
     * {@code FileoutputUtil.loggerName} - whose whole purpose for this family is
     * the {@code s.endsWith("Timer")} branch that rewrites {@code MapTimer} into
     * {@code tms.timer.map} - never matched. The nine pools therefore logged
     * under {@code tms.maptimer}, {@code tms.worldtimer} and so on, which meant
     * the {@code tms.timer.level = OFF} switch that {@code Libs/logging.properties}
     * documents silenced nothing at all.
     */
    private final String poolName;

    /** Where a task that threw out of {@code run} is recorded. */
    private final String file;

    private volatile ScheduledExecutorService ses;

    Timer(final String poolName) {
        this.poolName = poolName;
        this.file = "Logs/Log_" + poolName + "_Except.rtf";
    }

    public void start() {
        if (isRunning()) {
            return;
        }
        synchronized (this) {
            if (isRunning()) {
                return;
            }
            final ScheduledThreadPoolExecutor stpe =
                    new ScheduledThreadPoolExecutor(poolSize(), namedThreadFactory(poolName));
            // Nothing here is worth blocking a shutdown on, and periodic tasks
            // that outlive the shutdown just log into a closing world.
            stpe.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
            stpe.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            stpe.setRemoveOnCancelPolicy(true);
            stpe.setKeepAliveTime(10, TimeUnit.MINUTES);
            stpe.allowCoreThreadTimeOut(true);
            ses = stpe;
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
                    // WARNING rather than INFO: scheduled work was thrown away.
                    LOG.log(System.Logger.Level.WARNING, poolName + ": abandoned " + abandoned.size()
                            + " task(s) that did not finish in time.");
                }
            }
        } catch (final InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Stops every pool that was ever started. Used by the shutdown hook.
     *
     * <p>A pool that was never started has a null {@code ses} and {@link #stop}
     * returns immediately, so walking all nine is the same work the old
     * "every pool that registered itself on first start" list did - without the
     * list. The old {@code ShutdownServer} stopped seven of the nine by name
     * and left {@code CheatTimer} and {@code PingTimer} running.
     */
    public static void stopAll() {
        for (final Timer timer : values()) {
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

    public ScheduledFuture<?> scheduleAtTimestamp(final Runnable r, final long timestamp) {
        return schedule(r, timestamp - System.currentTimeMillis());
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

    private static ThreadFactory namedThreadFactory(final String name) {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return r -> {
            final Thread t = new Thread(r, name + "-Worker-" + threadNumber.getAndIncrement());
            // A stuck timer task must not keep a shut-down JVM alive.
            t.setDaemon(true);
            return t;
        };
    }
}
