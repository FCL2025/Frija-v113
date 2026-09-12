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
import tools.Encodings;

/**
 * Finds the scripts under {@code Libs/scripts} that the game cannot read.
 *
 * <p>{@code StringUtil.readFileAsString} decodes every script as BIG5, so a
 * file that was saved as UTF-8 throws {@code MalformedInputException} there,
 * is logged as one easy-to-miss line, and comes back as {@code ""}. The engine
 * still builds, so nothing fails loudly: the NPC simply does not answer. That
 * has already happened twice in this tree - nine portal scripts and eight NPC
 * scripts - which is why {@code Start} now says so at startup, and why the
 * same scan is behind {@code !checkscripts} and
 * {@code GET /api/scripts/encoding}.
 *
 * <p>Nothing here is cached: every call re-reads the directory, so a script
 * repaired (or broken) while the server runs is seen on the next scan. That
 * also means there is no shared state to guard - a panel thread, a handler
 * thread and the startup thread can all be in here at once.
 */
public final class ScriptEncodingCheck {

    /** Where the scripts live, relative to the working directory. */
    private static final Path ROOT = Path.of("Libs", "scripts");

    /**
     * The outcome of one scan: how many {@code *.js} files were looked at, and
     * the repo-relative path of every one that is not valid BIG5 (or could not
     * be read at all), sorted.
     */
    public record Result(int total, List<String> bad) {

        public boolean ok() {
            return bad.isEmpty();
        }

        public int badCount() {
            return bad.size();
        }
    }

    private ScriptEncodingCheck() {
    }

    /**
     * Strictly decodes every {@code *.js} under {@code Libs/scripts} as BIG5.
     *
     * <p>About 100 ms for the ~2,450 files in this tree: the bytes are read,
     * decoded and thrown away, and no {@code String} is ever built. Never
     * throws - an unreadable file is reported as a bad one, and a missing
     * {@code Libs/scripts} is an empty result.
     */
    public static Result scan() {
        final List<String> bad = new ArrayList<>();
        int total = 0;
        if (!Files.isDirectory(ROOT)) {
            return new Result(0, List.of());
        }
        final CharsetDecoder dec = Encodings.BIG5.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Stream<Path> walk = Files.walk(ROOT)) {
            for (final Path p : (Iterable<Path>) walk
                    .filter(x -> x.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".js"))
                    .filter(Files::isRegularFile)::iterator) {
                total++;
                try {
                    dec.reset().decode(ByteBuffer.wrap(Files.readAllBytes(p)));
                } catch (final CharacterCodingException notBig5) {
                    bad.add(display(p));
                } catch (final IOException e) {
                    // Gone, locked, or a bad sector - the game would not be
                    // able to read it either, so it belongs on the list.
                    bad.add(display(p));
                }
            }
        } catch (final IOException | UncheckedIOException e) {
            // The walk itself gave up. Report what was reached rather than
            // making the caller deal with it; this is a diagnostic, not a
            // precondition.
            System.out.println("[腳本] 掃描 " + ROOT + " 時中斷：" + e);
        }
        Collections.sort(bad);
        return new Result(total, bad);
    }

    /** Repo-relative and forward-slashed, so the output reads the same on any platform. */
    private static String display(final Path p) {
        return p.toString().replace('\\', '/');
    }
}
