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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import tools.FileoutputUtil;
import tools.StringUtil;

/**
 * The one Nashorn engine and the compiled form of every script that has been
 * run since the last reload.
 *
 * <p>Talking to an NPC used to mean {@code new ScriptEngineManager()
 * .getEngineByName("nashorn")} - a whole fresh engine, ~33 ms and ~370 KB -
 * plus a BIG5 read of the file and a parse of it, <i>every time</i>, for every
 * player, for every conversation. The engine sat in {@code MapleClient.engines}
 * until the conversation ended and was then thrown away. This class does the
 * read and the parse once per script and hands out a {@link ScriptScope} per
 * conversation instead: ~1 ms and ~125 KB. What each script sees is unchanged -
 * the same {@code cm}/{@code npcid}/{@code qm}/{@code rm}/{@code em} globals,
 * the same {@code start()}/{@code action(mode, type, selection)} contract, the
 * same {@code importPackage} - because a scope is a real Nashorn Global, just
 * not a whole engine's worth of one.
 *
 * <p><b>The prologue and {@code mozilla_compat.js}.</b> Every script is
 * compiled with exactly one line in front of it, as it always was, so that a
 * line number in a Nashorn error still means "the line in the file, plus one" -
 * the same offset {@code ScriptEncodingCheck} reports. What that line is
 * depends on whether {@code mozilla_compat.js} could be found as a resource in
 * the Nashorn jar:
 *
 * <ul>
 * <li>Found (the normal case): the prologue is blank, and {@link #newScope()}
 * evaluates a <i>once-compiled</i> copy of {@code mozilla_compat.js} into each
 * new Global before the script itself. {@code importPackage} and
 * {@code importClass} are per-Global, so this has to happen per scope either
 * way - what is saved is re-reading and re-compiling 12 KB of JavaScript each
 * time {@code load()} is called. Measured: 1.85 ms → 1.00 ms per scope.</li>
 * <li>Not found (a Nashorn build that moved the resource): the prologue is the
 * original {@code load('nashorn:mozilla_compat.js');} and nothing else changes.
 * This is the fallback, not the path, and it is exactly today's behaviour.</li>
 * </ul>
 *
 * <p>Inlining the 12 KB into every cached script was measured too and rejected:
 * marginally faster per scope (0.88 ms) but it multiplies the compiled bytecode
 * by the number of scripts - 47 KB per cached script instead of 0.4 KB, 23 MB
 * across the tree's ~500 reachable scripts - and heap is the reason this item
 * exists.
 *
 * <p><b>What is not cached.</b> A script that cannot be found, cannot be read,
 * or will not compile is not remembered as anything. That keeps today's
 * behaviour exactly: the caller gets null, an NPC falls back to
 * {@code npc/notcoded.js}, the same line goes to {@code Log_Script_Except.rtf},
 * and repairing the file on disk takes effect on the very next conversation
 * instead of waiting for a reload. The "cannot be read" case is worth spelling
 * out because it is this tree's classic silent failure: a script saved as UTF-8
 * makes {@code StringUtil.readFileAsString} throw {@code MalformedInputException}
 * internally and return {@code ""}, which compiles perfectly into a script with
 * no functions in it. An empty string out of a non-empty file is therefore
 * treated as a failure to read and left uncached.
 *
 * <p><b>{@code Throwable}, not {@code Exception}.</b> Nashorn's code generator
 * can throw a bare {@code AssertionError} on a pathological script - that is
 * how {@code npc/2040043.js} hid for years, because an {@code Error} walks
 * straight through every {@code catch (Exception)} between here and MINA's
 * {@code exceptionCaught}, whose body is commented out. Compiling and first-
 * evaluating a script therefore catch {@code Throwable}, which is the same
 * choice {@link ScriptEncodingCheck} already makes for the same reason.
 */
public final class ScriptCache {

    /** Where {@link #clearKind} and the panel build their keys from. */
    public static final String ROOT = "Libs/scripts/";

    private static final ScriptEngine ENGINE = findEngine();
    /** {@code mozilla_compat.js} compiled once, or null if it is not a resource. */
    private static final CompiledScript COMPAT = compileCompat();
    /**
     * Always exactly one line, so a Nashorn line number keeps meaning "file
     * line + 1" whichever branch above was taken.
     */
    private static final String PROLOGUE =
            (COMPAT == null ? "load('nashorn:mozilla_compat.js');" : "") + System.lineSeparator();

    /**
     * Path (as {@code Libs/scripts/npc/9000000.js}) to its compiled form.
     * Filled in by whichever Maple-Handler-N thread first runs a script and
     * emptied by the {@code !reload…} commands and by a save in the panel's
     * script editor, both of which arrive on other threads.
     */
    private static final Map<String, CompiledScript> CACHE = new ConcurrentHashMap<>();

    private ScriptCache() {
    }

