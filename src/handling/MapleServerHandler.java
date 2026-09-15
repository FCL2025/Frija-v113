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
package handling;

import constants.ServerConstants;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import client.MapleClient;
import handling.world.Client;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.mina.MaplePacketDecoder;
import handling.mina.MapleSession;
import java.io.File;
import java.io.FileWriter;
import java.lang.management.ManagementFactory;
import java.util.EnumSet;
import java.util.Scanner;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import server.Randomizer;
import tools.Encodings;
import tools.MapleAESOFB;
import tools.packet.LoginPacket;
import tools.data.input.ByteArrayByteStream;
import tools.data.input.GenericSeekableLittleEndianAccessor;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.Pair;

import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import server.ServerProperties;
import tools.FileoutputUtil;

public class MapleServerHandler extends IoHandlerAdapter implements MapleServerHandlerMBean {

    /**
     * Ordinary application logging for this class - see {@code tools.Logging}
     * for the conventions.
     *
     * <p>This is emphatically <em>not</em> where the three packet logs go. The
     * 10k ring flushed by the MBean's {@link #writeLog}, the per-player
     * {@code !monitor} file and the per-IP {@code PacketLog_<ip>.txt} are
     * forensic captures with their own files, their own lifetimes and their own
     * exclusion list ({@link #sensitive}); they write through {@link FileWriter}
     * and must stay that way. What goes through {@code LOG} here is the
     * reporting <em>about</em> them - "could not reload", "could not close" -
     * plus session state and the {@code tms.Debug} hex dump.
     */
    private static final System.Logger LOG = System.getLogger(MapleServerHandler.class.getName());

    public static final boolean Log_Packets = true;
    private int channel = -1;
    private boolean cs;
    /**
     * IPs that tripped the connection-flood check. Every accepted connection
     * reads it and any of them can add to it, so it cannot be an ArrayList -
     * which is what it was, with no lock anywhere near it.
     */
    private final Set<String> BlockedIP = ConcurrentHashMap.newKeySet();
    private final Map<String, Pair<Long, Byte>> tracker = new ConcurrentHashMap<>();
    private static final String nl = System.lineSeparator();
    /**
     * Opt-in list of IPs to keep a full packet log for, one address per line;
     * blank lines and {@code #} comments are ignored. Each listed IP gets its
     * own {@code Logs/PacketLog_<ip>.txt} holding every non-{@link #blocked}
     * packet that address sends, plus session open/close markers and the
     * account name it logs in with.
     *
     * <p>It is the only one of the three packet logs that covers the login
     * server. The per-player {@code !monitor} log needs {@code c.getPlayer()},
     * so it cannot see anything an address does before it picks a character,
     * and {@code PacketLog.txt} is a 10k ring shared by everyone. This is what
     * to reach for when one address is hammering the login server or failing at
     * character select.
     *
     * <p>The file is not in the repo and is not expected to exist. Absent means
     * "monitor nobody", which is the normal state and not an error: create it,
     * list an address, and run {@code !reloadipmonitor}.
     */
    private static final File loggedIPs = new File("Logs/LogIPs.txt");
    /** Rebuilt wholesale by {@code !reloadipmonitor} while handlers read it. */
    private static final Map<String, FileWriter> logIPMap = new ConcurrentHashMap<>();
    /**
     * {@code tms.Debug} - dump every received packet as hex.
     *
     * <p>It is now one of two gates, not the only one: the dump goes out at
     * {@code DEBUG}, so the logging configuration has to allow it as well. To
     * get the dumps back an operator needs {@code tms.Debug = true} in
     * {@code Settings.ini} <em>and</em> this line in
     * {@code Libs/logging.properties}:
     *
     * <pre>
     *     handling.MapleServerHandler.level = FINE
     * </pre>
     *
     * ({@code DEBUG} reaches {@code java.util.logging} as {@code FINE}; the
     * console handler is already at {@code ALL}, so the logger's level is the
     * only thing in the way.) Use {@link #debugDump()} rather than reading this
     * field - building the hex dump is the expensive part and must not happen
     * when nothing will print it.
     */
    private static boolean debugMode = Boolean.parseBoolean(ServerProperties.getProperty("tms.Debug", "false"));
    //Note to Zero: Use an enumset. Don't iterate through an array.
    private static final EnumSet<RecvPacketOpcode> blocked = EnumSet.noneOf(RecvPacketOpcode.class);
    /**
     * Opcodes that carry a password in clear text off the wire. Their bodies must
     * never reach a log: {@code PacketLog.txt}, the per-IP {@code PacketLog_<ip>.txt},
     * the per-player monitor logs, or the {@code tms.Debug} hex dump on stdout.
     * They are folded into {@link #blocked}, and {@code messageReceived} checks this
     * set separately so a debug dump prints the opcode name without the body.
     * Any new opcode that reads a password belongs here.
     */
    private static final EnumSet<RecvPacketOpcode> sensitive = EnumSet.of(
            RecvPacketOpcode.LOGIN_PASSWORD,       // account name + password
            RecvPacketOpcode.DELETE_CHAR,          // second password
            RecvPacketOpcode.AUTH_SECOND_PASSWORD, // second password
            RecvPacketOpcode.SET_GENDER);          // account name + second password

