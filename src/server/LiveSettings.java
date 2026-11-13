package server;

import database.DatabaseConnection;
import handling.MapleServerHandler;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.mina.MapleAcceptor;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import scripting.ScriptCache;
import server.panel.PanelServer;
import tools.data.output.MaplePacketLittleEndianWriter;

/**
 * Every {@code Settings.ini} key, what it means, and how to put a new value
 * of it into effect while the server runs.
 *
 * <p>This is the one place that knows. The panel's settings page
 * ({@code server.panel.SettingsApi}), the {@code Settings.ini} watcher
 * ({@link SettingsWatcher}) and {@code !reloadsettings} all come through here,
 * so a key applies the same way whichever of them changed it.
 *
 * <p>The rule is "live unless it truly cannot be". Of the keys the code reads,
 * exactly one still needs a restart, and it is not for want of trying:
 * {@code tms.Url} names the database itself, and every online character, guild
 * and auto-increment id in memory came from the one the server started on - a
 * running server pointed somewhere else would save that world into a database
 * it never loaded. Changing the credentials for the same database
 * ({@code tms.User} / {@code tms.Pass}) is live: they are tried on a test
 * connection first and only then swapped in. Four more keys
 * ({@code tms.Port}, {@code tms.LPort}, {@code tms.Packet},
 * {@code tms.MaxCharacters}) are read by nothing at all and are reported as
 * such rather than as "needs a restart", which would be a lie in the other
 * direction.
 *
 * <p>Applying sets {@code ServerProperties} <em>before</em> calling the
 * applier and puts the old value back if it throws, because several appliers
 * work by asking the running code to re-read its property
 * ({@code tms.AutoSaveMinutes}, {@code tms.Count} for channels it starts).
 * Everything is serialised on this class: two sources changing settings at
 * once - a panel save and a hand edit of the file - must not interleave.
 */
public final class LiveSettings {

    private static final System.Logger LOG = System.getLogger(LiveSettings.class.getName());

    /** How a key takes effect. The wire names are what the panel shows. */
    public enum Mode {
        /** Applied to the running server when it changes. */
        LIVE("live"),
        /** Only a restart picks it up. */
        RESTART("restart"),
        /** Nothing reads it; changing it does nothing at all. */
        NONE("none");

        public final String wire;

        Mode(final String wire) {
            this.wire = wire;
        }
    }

    /** What applying one key did. {@code message} is for a human, and may be null. */
    public record Outcome(String key, String value, boolean ok, Mode mode, String message) {
    }

    @FunctionalInterface
    private interface Applier {

        /** @return a note for the operator, or null */
        String apply(String value) throws Exception;
    }

    @FunctionalInterface
    private interface Validator {

        /** @throws IllegalArgumentException with a message fit for the operator */
        void check(String value);
    }

    private record Spec(Mode mode, String description, Validator validator, Applier applier) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    private static final Pattern CHANNEL_PORT = Pattern.compile("tms\\.Port(\\d+)");
    private static final Set<String> CREDENTIALS = Set.of("tms.User", "tms.Pass");
    /** Secrets: never echoed back in an outcome or a log line. */
    private static final Set<String> SECRET = Set.of("tms.Pass", "tms.PanelToken");

