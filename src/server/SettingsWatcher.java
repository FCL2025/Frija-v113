package server;

import handling.world.World;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/**
 * Applies hand edits of {@code Settings.ini} while the server runs.
 *
 * <p>Every two seconds it looks at the file's size and modification time. A
 * change is acted on only once it has held still for a whole poll, so an
 * editor caught halfway through writing the file is never read; then
 * {@link LiveSettings#applyFile()} compares the file with what the server is
 * running and applies the keys that differ. Saving the file from an editor is
 * therefore all it takes - there is nothing to reload by hand, though
 * {@code !reloadsettings} does the same thing on demand.
 *
 * <p>Saves made through the panel land here too, a moment after the panel has
 * already applied them; by then the file and the server agree, the
 * comparison finds nothing, and nothing is logged.
 *
 * <p>A poll is a {@code stat} call on one file. It runs on {@code Timer.ETC},
 * like the auto-save, never on a bare thread.
 */
public final class SettingsWatcher {

    private static final System.Logger LOG = System.getLogger(SettingsWatcher.class.getName());

    private static final long POLL_MILLIS = 2_000;
    private static final Path FILE = Path.of(ServerProperties.SETTINGS_FILE);

    private record Stamp(long size, long modified) {
    }

    private static ScheduledFuture<?> task;
    /** What the file looked like at the previous poll. */
    private static Stamp lastSeen;
    /** What it looked like the last time it was applied; null until the first poll has compared. */
    private static Stamp lastApplied;

    private SettingsWatcher() {
    }

    /**
     * Starts watching. Called once the server is fully up; the first poll
     * compares the file against what was loaded at startup, so an edit made
     * while the server was still starting is not missed.
     */
    public static synchronized void start() {
        if (task != null) {
            return;
        }
        lastSeen = stamp();
        lastApplied = null;
        task = Timer.ETC.register(SettingsWatcher::poll, POLL_MILLIS, POLL_MILLIS);
    }

    public static synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    private static synchronized void poll() {
        if (World.isShutDown || task == null) {
            return;
        }
        final Stamp now = stamp();
        if (now == null || now.equals(lastApplied)) {
            lastSeen = now;
            return;
        }
        if (!now.equals(lastSeen)) {
            // Changed since the last poll: give the writer one more interval.
            lastSeen = now;
            return;
        }
        lastApplied = now;
        try {
            report(LiveSettings.applyFile());
        } catch (final IOException e) {
            LOG.log(System.Logger.Level.WARNING, "[設定] 讀取 " + FILE + " 失敗，這次的變更沒有套用：" + e);
        }
    }

    /** One record per batch; nothing at all when the file and the server already agree. */
    static void report(final List<LiveSettings.Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return;
        }
        final boolean allOk = outcomes.stream().allMatch(LiveSettings.Outcome::ok);
        LOG.log(allOk ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                "[設定] " + FILE + " 已變更：" + System.lineSeparator() + LiveSettings.describe(outcomes));
    }

    private static Stamp stamp() {
        try {
            final BasicFileAttributes a = Files.readAttributes(FILE, BasicFileAttributes.class);
            return new Stamp(a.size(), a.lastModifiedTime().toMillis());
        } catch (final IOException e) {
            return null;
        }
    }
}
