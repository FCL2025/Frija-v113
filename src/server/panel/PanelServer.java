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
    private final String token;
    private final int port;

    private PanelServer(final HttpServer server, final ThreadPoolExecutor pool, final String token, final int port) {
        this.server = server;
        this.pool = pool;
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
            System.out.println("[面板] " + PORT_PROPERTY + " = 0，管理面板停用。");
            return;
        }
        final String token = resolveToken();
        final int threads = Math.max(1, intProperty(THREADS_PROPERTY, DEFAULT_THREADS));

        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (final BindException e) {
            System.err.println("[面板] 無法綁定 127.0.0.1:" + port + "（" + e.getMessage() + "）。"
                    + "port 已被其他程式占用；管理面板停用，伺服器照常啟動。可在 Settings.ini 以 "
                    + PORT_PROPERTY + " 改用別的 port。");
            return;
        } catch (final IOException e) {
            System.err.println("[面板] 無法建立 HTTP 伺服器，管理面板停用：" + e);
            return;
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
        server.createContext("/api", router);
        server.createContext("/", new StaticHandler());
        server.start();

        instance = new PanelServer(server, pool, token, port);
        System.out.println("[面板] 管理面板：http://127.0.0.1:" + port + "/  （" + threads + " 條處理執行緒）");
        System.out.println("[面板] 登入 Token：" + token);
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
        p.server.stop(1);
        p.pool.shutdown();
        try {
            if (!p.pool.awaitTermination(2, TimeUnit.SECONDS)) {
                p.pool.shutdownNow();
            }
        } catch (final InterruptedException e) {
            p.pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        System.out.println("[面板] 管理面板已關閉。");
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
                System.err.println("[面板] " + key + " 不是整數（" + configured + "），改用預設值 " + def + "。");
            }
        }
        return def;
    }

    private static ThreadFactory namedThreadFactory() {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return r -> new Thread(r, "Panel-Worker-" + threadNumber.getAndIncrement());
    }
}
