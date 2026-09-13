package server.panel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import scripting.PortalScriptManager;
import scripting.ScriptEncodingCheck;
import tools.Encodings;

/**
 * The script editor's backend: {@code Libs/scripts/<kind>/<name>.js} in and
 * out, with the encoding handled here so the browser never sees a byte.
 *
 * <p>The scripts are BIG5 on disk, because {@code StringUtil.readFileAsString}
 * reads them as BIG5 and the dialogue in them goes to the client verbatim.
 * The wire format of this API is UTF-8 like everything else in the panel, so
 * the translation happens at the file boundary in both directions - and on
 * the way in it is checked first: a character BIG5 cannot represent (an emoji,
 * a simplified-only hanzi, a smart quote) would otherwise be written as
 * {@code ?}, or worse, make the file undecodable and the script silently a
 * no-op. Such a save is refused with the line and column of every offending
 * character.
 *
 * <p>Nine portal scripts already sit in that undecodable state - saved as
 * UTF-8 at some point and dead ever since. Reading one reports
 * {@code encodingOk: false} and shows its UTF-8 reading, so saving it from
 * the editor is exactly the fix; nothing here does that on its own.
 *
 * <p>What a save does to the running server depends on the kind:
 * portal scripts are cached process-wide and are dropped here; event
 * scripts are compiled per channel at startup and want a reload from
 * {@link ActionsApi}; NPC, quest and reactor scripts are cached per
 * {@code MapleClient}, in a plain {@code HashMap} only that client's handler
 * thread touches, so the next client to run the script simply reads the new
 * file and the ones mid-conversation are told to use {@code @解卡}.
 */
final class ScriptsApi {

