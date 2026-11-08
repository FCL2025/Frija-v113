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

import java.util.LinkedHashMap;
import java.util.Map;
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
        super();
        final ScriptScope[] loaded = new ScriptScope[scripts.length];
        IntStream.range(0, scripts.length).parallel().forEach(i -> {
            if (!scripts[i].equals("")) {
                loaded[i] = getInvocable("event/" + scripts[i] + ".js", null);
            }
        });
        for (int i = 0; i < scripts.length; i++) {
            if (loaded[i] != null) {
                events.put(scripts[i], new EventEntry(scripts[i], loaded[i], new EventManager(cserv, loaded[i], scripts[i])));
            }
        }
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
            try {
                entry.iv.put("em", entry.em);
                entry.iv.invokeFunction("init", (Object) null);
               
            } catch (final Exception ex) {
                LOG.log(System.Logger.Level.ERROR, "Error initiating event: " + entry.script + ":" + ex);
                FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error initiating event: " + entry.script + ":" + ex);
            }
        }
    }

    public final void cancel() {
        for (final EventEntry entry : events.values()) {
            entry.em.cancel();
        }
    }
}