    static {
        restart("tms.Url", "JDBC 連線字串；資料庫名稱、主機與埠號都在這裡。"
                + "換資料庫等於換掉整個世界的資料來源（線上角色、公會、流水號都來自原本那個庫），所以只能重啟",
                v -> requireNonBlank("tms.Url", v));
        live("tms.User", "資料庫帳號（先用新帳密試連，成功才切換）", v -> {
        }, v -> credentials("tms.User", v));
        live("tms.Pass", "資料庫密碼（先用新帳密試連，成功才切換）", v -> {
        }, v -> credentials("tms.Pass", v));
        live("tms.Count", "頻道數；增加會立刻開新頻道，減少會把最後幾個頻道的玩家移到頻道 1 再關閉",
                v -> intIn("tms.Count", v, 1, 50, false),
                v -> ChannelServer.setChannelCount(Integer.parseInt(v.trim())));
        live("tms.Exp", "經驗倍率", LiveSettings::rate, v -> rates(v, (cs, n) -> cs.setExpRate(n)));
        live("tms.Meso", "楓幣倍率", LiveSettings::rate, v -> rates(v, (cs, n) -> cs.setMesoRate(n)));
        live("tms.Drop", "掉寶倍率", LiveSettings::rate, v -> rates(v, (cs, n) -> cs.setDropRate(n)));
        live("tms.Cash", "點數倍率", LiveSettings::rate, v -> rates(v, (cs, n) -> cs.setCashRate(n)));
        live("tms.ServerName", "伺服器名稱（登入畫面的伺服器清單）", LiveSettings::singleLine, v -> {
            LoginServer.setServerName(v);
            for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                cs.setServerName(v);
            }
            return null;
        });
        live("tms.EventMessage", "登入畫面的活動訊息", LiveSettings::singleLine, v -> {
            LoginServer.setEventMessage(v);
            return null;
        });
        live("tms.ServerMessage", "頻道公告（套用時會立刻廣播給線上玩家）", LiveSettings::singleLine, v -> {
            for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                cs.setServerMessage(v);
            }
            return "已廣播給 " + ChannelServer.getAllInstances().size() + " 個頻道";
        });
        live("tms.Events", "要載入的事件腳本；新增的立刻編譯啟動，移除的立刻停止，其餘照常執行",
                LiveSettings::singleLine, LiveSettings::events);
        live("tms.Flag", "登入畫面的伺服器旗標（0 無、1 活動、2 新、3 熱門）",
                v -> intIn("tms.Flag", v, 0, 3, false), v -> {
                    LoginServer.setFlag(Byte.parseByte(v.trim()));
                    return null;
                });
        live("tms.AutoRegister", "帳號不存在時自動註冊（每次登入時讀取）", v -> bool("tms.AutoRegister", v), v -> null);
        live("tms.WFlags", "世界旗標（腳本以 getTempFlag 讀取）", v -> intIn("tms.WFlags", v, 0, Integer.MAX_VALUE, true), v -> {
            final int flags = v.isBlank() ? 0 : Integer.parseInt(v.trim());
            for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                cs.setTempFlag(flags);
            }
            return null;
        });
        live("tms.Admin", "只允許 GM 登入（下一次登入起生效，已在線上的玩家不受影響）", v -> bool("tms.Admin", v), v -> {
            final boolean on = Boolean.parseBoolean(v.trim());
            LoginServer.setAdminOnly(on);
            for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                cs.setAdminOnly(on);
            }
            return null;
        });
        live("tms.UserLimit", "同時上線人數上限", v -> intIn("tms.UserLimit", v, 0, Integer.MAX_VALUE, false), v -> {
            LoginServer.setUserLimit(Integer.parseInt(v.trim()));
            return null;
        });
        none("tms.MaxCharacters", "（無作用）每個帳號的角色欄位數由資料表 character_slots 決定，沒有程式讀這個設定",
                v -> intIn("tms.MaxCharacters", v, 0, Integer.MAX_VALUE, true));
        none("tms.Port", "（無作用）頻道 port 由 tms.Port<n> 決定，預設 8585+n", v -> intIn("tms.Port", v, 0, 65535, true));
        none("tms.LPort", "（無作用）登入 port 固定 8484", v -> intIn("tms.LPort", v, 0, 65535, true));
        none("tms.Packet", "（無作用）", v -> bool("tms.Packet", v));
        live("tms.IP", "告訴客戶端要連的 IP（每次登入、換頻道時讀取）", LiveSettings::ipv4, v -> null);
        live("tms.Debug", "把收送的封包印成十六進位（另需在 Libs/logging.properties 打開對應 logger）",
                v -> bool("tms.Debug", v), v -> {
                    final boolean on = Boolean.parseBoolean(v.trim());
                    MapleServerHandler.setDebugMode(on);
                    MaplePacketLittleEndianWriter.setDebugMode(on);
                    return null;
                });
        live("tms.HandlerThreads", "封包處理執行緒數（留空 = 預設）",
                v -> intIn("tms.HandlerThreads", v, 1, 1024, true), v -> {
                    MapleAcceptor.setHandlerThreads(intOrZero(v));
                    return "目前 " + MapleAcceptor.getHandlerThreads() + " 條";
                });
        live("tms.TimerThreads", "每個排程池的執行緒數（留空 = 預設）",
                v -> intIn("tms.TimerThreads", v, 1, 256, true), v -> {
                    Timer.setPoolSize(intOrZero(v));
                    return null;
                });
        live("tms.DbPoolSize", "資料庫連線池上限（調小時，已借出的連線還回來才會收回）",
                v -> intIn("tms.DbPoolSize", v, 1, 1000, true), v -> {
                    DatabaseConnection.setPoolSize(intOrZero(v));
                    return "目前上限 " + DatabaseConnection.getPoolSize();
                });
        live("tms.DbPoolTimeout", "等待連線的逾時（毫秒）",
                v -> intIn("tms.DbPoolTimeout", v, 1000, 600000, true), v -> {
                    DatabaseConnection.setPoolTimeout(intOrZero(v));
                    return null;
                });
        live("tms.PanelPort", "管理面板 port（0 停用）；換 port 後請改用新網址",
                v -> intIn("tms.PanelPort", v, 0, 65535, true),
                v -> PanelServer.scheduleMove(v.isBlank() ? 8080 : Integer.parseInt(v.trim())));
        live("tms.PanelThreads", "管理面板執行緒數（留空 = 預設）",
                v -> intIn("tms.PanelThreads", v, 1, 256, true), v -> {
                    PanelServer.setThreads(intOrZero(v));
                    return null;
                });
        live("tms.PanelToken", "面板登入 Token（留空 = 不需登入，只限本機瀏覽器）；設定或更換後要用新 Token 重新登入",
                v -> {
                    if (v.trim().chars().anyMatch(Character::isWhitespace)) {
                        throw new IllegalArgumentException("tms.PanelToken 不可含空白");
                    }
                }, PanelServer::setToken);
        live("tms.AutoSaveMinutes", "自動存檔間隔（分鐘，0 或留空 = 關閉）；新間隔從套用的那一刻起算",
                v -> intIn("tms.AutoSaveMinutes", v, 0, 1440, true), v -> {
                    for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                        cs.restartAutoSave();
                    }
                    return null;
                });
    }

    private LiveSettings() {
    }

    /* ------------------------------------------------------------------ */
    /* Lookup                                                               */
    /* ------------------------------------------------------------------ */

    /** The spec for {@code key}, including the per-channel {@code tms.Port<n>} family; null if unknown. */
    private static Spec spec(final String key) {
        final Spec s = SPECS.get(key);
        if (s != null) {
            return s;
        }
        final var m = CHANNEL_PORT.matcher(key);
        if (m.matches()) {
            final int channel = Integer.parseInt(m.group(1));
            return new Spec(Mode.LIVE, "頻道 " + channel + " 的 port（留空 = " + (8585 + channel)
                    + "）；換 port 不會讓線上玩家斷線，之後登入或換頻道的玩家走新的 port",
                    v -> intIn(key, v, 1, 65535, true), v -> channelPort(channel, v));
        }
        return null;
    }

    public static boolean isKnown(final String key) {
        return spec(key) != null;
    }

    /** {@link Mode#RESTART} for a key nothing here knows - the honest default for something unexplained. */
    public static Mode mode(final String key) {
        final Spec s = spec(key);
        return s == null ? Mode.RESTART : s.mode();
    }

    public static String description(final String key) {
        final Spec s = spec(key);
        return s == null ? "" : s.description();
    }

    public static boolean isSecret(final String key) {
        return SECRET.contains(key);
    }

    /**
     * Checks a value the way the running server will parse it.
     *
     * @throws IllegalArgumentException with a message fit for the operator
     */
    public static void validate(final String key, final String value) {
        final Spec s = spec(key);
        if (s != null && s.validator() != null) {
            s.validator().check(value == null ? "" : value);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Applying                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Puts one key's new value into effect. Validates first; a value that does
     * not pass is not applied and {@code ServerProperties} keeps the old one.
     * For a {@link Mode#RESTART} key the new value is <em>not</em> put into
     * {@code ServerProperties} - what the server is running is still the old
     * one, and the panel shows the difference.
     */
    public static synchronized Outcome apply(final String key, final String value) {
        final String v = value == null ? "" : value;
        final Spec s = spec(key);
        if (s == null) {
            ServerProperties.setProperty(key, v);
            return outcome(key, v, true, Mode.RESTART, "程式不認得這個設定；已記在記憶體裡，用得到它的程式下次讀取時會看到");
        }
        try {
            validate(key, v);
        } catch (final IllegalArgumentException e) {
            return outcome(key, v, false, s.mode(), e.getMessage());
        }
        switch (s.mode()) {
            case RESTART:
                return outcome(key, v, false, Mode.RESTART, "這個設定只能重啟後生效");
            case NONE:
                ServerProperties.setProperty(key, v);
                return outcome(key, v, true, Mode.NONE, "沒有程式讀這個設定，改了不會有任何效果");
            default:
                break;
        }
        final String old = ServerProperties.getProperty(key);
        ServerProperties.setProperty(key, v);
        try {
            final String note = s.applier().apply(v);
            return outcome(key, v, true, Mode.LIVE, note);
        } catch (final Exception e) {
            if (old == null) {
                ServerProperties.removeProperty(key);
            } else {
                ServerProperties.setProperty(key, old);
            }
            final String reason = e instanceof IllegalArgumentException || e instanceof IllegalStateException
                    || e instanceof SQLException ? e.getMessage() : String.valueOf(e);
            LOG.log(System.Logger.Level.WARNING, "[設定] 無法套用 " + key + "：" + reason);
            return outcome(key, v, false, Mode.LIVE, reason);
        }
    }

    /**
     * Re-reads {@code Settings.ini} and applies every key whose value in the
     * file is not what the server is running.
     *
     * <p>A key deleted or commented out of the file counts as set to blank,
     * i.e. back to its default - where blank is allowed; a key that must
     * have a value (the rates, {@code tms.Count}, …) is then reported and left
     * as it is. Keys are applied in an order that lets one depend on another:
     * credentials first, the plain values next, channel ports before the
     * event list, and {@code tms.Count} last, so channels it starts see every
     * other new value.
     *
     * @return what happened to each changed key; empty when nothing changed
     */
    public static synchronized List<Outcome> applyFile() throws IOException {
        final Map<String, String> file = asMap(ServerProperties.readSettingsFile());
        final List<String> changed = changedKeys(file, ServerProperties::getProperty, ServerProperties.propertyNames());
        // A restart-only key put back to what the server is running is no
        // longer pending; forget it, so changing it again is reported again.
        pendingRestart.keySet().retainAll(changed);
        final List<Outcome> out = new ArrayList<>();
        boolean credentialsDone = false;
        for (final String key : changed) {
            final String value = file.getOrDefault(key, "");
            if (CREDENTIALS.contains(key)) {
                if (!credentialsDone) {
                    credentialsDone = true;
                    out.add(apply(key, value));
                }
                continue;
            }
            final Mode mode = mode(key);
            if (mode == Mode.RESTART && isKnown(key)) {
                if (!value.equals(pendingRestart.get(key))) {
                    pendingRestart.put(key, value);
                    out.add(outcome(key, value, false, Mode.RESTART, "需重啟伺服器才會生效"));
                }
                continue;
            }
            out.add(apply(key, value));
        }
        return out;
    }

    /** Restart-only values already reported, so an unrelated edit does not report them again. */
    private static final Map<String, String> pendingRestart = new HashMap<>();

    /**
     * The {@code tms.*} keys whose value in {@code file} differs from what
     * {@code running} answers, ordered for {@link #applyFile}. A key present
     * on only one side compares against blank. Pure, so it can be tested
     * without a running server.
     */
    static List<String> changedKeys(final Map<String, String> file, final Function<String, String> running,
            final Collection<String> runningKeys) {
        final Set<String> keys = new TreeSet<>();
        for (final String k : file.keySet()) {
            if (k.startsWith("tms.")) {
                keys.add(k);
            }
        }
        for (final String k : runningKeys) {
            if (k.startsWith("tms.")) {
                keys.add(k);
            }
        }
        final List<String> changed = new ArrayList<>();
        for (final String k : keys) {
            final String now = Objects.requireNonNullElse(running.apply(k), "").trim();
            final String want = file.getOrDefault(k, "").trim();
            if (!now.equals(want)) {
                changed.add(k);
            }
        }
        changed.sort(Comparator.comparingInt(LiveSettings::applyOrder));
        return changed;
    }

    private static int applyOrder(final String key) {
        if (CREDENTIALS.contains(key)) {
            return 0;
        }
        if (CHANNEL_PORT.matcher(key).matches()) {
            return 2;
        }
        return switch (key) {
            case "tms.Events" -> 3;
            case "tms.Count" -> 4;
            default -> 1;
        };
    }

    private static Map<String, String> asMap(final Properties p) {
        final Map<String, String> m = new LinkedHashMap<>();
        for (final String k : p.stringPropertyNames()) {
            m.put(k, p.getProperty(k).trim());
        }
        return m;
    }

    /** One record for a batch, for the watcher and {@code !reloadsettings}. */
    public static String describe(final List<Outcome> outcomes) {
        final StringBuilder sb = new StringBuilder();
        for (final Outcome o : outcomes) {
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append("  ").append(o.key());
            if (!isSecret(o.key()) && o.value() != null) {
                sb.append(" = ").append(o.value());
            }
            sb.append(" → ").append(o.ok() ? (o.mode() == Mode.NONE ? "無作用" : "已套用") : "未套用");
            if (o.message() != null && !o.message().isEmpty()) {
                sb.append("（").append(o.message()).append("）");
            }
        }
        return sb.toString();
    }

    private static Outcome outcome(final String key, final String value, final boolean ok, final Mode mode,
            final String message) {
        return new Outcome(key, isSecret(key) ? null : value, ok, mode, message);
    }

    /* ------------------------------------------------------------------ */
    /* Appliers                                                             */
    /* ------------------------------------------------------------------ */

    private interface RateSetter {

        void set(ChannelServer cs, int rate);
    }

    private static String rates(final String value, final RateSetter setter) {
        final int n = Integer.parseInt(value.trim());
        for (final ChannelServer cs : ChannelServer.getAllInstances()) {
            setter.set(cs, n);
        }
        return null;
    }

    /**
     * The other half of the pair comes from {@code Settings.ini} rather than
     * from memory: changing both through the panel is two saves, and the first
     * one - new user, old password - cannot log in on its own. By the time the
     * second save applies, the file holds both new values.
     */
    private static String credentials(final String key, final String value) throws SQLException {
        String user = key.equals("tms.User") ? value : null;
        String pass = key.equals("tms.Pass") ? value : null;
        try {
            final Properties file = ServerProperties.readSettingsFile();
            if (user == null) {
                user = file.getProperty("tms.User");
            }
            if (pass == null) {
                pass = file.getProperty("tms.Pass");
            }
        } catch (final IOException e) {
            // Fall back to what is in memory below.
        }
        if (user == null) {
            user = ServerProperties.getProperty("tms.User", "");
        }
        if (pass == null) {
            pass = ServerProperties.getProperty("tms.Pass", "");
        }
        user = user.trim();
        pass = pass.trim();
        try {
            DatabaseConnection.setCredentials(user, pass);
        } catch (final SQLException e) {
            throw new SQLException("新的資料庫帳密無法登入，維持原本的連線：" + e.getMessage(), e);
        }
        ServerProperties.setProperty("tms.User", user);
        ServerProperties.setProperty("tms.Pass", pass);
        return "已用新帳密連線成功並切換；借出中的連線用完後會換新";
    }

    private static String events(final String value) {
        final String[] names = Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        final Set<String> started = new LinkedHashSet<>();
        final Set<String> stopped = new LinkedHashSet<>();
        for (final ChannelServer cs : ChannelServer.getAllInstances()) {
            if (cs.isShutdown()) {
                continue;
            }
            final List<List<String>> result = cs.applyEvents(names, Set.of());
            started.addAll(result.get(0));
            stopped.addAll(result.get(1));
        }
        final List<String> missing = new ArrayList<>();
        for (final String name : names) {
            if (!Files.isRegularFile(Path.of(ScriptCache.ROOT, "event", name + ".js"))) {
                missing.add(name);
            }
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(started.isEmpty() ? "沒有新啟動的事件" : "啟動 " + started);
        sb.append("，").append(stopped.isEmpty() ? "沒有停止的事件" : "停止 " + stopped);
        if (!missing.isEmpty()) {
            sb.append("；找不到腳本檔（會被略過）：").append(missing);
        }
        return sb.toString();
    }

    private static String channelPort(final int channel, final String value) throws IOException {
        final ChannelServer cs = ChannelServer.getInstance(channel);
        final int port = value.isBlank() ? 8585 + channel : Integer.parseInt(value.trim());
        if (cs == null || cs.isShutdown()) {
            return "頻道 " + channel + " 目前沒有開，開啟時會用 port " + port;
        }
        cs.rebind(port);
        return "頻道 " + channel + " 改聽 port " + port;
    }

    /* ------------------------------------------------------------------ */
    /* Validators                                                           */
    /* ------------------------------------------------------------------ */

    private static void rate(final String v) {
        intIn("倍率", v, 1, 100000, false);
    }

    private static void intIn(final String key, final String v, final int min, final int max, final boolean blankOk) {
        final String t = v.trim();
        if (t.isEmpty()) {
            if (blankOk) {
                return;
            }
            throw new IllegalArgumentException(key + " 不可留空");
        }
        final int n;
        try {
            n = Integer.parseInt(t);
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必須是整數");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(key + " 必須介於 " + min + " 到 " + max);
        }
    }

    private static int intOrZero(final String v) {
        return v.isBlank() ? 0 : Integer.parseInt(v.trim());
    }

    private static void bool(final String key, final String v) {
        final String t = v.trim();
        if (!t.isEmpty() && !t.equalsIgnoreCase("true") && !t.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " 必須是 true 或 false");
        }
    }

    private static void singleLine(final String v) {
        if (v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("值不可含換行");
        }
    }

    private static void requireNonBlank(final String key, final String v) {
        if (v.isBlank()) {
            throw new IllegalArgumentException(key + " 不可留空");
        }
    }

    /**
     * {@code tms.IP} goes into every login and channel-change packet as four
     * bytes, so a value without an IPv4 address would send every client to
     * 0.0.0.0. Refusing it here keeps the running server on the old one.
     */
    private static void ipv4(final String v) {
        requireNonBlank("tms.IP", v);
        try {
            for (final InetAddress a : InetAddress.getAllByName(v.trim())) {
                if (a instanceof Inet4Address) {
                    return;
                }
            }
            throw new IllegalArgumentException("tms.IP = " + v.trim() + " 沒有 IPv4 位址");
        } catch (final UnknownHostException e) {
            throw new IllegalArgumentException("tms.IP = " + v.trim() + " 無法解析");
        }
    }

    /* ------------------------------------------------------------------ */

    private static void live(final String key, final String description, final Validator validator, final Applier applier) {
        SPECS.put(key, new Spec(Mode.LIVE, description, validator, applier));
    }

    private static void restart(final String key, final String description, final Validator validator) {
        SPECS.put(key, new Spec(Mode.RESTART, description, validator, null));
    }

    private static void none(final String key, final String description, final Validator validator) {
        SPECS.put(key, new Spec(Mode.NONE, description, validator, null));
    }
}