    static {
        reloadLoggedIPs();
        // The movement and heal opcodes are blocked because they are noise - they
        // arrive constantly and would drown the log.
        RecvPacketOpcode[] block = new RecvPacketOpcode[]{RecvPacketOpcode.NPC_ACTION, RecvPacketOpcode.MOVE_PLAYER, RecvPacketOpcode.MOVE_PET, RecvPacketOpcode.MOVE_SUMMON, RecvPacketOpcode.MOVE_DRAGON, RecvPacketOpcode.MOVE_LIFE, RecvPacketOpcode.HEAL_OVER_TIME, RecvPacketOpcode.STRANGE_DATA};
        blocked.addAll(Arrays.asList(block));
        blocked.addAll(sensitive);
        // Build the dispatch table now. Its own class initialiser would
        // otherwise run on whichever handler thread saw the first packet,
        // with the rest queued behind the class-init lock.
        PacketHandlerRegistry.preload();
    }

    public static synchronized void reloadLoggedIPs() {
        for (FileWriter fw : logIPMap.values()) {
            if (fw != null) {
                try {
                    synchronized (fw) {
                        fw.write("=== Closing Log ===");
                        fw.write(nl);
                        fw.flush(); //Just in case.
                        fw.close();
                    }
                } catch (IOException ex) {
                    // One record where there were two prints. Terse on purpose:
                    // this only ever reported ex.toString(). The reload carries
                    // on with the next writer, so WARNING.
                    LOG.log(System.Logger.Level.WARNING, "Error closing Packet Log." + nl + ex);
                }
            }
        }
        logIPMap.clear();
        if (!loggedIPs.isFile()) {
            // Monitoring nobody, which is the default. Not worth a word on
            // stdout: the Scanner used to throw FileNotFoundException here and
            // print it on every single startup.
            return;
        }
        try (Scanner sc = new Scanner(loggedIPs, Encodings.NATIVE)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();
                if (line.length() > 0 && !line.startsWith("#")) {
                    FileWriter fw = new FileWriter(new File("Logs/PacketLog_" + line + ".txt"), Encodings.NATIVE, true);
                    fw.write("=== Creating Log ===");
                    fw.write(nl);
                    fw.flush();
                    logIPMap.put(line, fw);
                }
            }
            LOG.log(System.Logger.Level.INFO, "[IP Monitor] 記錄封包中的 IP: " + logIPMap.keySet());
        } catch (Exception e) {
            // One record where there were two prints, terse as before. The
            // reload did not happen, so ERROR: whatever was listed in
            // LogIPs.txt is not being monitored.
            LOG.log(System.Logger.Level.ERROR, "Could not reload packet logged IPs." + nl + e);
        }
    }

    //Return the Filewriter if the IP is logged. Null otherwise.
    private static FileWriter isLoggedIP(IoSession sess) {
        if (logIPMap.isEmpty()) {
            // The common case by far, and it skips the string surgery below.
            return null;
        }
        String a = sess.getRemoteAddress().toString();
        String realIP = a.substring(a.indexOf('/') + 1, a.indexOf(':'));
        return logIPMap.get(realIP);
    }

    /**
     * One {@link FileWriter} per monitored IP is shared by every handler
     * thread that sees that IP, and an unsynchronised {@code write} from two of
     * them interleaves mid-line or worse. Every write to these goes through
     * here.
     */
    private static void writeMonitorLog(final FileWriter fw, final String text) throws IOException {
        synchronized (fw) {
            fw.write(text);
            fw.write(nl);
            fw.flush();
        }
    }
