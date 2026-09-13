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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.script.CompiledScript;

import client.MapleClient;
import server.MaplePortal;
import tools.FileoutputUtil;

public class PortalScriptManager {

    private static final PortalScriptManager instance = new PortalScriptManager();
    /**
     * One entry per portal script name, evaluated on demand by whichever
     * Maple-Handler-N thread first walks into that portal, and dropped by
     * {@code !reloadportal} or by saving a portal script in the panel from a
     * Panel-Worker-N thread.
     *
     * <p>The value is an {@link Optional} because a name with no file behind it
     * - or one whose script failed to compile - is cached as a miss, which the
     * old code said with a null value that a ConcurrentHashMap will not take.
     * That negative has to keep working: without it every step into such a
     * portal would read and recompile a script file.
     *
     * <p>Unlike NPC and quest scripts, a portal script has no per-player state
     * to keep apart - one {@link PortalScript} is shared by everyone who steps
     * on the portal, as it always was - so there is one {@link ScriptScope} per
     * name here rather than one per entry.
     */
    private final Map<String, Optional<PortalScript>> scripts = new ConcurrentHashMap<>();

    static {
        // Asked for here on purpose, and left to throw. Without it a missing
        // Nashorn takes every portal in the game down inside a bare
        // ExceptionInInitializerError with no hint as to why; with it, the
        // message names the jars that are not on the classpath.
        ScriptCache.requireEngine();
    }

    public final static PortalScriptManager getInstance() {
        return instance;
    }

    private final PortalScript getPortalScript(final String scriptName) {
        final Optional<PortalScript> cached = scripts.get(scriptName);
        if (cached != null) {
            return cached.orElse(null);
        }

        final String scriptPath = "Libs/scripts/portal/" + scriptName + ".js";
        final CompiledScript compiled = ScriptCache.compiled(scriptPath);
        if (compiled == null) {
            scripts.put(scriptName, Optional.<PortalScript>empty());
            return null;
        }
        PortalScript script = null;
        try {
            final ScriptScope scope = ScriptCache.newScope();
            scope.run(compiled);
            script = scope.getInterface(PortalScript.class);
        } catch (final Exception e) {
            System.err.println("Error executing Portalscript: " + scriptName + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing Portal script. (" + scriptName + ") " + e);
        }
        // Miss-then-put, not computeIfAbsent: evaluating a script under the
        // map's bin lock would block everyone else who missed in the same bin,
        // and the script's own top-level code runs in there. Two threads
        // entering the same portal at once evaluate it twice and the second one
        // wins; both callers get a working script.
        scripts.put(scriptName, Optional.ofNullable(script));
        return script;
    }

    public final void executePortalScript(final MaplePortal portal, final MapleClient c) {
        final PortalScript script = getPortalScript(portal.getScriptName());

        if (script != null) {
            try {
                script.enter(new PortalPlayerInteraction(c, portal));
            } catch (Exception e) {
                System.err.println("Error entering Portalscript: " + portal.getScriptName() + ":" + e);
            }
        } else {
            System.out.println("Unhandled portal script " + portal.getScriptName() + " on map " + c.getPlayer().getMapId());
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Unhandled portal script " + portal.getScriptName() + " on map " + c.getPlayer().getMapId());
        }
    }

    /**
     * Drops both halves: the {@link PortalScript} objects here and the compiled
     * scripts behind them. Without the second one a portal script edited on
     * disk would be re-evaluated from the stale bytecode and never change.
     */
    public final void clearScripts() {
        scripts.clear();
        ScriptCache.clearKind("portal");
    }
}
