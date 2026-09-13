package server.panel;

import handling.channel.ChannelServer;
import handling.login.LoginServer;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import server.ServerProperties;

/**
 * Reads and writes {@code Settings.ini}, and applies what can be applied to
 * the running server.
 *
 * <p>Every key falls into one of three classes, and the panel shows which,
 * because "saved" and "in effect" are different things here:
 *
 * <dl>
 * <dt>{@code live}</dt><dd>There is a setter on a running object: the four
 * rates on every {@code ChannelServer}, the login-screen fields on
 * {@code LoginServer}. Saving and applying makes the change felt now.</dd>
 * <dt>{@code memory}</dt><dd>Only {@code ServerProperties.setProperty} - the
 * code reads the property again each time it needs it, so the change takes
 * effect for the next use without a restart, but nothing already computed
 * from the old value moves.</dd>
 * <dt>{@code restart}</dt><dd>Read once at startup into a field, or before
 * the panel even exists (ports, the database URL, thread pool sizes). The
 * file changes; the process does not.</dd>
 * </dl>
 *
 * <p>{@code tms.ServerMessage} is deliberately not a setting here.
 * {@code ChannelServer.setServerMessage} broadcasts to the channel the moment
 * it is called, so it is a "send" action in {@link ActionsApi}'s neighbour
 * {@link #serverMessage}, never something a form auto-saves.
 *
 * <p>Writes go through {@link IniFile}, which keeps every byte of the file it
 * is not asked to change - the BOM, the CRLFs, the comments that are the only
 * documentation some of these keys have.
 */
final class SettingsApi {

    private static final System.Logger LOG = System.getLogger(SettingsApi.class.getName());

    static final Path INI = Path.of("Settings.ini");

    private enum Apply {
        LIVE("live"), MEMORY("memory"), RESTART("restart");

        final String name;

        Apply(final String name) {
            this.name = name;
        }
    }

    /** One known key: how it applies, what it is for, and how to apply it. */
    private record Spec(Apply apply, String description, Consumer<String> live) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();
    /** Never leave the server. Writes are still allowed; reads come back blank. */
    private static final Set<String> SECRET = Set.of("tms.Pass", "tms.PanelToken");
    private static final Set<String> RATE_KEYS = Set.of("tms.Exp", "tms.Meso", "tms.Drop", "tms.Cash");

    static {
        spec("tms.Url", Apply.RESTART, "JDBC 連線字串；資料庫名稱、主機與埠號都在這裡");
        spec("tms.User", Apply.RESTART, "資料庫帳號");
        spec("tms.Pass", Apply.RESTART, "資料庫密碼");
        spec("tms.Count", Apply.RESTART, "頻道數");
        spec("tms.Exp", Apply.LIVE, "經驗倍率", v -> rate(v, (cs, n) -> cs.setExpRate(n)));
        spec("tms.Meso", Apply.LIVE, "楓幣倍率", v -> rate(v, (cs, n) -> cs.setMesoRate(n)));
        spec("tms.Drop", Apply.LIVE, "掉寶倍率", v -> rate(v, (cs, n) -> cs.setDropRate(n)));
        spec("tms.Cash", Apply.LIVE, "點數倍率", v -> rate(v, (cs, n) -> cs.setCashRate(n)));
        spec("tms.ServerName", Apply.RESTART, "伺服器名稱（登入畫面與頻道各存一份，啟動時讀取）");
        spec("tms.EventMessage", Apply.LIVE, "登入畫面的活動訊息", LoginServer::setEventMessage);
        spec("tms.ServerMessage", Apply.LIVE, "頻道公告（改了會立刻廣播，請用「送出公告」）");
        spec("tms.Events", Apply.MEMORY, "啟動時載入的事件腳本清單；改完按「重載事件」才會重新編譯");
        spec("tms.Flag", Apply.LIVE, "登入畫面的伺服器旗標（0 無、1 活動、2 新、3 熱門）",
                v -> LoginServer.setFlag(Byte.parseByte(v.trim())));
        spec("tms.AutoRegister", Apply.MEMORY, "帳號不存在時自動註冊（每次登入時讀取）");
        spec("tms.WFlags", Apply.RESTART, "世界旗標");
        spec("tms.Admin", Apply.RESTART, "只允許 GM 登入");
        spec("tms.UserLimit", Apply.LIVE, "同時上線人數上限", v -> LoginServer.setUserLimit(Integer.parseInt(v.trim())));
        spec("tms.MaxCharacters", Apply.RESTART, "每個帳號的角色數上限");
        spec("tms.Port", Apply.RESTART, "（無作用）頻道 port 由 tms.Port<n> 決定，預設 8585+n");
        spec("tms.LPort", Apply.RESTART, "（無作用）登入 port 固定 8484");
        spec("tms.IP", Apply.MEMORY, "告訴客戶端要連的 IP（換頻道時讀取）");
        spec("tms.Packet", Apply.RESTART, "（無作用）");
        spec("tms.Debug", Apply.RESTART, "把每個收到的封包印成十六進位");
        spec("tms.HandlerThreads", Apply.RESTART, "封包處理執行緒數");
        spec("tms.TimerThreads", Apply.RESTART, "每個排程池的執行緒數");
        spec("tms.DbPoolSize", Apply.RESTART, "資料庫連線池上限");
        spec("tms.DbPoolTimeout", Apply.RESTART, "等待連線的逾時（毫秒）");
        spec("tms.PanelPort", Apply.RESTART, "管理面板 port（0 停用）");
        spec("tms.PanelThreads", Apply.RESTART, "管理面板執行緒數");
        spec("tms.PanelToken", Apply.RESTART, "固定的面板登入 Token");
    }

