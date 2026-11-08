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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.buffer.SimpleBufferAllocator;
import org.apache.mina.core.service.IoHandler;
import org.apache.mina.core.service.SimpleIoProcessorPool;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.executor.ExecutorFilter;
import org.apache.mina.filter.executor.OrderedThreadPoolExecutor;
import org.apache.mina.transport.socket.nio.NioProcessor;
import org.apache.mina.transport.socket.nio.NioSession;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;

import server.ServerProperties;

/**
 * The one place the server talks to MINA about threads and sockets.
 *
 * <p>The login server, every channel and the cash shop each used to build their
 * own acceptor and their own config, three near-identical copies, and none of
 * them said anything about threading. That silence was the interesting part:
 * MINA 1.1 filled an unset {@code ThreadModel} in with
 * {@code ExecutorThreadModel}, which quietly installed an {@link ExecutorFilter}
 * over a fixed sixteen-thread pool. So every packet handler in this codebase
 * has always run concurrently, on threads nobody named, sized, or could see -
 * which is why so much of the shared state in here is racy in ways that only
 * ever showed up as "the server got weird under load".
 *
 * <h2>Why one acceptor per port</h2>
 *
 * <p>MINA 1.1 let one {@code SocketAcceptor} carry a different {@code IoHandler}
 * per bound address: {@code bind(address, handler, config)}. <b>MINA 2 has no
 * such thing</b> - an {@code IoService} has exactly one {@link IoHandler}, set
 * with {@code setHandler}. The three roles here each need their own
 * {@code MapleServerHandler} (it carries the channel number and the cash-shop
 * flag), so there were two ways out:
 *
 * <ol>
 * <li>one {@code NioSocketAcceptor} per port, or</li>
 * <li>one acceptor plus a dispatching handler that looks the real handler up by
 *     {@code session.getLocalAddress()} on every event.</li>
 * </ol>
 *
 * <p>This is (1). (2) puts a map lookup on the hot path of every event of every
 * session and invents a new failure mode - a port that is bound but missing
 * from the map dispatches into nothing, silently - to save object allocations
 * that are made once at startup. (1) also keeps this class's public API
 * unchanged, so {@code LoginServer}, {@code ChannelServer} and
 * {@code CashShopServer} did not have to be touched, and it keeps
 * {@code ChannelServer.shutdown()}'s unbind honest: one channel's acceptor goes
 * away and the others cannot notice.
 *
 * <p>The cost (1) is supposed to have - N acceptors meaning N sets of I/O
 * threads, which is exactly what the single-acceptor rewrite was for - is paid
 * off by sharing: every acceptor is built with the
 * {@code NioSocketAcceptor(Executor, IoProcessor)} constructor and handed the
 * same {@link #NIO_EXECUTOR} and the same {@link #IO_PROCESSORS}. MINA records
 * those as "not mine" ({@code createdExecutor} / {@code createdProcessor} both
 * false), so disposing one acceptor cannot take the shared pools down with it.
 * There is one set of selector threads for the whole process, as before.
 *
 * <h2>Why OrderedThreadPoolExecutor and not just any Executor</h2>
 *
 * <p>{@link MaplePacketDecoder} drives a per-session AES-OFB stream cipher, so
 * two packets from one client decoded out of order desynchronise that client
 * for good - it never understands the server again. MINA 1.1's default thread
 * model gave that ordering for free. <b>MINA 2's {@link ExecutorFilter} does
 * not</b>: handed a plain {@code ThreadPoolExecutor} it will start two events
 * of the same session on two threads at once, which is the classic way to
 * break a MINA 2 migration and is invisible until the server is under load.
 * The guarantee comes from the pool, not the filter, and only
 * {@link OrderedThreadPoolExecutor} provides it - it keeps a task queue per
 * session and lets exactly one worker drain a given session's queue at a time,
 * so sessions run in parallel with each other and strictly in order within
 * themselves. That is the same contract as before, and it is not optional.
 *
 * <p>Core size equals maximum size on purpose. Workers are still created
 * lazily - {@code OrderedThreadPoolExecutor} only adds one when no idle worker
 * is available - but once created they stay, because a worker only exits when
 * {@code workers.size() > corePoolSize}, and letting workers exit opens a
 * window where a session can be handed to {@code waitingSessions} just as the
 * last idle worker times out, leaving that session's packets sitting until some
 * other client happens to send something.
 */
public final class MapleAcceptor {

    private static final System.Logger LOG = System.getLogger(MapleAcceptor.class.getName());

    /** Size of the shared packet-handler pool. */
    public static final String HANDLER_THREADS_PROPERTY = "tms.HandlerThreads";

    /**
     * Handlers block - on the database, on WZ parsing, on other channels - so
     * there is no point pinning this to the core count.
     */
    private static final int DEFAULT_HANDLER_THREADS =
            Math.max(16, Runtime.getRuntime().availableProcessors() * 2);

    /** Ordered, one task per session at a time. See the class javadoc. */
    private static final OrderedThreadPoolExecutor HANDLER_POOL;