    /**
     * Nashorn left the JDK in 15, so it only exists when nashorn-core and the
     * asm jars are on the classpath. This deliberately stores null rather than
     * throwing: a static initialiser that throws leaves the whole class
     * permanently unusable behind a {@code NoClassDefFoundError}, which is a
     * far worse thing to debug than the message {@link #requireEngine} gives.
     */
    private static ScriptEngine findEngine() {
        try {
            return new ScriptEngineManager().getEngineByName("nashorn");
        } catch (final Throwable t) {
            System.err.println("Could not create the 'nashorn' script engine: " + t);
            return null;
        }
    }

    private static CompiledScript compileCompat() {
        if (!(ENGINE instanceof Compilable compiler)) {
            return null;
        }
        // The resource moved package when Nashorn left the JDK, and could move
        // again; a miss is not an error, it just means the load() prologue.
        final String name = "org/openjdk/nashorn/internal/runtime/resources/mozilla_compat.js";
        try (InputStream in = ENGINE.getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                return null;
            }
            return compiler.compile(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (final Throwable t) {
            System.err.println("Could not pre-compile mozilla_compat.js, falling back to load(): " + t);
            return null;
        }
    }

    /**
     * The shared engine, or an {@code IllegalStateException} saying why there
     * is none. {@link PortalScriptManager} resolves this in a static
     * initialiser on purpose, so that "no Nashorn" is one clear message rather
     * than every portal in the game dying inside an
     * {@code ExceptionInInitializerError}.
     */
    public static ScriptEngine requireEngine() {
        if (ENGINE == null) {
            throw new IllegalStateException("No 'nashorn' script engine found. Put nashorn-core and the asm jars on the classpath.");
        }
        return ENGINE;
    }

    /**
     * The compiled form of {@code path}, or null if there is no such file, it
     * could not be read as BIG5, or it does not compile. Only successes are
     * remembered.
     *
     * <p>Miss-then-put rather than {@code computeIfAbsent}: compiling runs
     * Nashorn's whole front end and would otherwise do it while holding the
     * map's bin lock, blocking every other thread that missed in the same bin.
     * Two players walking into the same new script at the same moment compile
     * it twice and the second {@code put} wins; both of them get a working
     * script and the loser is collected. That is the same trade
     * {@link ReactorScriptManager#getDrops} and {@link PortalScriptManager}
     * already make.
     */
    public static CompiledScript compiled(final String path) {
        final CompiledScript cached = CACHE.get(path);
        if (cached != null) {
            return cached;
        }
        final File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        final String source = StringUtil.readFileAsString(path);
        // readFileAsString swallows the MalformedInputException and answers "",
        // so an empty string out of a file with bytes in it means the file is
        // not BIG5. Compile it anyway - the caller must fail the way it always
        // did, with "No such function action" - but do not remember it, so that
        // re-saving the file as BIG5 takes effect at once.
        final boolean unreadable = source.isEmpty() && file.length() > 0;
        final CompiledScript script;
        try {
            script = ((Compilable) requireEngine()).compile(PROLOGUE + source);
        } catch (final Throwable t) {
            System.err.println("Error compiling script. Path: " + path + "\nException " + t);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error compiling script. Path: " + path + "\nException " + t);
            return null;
        }
        if (!unreadable) {
            CACHE.put(path, script);
        }
        return script;
    }

    /** A fresh private Global with {@code mozilla_compat.js} already in it. */
    public static ScriptScope newScope() throws Exception {
        final ScriptScope scope = new ScriptScope(requireEngine());
        if (COMPAT != null) {
            scope.run(COMPAT);
        }
        return scope;
    }

    /**
     * Compiles {@code path} if it is not compiled already and runs it in a new
     * scope - the whole of what {@code getInvocable} used to do with a brand
     * new engine. Null if the script is missing, unreadable or broken, which is
     * what every caller already treats as "no script".
     */
    public static ScriptScope load(final String path) {
        final CompiledScript script = compiled(path);
        if (script == null) {
            return null;
        }
        try {
            final ScriptScope scope = newScope();
            scope.run(script);
            return scope;
        } catch (final Throwable t) {
            // A script's own top-level code runs here, so this is a script bug,
            // not a cache bug - and Nashorn can raise an Error rather than an
            // Exception while generating its bytecode.
            System.err.println("Error executing script. Path: " + path + "\nException " + t);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing script. Path: " + path + "\nException " + t);
            return null;
        }
    }

    /** Forgets one script, by the same path {@link #load} is called with. */
    public static boolean remove(final String path) {
        return CACHE.remove(path) != null;
    }

    /** Forgets one script named the way the panel names it: kind + file name. */
    public static boolean removeScript(final String kind, final String name) {
        return remove(ROOT + kind + "/" + name);
    }

    /** Forgets every script under one of {@code npc quest reactor portal event}. */
    public static int clearKind(final String kind) {
        final String prefix = ROOT + kind + "/";
        int dropped = 0;
        for (final String key : CACHE.keySet()) {
            if (key.startsWith(prefix) && CACHE.remove(key) != null) {
                dropped++;
            }
        }
        return dropped;
    }

    /** Forgets everything. The next player to run a script re-reads the file. */
    public static int clear() {
        final int had = CACHE.size();
        CACHE.clear();
        return had;
    }

    public static int size() {
        return CACHE.size();
    }
}