    private static void spec(final String key, final Apply apply, final String description) {
        SPECS.put(key, new Spec(apply, description, null));
    }

    private static void spec(final String key, final Apply apply, final String description, final Consumer<String> live) {
        SPECS.put(key, new Spec(apply, description, live));
    }

    private interface RateSetter {

        void set(ChannelServer cs, int rate);
    }

    private static void rate(final String value, final RateSetter setter) {
        final int n = parseRate(value);
        for (final ChannelServer cs : ChannelServer.getAllInstances()) {
            setter.set(cs, n);
        }
    }

    private static int parseRate(final String value) {
        final int n;
        try {
            n = Integer.parseInt(value.trim());
        } catch (final NumberFormatException e) {
            throw new Router.HttpError(400, "倍率必須是整數");
        }
        if (n < 1 || n > 100000) {
            throw new Router.HttpError(400, "倍率必須介於 1 到 100000");
        }
        return n;
    }

    private SettingsApi() {
    }

    static void register(final Router r) {
        r.get("/api/settings", req -> settings());
        r.put("/api/settings/{key}", req -> put(req.param("key"), req.bodyObject()));
        r.post("/api/rates", req -> rates(req.bodyObject()));
        r.post("/api/server-message", req -> serverMessage(req.bodyObject()));
    }

    /**
     * The file as the panel shows it: every active key in file order, with
     * its class and description, plus the commented-out default when the file
     * carries one and the key is not active - so the form can show
     * {@code #tms.DbPoolSize = 64} as a placeholder rather than a blank.
     */
    private static Map<String, Object> settings() throws IOException {
        final IniFile ini = IniFile.load(INI);
        final Map<String, String> active = ini.entries();
        final Map<String, String> commented = new LinkedHashMap<>();
        for (final IniFile.Entry e : ini.lines()) {
            if (e.kind() == IniFile.Kind.COMMENT && e.key() != null && !active.containsKey(e.key())) {
                commented.put(e.key(), e.value());
            }
        }
        final List<Object> entries = new ArrayList<>();
        for (final Map.Entry<String, String> e : active.entrySet()) {
            entries.add(entry(e.getKey(), e.getValue(), true, null));
        }
        for (final Map.Entry<String, String> e : commented.entrySet()) {
            entries.add(entry(e.getKey(), null, false, e.getValue()));
        }
        return Json.obj(
                "path", INI.toAbsolutePath().toString(),
                "bom", ini.hasBom(),
                "entries", entries);
    }

    private static Map<String, Object> entry(final String key, final String value, final boolean active,
            final String commentedDefault) {
        final Spec spec = SPECS.get(key);
        final boolean secret = SECRET.contains(key);
        final String liveValue = liveValue(key);
        return Json.obj(
                "key", key,
                "value", secret ? null : value,
                "active", active,
                "secret", secret,
                "commentedDefault", commentedDefault,
                "apply", spec == null ? Apply.RESTART.name : spec.apply().name,
                "description", spec == null ? "" : spec.description(),
                "runtimeValue", secret ? null : liveValue,
                "known", spec != null);
    }

