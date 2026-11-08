package server.panel;

import database.DatabaseConnection;
import handling.channel.ChannelServer;
import handling.login.LoginServer;
import handling.mina.MapleAcceptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import server.LiveSettings;
import server.ServerProperties;

/**
 * Reads and writes {@code Settings.ini}, and puts what is saved into effect.
 *
 * <p>What a key means and how it is applied lives in {@link LiveSettings},
 * shared with the {@code Settings.ini} watcher and {@code !reloadsettings};
 * this class is the HTTP face of it plus the line-preserving file write. Every
 * key is one of:
 *
 * <dl>
 * <dt>{@code live}</dt><dd>Applied to the running server on save - every key
 * the code reads, except one.</dd>
 * <dt>{@code restart}</dt><dd>{@code tms.Url} only: the database itself
 * cannot change under a running world. The file changes; the process does
 * not.</dd>
 * <dt>{@code none}</dt><dd>Nothing reads it.</dd>
 * </dl>
 *
 * <p>A save always applies. There used to be a "save without applying"
 * button; with the watcher applying every change to the file a couple of
 * seconds later anyway, that button could only ever have meant "apply, but
 * not where I can see the result".
 *
 * <p>{@code tms.ServerMessage} is deliberately not a setting here.
 * {@code ChannelServer.setServerMessage} broadcasts to the channel the moment
 * it is called, so it is a "send" action ({@link #serverMessage}), never
 * something a form saves.
 *
 * <p>Writes go through {@link IniFile}, which keeps every byte of the file it
 * is not asked to change - the BOM, the CRLFs, the comments that are the only
 * documentation some of these keys have.
 */
final class SettingsApi {

    private static final System.Logger LOG = System.getLogger(SettingsApi.class.getName());

    static final Path INI = Path.of(ServerProperties.SETTINGS_FILE);

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
        final boolean secret = LiveSettings.isSecret(key);
        return Json.obj(
                "key", key,
                "value", secret ? null : value,
                "active", active,
                "secret", secret,
                "commentedDefault", commentedDefault,
                "apply", LiveSettings.mode(key).wire,
                "description", LiveSettings.description(key),
                "runtimeValue", secret ? null : liveValue(key),
                "known", LiveSettings.isKnown(key));
    }

    /**
     * What the running server is actually using, for the keys where that can
     * differ from the file: the rates (per channel, but they are set together,
     * so the lowest channel stands for all), the values held in fields, and
     * everything else through {@code ServerProperties}.
     */
    private static String liveValue(final String key) {
        final ChannelServer first = ChannelServer.getAllInstances().stream()
                .filter(cs -> !cs.isShutdown())
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
            case "tms.Count":
                return String.valueOf(ChannelServer.getAllInstances().stream().filter(cs -> !cs.isShutdown()).count());
            case "tms.HandlerThreads":
                return String.valueOf(MapleAcceptor.getHandlerThreads());
            case "tms.DbPoolSize":
                return String.valueOf(DatabaseConnection.getPoolSize());
            case "tms.PanelPort": {
                final PanelServer p = PanelServer.getInstance();
                return p == null ? "0" : String.valueOf(p.getPort());
            }
            default:
                return ServerProperties.getProperty(key);
        }
    }

    /**
     * Saves one key and puts it into effect. The reply keeps the two outcomes
     * apart: a full disk and a refused value are different failures, and
     * "saved but not applied" is a state the UI has to be able to show rather
     * than round down to "needs restart".
     */
    private static Map<String, Object> put(final String key, final Map<String, Object> body) {
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

        // Validate before touching anything: a value the server cannot parse
        // must not reach the file, or the next restart dies on it.
        if (value.trim().endsWith("\\")) {
            // A Properties line continuation: it would swallow the next key.
            throw new Router.HttpError(400, "值不可以反斜線結尾");
        }
        try {
            LiveSettings.validate(key, value);
        } catch (final IllegalArgumentException e) {
            throw new Router.HttpError(400, e.getMessage());
        }

        final Map<String, Object> saved = save(key, value);
        final LiveSettings.Outcome outcome = LiveSettings.apply(key, value);
        return Json.obj(
                "key", key,
                "value", LiveSettings.isSecret(key) ? null : value,
                "apply", LiveSettings.mode(key).wire,
                "saved", saved,
                "applied", applied(outcome));
    }

    /** The {@code applied} half of a reply: {@code ok}, {@code mode}, and {@code note} or {@code error}. */
    private static Map<String, Object> applied(final LiveSettings.Outcome o) {
        return o.ok()
                ? Json.obj("ok", true, "mode", o.mode().wire, "note", o.message())
                : Json.obj("ok", false, "mode", o.mode().wire, "error", o.message());
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

    /**
     * All four rates at once, applied to every channel and written back, with
     * the two outcomes reported separately - see {@link #put}.
     */
    private static Map<String, Object> rates(final Map<String, Object> body) {
        final Map<String, String> wanted = new LinkedHashMap<>();
        final Map<String, Integer> echo = new LinkedHashMap<>();
        for (final String field : List.of("exp", "meso", "drop", "cash")) {
            final Object v = body.get(field);
            if (v == null) {
                continue;
            }
            final String key = "tms." + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            final String value = String.valueOf(v).trim();
            try {
                LiveSettings.validate(key, value);
            } catch (final IllegalArgumentException e) {
                throw new Router.HttpError(400, e.getMessage());
            }
            wanted.put(key, value);
            echo.put(field, Integer.valueOf(value));
        }
        if (wanted.isEmpty()) {
            throw new Router.HttpError(400, "至少要給 exp / meso / drop / cash 其中一個");
        }
        final List<String> failed = new ArrayList<>();
        for (final Map.Entry<String, String> e : wanted.entrySet()) {
            final LiveSettings.Outcome o = LiveSettings.apply(e.getKey(), e.getValue());
            if (!o.ok()) {
                failed.add(e.getKey() + "：" + o.message());
            }
        }
        final int channels = ChannelServer.getAllInstances().size();
        return Json.obj(
                "applied", failed.isEmpty()
                        ? Json.obj("ok", true, "channels", channels)
                        : Json.obj("ok", false, "channels", channels, "error", String.join("；", failed)),
                "saved", save(wanted),
                "rates", echo);
    }

    /**
     * Sets the channel notice on every channel - which broadcasts it at once -
     * and, when asked, writes it to the file as {@code tms.ServerMessage}.
     *
     * <p>A notice that is only sent leaves {@code ServerProperties} alone on
     * purpose. The watcher compares the file with {@code ServerProperties};
     * had the one-off notice gone in there, the next unrelated edit of the
     * file would have found the file's notice "changed" and broadcast it again.
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
        if (!Boolean.TRUE.equals(body.get("save"))) {
            for (final ChannelServer cs : channels) {
                cs.setServerMessage(message);
            }
            return Json.obj("applied", Json.obj("ok", true, "channels", channels.size()),
                    "saved", Json.obj("ok", false, "skipped", true));
        }
        final Map<String, Object> saved = save("tms.ServerMessage", message);
        final LiveSettings.Outcome o = LiveSettings.apply("tms.ServerMessage", message);
        return Json.obj(
                "applied", o.ok()
                        ? Json.obj("ok", true, "channels", channels.size())
                        : Json.obj("ok", false, "channels", channels.size(), "error", o.message()),
                "saved", saved);
    }

    private static void validateKey(final String key) {
        if (key == null || key.isBlank() || key.length() > 64 || !key.matches("[A-Za-z0-9_.-]+")) {
            throw new Router.HttpError(400, "不合法的設定名稱");
        }
    }
}
