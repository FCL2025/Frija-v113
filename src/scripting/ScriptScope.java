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

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

/**
 * One script's private global object: what used to be a whole
 * {@code ScriptEngine} per conversation.
 *
 * <p>Every script in {@code Libs/scripts} declares top-level state -
 * {@code var status = -1;} is in nearly all of them - so two players talking to
 * the same NPC at the same time must not share globals. They used to be kept
 * apart by giving each conversation its own Nashorn engine, at a cost of tens
 * of milliseconds and a few hundred KB each. They are kept apart here by giving
 * each conversation its own Nashorn <i>Global</i> instead, off one shared
 * engine: {@link ScriptEngine#createBindings()} on Nashorn returns a
 * {@code ScriptObjectMirror} wrapping a brand-new Global, and evaluating a
 * {@link CompiledScript} against a {@link ScriptContext} holding that Bindings
 * as its {@code ENGINE_SCOPE} puts the script's {@code var}s and
 * {@code function}s in there rather than in the engine's own.
 *
 * <p><b>Why {@code invokeFunction} forwards to {@code invokeMethod}.</b>
 * {@code Invocable.invokeFunction(name)} on the shared engine looks the name up
 * in the engine's <i>current</i> context, which is the engine's own default
 * Global, not this scope - it answers {@code NoSuchMethodException: No such
 * function start} for every script. {@code invokeMethod(thiz, name, args)} with
 * this scope's mirror as {@code thiz} resolves the name on that mirror's home
 * Global and calls it with the Global as {@code this}, which is exactly what a
 * plain {@code invokeFunction} used to do on a per-conversation engine. So this
 * class implements {@link Invocable} and routes {@code invokeFunction} through
 * {@code invokeMethod}; every existing call site - {@code iv.invokeFunction
 * ("action", …)} in {@link NPCScriptManager}, the seventeen
 * {@code em.getIv().invokeFunction(…)} in {@link EventInstanceManager} - keeps
 * working unchanged, and a missing entry point still throws
 * {@code NoSuchMethodException}, which {@code NPCScriptManager.start} catches
 * to fall back to {@code action(1, 0, 0)}.
 *
 * <p><b>Threading.</b> Nashorn's factory reports no {@code THREADING}
 * parameter, which by the {@code javax.script} contract means "ask no
 * guarantees of one engine used concurrently". What is actually safe, and what
 * this relies on, is narrower and is Nashorn's own design: the current Global
 * is a {@code ThreadLocal} that Nashorn swaps around every eval and invoke, and
 * a compiled script is a {@code MultiGlobalCompiledScript} precisely so that it
 * can be run in more than one Global. So N threads in N different scopes off
 * one engine is the supported shape; two threads inside the <i>same</i> scope
 * is not, and never was - a conversation is serialised by
 * {@code MapleClient.getNPCLock()} exactly as before.
 */
public final class ScriptScope implements Invocable {

    private final ScriptEngine engine;
    /** The Nashorn Global for this scope, seen as a {@code Bindings}. */
    private final Bindings globals;
    private final ScriptContext ctx;

    ScriptScope(final ScriptEngine engine) {
        this.engine = engine;
        this.globals = engine.createBindings();
        this.ctx = new SimpleScriptContext();
        this.ctx.setBindings(globals, ScriptContext.ENGINE_SCOPE);
        this.ctx.setBindings(engine.getContext().getBindings(ScriptContext.GLOBAL_SCOPE),
                ScriptContext.GLOBAL_SCOPE);
    }

    /** Runs a compiled script in this scope. Its {@code var}s land here. */
    void run(final CompiledScript script) throws ScriptException {
        script.eval(ctx);
    }

    /**
     * Defines a global in this scope - {@code cm}, {@code npcid}, {@code qm},
     * {@code rm}, {@code em}. Same name and meaning the scripts always saw;
     * this used to be {@code ScriptEngine.put}, which wrote to the
     * conversation's own engine.
     */
    public void put(final String key, final Object value) {
        globals.put(key, value);
    }

    public Object get(final String key) {
        return globals.get(key);
    }

    @Override
    public Object invokeFunction(final String name, final Object... args)
            throws ScriptException, NoSuchMethodException {
        return ((Invocable) engine).invokeMethod(globals, name, args);
    }

    @Override
    public Object invokeMethod(final Object thiz, final String name, final Object... args)
            throws ScriptException, NoSuchMethodException {
        return ((Invocable) engine).invokeMethod(thiz, name, args);
    }

    /** The {@code PortalScript} view of this scope, or null if it has no {@code enter}. */
    @Override
    public <T> T getInterface(final Class<T> clasz) {
        return ((Invocable) engine).getInterface(globals, clasz);
    }

    @Override
    public <T> T getInterface(final Object thiz, final Class<T> clasz) {
        return ((Invocable) engine).getInterface(thiz, clasz);
    }
}