// <editor-fold defaultstate="collapsed" desc="Packet Log Implementation">
    private static final int Log_Size = 10000;
    /**
     * A ring of the last {@value #Log_Size} packets.
     *
     * <p>This was an {@code ArrayList} that dropped its oldest entry with
     * {@code remove(0)} - a ten-thousand element {@code arraycopy} on every
     * single packet once the log filled up, performed while holding the one
     * write lock that all sixteen handler threads have to queue behind. An
     * {@link ArrayDeque} drops from the front in constant time.
     */
    private static final ArrayDeque<LoggedPacket> Packet_Log = new ArrayDeque<>(Log_Size);
    private static final ReentrantReadWriteLock Packet_Log_Lock = new ReentrantReadWriteLock();
    private static final File Packet_Log_Output = new File("PacketLog.txt");

    public static void log(SeekableLittleEndianAccessor packet, RecvPacketOpcode op, MapleClient c, IoSession io) {
        if (blocked.contains(op)) {
            return;
        }
        Packet_Log_Lock.writeLock().lock();
        try {
            LoggedPacket logged = null;
            if (Packet_Log.size() == Log_Size) {
                logged = Packet_Log.pollFirst();
            }
            //This way, we don't create new LoggedPacket objects, we reuse them =]
            if (logged == null) {
                logged = new LoggedPacket(packet, op, io.getRemoteAddress().toString(),
                        c == null ? -1 : c.getAccID(),
                        c == null || c.getAccountName() == null ? "[Null]" : c.getAccountName(),
                        c == null || c.getPlayer() == null || c.getPlayer().getName() == null ? "[Null]" : c.getPlayer().getName());
            } else {
                logged.setInfo(packet, op, io.getRemoteAddress().toString(),
                        c == null ? -1 : c.getAccID(),
                        c == null || c.getAccountName() == null ? "[Null]" : c.getAccountName(),
                        c == null || c.getPlayer() == null || c.getPlayer().getName() == null ? "[Null]" : c.getPlayer().getName());
            }
            Packet_Log.add(logged);
        } finally {
            Packet_Log_Lock.writeLock().unlock();
        }
    }

    private static class LoggedPacket {

        private static final String nl = System.lineSeparator();
        private String ip, accName, chrName;
        private int accId;
        private SeekableLittleEndianAccessor packet;
        private long timestamp;
        private RecvPacketOpcode op;

        public LoggedPacket(SeekableLittleEndianAccessor p, RecvPacketOpcode op, String ip, int id, String accName, String chrName) {
            setInfo(p, op, ip, id, accName, chrName);
        }

        public final void setInfo(SeekableLittleEndianAccessor p, RecvPacketOpcode op, String ip, int id, String accName, String chrName) {
            this.ip = ip;
            this.op = op;
            packet = p;
            // The account id was taken as a parameter and then thrown away -
            // accId was a String nobody ever assigned, so every line of
            // PacketLog.txt has always read "null" where the id belongs.
            this.accId = id;
            this.accName = accName;
            this.chrName = chrName;
            timestamp = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("[IP: ").append(ip).append("] [").append(accId).append('|').append(accName).append('|').append(chrName).append("] [Time: ").append(timestamp).append(']');
            sb.append(nl);
            sb.append("[Op: ").append(op.toString()).append(']');
            sb.append(" [Data: ").append(packet.toString()).append(']');
            return sb.toString();
        }
    }

    public static void registerMBean() {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        try {
            MapleServerHandler mbean = new MapleServerHandler();
            //The log is a static object, so we can just use this hacky method.
            mBeanServer.registerMBean(mbean, new ObjectName("handling:type=MapleServerHandler"));
        } catch (Exception e) {
            // One record: the message and the trace are the same event.
            LOG.log(System.Logger.Level.ERROR, "Error registering PacketLog MBean", e);
        }
    }

    public void writeLog() {
        // 寫檔機制刻意維持 FileWriter（見 tools.Logging 的「不准轉換」清單）；這裡只動
        // 兩件事：資源管理與錯誤回報。
        //
        // 舊寫法把 fw.close() 放在 try 裡、finally 只解鎖，所以只要任何一次 fw.write
        // 丟出 IOException，那個檔案代號就永遠不會關——在 Windows 上還會讓 PacketLog.txt
        // 一直被鎖住。改成 try-with-resources 之後，鎖的範圍與原本一模一樣：開檔在鎖外，
        // 讀鎖只圍住走訪環，close() 發生在解鎖之後。
        try (FileWriter fw = new FileWriter(Packet_Log_Output, Encodings.NATIVE, true)) {
            Packet_Log_Lock.readLock().lock();
            try {
                for (LoggedPacket loggedPacket : Packet_Log) {
                    fw.write(loggedPacket.toString());
                    fw.write(nl);
                }
                fw.flush();
            } finally {
                Packet_Log_Lock.readLock().unlock();
            }
        } catch (IOException ex) {
            // writeLog() 是 handling:type=MapleServerHandler 這個 MBean 的操作，回傳
            // void 也不宣告受檢例外，IOException 從來不會傳出去——JMX 呼叫端無論如何
            // 都只看到「執行成功」。所以附上 ex 只是讓日誌多一份堆疊，MBean 那端看到的
            // 東西完全沒變。整份傾印被放棄了，所以是 ERROR。
            LOG.log(System.Logger.Level.ERROR, "Error writing log to file.", ex);
        }
    }

    public MapleServerHandler() {
        //ONLY FOR THE MBEAN
    }
    // </editor-fold>

    public MapleServerHandler(final int channel, final boolean cs) {
        this.channel = channel;
        this.cs = cs;
    }

    @Override
    public void messageSent(final IoSession session, final Object message) throws Exception {
        final Runnable r = ((MaplePacket) message).getOnSend();
        if (r != null) {
            r.run();
        }
        super.messageSent(session, message);
    }

    /**
     * Full log entries written per session before this handler goes quiet, and
     * the session attribute the running count lives in.
     *
     * <p>A broken or hostile client can drive {@link #exceptionCaught} in a
     * loop, and an unbounded append to an {@code .rtf} from a MINA handler
     * thread is a denial-of-service on its own - the disk write is the
     * expensive part and the attacker pays nothing for it. A per-session budget
     * is the right bound rather than a global rate: a global one lets one bad
     * session hide every other session's first failure, which is the exact
     * failure mode this method exists to stop. The overall ceiling is still
     * bounded, because {@link #sessionOpened}'s flood check already caps how
     * many sessions one address may open before the address is blocked
     * outright.
     *
     * <p>The counter is an {@link AtomicInteger} in the session's attribute
     * map, so it dies with the session and leaks nothing. Two threads racing to
     * create it would cost a couple of extra entries and nothing else;
     * MINA's {@code ExecutorFilter} runs at most one event per session at a
     * time, so it should not happen at all.
     */
    private static final int EXCEPTION_LOG_LIMIT = 5;
    private static final String EXCEPTION_COUNT_KEY = "handling.exceptionCount";

    /**
     * The last stop for anything thrown out of decoding, packet handling or a
     * write - and until now it threw it away. The body was commented out
     * entirely, so an {@link Error}, which walks straight through every
     * {@code catch (Exception)} on the way here, produced literally nothing: no
     * log line, no stack trace, no sign that anything had happened.
     *
     * <p>That is not theoretical. {@code Libs/scripts/npc/2040043.js} makes
     * Nashorn throw a bare {@link AssertionError} while generating bytecode for
     * it. The error passed through {@code AbstractScriptManager}, through
     * {@code NPCScriptManager}, through the dispatch path and arrived here,
     * where it vanished - so LPQ stage 8's NPC simply did nothing, forever,
     * with not one line anywhere to say why.
     *
     * <p>Three rules shape what it may do, all of them because it runs on a
     * handler thread on the error path:
     *
     * <ul>
     * <li><b>It must not throw.</b> The whole body sits inside one
     * {@code catch (Throwable)}. If the reporting itself fails there is nowhere
     * left to report to, and propagating out of here would take a MINA thread
     * with it.</li>
     * <li><b>It must not write without a bound.</b> See
     * {@link #EXCEPTION_LOG_LIMIT}.</li>
     * <li><b>It must not bury the routine ones in stack traces.</b> A client
     * that vanishes mid-write surfaces here as an {@link IOException} -
     * "connection reset", {@code ClosedChannelException}. That is normal, it
     * happens once per ungraceful disconnect, and its trace says nothing anyone
     * can act on. Those get one line naming the exception and the player, and
     * no trace. The category is still logged, not swallowed: a genuine I/O
     * failure that is not a disconnect still leaves its name, its message and
     * its context in the same file.</li>
     * </ul>
     *
     * <p>What the commented-out original body had was the client lookup, the
     * {@code MapleClient.getLogMessage(client, cause.getMessage())} context
     * string and passing {@code cause} itself so the trace went out - all three
     * are kept. What could not be kept was its sink: it called {@code log.error}
     * on an slf4j logger that this class has not had for as long as the repo
     * has existed. {@link FileoutputUtil} is where the rest of this class
     * already sends swallowed exceptions, so this goes to the same file as
     * {@code messageReceived}'s own catch, {@link FileoutputUtil#PacketEx_Log}.
     * The second commented-out line, a bare {@code cause.printStackTrace()}, is
     * deliberately not restored as-is - full traces on the console are the same
     * unbounded-write problem - and is replaced by one {@code ERROR} record
     * naming the log file, so an operator watching the console still sees that
     * something happened. That record carries <em>no</em> {@link Throwable} for
     * the same reason: {@code tools.Logging.ConsoleFormat} appends a full stack
     * trace to any record that has one, which is exactly what this is avoiding.
     */
    @Override
    public void exceptionCaught(final IoSession session, final Throwable cause) throws Exception {
        try {
            if (cause == null) {
                return;
            }
            final int seen = countException(session);
            if (seen > EXCEPTION_LOG_LIMIT) {
                return; // already said, on entry EXCEPTION_LOG_LIMIT, that we were going quiet
            }
            final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);
            final String where = channel > -1 ? "[Channel " + channel + "]" : (cs ? "[Cash Server]" : "[Login Server]");
            final String address = session.getRemoteAddress() == null ? "?" : session.getRemoteAddress().toString();
            // MINA's own decoder exceptions carry a hex dump of the offending
            // buffer in getMessage(), so the message is truncated: one entry has
            // to stay one readable entry, and the client chooses its length.
            String message = cause.getMessage();
            if (message != null && message.length() > 512) {
                message = message.substring(0, 512) + "…(截斷)";
            }
            final String what = cause.getClass().getName() + (message == null ? "" : ": " + message);

            final StringBuilder sb = new StringBuilder();
            sb.append(where).append(" [IP: ").append(address).append("] ");
            // getLogMessage prefixes the character (name + cid) and the account
            // name when there is a client far enough along to have them.
            sb.append(MapleClient.getLogMessage(client, what));
            final boolean routine = isRoutineDisconnect(cause);
            if (routine) {
                sb.append(" [連線中斷，不輸出堆疊]");
            } else {
                sb.append(nl).append(FileoutputUtil.getString(cause));
            }
            if (seen == EXCEPTION_LOG_LIMIT) {
                sb.append(nl).append("[此連線已達 ").append(EXCEPTION_LOG_LIMIT)
                        .append(" 筆上限，後續的例外不再記錄]");
            }
            // One append, one entry: FileoutputUtil writes the header and the
            // body in a single call for exactly this reason.
            FileoutputUtil.log(FileoutputUtil.PacketEx_Log, sb.toString());
            if (!routine) {
                // No Throwable attached: see this method's javadoc. The full
                // trace is already in PacketEx_Log, one entry, bounded by
                // EXCEPTION_LOG_LIMIT.
                LOG.log(System.Logger.Level.ERROR,
                        where + " exceptionCaught " + what + " - 詳見 " + FileoutputUtil.PacketEx_Log);
            }
        } catch (final Throwable ignored) {
            // The error path's own error path. There is nothing left to report
            // to, and throwing from here would kill a MINA handler thread.
        }
    }

    /**
     * How many times this session has reached {@link #exceptionCaught},
     * including this one.
     */
    private static int countException(final IoSession session) {
        if (session.getAttribute(EXCEPTION_COUNT_KEY) instanceof AtomicInteger seen) {
            return seen.incrementAndGet();
        }
        final AtomicInteger fresh = new AtomicInteger(1);
        session.setAttribute(EXCEPTION_COUNT_KEY, fresh);
        return 1;
    }

    /**
     * True for the everyday "the client went away" throwables - anything with
     * an {@link IOException} anywhere in its cause chain, which covers
     * {@code SocketException: Connection reset},
     * {@code ClosedChannelException} and {@code EOFException}. These are logged
     * as one line instead of a stack trace.
     *
     * <p>The walk is depth-bounded because a {@code Throwable} whose cause
     * chain loops back on itself would otherwise spin here, on an I/O thread,
     * forever.
     */
    private static boolean isRoutineDisconnect(final Throwable cause) {
        Throwable t = cause;
        for (int depth = 0; t != null && depth < 8; depth++) {
            if (t instanceof IOException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return false;
    }

    @Override
    public void sessionOpened(final IoSession session) throws Exception {
        // Start of IP checking
        final String address = session.getRemoteAddress().toString().split(":")[0];

        if (BlockedIP.contains(address)) {
            session.closeNow();
            return;
        }
        // Read-modify-write in one atomic step. Split across a get and a put,
        // as it was, ten machines behind one NAT could each see the same stale
        // count and none of them would ever reach the threshold.
        final Pair<Long, Byte> updated = tracker.compute(address, (key, track) -> {
            final long now = System.currentTimeMillis();
            if (track == null) {
                return new Pair<>(now, (byte) 1);
            }
            byte count = track.right;
            final long difference = now - track.left;
            if (difference < 2000) { // Less than 2 sec
                count++;
            } else if (difference > 20000) { // Over 20 sec
                count = 1;
            }
            return new Pair<>(now, count);
        });
        if (updated.right >= 10) {
            BlockedIP.add(address);
            tracker.remove(address); // Cleanup
            session.closeNow();
            return;
        }
        // End of IP checking.

        if (channel > -1) {
            if (ChannelServer.getInstance(channel).isShutdown()) {
                session.closeNow();
                return;
            }
        } else if (cs) {
            if (CashShopServer.isShutdown()) {
                session.closeNow();
                return;
            }
        } else {
            if (LoginServer.isShutdown()) {
                session.closeNow();
                return;
            }
        }
        final byte serverRecv[] = new byte[]{70, 114, 122, (byte) Randomizer.nextInt(255)};
        final byte serverSend[] = new byte[]{82, 48, 120, (byte) Randomizer.nextInt(255)};
        final byte ivRecv[] = ServerConstants.Use_Fixed_IV ? new byte[]{9, 0, 0x5, 0x5F} : serverRecv;
        final byte ivSend[] = ServerConstants.Use_Fixed_IV ? new byte[]{1, 0x5F, 4, 0x3F} : serverSend;

        final MapleClient client = new MapleClient(
                new MapleAESOFB(ivSend, (short) (0xFFFF - ServerConstants.MAPLE_VERSION)), // Sent Cypher
                new MapleAESOFB(ivRecv, ServerConstants.MAPLE_VERSION), // Recv Cypher
                session);
        client.setChannel(channel);

        MaplePacketDecoder.DecoderState decoderState = new MaplePacketDecoder.DecoderState();
        session.setAttribute(MaplePacketDecoder.DECODER_STATE_KEY, decoderState);

        session.write(LoginPacket.getHello(ServerConstants.MAPLE_VERSION,
                ServerConstants.Use_Fixed_IV ? serverSend : ivSend, ServerConstants.Use_Fixed_IV ? serverRecv : ivRecv));
        session.setAttribute(MapleClient.CLIENT_KEY, client);
        // MINA 1.1 had these on the session; MINA 2 moved them onto the
        // session's config object. Same units (seconds), same effect: after 60
        // idle seconds sessionIdle fires and the server pings the client.
        session.getConfig().setIdleTime(IdleStatus.READER_IDLE, 60);
        session.getConfig().setIdleTime(IdleStatus.WRITER_IDLE, 60);

        StringBuilder sb = new StringBuilder();
        if (channel > -1) {
            sb.append("[Channel Server] Channel ").append(channel).append(" : ");
        } else if (cs) {
            sb.append("[Cash Server]");
        } else {
            sb.append("[Login Server]");
        }
        sb.append("IoSession opened ").append(address);
        LOG.log(System.Logger.Level.INFO, sb.toString());
        Client.addClient(client);

        FileWriter fw = isLoggedIP(session);
        if (fw != null) {
            if (channel > -1) {
                writeMonitorLog(fw, "=== Logged Into Channel " + channel + " ===");
            } else if (cs) {
                writeMonitorLog(fw, "=== Logged Into CashShop Server ===");
            } else {
                writeMonitorLog(fw, "=== Logged Into Login Server ===");
            }
        }
    }

    @Override
    public void sessionClosed(final IoSession session) throws Exception {
        final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);

        if (client != null) {
            try {
                FileWriter fw = isLoggedIP(session);
                if (fw != null) {
                    writeMonitorLog(fw, "=== Session Closed ===");
                }
                client.disconnect(true, cs);
            } finally {
                Client.removeClient(client);
                session.closeNow();
                session.removeAttribute(MapleClient.CLIENT_KEY);
            }
        }
        super.sessionClosed(session);
    }

    /**
     * Replays a packet as if the client had sent it, for the {@code !packet} GM
     * command.
     *
     * <p>That command reaches this class through {@code MapleClient.getSession()},
     * which hands out a {@link MapleSession} rather than a MINA
     * {@code IoSession} - see that class for why - so this overload exists to
     * unwrap it. It deliberately does no work of its own: the whole point of
     * {@code !packet} is that the bytes take the identical path a real packet
     * takes.
     */
    public void messageReceived(final MapleSession session, final Object message) {
        messageReceived(session.unwrap(), message);
    }

    @Override
    public void messageReceived(final IoSession session, final Object message) {
        try {
            final SeekableLittleEndianAccessor slea = new GenericSeekableLittleEndianAccessor(new ByteArrayByteStream((byte[]) message));
            if (slea.available() < 2) {
                return;
            }
            final short header_num = slea.readShort();
            // One hash lookup. This used to be a linear scan over all ~190
            // enum constants, on every packet from every client.
            final RecvPacketOpcode recv = RecvPacketOpcode.forValue(header_num);
            if (recv == null) {
                if (debugDump()) {
                    final StringBuilder sb = new StringBuilder("Received data 未處理 : ");
                    sb.append(tools.HexTool.toString((byte[]) message)).append("\n").append(tools.HexTool.toStringFromAscii((byte[]) message));
                    LOG.log(System.Logger.Level.DEBUG, sb.toString());
                }
                return;
            }

            if (debugDump()) {
                if (sensitive.contains(recv)) {
                    // The body holds a password in clear text - name the opcode only.
                    LOG.log(System.Logger.Level.DEBUG, "Received data 已處理 :" + String.valueOf(recv) + " [內容含密碼，不輸出]");
                } else {
                    final StringBuilder sb = new StringBuilder("Received data 已處理 :" + String.valueOf(recv) + "\n");
                    sb.append(tools.HexTool.toString((byte[]) message)).append("\n").append(tools.HexTool.toStringFromAscii((byte[]) message));
                    LOG.log(System.Logger.Level.DEBUG, sb.toString());
                }
            }
            final MapleClient c = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);
            if (c == null || !c.isReceiving()) {
                return;
            }
            if (recv.NeedsChecking() && !c.isLoggedIn()) {
                return;
            }
            if (c.getPlayer() != null && c.isMonitored() && !blocked.contains(recv)) {
                try (FileWriter fw = new FileWriter(new File("Logs/MonitorLogs/" + c.getPlayer().getName() + "_log.txt"), Encodings.NATIVE, true)) {
                    fw.write(String.valueOf(recv) + " (" + Integer.toHexString(header_num) + ") Handled: \r\n" + slea.toString() + "\r\n");
                }
            }
            if (Log_Packets) {
                log(slea, recv, c, session);
            }
            PacketHandlerRegistry.handle(recv, slea, c, cs);

            //Log after the packet is handle. You'll see why =]
            final FileWriter fw = isLoggedIP(session);
            if (fw != null && !blocked.contains(recv)) {
                if (recv == RecvPacketOpcode.PLAYER_LOGGEDIN) { // << This is why. Win.
                    writeMonitorLog(fw, ">> [AccountName: "
                            + (c.getAccountName() == null ? "null" : c.getAccountName())
                            + "] | [IGN: "
                            + (c.getPlayer() == null || c.getPlayer().getName() == null ? "null" : c.getPlayer().getName())
                            + "] | [Time: "
                            + FileoutputUtil.CurrentReadable_Time()
                            + "]");
                }
                writeMonitorLog(fw, "[" + recv.toString() + "]" + slea.toString(true));
            }
        } catch (Exception e) {
            FileoutputUtil.outputFileError(FileoutputUtil.PacketEx_Log, e);
            // The trace went to stdout before and still does, as one record
            // rather than an interleaved block: tools.Logging.ConsoleFormat
            // appends it to the message.
            LOG.log(System.Logger.Level.ERROR, "封包處理發生例外 - 詳見 " + FileoutputUtil.PacketEx_Log, e);
        }

    }

    /**
     * Whether a {@code tms.Debug} hex dump should be built at all.
     *
     * <p>Both gates, checked in the cheap order, on the per-packet path: the
     * {@code tms.Debug} switch first, then whether {@code DEBUG} is loggable.
     * The dump itself is two passes of {@code HexTool} over the whole packet,
     * so it must not be built for a record that will be thrown away - see
     * {@link #debugMode} for what an operator has to set.
     */
    private static boolean debugDump() {
        return debugMode && LOG.isLoggable(System.Logger.Level.DEBUG);
    }

    @Override
    public void sessionIdle(final IoSession session, final IdleStatus status) throws Exception {
        final MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);

        /*       	if (client != null && client.getPlayer() != null) {
         System.out.println("玩家 "+ client.getPlayer().getName() +" 正在掛網");
         }*/
        if (client != null) {
            client.sendPing();
        }
        super.sessionIdle(session, status);
    }
}
