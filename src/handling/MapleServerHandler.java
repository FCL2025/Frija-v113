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

import client.MapleClient;
import handling.world.Client;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.mina.MaplePacketDecoder;
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

import org.apache.mina.common.IoHandlerAdapter;
import org.apache.mina.common.IdleStatus;
import org.apache.mina.common.IoSession;
import server.ServerProperties;
import tools.FileoutputUtil;

public class MapleServerHandler extends IoHandlerAdapter implements MapleServerHandlerMBean {

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
                    System.out.println("Error closing Packet Log.");
                    System.out.println(ex);
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
            System.out.println("[IP Monitor] 記錄封包中的 IP: " + logIPMap.keySet());
        } catch (Exception e) {
            System.out.println("Could not reload packet logged IPs.");
            System.out.println(e);
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
            System.out.println("Error registering PacketLog MBean");
            e.printStackTrace();
        }
    }

    public void writeLog() {
        try {
            FileWriter fw = new FileWriter(Packet_Log_Output, Encodings.NATIVE, true);
            try {
                Packet_Log_Lock.readLock().lock();
                String nl = System.getProperty("line.separator");
                for (LoggedPacket loggedPacket : Packet_Log) {
                    fw.write(loggedPacket.toString());
                    fw.write(nl);
                }
                fw.flush();
                fw.close();
            } finally {
                Packet_Log_Lock.readLock().unlock();
            }
        } catch (IOException ex) {
            System.out.println("Error writing log to file.");
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

    @Override
    public void exceptionCaught(final IoSession session, final Throwable cause) throws Exception {
        /*	MapleClient client = (MapleClient) session.getAttribute(MapleClient.CLIENT_KEY);
         log.error(MapleClient.getLogMessage(client, cause.getMessage()), cause);*/
//	cause.printStackTrace();
    }

    @Override
    public void sessionOpened(final IoSession session) throws Exception {
        // Start of IP checking
        final String address = session.getRemoteAddress().toString().split(":")[0];

        if (BlockedIP.contains(address)) {
            session.close();
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
            session.close();
            return;
        }
        // End of IP checking.

        if (channel > -1) {
            if (ChannelServer.getInstance(channel).isShutdown()) {
                session.close();
                return;
            }
        } else if (cs) {
            if (CashShopServer.isShutdown()) {
                session.close();
                return;
            }
        } else {
            if (LoginServer.isShutdown()) {
                session.close();
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
        session.setIdleTime(IdleStatus.READER_IDLE, 60);
        session.setIdleTime(IdleStatus.WRITER_IDLE, 60);

        StringBuilder sb = new StringBuilder();
        if (channel > -1) {
            sb.append("[Channel Server] Channel ").append(channel).append(" : ");
        } else if (cs) {
            sb.append("[Cash Server]");
        } else {
            sb.append("[Login Server]");
        }
        sb.append("IoSession opened ").append(address);
        System.out.println(sb.toString());
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
                session.close();
                session.removeAttribute(MapleClient.CLIENT_KEY);
            }
        }
        super.sessionClosed(session);
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
                if (debugMode) {
                    final StringBuilder sb = new StringBuilder("Received data 未處理 : ");
                    sb.append(tools.HexTool.toString((byte[]) message)).append("\n").append(tools.HexTool.toStringFromAscii((byte[]) message));
                    System.out.println(sb.toString());
                }
                return;
            }

            if (debugMode) {
                if (sensitive.contains(recv)) {
                    // The body holds a password in clear text - name the opcode only.
                    System.out.println("Received data 已處理 :" + String.valueOf(recv) + " [內容含密碼，不輸出]");
                } else {
                    final StringBuilder sb = new StringBuilder("Received data 已處理 :" + String.valueOf(recv) + "\n");
                    sb.append(tools.HexTool.toString((byte[]) message)).append("\n").append(tools.HexTool.toStringFromAscii((byte[]) message));
                    System.out.println(sb.toString());
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
            e.printStackTrace();
        }

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