    /**
     * What the running server is actually using, for the keys where that can
     * differ from the file: the rates (per channel, but the panel sets them
     * together, so channel 1 stands for all), and the memory-only ones through
     * {@code ServerProperties}.
     */
    private static String liveValue(final String key) {
        final ChannelServer first = ChannelServer.getAllInstances().stream()
                .min((a, b) -> Integer.compare(a.getChannel(), b.getChannel())).orElse(null);
        switch (key) {
            case "tms.Exp":
                return first == null ? null : String.valueOf(first.getExpRate());
            case "tms.Meso":
                return first == null ? null : String.valueOf(first.getMesoRate());
            case "tms.Drop":
                return first == null ? null : String.valueOf(first.getDropRate());
            case "tms.Cash":
                return first == null ? null : String.valueOf(first.getCashRate());
            case "tms.ServerMessage":
                return first == null ? null : first.getServerMessage();
            case "tms.EventMessage":
                return LoginServer.getEventMessage();
            case "tms.Flag":
                return String.valueOf(LoginServer.getFlag());
            case "tms.UserLimit":
                return String.valueOf(LoginServer.getUserLimit());
            case "tms.ServerName":
                return LoginServer.getServerName();
            default:
                return ServerProperties.getProperty(key);
        }
    }

    /**
     * Saves one key, and applies it when asked and possible. The reply keeps
     * the two outcomes apart: a full disk and a stopped channel are different
     * failures, and "saved but not applied" is a state the UI has to be able
     * to show rather than round down to "needs restart".
     */
    private static Map<String, Object> put(final String key, final Map<String, Object> body) throws IOException {
        validateKey(key);
        final Object raw = body.get("value");
        if (raw == null) {
            throw new Router.HttpError(400, "缺少 value");
        }
        final String value = String.valueOf(raw);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new Router.HttpError(400, "值不可含換行");
        }
        if (key.equals("tms.ServerMessage")) {
            throw new Router.HttpError(400, "頻道公告請用「送出公告」，它會立刻廣播給線上玩家");
        }
        final boolean apply = Boolean.TRUE.equals(body.get("apply"));
        final Spec spec = SPECS.get(key);

        // Validate before touching anything: a value the server cannot parse
        // must not reach the file, or the next restart dies on it.
        validateValue(key, value);

