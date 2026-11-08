package server.panel;

import handling.RecvPacketOpcode;
import handling.SendPacketOpcode;
import handling.MapleServerHandler;
import handling.channel.ChannelServer;
import handling.world.Broadcast;
import handling.world.World;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import scripting.PortalScriptManager;
import scripting.ReactorScriptManager;
import scripting.ScriptCache;
import server.LiveSettings;
import server.MapleShopFactory;
import server.ShutdownServer;
import server.life.MapleMonsterInformationProvider;
import server.quest.MapleQuest;
import tools.packet.ChatPacket;

/**
 * The routes that reach into the running game: broadcasts, the reloads, and
 * shutdown.
 *
 * <p>Each reload calls the same static or singleton the matching
 * {@code !reload…} GM command calls, and not the command itself:
 * {@code CommandProcessor.processCommand} dereferences
 * {@code c.getPlayer().getGMLevel()} and there is no {@code MapleClient} on a
 * panel thread. The command bodies never touched their client parameter, so
 * this is the same code path minus the NPE.
 *
 * <p>Shutdown is started on its own thread. {@link ShutdownServer#run} sleeps
 * five seconds mid-way and ends in {@code System.exit}, and its first act is
 * to stop the panel - calling it on the request thread would have the panel
 * wait on itself. Nothing brings the JVM back afterwards; that is the price of
 * living inside it, and the UI says so.
 */
final class ActionsApi {

    private static final System.Logger LOG = System.getLogger(ActionsApi.class.getName());

    /** Notice styles the client renders, by the number {@code serverNotice} takes. */
    private static final Map<String, Integer> NOTICE_TYPES = Map.of(
            "notice", 0, // [公告] in the chat log
            "popup", 1, // a dialog box
            "scroll", 4, // the ticker at the top of the screen
            "pink", 5, // pink chat line
            "blue", 6); // light blue chat line

    private static final int MAX_NOTICE_LENGTH = 400;

    private ActionsApi() {
    }

    static void register(final Router r) {
        r.post("/api/broadcast", req -> broadcast(req.bodyObject()));
        r.post("/api/reload/{target}", req -> reload(req.param("target")));
        r.post("/api/shutdown", req -> shutdown(req.bodyObject()));
    }

    private static Map<String, Object> broadcast(final Map<String, Object> body) {
        final String message = string(body, "message");
        if (message == null || message.isBlank()) {
            throw new Router.HttpError(400, "message 不可為空");
        }
        if (message.length() > MAX_NOTICE_LENGTH) {
            throw new Router.HttpError(400, "message 太長（上限 " + MAX_NOTICE_LENGTH + " 字）");
        }
        final String typeName = body.get("type") == null ? "notice" : String.valueOf(body.get("type"));
        final Integer type = NOTICE_TYPES.get(typeName);
        if (type == null) {
            throw new Router.HttpError(400, "type 必須是 " + String.join(" / ", NOTICE_TYPES.keySet()));
        }
        Broadcast.broadcastMessage(ChatPacket.serverNotice(type, message).getBytes());
        return Json.obj("ok", true, "type", typeName, "recipients", World.getConnected().getOrDefault(0, 0));
    }

    /** The reloads, by the name used in the URL. */
    static final List<String> RELOAD_TARGETS = List.of("ops", "drops", "portal", "shops", "quests", "events", "ipmonitor", "settings");

    private static Map<String, Object> reload(final String target) {
        final long t0 = System.nanoTime();
        final List<String> notes = new ArrayList<>();
        switch (target) {
            case "ops" -> {
                SendPacketOpcode.reloadValues();
                RecvPacketOpcode.reloadValues();
            }
            case "drops" -> {
                MapleMonsterInformationProvider.getInstance().clearDrops();
                ReactorScriptManager.getInstance().clearDrops();
            }
            case "portal" -> PortalScriptManager.getInstance().clearScripts();
            case "shops" -> MapleShopFactory.getInstance().clear();
            case "quests" -> MapleQuest.clearQuests();
            case "events" -> {
                // Before the channels rebuild, as !reloadevents does: they
                // compile through ScriptCache, and a stale entry left there
                // would be handed straight back, so an edited event script
                // never took effect through this button.
                ScriptCache.clearKind("event");
                // Per channel, and one failing must not stop the rest.
                for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                    try {
                        cs.reloadEvents();
                    } catch (final RuntimeException e) {
                        notes.add("頻道 " + cs.getChannel() + "：" + e);
                        // One record where there were two prints: the message
                        // and the trace are the same event. The HTTP reply is
                        // unchanged - the note above still goes back in "errors".
                        LOG.log(System.Logger.Level.ERROR,
                                "[面板] 頻道 " + cs.getChannel() + " 重載事件失敗", e);
                    }
                }
            }
            case "ipmonitor" -> MapleServerHandler.reloadLoggedIPs();
            case "settings" -> {
                // The watcher does this by itself a couple of seconds after
                // the file is saved; this is the "now, and tell me" version.
                try {
                    for (final LiveSettings.Outcome o : LiveSettings.applyFile()) {
                        notes.add(o.key() + (o.ok() ? " 已套用" : " 未套用")
                                + (o.message() == null ? "" : "（" + o.message() + "）"));
                    }
                } catch (final IOException e) {
                    throw new Router.HttpError(500, "讀取 Settings.ini 失敗：" + e.getMessage());
                }
                return Json.obj("ok", true, "target", target,
                        "elapsedMs", (System.nanoTime() - t0) / 1_000_000L,
                        "errors", List.of(), "applied", notes);
            }
            default -> throw new Router.HttpError(404, "沒有這個 reload 目標，可用：" + String.join(", ", RELOAD_TARGETS));
        }
        return Json.obj(
                "ok", notes.isEmpty(),
                "target", target,
                "elapsedMs", (System.nanoTime() - t0) / 1_000_000L,
                "errors", notes);
    }

    private static Map<String, Object> shutdown(final Map<String, Object> body) {
        if (!Boolean.TRUE.equals(body.get("confirm"))) {
            throw new Router.HttpError(400, "需要 confirm: true");
        }
        if (ShutdownServer.running) {
            return Json.obj("ok", true, "alreadyRunning", true);
        }
        final Thread t = new Thread(ShutdownServer.getInstance(), "Panel-Shutdown");
        t.setDaemon(false);
        t.start();
        return Json.obj("ok", true, "alreadyRunning", false);
    }

    static String string(final Map<String, Object> body, final String key) {
        final Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