    /**
     * MINA's own workers - the accept loops and the selector loops. Cached and
     * unbounded because MINA starts and stops these on demand and renames the
     * thread while one is running ({@code NamePreservingRunnable}), so the name
     * below is only what an idle one sits under.
     */
    private static final ExecutorService NIO_EXECUTOR;

    /** One set of selectors for the whole process, shared by every acceptor. */
    private static final SimpleIoProcessorPool<NioSession> IO_PROCESSORS;

    /**
     * The acceptor behind each bound port. Read and written by startup, by
     * {@code ChannelServer.shutdown()} and by the panel's shutdown route, i.e.
     * from several threads.
     */
    private static final Map<InetSocketAddress, NioSocketAcceptor> ACCEPTORS = new ConcurrentHashMap<>();

    static {
        // Must precede the first buffer allocation, i.e. the first bind.
        IoBuffer.setUseDirectBuffer(false);
        IoBuffer.setAllocator(new SimpleBufferAllocator());

        final int threads = handlerThreads();
        HANDLER_POOL = new OrderedThreadPoolExecutor(threads, threads,
                60, TimeUnit.SECONDS, namedThreadFactory("Maple-Handler-"));
        NIO_EXECUTOR = Executors.newCachedThreadPool(namedThreadFactory("Maple-NIO-"));
        IO_PROCESSORS = new SimpleIoProcessorPool<>(NioProcessor.class, NIO_EXECUTOR);
        LOG.log(System.Logger.Level.INFO, "[Network] Packet handlers: " + threads + " threads.");
    }

    private MapleAcceptor() {
    }

    /**
     * Binds one service. The returned address is what {@link #unbind} and
     * {@link #getManagedSessionCount} expect back.
     */
    public static InetSocketAddress bind(final int port, final IoHandler handler) throws IOException {
        final InetSocketAddress address = new InetSocketAddress(port);
        final NioSocketAcceptor acceptor = new NioSocketAcceptor(NIO_EXECUTOR, IO_PROCESSORS);
        acceptor.setHandler(handler);
        acceptor.setReuseAddress(true);
        acceptor.getSessionConfig().setTcpNoDelay(true);
        // MINA 1.1's setDisconnectOnUnbind(true), renamed. Sessions on this
        // port are closed when it is unbound.
        acceptor.setCloseOnDeactivation(true);
        // Head of the chain, matching where the old implicit thread model put
        // it: the decode is stateful per session and belongs on the same
        // ordered worker as the handling that follows it.
        acceptor.getFilterChain().addLast("threadPool", new ExecutorFilter(HANDLER_POOL));
        acceptor.getFilterChain().addLast("codec", new ProtocolCodecFilter(new MapleCodecFactory()));
        // Must come after the codec, because writes run the chain backwards:
        // this is the outermost filter on the way out, so it wraps the encode
        // and the handover to the write queue in one critical section. See
        // MaplePacketEncoder.WriteOrderFilter - without it MINA 2 lets two
        // threads encrypt in one order and queue in the other.
        acceptor.getFilterChain().addLast("writeOrder", new MaplePacketEncoder.WriteOrderFilter());
        try {
            acceptor.bind(address);
        } catch (final IOException | RuntimeException e) {
            // Nothing else has a reference to it yet, so it would leak a
            // selector otherwise.
            acceptor.dispose(false);
            throw e;
        }
        ACCEPTORS.put(address, acceptor);
        return address;
    }

    /**
     * Unbinds one service and releases its acceptor. Tolerates an address that
     * was never bound or has already been unbound - {@code ChannelServer} can
     * reach this twice for the same channel.
     */
    public static void unbind(final InetSocketAddress address) {
        if (address == null) {
            return;
        }
        final NioSocketAcceptor acceptor = ACCEPTORS.remove(address);
        if (acceptor != null) {
            // The no-argument form: this acceptor only ever carried the one
            // address, and unbind(SocketAddress) would have to match it by
            // equality against whatever MINA recorded at bind time.
            acceptor.unbind();
            // Non-blocking, and it cannot touch NIO_EXECUTOR or IO_PROCESSORS:
            // those were handed in, so MINA does not consider them its own.
            acceptor.dispose(false);
        }
    }

    public static int getManagedSessionCount(final InetSocketAddress address) {
        if (address == null) {
            return 0;
        }
        final NioSocketAcceptor acceptor = ACCEPTORS.get(address);
        return acceptor == null ? 0 : acceptor.getManagedSessionCount();
    }

    /** Stops accepting work and releases every socket, selector and thread. */
    public static void shutdown() {
        for (final InetSocketAddress address : ACCEPTORS.keySet()) {
            unbind(address);
        }
        HANDLER_POOL.shutdown();
        try {
            if (!HANDLER_POOL.awaitTermination(5, TimeUnit.SECONDS)) {
                HANDLER_POOL.shutdownNow();
            }
        } catch (final InterruptedException e) {
            HANDLER_POOL.shutdownNow();
            Thread.currentThread().interrupt();
        }
        IO_PROCESSORS.dispose();
        NIO_EXECUTOR.shutdown();
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

    private static ThreadFactory namedThreadFactory(final String prefix) {
        final AtomicInteger threadNumber = new AtomicInteger(1);
        return r -> new Thread(r, prefix + threadNumber.getAndIncrement());
    }
}
