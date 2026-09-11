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
package handling.mina;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.mina.common.ByteBuffer;
import org.apache.mina.common.IoAcceptor;
import org.apache.mina.common.IoHandler;
import org.apache.mina.common.SimpleByteBufferAllocator;
import org.apache.mina.common.ThreadModel;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.executor.ExecutorFilter;
import org.apache.mina.transport.socket.nio.SocketAcceptor;
import org.apache.mina.transport.socket.nio.SocketAcceptorConfig;

import server.ServerProperties;

/**
 * The one place the server talks to MINA about threads and sockets.
 *
 * <p>The login server, every channel and the cash shop each used to build their
 * own {@link SocketAcceptor} and their own config, three near-identical copies,
 * and none of them said anything about threading. That silence was the
 * interesting part: MINA 1.1 fills an unset {@code ThreadModel} in with
 * {@link org.apache.mina.common.ExecutorThreadModel}, which quietly installs an
 * {@link ExecutorFilter} over a fixed sixteen-thread pool. So every packet
 * handler in this codebase has always run concurrently, on threads nobody
 * named, sized, or could see - which is why so much of the shared state in here
 * is racy in ways that only ever showed up as "the server got weird under
 * load".
 *
 * <p>The pool is now explicit: named threads, a size that scales with the
 * machine and is overridable with {@code tms.HandlerThreads}, and one pool
 * shared by every acceptor rather than one per service. The ordering guarantee
 * the old default gave is preserved, and it matters - {@link MaplePacketDecoder}
 * drives a per-session AES-OFB stream cipher, so two packets from one client
 * decrypted out of order would desynchronise that client for good.
 * {@code ExecutorFilter} buffers events per session and runs at most one task
 * per session at a time, so sessions run in parallel with each other and
 * strictly in order within themselves.
 *
 * <p>There is also one {@code SocketAcceptor} for the whole process now, with
 * each service binding its own port on it. MINA gives every acceptor its own
 * set of I/O processor threads, so five acceptors meant five sets for no gain.
 */
public final class MapleAcceptor {

    /** Size of the shared packet-handler pool. */
    public static final String HANDLER_THREADS_PROPERTY = "tms.HandlerThreads";

    /**
     * Handlers block - on the database, on WZ parsing, on other channels - so
     * there is no point pinning this to the core count.
     */
    private static final int DEFAULT_HANDLER_THREADS =
            Math.max(16, Runtime.getRuntime().availableProcessors() * 2);

    private static final ThreadPoolExecutor HANDLER_POOL;
    private static final IoAcceptor ACCEPTOR;

    static {
        // Must precede the first buffer allocation, i.e. the first bind.
        ByteBuffer.setUseDirectBuffers(false);
        ByteBuffer.setAllocator(new SimpleByteBufferAllocator());

        final int threads = handlerThreads();
        HANDLER_POOL = new ThreadPoolExecutor(threads, threads,
                60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), namedThreadFactory());
        HANDLER_POOL.allowCoreThreadTimeOut(true);
        ACCEPTOR = new SocketAcceptor();
        System.out.println("[Network] Packet handlers: " + threads + " threads.");
    }

    private MapleAcceptor() {
    }

    /**
     * Binds one service. The returned address is what {@link #unbind} and
     * {@link #getManagedSessionCount} expect back.
     */
    public static InetSocketAddress bind(final int port, final IoHandler handler) throws IOException {
        final InetSocketAddress address = new InetSocketAddress(port);
        ACCEPTOR.bind(address, handler, newConfig());
        return address;
    }

    public static void unbind(final InetSocketAddress address) {
        if (address != null) {
            ACCEPTOR.unbind(address);
        }
    }

    public static int getManagedSessionCount(final InetSocketAddress address) {
        if (address == null) {
            return 0;
        }
        final Set<?> sessions = ACCEPTOR.getManagedSessions(address);
        return sessions == null ? 0 : sessions.size();
    }

    /** Stops accepting work. The sockets themselves go with the JVM. */
    public static void shutdown() {
        HANDLER_POOL.shutdown();
        try {
            if (!HANDLER_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                HANDLER_POOL.shutdownNow();
            }
        } catch (final InterruptedException e) {
            HANDLER_POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static SocketAcceptorConfig newConfig() {
        final SocketAcceptorConfig cfg = new SocketAcceptorConfig();
        cfg.getSessionConfig().setTcpNoDelay(true);
        cfg.setDisconnectOnUnbind(true);
        // MANUAL, or MINA appends its own hidden ExecutorFilter to the chain
        // below and every packet would be handed between two pools.
        cfg.setThreadModel(ThreadModel.MANUAL);
        // Head of the chain, matching where the old implicit model put it: the
        // decode is stateful per session and belongs on the same ordered
        // worker as the handling that follows it.
        cfg.getFilterChain().addLast("threadPool", new ExecutorFilter(HANDLER_POOL));
        cfg.getFilterChain().addLast("codec", new ProtocolCodecFilter(new MapleCodecFactory()));
        return cfg;
    }

    private static int handlerThreads() {
        final String configured = ServerProperties.getProperty(HANDLER_THREADS_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                return Math.max(1, Integer.parseInt(configured.trim()));
            } catch (final NumberFormatException e) {
                // Fall through to the default rather than refuse to start.
            }
        }
        return DEFAULT_HANDLER_THREADS;
    }

    private static ThreadFactory namedThreadFactory() {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return r -> new Thread(r, "Maple-Handler-" + threadNumber.getAndIncrement());
    }
}
