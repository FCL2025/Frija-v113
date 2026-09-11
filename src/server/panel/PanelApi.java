package server.panel;

import client.MapleCharacter;
import client.MapleClient;
import constants.ServerConstants;
import database.DatabaseConnection;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.world.World;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import server.MapleCarnivalChallenge;
import server.maps.MapleMap;
import tools.Encodings;

/**
 * The read-only routes: what the server is, who is on it, and what it has
 * logged. Everything here is a snapshot taken on a {@code Panel-Worker}
 * thread while the game runs on its own, so the same rules apply as to any
 * other cross-thread reader - take copies, never hold a lock across a call
 * into game code, and expect a player to vanish between two reads.
 *
 * <p>Head counts come from {@link World#getConnected()}, which walks each
 * channel's {@code PlayerStorage} right now. {@code LoginServer.getUsersOn()}
 * would be cheaper but is only as fresh as the last {@code setLoad()} push.
 */
final class PanelApi {

    private static final Path LOG_DIR = Path.of("Logs");
    private static final Pattern LOG_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;
    /** How much of the end of a log to decode when tailing. Enough for 2000 long lines. */
    private static final int TAIL_WINDOW_BYTES = 2 * 1024 * 1024;

    private PanelApi() {
    }

    static void register(final Router r) {
        r.get("/api/ping", req -> ping());
        r.get("/api/status", req -> status());
        r.get("/api/channels", req -> channels());
        r.get("/api/players", req -> players());
        r.get("/api/logs", req -> logs());
        r.get("/api/logs/{name}", req -> tail(req.param("name"),
                req.queryInt("lines", DEFAULT_TAIL_LINES, 1, MAX_TAIL_LINES)));
    }

    private static Map<String, Object> ping() {
        final var rt = ManagementFactory.getRuntimeMXBean();
        return Json.obj(
                "ok", true,
                "version", ServerConstants.MAPLE_VERSION,
                "patch", ServerConstants.MAPLE_PATCH,
                "serverName", LoginServer.getServerName(),
                "uptimeMs", rt.getUptime(),
                "startupMs", PanelServer.getStartupMillis(),
                "startedAt", rt.getStartTime());
    }

    private static Map<String, Object> status() {
        final Map<Integer, Integer> connected = World.getConnected();
        final Map<String, Object> perChannel = new LinkedHashMap<>();
        connected.entrySet().stream()
                .filter(e -> e.getKey() != 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> perChannel.put(String.valueOf(e.getKey()), e.getValue()));

        final var rt = ManagementFactory.getRuntimeMXBean();
        final MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return Json.obj(
                "serverName", LoginServer.getServerName(),
                "eventMessage", LoginServer.getEventMessage(),
                "online", connected.getOrDefault(0, 0),
                "userLimit", LoginServer.getUserLimit(),
                "channelCount", ChannelServer.getAllInstances().size(),
                "perChannel", perChannel,
                "loginSessions", LoginServer.getNumberOfSessions(),
                "adminOnly", LoginServer.isAdminOnly(),
                "shutdown", LoginServer.isShutdown() || World.isShutDown,
                "db", Json.obj(
                        "active", DatabaseConnection.getActiveConnections(),
                        "idle", DatabaseConnection.getIdleConnections(),
                        "poolSize", DatabaseConnection.getPoolSize()),
                "jvm", Json.obj(
                        "uptimeMs", rt.getUptime(),
                        "heapUsed", heap.getUsed(),
                        "heapCommitted", heap.getCommitted(),
                        "heapMax", heap.getMax(),
                        "threads", ManagementFactory.getThreadMXBean().getThreadCount(),
                        "cores", Runtime.getRuntime().availableProcessors(),
                        "javaVersion", Runtime.version().toString()));
    }

    private static List<Object> channels() {
        final List<ChannelServer> sorted = new ArrayList<>(ChannelServer.getAllInstances());
        sorted.sort(Comparator.comparingInt(ChannelServer::getChannel));
        final List<Object> out = new ArrayList<>(sorted.size());
        for (final ChannelServer cs : sorted) {
            out.add(Json.obj(
                    "channel", cs.getChannel(),
                    "port", cs.getPort(),
                    "ip", cs.getIP(),
                    "loadedMaps", cs.getLoadedMaps(),
                    "connected", cs.getConnectedClients(),
                    "expRate", cs.getExpRate(),
                    "mesoRate", cs.getMesoRate(),
                    "dropRate", cs.getDropRate(),
                    "cashRate", cs.getCashRate(),
                    "serverMessage", cs.getServerMessage(),
                    "adminOnly", cs.isAdminOnly(),
                    "shutdown", cs.isShutdown(),
                    "megaphoneMuted", cs.getMegaphoneMuteState()));
        }
        return out;
    }

