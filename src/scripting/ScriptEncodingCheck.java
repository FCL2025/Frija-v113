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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.script.Compilable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import tools.Encodings;

/**
 * Finds the scripts under {@code Libs/scripts} that the game cannot use.
 *
 * <p>There are two ways for a script to be dead without anyone noticing, and
 * they look identical from the outside - the NPC or quest simply does not
 * answer:
 *
 * <ul>
 * <li><b>Not BIG5.</b> {@code StringUtil.readFileAsString} decodes every script
 * as BIG5, so a file that was saved as UTF-8 throws
 * {@code MalformedInputException} there, is logged as one easy-to-miss line,
 * and comes back as {@code ""}. The engine still builds, so nothing fails
 * loudly. That has already happened twice in this tree - nine portal scripts
 * and eight NPC scripts.</li>
 * <li><b>Will not compile.</b> The file decodes perfectly and then Nashorn
 * rejects it: an unbalanced paren, an unterminated string, or - for
 * {@code npc/2040043.js} - an {@code AssertionError: Failed generating
 * bytecode} out of a single call with 126 call-valued arguments. The eval
 * throws, {@code getInvocable} returns null, and all that reaches
 * {@code Log_Script_Except.rtf} is the misleading {@code No such function
 * action}. Six scripts in this tree had never once compiled.</li>
 * </ul>
 *
 * <p>The two halves cost very different amounts, so they are separate entry
 * points. {@link #scanEncoding()} only decodes - 0.2-0.4 s for the ~2,450
 * files here - and is what {@code Start} runs on every boot. {@link #scan()}
 * decodes <i>and</i> compiles, which is 2.5-4 s and allocates a few hundred
 * MB of short-lived Nashorn output, so it is on demand only:
 * {@code !checkscripts} and {@code GET /api/scripts/encoding}. Adding 3-4 s
 * to a 6 s startup was not worth it for a fault that cannot appear between
 * two restarts of the same source tree. Both figures are for the first sweep
 * in a fresh JVM, which is the slow one.
 *
 * <p>Nothing here is cached: every call re-reads the directory, so a script
 * repaired (or broken) while the server runs is seen on the next scan. That
 * also means there is no shared state to guard - a panel thread, a handler
 * thread and the startup thread can all be in here at once. The engine a
 * compile sweep builds is local to that sweep and dropped with it, so nothing
 * accumulates between scans.
 */
public final class ScriptEncodingCheck {

    private static final System.Logger LOG = System.getLogger(ScriptEncodingCheck.class.getName());

    /** Where the scripts live, relative to the working directory. */
    private static final Path ROOT = Path.of("Libs", "scripts");

    /**
     * What {@code AbstractScriptManager} puts in front of every script. The
     * sweep has to compile the same text the server runs, line numbers
     * included.
     */
    private static final String PROLOGUE = "load('nashorn:mozilla_compat.js');" + System.lineSeparator();

    /** Nashorn's complaints carry the offending source line; keep the report readable. */
    private static final int MAX_ERROR_CHARS = 160;

    /** One script that compiles nowhere, with Nashorn's own first line about it. */
    public record Failure(String path, String error) {
    }

    /**
     * The outcome of one scan: how many {@code *.js} files were looked at,
     * which ones are not valid BIG5 (or could not be read at all), and - when
     * the sweep included the compile half - which ones Nashorn refuses.
     *
     * <p>{@link #bad()} is every script the game cannot use, both kinds
     * together, which is what the startup report, {@code !checkscripts} and
     * the panel all list. {@link #notBig5()} and {@link #notCompiling()} are
     * the two halves of it.
     */
    public record Result(int total, List<String> notBig5, List<Failure> notCompiling, boolean compileChecked) {

        public Result {
            notBig5 = List.copyOf(notBig5);
            notCompiling = List.copyOf(notCompiling);
        }

        /** Every unusable script, sorted, however it is broken. */
        public List<String> bad() {
            final List<String> all = new ArrayList<>(notBig5);
            for (final Failure f : notCompiling) {
                all.add(f.path());
            }
            Collections.sort(all);
            return all;
        }

        public boolean ok() {
            return notBig5.isEmpty() && notCompiling.isEmpty();
        }

        public int badCount() {
            return notBig5.size() + notCompiling.size();
        }
    }

    private ScriptEncodingCheck() {
    }

    /**
     * Strictly decodes every {@code *.js} under {@code Libs/scripts} as BIG5,
     * and then hands each one that decoded to Nashorn's compiler.
     *
     * <p>About 2.5-4 s for the ~2,450 files in this tree, so this is the
     * on-demand entry point; {@link #scanEncoding()} is the one startup uses.
     * If Nashorn is not on the classpath the compile half is skipped and
     * {@link Result#compileChecked()} says so, exactly as
     * {@code AbstractScriptManager} and {@code PortalScriptManager} cope with
     * the same absence. Never throws.
     */
    public static Result scan() {
        return walk(true);
    }

