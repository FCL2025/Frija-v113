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
package database;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import server.ServerProperties;

/**
 * A small, dependency-free JDBC connection pool.
 *
 * <p>This used to hand out one {@link Connection} per thread from a
 * {@link ThreadLocal} that was never emptied. That was workable only because
 * every thread in the process happened to be pooled and long-lived: MINA's
 * workers plus the nine {@code server.Timer} pools already pinned roughly
 * ninety MySQL connections for the lifetime of the server, none of them ever
 * validated, and a single short-lived thread anywhere would have pinned one
 * more forever. It also raced - the bookkeeping list of open connections was a
 * {@code LinkedList} appended to from every thread's first query with no
 * synchronisation at all.
 *
 * <p>The replacement is an ordinary bounded pool. {@link #getConnection()}
 * borrows; {@link Connection#close()} on what it returns gives the connection
 * back instead of closing the socket, so the idiom at every call site is plain
 * try-with-resources:
 *
 * <pre>
 * try (Connection con = DatabaseConnection.getConnection();
 *      PreparedStatement ps = con.prepareStatement(...)) {
 *     ...
 * }
 * </pre>
 *
 * <h2>Why the borrow is thread-bound and reference-counted</h2>
 *
 * <p>A hundred-odd call sites were written against the old contract, where
 * asking twice on one thread handed back the same connection and closing was
 * something you simply never did. Converting all of them in one go, with a
 * plain borrow-and-return pool, would mean the server is broken until the very
 * last one is converted - every unconverted site would pin a connection
 * forever and the pool would run dry on startup.
 *
 * <p>So a borrow is bound to the borrowing thread and reference-counted. Asking
 * again on a thread that already holds one hands back another handle onto the
 * <em>same</em> connection and bumps the count; the connection goes back to the
 * pool when the outermost handle closes. That has three consequences worth
 * knowing:
 *
 * <ul>
 * <li>A converted call site returns its connection promptly, which is the point.
 * <li>A call site that has not been converted yet behaves exactly as it did
 * under the {@code ThreadLocal} - it pins one connection to its thread and
 * never gives it back - so it is no worse than before, and conversion can
 * proceed one file at a time instead of all at once.
 * <li>Nesting keeps the semantics the old code was written against: an outer
 * transaction and an inner helper that asks for its own connection still share
 * one, rather than deadlocking against a bounded pool or committing halves of
 * the same save down two different connections.
 * </ul>
 *
 * <p>{@link #getActiveConnections()} is the number to watch while the remaining
 * call sites are converted. On a fully converted server it falls back to zero
 * whenever the server is idle; a floor that never drops is the count of call
 * sites still holding on.
 *
 * <p>A borrowed connection must not be shared between threads and must not be
 * held across a wait on another thread; borrow it, use it, close it.
 *
 * <p>Connections are validated on the way out and reset on the way back, which
 * is what makes the pool survive MySQL dropping an idle connection after
 * {@code wait_timeout}. That is the job the driver's deprecated
 * {@code autoReconnect} used to be doing, so this class no longer forces that
 * property on; {@code tms.Url} still governs it for anyone who wants it.
 *
 * @author Frz
 */
public final class DatabaseConnection {

    /**
     * Passed to {@code prepareStatement} by the handful of call sites that read
     * an auto-increment id back. Kept here only because that is where they have
     * always looked for it; it is {@link Statement#RETURN_GENERATED_KEYS}.
     */
    public static final int RETURN_GENERATED_KEYS = Statement.RETURN_GENERATED_KEYS;

    /** Maximum number of live connections. Default {@value #DEFAULT_POOL_SIZE}. */
    public static final String POOL_SIZE_PROPERTY = "tms.DbPoolSize";
    /** Milliseconds to wait for a free connection before giving up. */
    public static final String POOL_TIMEOUT_PROPERTY = "tms.DbPoolTimeout";

    /**
     * Has to cover every thread that might still be holding an unreturned
     * connection from an unconverted call site - the packet handlers and the
     * nine timer pools - with room to spare. It can come down once every call
     * site closes what it borrows.
     */
    private static final int DEFAULT_POOL_SIZE = 64;
    private static final int DEFAULT_POOL_TIMEOUT = 15_000;
    /** Seconds given to the driver's own liveness check on borrow. */
    private static final int VALIDATION_TIMEOUT = 3;

    private static volatile Pool pool;

    /**
     * The connection this thread is currently holding, if any. See the class
     * javadoc for why a borrow is thread-bound rather than free-floating.
     */
    private static final ThreadLocal<Handle> BOUND = new ThreadLocal<>();

    private DatabaseConnection() {
    }

    /**
     * Borrows a connection. Close it - ideally with try-with-resources - to
     * give it back. Asking again on a thread that already holds one returns
     * another handle onto that same connection.
     *
     * @throws IllegalStateException if no connection came free within
     * {@link #POOL_TIMEOUT_PROPERTY}, which in practice means a call site
     * somewhere is not closing what it borrows.
     */
    public static Connection getConnection() {
        final Handle bound = BOUND.get();
        if (bound != null) {
            return bound.reenter();
        }
        try {
            return pool().borrow();
        } catch (final SQLException e) {
            // Historically this returned null on failure and every one of the
            // hundred-odd call sites dereferenced it blind. Failing here at
            // least names the cause.
            throw new IllegalStateException("Could not obtain a database connection", e);
        }
    }


