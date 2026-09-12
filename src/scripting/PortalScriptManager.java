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

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;

import client.MapleClient;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import server.MaplePortal;
import tools.FileoutputUtil;
import tools.StringUtil;

public class PortalScriptManager {

    private static final PortalScriptManager instance = new PortalScriptManager();
    /**
     * One entry per portal script name, compiled on demand by whichever
     * Maple-Handler-N thread first walks into that portal, and dropped by
     * {@code !reloadportal} or by saving a portal script in the panel from a
     * Panel-Worker-N thread.
     *
     * <p>The value is an {@link Optional} because a name with no file behind it
     * - or one whose script failed to compile - is cached as a miss, which the
     * old code said with a null value that a ConcurrentHashMap will not take.
     * That negative has to keep working: without it every step into such a
     * portal would read and recompile a script file.
     */
    private final Map<String, Optional<PortalScript>> scripts = new ConcurrentHashMap<String, Optional<PortalScript>>();
    private final static ScriptEngineFactory sef = findNashorn();

    /**
     * Nashorn was dropped from the JDK in 15, so from JDK 15 on it only exists
     * if nashorn-core and the asm jars are on the classpath. Say so plainly:
     * without this the field above is simply null and every portal in the game
     * dies inside a static initialiser with no hint as to why.
     */
    private static ScriptEngineFactory findNashorn() {
        final ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
        if (engine == null) {
            throw new IllegalStateException("No 'nashorn' script engine found. Put nashorn-core and the asm jars on the classpath.");
        }
        return engine.getFactory();
    }

    public final static PortalScriptManager getInstance() {
        return instance;
    }

    private final PortalScript getPortalScript(final String scriptName) {
        final Optional<PortalScript> cached = scripts.get(scriptName);
        if (cached != null) {
            return cached.orElse(null);
        }

        String scriptPath = "Libs/scripts/portal/" + scriptName + ".js";
        final File scriptFile = new File(scriptPath);
        if (!scriptFile.exists()) {
            scripts.put(scriptName, Optional.<PortalScript>empty());
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("load('nashorn:mozilla_compat.js');" + System.lineSeparator());
        builder.append(StringUtil.readFileAsString(scriptPath));

        //InputStreamReader fr = null;
        final ScriptEngine portal = sef.getScriptEngine();
        try {
            //fr = new InputStreamReader(new FileInputStream(scriptFile), "Big5");
            CompiledScript compiled = ((Compilable) portal).compile(builder.toString());
            compiled.eval();
        } catch (final Exception e) {
            System.err.println("Error executing Portalscript: " + scriptName + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing Portal script. (" + scriptName + ") " + e);
        }/* finally {
            if (fr != null) {
                try {
                    fr.close();
                } catch (final IOException e) {
                    System.err.println("ERROR CLOSING" + e);
                }
            }
        }*/
        final PortalScript script = ((Invocable) portal).getInterface(PortalScript.class);
        // Miss-then-put, not computeIfAbsent: compiling a script under the
        // map's bin lock would block everyone else who missed in the same bin,
        // and the script's own top-level code runs in there. Two threads
        // entering the same portal at once compile it twice and the second one
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

    public final void clearScripts() {
        scripts.clear();
    }
}