    /**
     * Strictly decodes every {@code *.js} under {@code Libs/scripts} as BIG5
     * and stops there.
     *
     * <p>0.2-0.4 s for the ~2,450 files in this tree: the bytes are read,
     * decoded and thrown away, and no {@code String} is ever built. Never
     * throws - an unreadable file is reported as a bad one, and a missing
     * {@code Libs/scripts} is an empty result.
     */
    public static Result scanEncoding() {
        return walk(false);
    }

    private static Result walk(final boolean compile) {
        final List<String> notBig5 = new ArrayList<>();
        final List<Failure> notCompiling = new ArrayList<>();
        int total = 0;
        if (!Files.isDirectory(ROOT)) {
            return new Result(0, List.of(), List.of(), false);
        }
        final Compilable compiler = compile ? compiler() : null;
        final CharsetDecoder dec = Encodings.BIG5.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Stream<Path> walk = Files.walk(ROOT)) {
            for (final Path p : (Iterable<Path>) walk
                    .filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".js"))
                    .filter(Files::isRegularFile)::iterator) {
                total++;
                final CharSequence text;
                try {
                    text = dec.reset().decode(ByteBuffer.wrap(Files.readAllBytes(p)));
                } catch (final CharacterCodingException notBig5Here) {
                    notBig5.add(display(p));
                    continue;
                } catch (final IOException e) {
                    // Gone, locked, or a bad sector - the game would not be
                    // able to read it either, so it belongs on the list.
                    notBig5.add(display(p));
                    continue;
                }
                if (compiler == null) {
                    continue;
                }
                try {
                    // The result is deliberately dropped: all this wants to
                    // know is whether bytecode came out, and holding 2,456
                    // CompiledScripts would pin their generated classes.
                    compiler.compile(PROLOGUE + text);
                } catch (final Throwable t) {
                    // Not just ScriptException: a call with 126 call-valued
                    // arguments makes Nashorn's code generator throw a bare
                    // AssertionError, which is how npc/2040043.js hid.
                    notCompiling.add(new Failure(display(p), firstLine(t)));
                }
            }
        } catch (final IOException | UncheckedIOException e) {
            // The walk itself gave up. Report what was reached rather than
            // making the caller deal with it; this is a diagnostic, not a
            // precondition.
            LOG.log(System.Logger.Level.WARNING, "[腳本] 掃描 " + ROOT + " 時中斷：" + e);
        }
        Collections.sort(notBig5);
        notCompiling.sort((a, b) -> a.path().compareTo(b.path()));
        return new Result(total, notBig5, notCompiling, compiler != null);
    }

    /**
     * One engine for the whole sweep, or null if Nashorn is not there.
     *
     * <p>Nashorn left the JDK in 15, so it only exists when nashorn-core and
     * the asm jars are on the classpath. A missing engine is not this class's
     * problem to report - the server is already unable to run any script at
     * all and says so from {@code AbstractScriptManager} - so the compile half
     * is simply skipped.
     */
    private static Compilable compiler() {
        try {
            final ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
            if (engine instanceof Compilable c) {
                return c;
            }
            LOG.log(System.Logger.Level.WARNING, "[腳本] 找不到 nashorn 引擎，這次只檢查編碼、不檢查能不能編譯。");
        } catch (final Exception e) {
            LOG.log(System.Logger.Level.WARNING, "[腳本] 建立 nashorn 引擎失敗，這次只檢查編碼：" + e);
        }
        return null;
    }

    /**
     * Nashorn quotes the offending source line and draws a caret under it, so
     * a complaint about a Chinese dialogue line runs to several hundred
     * characters. Keep the first line and cap it; the console this goes to is
     * the platform's, not BIG5, and these strings decoded from BIG5 bytes.
     */
    private static String firstLine(final Throwable t) {
        String s = t.getMessage();
        if (s == null || s.isBlank()) {
            s = t.getClass().getName();
        } else if (t instanceof Error) {
            s = t.getClass().getSimpleName() + ": " + s;
        }
        final int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl);
        }
        s = s.replace('\r', ' ').strip();
        return s.length() > MAX_ERROR_CHARS ? s.substring(0, MAX_ERROR_CHARS) + "…" : s;
    }

    /** Repo-relative and forward-slashed, so the output reads the same on any platform. */
    private static String display(final Path p) {
        return p.toString().replace('\\', '/');
    }
}
