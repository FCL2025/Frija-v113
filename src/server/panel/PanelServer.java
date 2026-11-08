package server.panel;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import server.ServerProperties;
import server.Timer;

/**
 * The embedded management panel: one {@link HttpServer} on the loopback
 * address, living in the same JVM as the game so that it can read
 * {@code World} and {@code ChannelServer} directly.
 *
 * <p>It is built on the JDK's own {@code com.sun.net.httpserver} rather than
 * a servlet container or a Swing/JavaFX window because that costs no new jar
 * at all, and every jar already in {@code dist/lib/} is one the JDK forced on
 * this tree. The frontend is a static bundle served by {@link StaticHandler};
 * the API is {@link Router} plus the routes {@link PanelApi} registers.
 *
 * <p>Threading: {@code HttpServer.setExecutor(null)} would run every request
 * on the single dispatch thread, so one slow database query would freeze the
 * whole panel. The pool here is a fixed number of named platform threads,
 * {@code Panel-Worker-N}, shaped like {@code MapleAcceptor}'s handler pool.
 * Not virtual threads: {@code DatabaseConnection} binds each borrowed
 * connection to the borrowing thread, reference-counts it, and reaps
 * connections whose owning thread has died - a fresh thread per request
 * would make that bookkeeping churn for nothing and take the thread names
 * out of the logs.
 *
 * <p>Failing to bind the port is not fatal. The game server starts without
 * the panel and says so loudly; a stale process holding port 8080 must not
 * keep the login server from coming up.
 */
public final class PanelServer {

    private static final System.Logger LOG = System.getLogger(PanelServer.class.getName());