        final Map<String, Object> saved = save(key, value);
        final Map<String, Object> applied = apply ? applyOne(key, value, spec) : Json.obj("ok", false, "skipped", true);
        return Json.obj(
                "key", key,
                "value", SECRET.contains(key) ? null : value,
                "apply", spec == null ? Apply.RESTART.name : spec.apply().name,
                "saved", saved,
                "applied", applied);
    }

    private static Map<String, Object> save(final String key, final String value) {
        return save(Map.of(key, value));
    }

    /**
     * Load, set, save - under one lock, because {@link IniFile} is a plain
     * in-memory copy and two panel requests writing different keys at once
     * would each save a file missing the other's change.
     */
    private static Map<String, Object> save(final Map<String, String> values) {
        synchronized (INI_LOCK) {
            try {
                final IniFile ini = IniFile.load(INI);
                boolean changed = false;
                for (final Map.Entry<String, String> e : values.entrySet()) {
                    changed |= ini.set(e.getKey(), e.getValue());
                }
                if (changed) {
                    ini.save(INI);
                }
                return Json.obj("ok", true, "changed", changed);
            } catch (final IOException | RuntimeException e) {
                // One record where there were two prints: the message and the
                // trace are the same event. Only the console half moved - the
                // reply below is the same {"ok": false, "error": …} object.
                LOG.log(System.Logger.Level.ERROR, "[面板] 寫入 " + INI + " 失敗", e);
                return Json.obj("ok", false, "error", String.valueOf(e));
            }
        }
    }

    private static final Object INI_LOCK = new Object();

    private static Map<String, Object> applyOne(final String key, final String value, final Spec spec) {
        final Apply mode = spec == null ? Apply.RESTART : spec.apply();
        if (mode == Apply.RESTART) {
            return Json.obj("ok", false, "mode", mode.name, "error", "這個設定只能重啟後生效");
        }
        try {
            ServerProperties.setProperty(key, value);
            if (spec.live() != null) {
                spec.live().accept(value);
            }
            return Json.obj("ok", true, "mode", mode.name);
        } catch (final Router.HttpError e) {
            throw e;
        } catch (final RuntimeException e) {
            return Json.obj("ok", false, "mode", mode.name, "error", String.valueOf(e));
        }
    }

    /**
     * All four rates at once, applied to every channel and written back, with
     * the two outcomes reported separately - see {@link #put}.
     */
    private static Map<String, Object> rates(final Map<String, Object> body) {
        final Map<String, Integer> wanted = new LinkedHashMap<>();
        for (final String field : List.of("exp", "meso", "drop", "cash")) {
            final Object v = body.get(field);
            if (v != null) {
                wanted.put(field, parseRate(String.valueOf(v)));
            }
        }
        if (wanted.isEmpty()) {
            throw new Router.HttpError(400, "至少要給 exp / meso / drop / cash 其中一個");
        }
        final List<ChannelServer> channels = new ArrayList<>(ChannelServer.getAllInstances());
        for (final ChannelServer cs : channels) {
            if (wanted.containsKey("exp")) {
                cs.setExpRate(wanted.get("exp"));
            }
            if (wanted.containsKey("meso")) {
                cs.setMesoRate(wanted.get("meso"));
            }
            if (wanted.containsKey("drop")) {
                cs.setDropRate(wanted.get("drop"));
            }
            if (wanted.containsKey("cash")) {
                cs.setCashRate(wanted.get("cash"));
            }
        }
        final Map<String, String> toSave = new LinkedHashMap<>();
        for (final Map.Entry<String, Integer> e : wanted.entrySet()) {
            final String key = "tms." + Character.toUpperCase(e.getKey().charAt(0)) + e.getKey().substring(1);
            ServerProperties.setProperty(key, String.valueOf(e.getValue()));
            toSave.put(key, String.valueOf(e.getValue()));
        }
        return Json.obj("applied", Json.obj("ok", true, "channels", channels.size()), "saved", save(toSave), "rates", wanted);
    }

    /**
     * Sets the channel notice on every channel - which broadcasts it at once -
     * and, when asked, writes it to the file as {@code tms.ServerMessage}.
     */
    private static Map<String, Object> serverMessage(final Map<String, Object> body) {
        final String message = ActionsApi.string(body, "message");
        if (message == null) {
            throw new Router.HttpError(400, "缺少 message");
        }
        if (message.indexOf('\r') >= 0 || message.indexOf('\n') >= 0) {
            throw new Router.HttpError(400, "公告不可含換行");
        }
        final List<ChannelServer> channels = new ArrayList<>(ChannelServer.getAllInstances());
        for (final ChannelServer cs : channels) {
            cs.setServerMessage(message);
        }
        ServerProperties.setProperty("tms.ServerMessage", message);
        final boolean persist = Boolean.TRUE.equals(body.get("save"));
        final Map<String, Object> saved = persist ? save("tms.ServerMessage", message) : Json.obj("ok", false, "skipped", true);
        return Json.obj("applied", Json.obj("ok", true, "channels", channels.size()), "saved", saved);
    }

    private static void validateKey(final String key) {
        if (key == null || key.isBlank() || key.length() > 64 || !key.matches("[A-Za-z0-9_.-]+")) {
            throw new Router.HttpError(400, "不合法的設定名稱");
        }
    }

    private static final Set<String> INT_KEYS = Set.of("tms.Count", "tms.UserLimit", "tms.MaxCharacters",
            "tms.WFlags", "tms.HandlerThreads", "tms.TimerThreads", "tms.DbPoolSize", "tms.DbPoolTimeout",
            "tms.PanelPort", "tms.PanelThreads", "tms.Port", "tms.LPort");
    private static final Set<String> BOOL_KEYS = Set.of("tms.AutoRegister", "tms.Admin", "tms.Debug", "tms.Packet");

    /** Mirrors how the server parses each key at startup, so what is saved will load. */
    private static void validateValue(final String key, final String value) {
        final String v = value.trim();
        if (v.endsWith("\\")) {
            // A Properties line continuation: it would swallow the next key.
            throw new Router.HttpError(400, "值不可以反斜線結尾");
        }
        if (RATE_KEYS.contains(key)) {
            parseRate(v);
        } else if (key.equals("tms.Flag")) {
            try {
                final byte b = Byte.parseByte(v);
                if (b < 0 || b > 3) {
                    throw new Router.HttpError(400, "tms.Flag 必須是 0 到 3");
                }
            } catch (final NumberFormatException e) {
                throw new Router.HttpError(400, "tms.Flag 必須是 0 到 3");
            }
        } else if (INT_KEYS.contains(key) || key.matches("tms\\.Port\\d+")) {
            try {
                Integer.parseInt(v);
            } catch (final NumberFormatException e) {
                throw new Router.HttpError(400, key + " 必須是整數");
            }
        } else if (BOOL_KEYS.contains(key)) {
            if (!v.isEmpty() && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw new Router.HttpError(400, key + " 必須是 true 或 false");
            }
        }
    }
}