    private static List<Object> players() {
        final List<ChannelServer> sorted = new ArrayList<>(ChannelServer.getAllInstances());
        sorted.sort(Comparator.comparingInt(ChannelServer::getChannel));
        final List<Object> out = new ArrayList<>();
        for (final ChannelServer cs : sorted) {
            // getAllCharacters() is a snapshot copy; a player who logs out
            // while this runs is still in it, with a client that is closing.
            for (final MapleCharacter chr : cs.getPlayerStorage().getAllCharacters()) {
                try {
                    out.add(player(chr, cs.getChannel()));
                } catch (final RuntimeException e) {
                    // Half torn-down player: skip rather than fail the list.
                }
            }
        }
        return out;
    }

    private static Map<String, Object> player(final MapleCharacter chr, final int channel) {
        final MapleMap map = chr.getMap();
        final MapleClient c = chr.getClient();
        String ip = "";
        if (c != null) {
            try {
                ip = c.getSessionIPAddress();
            } catch (final RuntimeException e) {
                // Session already gone.
            }
            // MINA renders the address as "/127.0.0.1"; the slash is noise.
            if (ip.startsWith("/")) {
                ip = ip.substring(1);
            }
        }
        return Json.obj(
                "id", chr.getId(),
                "name", chr.getName(),
                "level", chr.getLevel(),
                "job", chr.getJob(),
                "jobName", MapleCarnivalChallenge.getJobNameById(chr.getJob()),
                "channel", channel,
                "mapId", chr.getMapId(),
                "mapName", map == null ? "" : map.getMapName(),
                "accountId", c == null ? 0 : c.getAccID(),
                "accountName", c == null ? "" : String.valueOf(c.getAccountName()),
                "gmLevel", chr.getGMLevel(),
                "hidden", chr.isHidden(),
                "ip", ip);
    }

    private static List<Object> logs() throws IOException {
        final List<Object> out = new ArrayList<>();
        final File[] files = LOG_DIR.toFile().listFiles();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (final File f : files) {
            final String n = f.getName();
            if (f.isFile() && (n.endsWith(".rtf") || n.endsWith(".txt")) && LOG_NAME.matcher(n).matches()) {
                out.add(Json.obj("name", n, "size", f.length(), "modified", f.lastModified()));
            }
        }
        return out;
    }

    /**
     * The last {@code lines} lines of one log. The logs are written in
     * {@link Encodings#NATIVE} - MS950 on the machine this runs on, neither
     * UTF-8 nor quite BIG5 - so they are decoded with that and re-encoded as
     * UTF-8 by the JSON layer. Only the tail of a large file is read; a
     * window that starts inside a multi-byte character decodes with a
     * replacement mark, which lands in the first, partial line that is
     * dropped anyway.
     */
    private static Map<String, Object> tail(final String name, final int lines) throws IOException {
        // The name pattern has no separators in it, so resolve() cannot leave Logs/.
        if (!LOG_NAME.matcher(name).matches() || !(name.endsWith(".rtf") || name.endsWith(".txt"))) {
            throw new Router.HttpError(404, "找不到這個日誌");
        }
        final Path file = LOG_DIR.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new Router.HttpError(404, "找不到這個日誌");
        }
        final long size = Files.size(file);
        final long start = Math.max(0, size - TAIL_WINDOW_BYTES);
        final byte[] bytes = new byte[(int) (size - start)];
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            raf.readFully(bytes);
        }
        final String text = Encodings.NATIVE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        final String[] all = text.split("\r?\n", -1);
        int from = 0;
        int to = all.length;
        if (to > 0 && all[to - 1].isEmpty()) {
            to--; // trailing newline
        }
        boolean truncated = false;
        if (start > 0) {
            from = 1; // the window cut this line somewhere in the middle
            truncated = true;
        }
        if (to - from > lines) {
            from = to - lines;
            truncated = true;
        }
        final List<String> tail = new ArrayList<>(Arrays.asList(all).subList(from, to));
        return Json.obj(
                "name", name,
                "size", size,
                "modified", Files.getLastModifiedTime(file).toMillis(),
                "lines", tail,
                "truncated", truncated);
    }
}