    /** Listening port. {@code 0} disables the panel. Default {@value #DEFAULT_PORT}. */
    public static final String PORT_PROPERTY = "tms.PanelPort";
    /** Size of the request pool. Default {@code max(4, cores)}. */
    public static final String THREADS_PROPERTY = "tms.PanelThreads";
    /**
     * A fixed login token. When unset, a fresh one is generated at every start
     * and printed to the console - fine for a machine that is rarely
     * restarted, tedious while developing.
     */
    public static final String TOKEN_PROPERTY = "tms.PanelToken";

    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());

    private static volatile PanelServer instance;
    /** Set once {@code Start.main} has printed {@code Loaded Complete}. */
    private static volatile long startupMillis = -1;

    private final HttpServer server;
    private final ThreadPoolExecutor pool;
    private final Router router;
    private volatile String token;
    private final int port;

    private PanelServer(final HttpServer server, final ThreadPoolExecutor pool, final Router router,
            final String token, final int port) {
        this.server = server;
        this.pool = pool;
        this.router = router;
        this.token = token;
        this.port = port;
    }

    /** Starts the panel, or explains why it could not. Never throws. */
    public static synchronized void start() {
        if (instance != null) {
            return;
        }
        final int port = intProperty(PORT_PROPERTY, DEFAULT_PORT);
        if (port <= 0) {
            LOG.log(System.Logger.Level.INFO, "[面板] " + PORT_PROPERTY + " = 0，管理面板停用。");
            return;
        }
        final PanelServer p = create(port, resolveToken(), Math.max(1, intProperty(THREADS_PROPERTY, DEFAULT_THREADS)));
        if (p == null) {
            return;
        }
        instance = p;
        // One record: the two lines are one banner, and two calls would let
        // another thread log between the address and the token.
        LOG.log(System.Logger.Level.INFO,
                "[面板] 管理面板：http://127.0.0.1:" + port + "/  （" + p.pool.getMaximumPoolSize() + " 條處理執行緒）"
                + System.lineSeparator() + "[面板] 登入 Token：" + p.token);
    }

    /**
     * Moves the panel to {@code port}, turns it off ({@code 0}) or on - while
     * the game keeps running.
     *
     * <p>This is reached from a panel request, i.e. from one of the very
     * threads it is about to stop, and {@code HttpServer.stop} waits for the
     * exchanges in flight to finish - including the one asking. So the move is
     * handed to {@code Timer.ETC} and happens just after the reply has gone
     * out. The new port is bound before the old one is let go: a port that is
     * taken leaves the panel where it was.
     *
     * @return what will happen, for the reply
     */
    public static String scheduleMove(final int port) {
        final PanelServer current = instance;
        if (current != null && current.port == port) {
            return "管理面板已在 port " + port;
        }
        Timer.ETC.schedule(() -> move(port), 500);
        if (port <= 0) {
            return "管理面板將在 0.5 秒後關閉；要再開啟，請直接編輯 Settings.ini 的 " + PORT_PROPERTY;
        }
        return "管理面板將在 0.5 秒後移到 http://127.0.0.1:" + port + "/ ，請改用新網址";
    }

    private static synchronized void move(final int port) {
        final PanelServer old = instance;
        if (port <= 0) {
            stop();
            return;
        }
        if (old == null) {
            start();
            return;
        }
        final PanelServer fresh = create(port, old.token, old.pool.getMaximumPoolSize());
        if (fresh == null) {
            return;
        }
        instance = fresh;
        old.halt();
        LOG.log(System.Logger.Level.INFO, "[面板] 管理面板已移到 http://127.0.0.1:" + port + "/");
    }

    /**
     * Changes the login token. Every browser still holding the old one gets a
     * 401 on its next request and is sent back to the login screen. A blank
     * value means "a random token per start", and the running panel already
     * has one, so it keeps it.
     *
     * @return what happened, for the reply
     */
    public static String setToken(final String token) {
        final PanelServer p = instance;
        if (p == null) {
            return "管理面板沒有在執行";
        }
        if (token == null || token.isBlank()) {
            return "留空代表每次啟動隨機產生；目前這組隨機 Token 繼續有效";
        }
        p.token = token.trim();
        p.router.setToken(p.token);
        LOG.log(System.Logger.Level.INFO, "[面板] 登入 Token 已更換，舊的 Token 立即失效。");
        return "Token 已更換，請用新的 Token 重新登入";
    }

    /** Resizes the request pool. {@code threads <= 0} means the default. */
    public static void setThreads(final int threads) {
        final PanelServer p = instance;
        if (p == null) {
            return;
        }
        final int n = threads <= 0 ? DEFAULT_THREADS : threads;
        synchronized (p.pool) {
            if (n > p.pool.getMaximumPoolSize()) {
                p.pool.setMaximumPoolSize(n);
                p.pool.setCorePoolSize(n);
            } else {
                p.pool.setCorePoolSize(n);
                p.pool.setMaximumPoolSize(n);
            }
        }
    }

    /** Builds and starts one HTTP server, or logs why it could not and returns null. */
    private static PanelServer create(final int port, final String token, final int threads) {
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (final BindException e) {
            // ERROR, and terse on purpose: this one only ever reported
            // e.getMessage(). The panel is given up on; the game still starts.
            LOG.log(System.Logger.Level.ERROR, "[面板] 無法綁定 127.0.0.1:" + port + "（" + e.getMessage() + "）。"
                    + "port 已被其他程式占用；"
                    + (instance == null ? "管理面板停用，伺服器照常啟動" : "管理面板留在原本的 port")
                    + "。可在 Settings.ini 以 " + PORT_PROPERTY + " 改用別的 port。");
            return null;
        } catch (final IOException e) {
            // ERROR, and terse on purpose: this one only ever concatenated
            // the exception. The panel is given up on; the game still starts.
            LOG.log(System.Logger.Level.ERROR, "[面板] 無法建立 HTTP 伺服器：" + e);
            return null;
        }

        final ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads,
                60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), namedThreadFactory());
        pool.allowCoreThreadTimeOut(true);
        server.setExecutor(pool);

        final Router router = new Router(token, port);
        PanelApi.register(router);
        ActionsApi.register(router);
        SettingsApi.register(router);
        ScriptsApi.register(router);
        AccountsApi.register(router);
        DataApi.register(router);
        server.createContext("/api", router);
        server.createContext("/", new StaticHandler());
        server.start();
        return new PanelServer(server, pool, router, token, port);
    }

    /**
     * Stops accepting and lets in-flight requests finish for a moment. Called
     * first thing in {@code ShutdownServer.run}, before the channels go down:
     * the shutdown sequence ends with a five-second drain and then
     * {@code DatabaseConnection.closeAll()}, and a panel request arriving in
     * that window would borrow from a pool that is about to close.
     */
    public static synchronized void stop() {
        final PanelServer p = instance;
        if (p == null) {
            return;
        }
        instance = null;
        p.halt();
        LOG.log(System.Logger.Level.INFO, "[面板] 管理面板已關閉。");
    }

    private void halt() {
        server.stop(1);
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (final InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static PanelServer getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void setStartupMillis(final long millis) {
        startupMillis = millis;
    }

    public static long getStartupMillis() {
        return startupMillis;
    }

    public String getToken() {
        return token;
    }

    public int getPort() {
        return port;
    }

    private static String resolveToken() {
        final String fixed = ServerProperties.getProperty(TOKEN_PROPERTY);
        if (fixed != null && !fixed.isBlank()) {
            return fixed.trim();
        }
        final byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static int intProperty(final String key, final int def) {
        final String configured = ServerProperties.getProperty(key);
        if (configured != null && !configured.isBlank()) {
            try {
                return Integer.parseInt(configured.trim());
            } catch (final NumberFormatException e) {
                // WARNING: the default is used and the panel starts anyway.
                LOG.log(System.Logger.Level.WARNING, "[面板] " + key + " 不是整數（" + configured + "），改用預設值 " + def + "。");
            }
        }
        return def;
    }

    private static ThreadFactory namedThreadFactory() {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return r -> new Thread(r, "Panel-Worker-" + threadNumber.getAndIncrement());
    }
}