    /**
     * Closes every pooled connection. Called once from {@code ShutdownServer};
     * the pool refuses to hand anything out afterwards.
     */
    public static void closeAll() throws SQLException {
        final Pool p = pool;
        if (p != null) {
            p.shutdown();
        }
    }

    /** Connections currently checked out. Exposed for diagnostics. */
    public static int getActiveConnections() {
        final Pool p = pool;
        return p == null ? 0 : p.active.get();
    }

    /** Connections sitting idle in the pool. Exposed for diagnostics. */
    public static int getIdleConnections() {
        final Pool p = pool;
        return p == null ? 0 : p.idle.size();
    }

    /** The pool's upper bound, i.e. {@link #POOL_SIZE_PROPERTY} as it took effect. Exposed for diagnostics. */
    public static int getPoolSize() {
        final Pool p = pool;
        return p == null ? 0 : p.size;
    }

    private static Pool pool() {
        Pool p = pool;
        if (p == null) {
            synchronized (DatabaseConnection.class) {
                p = pool;
                if (p == null) {
                    p = new Pool();
                    pool = p;
                }
            }
        }
        return p;
    }

    private static int intProperty(final String key, final int fallback) {
        final String value = ServerProperties.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static final class Pool {

        private final ConcurrentLinkedDeque<Connection> idle = new ConcurrentLinkedDeque<>();
        private final AtomicInteger active = new AtomicInteger();
        /**
         * Which thread is holding which connection, so that a connection can be
         * reclaimed when its owner dies still holding it. Both the packet
         * handler pool and the timer pools let idle threads time out, so a
         * worker that picked up an unconverted call site - one that never
         * closes - would take its permit to the grave and the pool would drain
         * a connection at a time until it stopped handing anything out.
         */
        private final Map<Thread, Handle> owners = new ConcurrentHashMap<>();
        private final Semaphore permits;
        private final int size;
        private final long borrowTimeout;
        private final String url;
        private final Properties props;
        private volatile boolean closed;

        Pool() {
            final int size = Math.max(1, intProperty(POOL_SIZE_PROPERTY, DEFAULT_POOL_SIZE));
            this.permits = new Semaphore(size, true);
            this.size = size;
            this.borrowTimeout = Math.max(1000, intProperty(POOL_TIMEOUT_PROPERTY, DEFAULT_POOL_TIMEOUT));
            this.url = ServerProperties.getProperty("tms.Url");
            this.props = new Properties();
            this.props.put("user", String.valueOf(ServerProperties.getProperty("tms.User")));
            this.props.put("password", String.valueOf(ServerProperties.getProperty("tms.Pass")));
            touchDriver();
        }

        /**
         * Connector/J 8 renamed the driver class from com.mysql.jdbc.Driver to
         * com.mysql.cj.jdbc.Driver. DriverManager discovers either one through
         * the service loader by itself, so this exists only so that a classpath
         * without the jar fails with a message a human can act on instead of a
         * bare "No suitable driver".
         */
        private static void touchDriver() {
            for (final String driver : new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"}) {
                try {
                    Class.forName(driver);
                    return;
                } catch (final ClassNotFoundException e) {
                    // Not that one - fall through to the older name.
                }
            }
            System.err.println("ERROR: no MySQL JDBC driver on the classpath.");
        }

        Connection borrow() throws SQLException {
            if (closed) {
                throw new SQLException("The connection pool has been shut down.");
            }
            try {
                if (!permits.tryAcquire(borrowTimeout, TimeUnit.MILLISECONDS)) {
                    // Before giving up, take back anything owned by a thread
                    // that has since died.
                    final int reaped = reapDeadOwners();
                    if (reaped == 0 || !permits.tryAcquire(borrowTimeout, TimeUnit.MILLISECONDS)) {
                        throw new SQLTransientConnectionException("Timed out after " + borrowTimeout
                                + "ms waiting for a free database connection (" + active.get()
                                + " in use). A call site is most likely not closing what it borrows.");
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for a database connection", e);
            }
            boolean handedOut = false;
            try {
                final Connection raw = takeLive();
                active.incrementAndGet();
                handedOut = true;
                final Handle handle = new Handle(this, raw);
                BOUND.set(handle);
                owners.put(Thread.currentThread(), handle);
                return handle.proxy();
            } finally {
                if (!handedOut) {
                    permits.release();
                }
            }
        }

        /** Pops idle connections until one is still alive, else opens a new one. */
        private Connection takeLive() throws SQLException {
            Connection con;
            while ((con = idle.pollLast()) != null) {
                try {
                    if (con.isValid(VALIDATION_TIMEOUT)) {
                        return con;
                    }
                } catch (final SQLException e) {
                    // Treat an unanswerable connection as a dead one.
                }
                closeQuietly(con);
            }
            return DriverManager.getConnection(url, props);
        }

        /**
         * Takes back every connection whose owning thread has died still
         * holding it.
         *
         * @return how many were reclaimed
         */
        private int reapDeadOwners() {
            int reaped = 0;
            for (final Map.Entry<Thread, Handle> entry : owners.entrySet()) {
                if (!entry.getKey().isAlive() && owners.remove(entry.getKey(), entry.getValue())) {
                    System.err.println("[db] Reclaiming a connection abandoned by "
                            + entry.getKey().getName() + "; a call site there is not closing what it borrows.");
                    entry.getValue().abandon();
                    reaped++;
                }
            }
            return reaped;
        }

        void release(final Handle handle, final Connection raw) {
            owners.remove(handle.owner, handle);
            active.decrementAndGet();
            try {
                if (closed || raw.isClosed()) {
                    closeQuietly(raw);
                } else {
                    reset(raw);
                    idle.addLast(raw);
                }
            } catch (final SQLException e) {
                closeQuietly(raw);
            } finally {
                permits.release();
            }
        }

        /**
         * Undoes anything the borrower left behind. An abandoned transaction is
         * the dangerous one: {@code MapleCharacter.saveToDB} turns auto-commit
         * off, and a connection handed to the next borrower still inside that
         * transaction would silently swallow their writes.
         */
        private static void reset(final Connection con) throws SQLException {
            if (!con.getAutoCommit()) {
                con.rollback();
                con.setAutoCommit(true);
            }
            con.clearWarnings();
        }

        private static void closeQuietly(final Connection con) {
            try {
                con.close();
            } catch (final SQLException e) {
                // Nothing useful to do while discarding a connection.
            }
        }

        void shutdown() {
            closed = true;
            Connection con;
            while ((con = idle.poll()) != null) {
                closeQuietly(con);
            }
        }
    }

    /**
     * Makes {@code close()} mean "give it back". Every other call goes straight
     * to the driver. A dynamic proxy rather than a hand-written delegate,
     * because {@link Connection} has some fifty methods and none of the rest
     * need intercepting.
     */
    private static final class Handle implements InvocationHandler {

        private final Pool pool;
        private final Connection raw;
        /**
         * Statements opened through this handle. The legacy call sites are not
         * uniformly careful about closing their own, and a statement left open
         * on a pooled connection keeps a cursor alive on the server for as long
         * as the pool does - so the pool closes them when the connection comes
         * back.
         */
        private final List<Statement> statements = new ArrayList<>(4);
        private final Connection proxy;
        /**
         * How many handles onto this connection are still open. Only ever
         * touched by the one thread this handle is bound to, so a plain int is
         * enough.
         */
        private int depth = 1;
        private final AtomicBoolean released = new AtomicBoolean();
        /** The thread this handle is bound to, so the reaper can identify it. */
        private final Thread owner = Thread.currentThread();

        private Handle(final Pool pool, final Connection raw) {
            this.pool = pool;
            this.raw = raw;
            this.proxy = (Connection) Proxy.newProxyInstance(
                    Handle.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    this);
        }

        Connection proxy() {
            return proxy;
        }

        /**
         * A second, nested ask on the same thread. The caller gets the same
         * connection back and its close only counts down.
         */
        Connection reenter() {
            depth++;
            return proxy;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            switch (method.getName()) {
                case "close" -> {
                    release();
                    return null;
                }
                case "isClosed" -> {
                    return released.get() || raw.isClosed();
                }
                // Two handles onto one underlying connection would be a bug, so
                // identity here is the handle's own, not the driver's.
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "toString" -> {
                    return "PooledConnection[" + raw + (released.get() ? ", released]" : "]");
                }
                default -> {
                }
            }
            if (released.get()) {
                throw new SQLException("This connection has already been returned to the pool.");
            }
            try {
                final Object result = method.invoke(raw, args);
                if (result instanceof Statement statement) {
                    synchronized (statements) {
                        statements.add(statement);
                    }
                }
                return result;
            } catch (final InvocationTargetException e) {
                throw e.getCause();
            }
        }

        private void release() {
            if (released.get()) {
                // Double close: try-with-resources over an already-closed
                // connection is legal and must stay a no-op, or the pool would
                // hand the same connection out twice.
                return;
            }
            if (--depth > 0) {
                // An inner, nested holder let go; the outer one still has it.
                return;
            }
            BOUND.remove();
            giveBack();
        }

        /**
         * Reclaims a connection whose owner died still holding it. Called from
         * the reaper, i.e. from a thread that is not the owner - which is why
         * the handover is guarded by a compare-and-set rather than a plain
         * flag, and why the owner's {@code ThreadLocal} is not touched here
         * (there is no owner left to touch it for, and the entry goes with the
         * dead thread).
         */
        void abandon() {
            giveBack();
        }

        private void giveBack() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (statements) {
                for (final Statement statement : statements) {
                    try {
                        statement.close();
                    } catch (final SQLException e) {
                        // Already closed by a well-behaved caller, most likely.
                    }
                }
                statements.clear();
            }
            pool.release(this, raw);
        }
    }
}
