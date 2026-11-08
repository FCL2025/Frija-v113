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
package scripting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import handling.channel.ChannelServer;
import tools.FileoutputUtil;

/**
 *
 * @author Matze
 */
// final (-Xlint:this-escape): the constructor compiles the scripts on the
// common ForkJoinPool, so 'this' is handed to other threads (through the
// inherited getInvocable) while the object is still being built. The parallel
// forEach joins before the constructor returns and nothing extends this class,
// so no subclass can ever see the half-built object.
public final class EventScriptManager extends AbstractScriptManager {

    private static final System.Logger LOG = System.getLogger(EventScriptManager.class.getName());

    private static class EventEntry {

        public EventEntry(final String script, final ScriptScope iv, final EventManager em) {
            this.script = script;
            this.iv = iv;
            this.em = em;
        }
        public String script;
        public ScriptScope iv;
        public EventManager em;
    }
    private final Map<String, EventEntry> events = new LinkedHashMap<>();
    private final AtomicInteger runningInstanceMapId = new AtomicInteger(0);

    public final int getNewInstanceMapId() {
        return runningInstanceMapId.addAndGet(1);
    }

    /**
     * Compiles every event script named by {@code tms.Events} for one channel.
     *
     * <p>Each script gets its own Nashorn engine, and each engine has to be
     * created and then handed {@code mozilla_compat.js} plus the script itself
     * to compile. There are about a hundred of them per channel and doing that
     * one at a time was most of a channel's startup, so they are compiled in
     * parallel - they are completely independent of each other, and nothing
     * here is shared but the {@code ScriptEngineManager}, which is only read.
     *
     * <p>The results are collected back in the order {@code tms.Events} lists
     * them, because {@link #init()} runs them in the map's iteration order.
     * This is deliberately the common ForkJoinPool rather than a
     * {@code server.Timer} pool: it is one bounded burst of CPU work at
     * startup, not scheduled work that wants a named, shut-down-with-the-server
     * pool.
     */
    public EventScriptManager(final ChannelServer cserv, final String[] scripts) {
        this(cserv, scripts, null, Set.of());
    }

    /**
     * The manager for a new {@code tms.Events} list, built from the one the
     * channel is running now.
     *
     * <p>Every event that is in both lists and not named in {@code recompile}
     * is carried over as the same running object - its schedules, its open
     * instances and the players in them are not touched. Only the names that
     * are new, or that are being recompiled, are compiled here; they start
     * when {@link #initFresh()} is called, and the ones being dropped or
     * replaced stop when {@code previous.cancelDropped(this)} is.
     *
     * <p>The instance-map counter carries over too. A fresh counter would start
     * handing out instance ids from zero again while instances made under the
     * old one are still open.
     */
    public static EventScriptManager derive(final ChannelServer cserv, final EventScriptManager previous,
            final String[] scripts, final Set<String> recompile) {
        return new EventScriptManager(cserv, scripts, previous, recompile);
    }

    private EventScriptManager(final ChannelServer cserv, final String[] scripts,
            final EventScriptManager previous, final Set<String> recompile) {
        super();
        final ScriptScope[] loaded = new ScriptScope[scripts.length];
        IntStream.range(0, scripts.length).parallel().forEach(i -> {
            if (!scripts[i].equals("") && reusable(previous, scripts[i], recompile) == null) {
                loaded[i] = getInvocable("event/" + scripts[i] + ".js", null);
            }
        });
        for (int i = 0; i < scripts.length; i++) {
            if (events.containsKey(scripts[i])) {
                continue;
            }
            final EventEntry kept = reusable(previous, scripts[i], recompile);
            if (kept != null) {
                events.put(scripts[i], kept);
            } else if (loaded[i] != null) {
                events.put(scripts[i], new EventEntry(scripts[i], loaded[i], new EventManager(cserv, loaded[i], scripts[i])));
                fresh.add(scripts[i]);
            }
        }
        if (previous != null) {
            runningInstanceMapId.set(previous.runningInstanceMapId.get());
        }
    }

    private static EventEntry reusable(final EventScriptManager previous, final String name, final Set<String> recompile) {
        return previous == null || recompile.contains(name) ? null : previous.events.get(name);
    }

    /** Names whose entries this manager compiled itself rather than carried over. */
    private final Set<String> fresh = new HashSet<>();

    /** The event names this manager runs, in {@code tms.Events} order. */
    public final List<String> getEventNames() {
        return new ArrayList<>(events.keySet());
    }

    public final EventManager getEventManager(final String event) {
        final EventEntry entry = events.get(event);
        if (entry == null) {
            return null;
        }
        return entry.em;
    }

    public final void init() {
        for (final EventEntry entry : events.values()) {
            init(entry);
        }
    }

    /** Starts only what {@link #derive} compiled; carried-over events are already running. */
    public final void initFresh() {
        for (final EventEntry entry : events.values()) {
            if (fresh.contains(entry.script)) {
                init(entry);
            }
        }
    }

    private static void init(final EventEntry entry) {
        try {
            entry.iv.put("em", entry.em);
            entry.iv.invokeFunction("init", (Object) null);
        } catch (final Exception ex) {
            LOG.log(System.Logger.Level.ERROR, "Error initiating event: " + entry.script + ":" + ex);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error initiating event: " + entry.script + ":" + ex);
        }
    }

    public final void cancel() {
        for (final EventEntry entry : events.values()) {
            entry.em.cancel();
        }
    }

    /**
     * Stops every event of this manager that {@code successor} does not carry
     * over - the ones taken out of the list and the ones being recompiled.
     *
     * @return their names
     */
    public final List<String> cancelDropped(final EventScriptManager successor) {
        final List<String> dropped = new ArrayList<>();
        for (final EventEntry entry : events.values()) {
            if (successor.events.get(entry.script) != entry) {
                entry.em.cancel();
                dropped.add(entry.script);
            }
        }
        return dropped;
    }

    /** What {@link #derive} compiled, i.e. what {@link #initFresh()} will start. */
    public final List<String> getFreshNames() {
        final List<String> out = new ArrayList<>();
        for (final String name : events.keySet()) {
            if (fresh.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }
}