    static final Path ROOT = Path.of("Libs", "scripts");
    static final List<String> KINDS = List.of("npc", "portal", "quest", "reactor", "event");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-.]+\\.js");
    private static final int MAX_SEARCH_HITS = 300;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;

    private ScriptsApi() {
    }

    static void register(final Router r) {
        r.get("/api/scripts/kinds", req -> kinds());
        r.get("/api/scripts/list/{kind}", req -> list(kind(req.param("kind"))));
        r.get("/api/scripts/search", req -> search(req.query("q", ""), req.query("kind", "")));
        r.get("/api/scripts/encoding", req -> encoding());
        r.get("/api/scripts/file/{kind}/{name}", req -> read(kind(req.param("kind")), name(req.param("name"))));
        r.put("/api/scripts/file/{kind}/{name}", req -> write(kind(req.param("kind")), name(req.param("name")),
                req.bodyObject()));
    }

    private static String kind(final String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new Router.HttpError(404, "沒有這種腳本，可用：" + String.join(", ", KINDS));
        }
        return kind;
    }

    private static String name(final String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
            throw new Router.HttpError(400, "不合法的腳本檔名");
        }
        return name;
    }

    private static List<Object> kinds() throws IOException {
        final List<Object> out = new ArrayList<>();
        for (final String k : KINDS) {
            final Path dir = ROOT.resolve(k);
            long count = 0;
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    count = s.filter(p -> p.getFileName().toString().endsWith(".js")).count();
                }
            }
            out.add(Json.obj("kind", k, "count", count));
        }
        return out;
    }

    private static List<Object> list(final String kind) throws IOException {
        final Path dir = ROOT.resolve(kind);
        final List<Object> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".js") && Files.isRegularFile(p))
                    .sorted((a, b) -> compareNames(a.getFileName().toString(), b.getFileName().toString()))
                    .forEach(p -> {
                        try {
                            out.add(Json.obj(
                                    "name", p.getFileName().toString(),
                                    "size", Files.size(p),
                                    "modified", Files.getLastModifiedTime(p).toMillis()));
                        } catch (final IOException e) {
                            // Vanished between list and stat; leave it out.
                        }
                    });
        }
        return out;
    }

    /** Numeric names (NPC and quest ids) sort by value; the rest alphabetically. */
    private static int compareNames(final String a, final String b) {
        final String an = a.substring(0, a.length() - 3);
        final String bn = b.substring(0, b.length() - 3);
        final boolean ai = an.chars().allMatch(Character::isDigit);
        final boolean bi = bn.chars().allMatch(Character::isDigit);
        if (ai && bi) {
            return Long.compare(Long.parseLong(an), Long.parseLong(bn));
        }
        if (ai != bi) {
            return ai ? -1 : 1;
        }
        return an.compareToIgnoreCase(bn);
    }

    /**
     * Case-insensitive substring search over one kind or all of them. Every
     * file is decoded as BIG5 with replacement, so an undecodable file still
     * matches on the parts that do decode. A few thousand small files fit in
     * a second; this is fine for a panel, and there is no index to keep.
     */
    private static Map<String, Object> search(final String q, final String kindFilter) throws IOException {
        final String needle = q.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < 2) {
            throw new Router.HttpError(400, "搜尋字串至少 2 個字");
        }
        final List<String> kinds = kindFilter.isBlank() ? KINDS : List.of(kind(kindFilter));
        final List<Object> hits = new ArrayList<>();
        final CharsetDecoder dec = Encodings.BIG5.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        int filesMatched = 0;
        boolean capped = false;
        outer:
        for (final String k : kinds) {
            final Path dir = ROOT.resolve(k);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            final List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".js")).sorted().toList();
            }
            for (final Path p : files) {
                final String text;
                try {
                    text = dec.reset().decode(ByteBuffer.wrap(Files.readAllBytes(p))).toString();
                } catch (final IOException e) {
                    continue;
                }
                if (!text.toLowerCase(Locale.ROOT).contains(needle)) {
                    continue;
                }
                filesMatched++;
                final String[] lines = text.split("\r?\n");
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].toLowerCase(Locale.ROOT).contains(needle)) {
                        hits.add(Json.obj("kind", k, "name", p.getFileName().toString(), "line", i + 1,
                                "text", lines[i].length() > 200 ? lines[i].substring(0, 200) + "…" : lines[i]));
                        if (hits.size() >= MAX_SEARCH_HITS) {
                            capped = true;
                            break outer;
                        }
                    }
                }
            }
        }
        return Json.obj("q", q, "hits", hits, "filesMatched", filesMatched, "capped", capped);
    }

    /**
     * The same scan {@code !checkscripts} runs in game: every {@code *.js}
     * under {@code Libs/scripts} strictly decoded as BIG5 and then handed to
     * Nashorn's compiler, with everything that fails either half named.
     * Read-only and uncached, so it answers for the files as they are on disk
     * right now - including a script repaired through {@link #write} a moment
     * ago.
     *
     * <p>Both halves matter and they fail the same way from the outside: the
     * NPC simply stops answering. {@code bad} is the two of them together, the
     * way it always was; {@code notBig5} and {@code notCompiling} split it.
     * The compile half takes a few seconds for ~2,450 files, which is why
     * startup only runs the decode half - this endpoint is one of the two
     * places that pays for the rest.
     */
    private static Map<String, Object> encoding() {
        final ScriptEncodingCheck.Result result = ScriptEncodingCheck.scan();
        final List<Map<String, Object>> notCompiling = new ArrayList<>();
        for (final ScriptEncodingCheck.Failure f : result.notCompiling()) {
            notCompiling.add(Json.obj("path", f.path(), "error", f.error()));
        }
        return Json.obj("total", result.total(), "bad", result.bad(),
                "notBig5", result.notBig5(), "notCompiling", notCompiling,
                "compileChecked", result.compileChecked());
    }

    private static Map<String, Object> read(final String kind, final String name) throws IOException {
        final Path file = ROOT.resolve(kind).resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new Router.HttpError(404, "找不到這支腳本");
        }
        final byte[] bytes = Files.readAllBytes(file);
        String content;
        boolean encodingOk = true;
        String detected = "BIG5";
        try {
            content = Encodings.BIG5.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (final CharacterCodingException notBig5) {
            // The game cannot read this file at all. Show the most likely
            // reading so that saving it from the editor repairs it.
            encodingOk = false;
            try {
                content = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                detected = "UTF-8";
            } catch (final CharacterCodingException notUtf8) {
                content = Encodings.BIG5.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                detected = "unknown";
            }
        }
        if (!content.isEmpty() && content.charAt(0) == 0xFEFF) {
            content = content.substring(1); // a UTF-8 BOM, which BIG5 has no way to write back
        }
        return Json.obj(
                "kind", kind,
                "name", name,
                "content", content,
                "size", bytes.length,
                "modified", Files.getLastModifiedTime(file).toMillis(),
                "encodingOk", encodingOk,
                "detectedEncoding", detected,
                "afterSave", afterSave(kind));
    }

    /** What the UI tells the user a save will mean for the running server. */
    private static String afterSave(final String kind) {
        return switch (kind) {
            case "portal" -> "存檔後會自動清掉傳送點腳本快取，下次踩到就是新版。";
            case "event" -> "事件腳本是各頻道啟動時編譯的；存檔後請按「重載事件」。";
            default -> "沒有全域快取：還沒跑過這支腳本的玩家下次就會讀到新檔；正卡在對話中的玩家請用 @解卡 或重登。";
        };
    }

    private static Map<String, Object> write(final String kind, final String name, final Map<String, Object> body)
            throws IOException {
        final Object raw = body.get("content");
        if (!(raw instanceof String content)) {
            throw new Router.HttpError(400, "缺少 content");
        }
        if (content.length() > MAX_FILE_BYTES) {
            throw new Router.HttpError(400, "檔案太大");
        }
        final List<Object> bad = unencodable(content);
        if (!bad.isEmpty()) {
            final Map<?, ?> first = (Map<?, ?>) bad.get(0);
            throw new Router.HttpError(400, "有 " + bad.size() + (bad.size() >= 50 ? "+" : "")
                    + " 個字元無法以 BIG5 儲存，第一個在第 " + first.get("line") + " 行第 " + first.get("col")
                    + " 字：" + first.get("char") + "（" + first.get("codePoint") + "）", Json.obj("unencodable", bad));
        }
        // The game reads the file with readAllLines, which accepts either
        // terminator, but every existing script is CRLF; keep it that way.
        final String normalised = content.replace("\r\n", "\n").replace("\n", "\r\n");
        final byte[] bytes = normalised.getBytes(Encodings.BIG5);

        final Path dir = ROOT.resolve(kind);
        Files.createDirectories(dir);
        final Path file = dir.resolve(name);
        final boolean existed = Files.exists(file);
        final Path tmp = dir.resolve(name + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        String reloaded = null;
        if (kind.equals("portal")) {
            PortalScriptManager.getInstance().clearScripts();
            reloaded = "portal";
        }
        return Json.obj(
                "ok", true,
                "kind", kind,
                "name", name,
                "bytes", bytes.length,
                "created", !existed,
                "modified", Files.getLastModifiedTime(file).toMillis(),
                "reloaded", reloaded,
                "afterSave", afterSave(kind));
    }

    /** Every character BIG5 cannot encode, with its position, up to a sane limit. */
    static List<Object> unencodable(final String text) {
        final CharsetEncoder enc = Encodings.BIG5.newEncoder();
        final List<Object> out = new ArrayList<>();
        int line = 1;
        int col = 1;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == '\n') {
                line++;
                col = 1;
                continue;
            }
            if (c == '\r') {
                continue;
            }
            final boolean ok;
            if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                ok = enc.canEncode(CharBuffer.wrap(text, i, i + 2));
                if (!ok) {
                    out.add(Json.obj("line", line, "col", col, "char", text.substring(i, i + 2),
                            "codePoint", String.format("U+%04X", text.codePointAt(i))));
                }
                i++;
            } else {
                ok = enc.canEncode(c);
                if (!ok) {
                    out.add(Json.obj("line", line, "col", col, "char", String.valueOf(c),
                            "codePoint", String.format("U+%04X", (int) c)));
                }
            }
            col++;
            if (out.size() >= 50) {
                break;
            }
        }
        return out;
    }
}
