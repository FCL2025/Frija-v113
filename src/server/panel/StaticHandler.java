package server.panel;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Serves the built frontend out of the jar.
 *
 * <p>{@code Launcher.bat} copies {@code panel/dist} into
 * {@code build/classes/server/panel/web/} before packaging, so the files sit
 * next to this class and are read with {@link Class#getResourceAsStream}. The
 * frontend is built with Vite's {@code base: './'}, so every asset URL it emits
 * is relative and the page works no matter where the jar is served from.
 *
 * <p>Unknown paths without an extension fall back to {@code index.html}, the
 * usual single-page-app arrangement; unknown paths with an extension are a
 * 404, so a typo in an asset name does not come back as HTML. A tree with no
 * frontend at all - a build made before {@code panel/} existed, or with Node
 * missing - gets a plain page saying so instead of a 404, because the message
 * is the only clue the administrator has about why the panel is blank.
 */
public final class StaticHandler implements HttpHandler {

    private static final String ROOT = "web/";
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    private static final String MISSING_PAGE = """
            <!doctype html><html lang="zh-Hant"><head><meta charset="utf-8">
            <title>TMS113 管理面板</title></head>
            <body style="font-family:system-ui,sans-serif;max-width:40em;margin:4em auto;line-height:1.6">
            <h1>前端尚未建置</h1>
            <p>這個 jar 裡沒有管理面板的網頁檔。請在 <code>panel\\</code> 目錄執行一次
            <code>npm install</code>，再重新執行 <code>Launcher.bat</code>；它會自動建置前端並打包進 jar。</p>
            <p>API 本身是正常的：<code>GET /api/ping</code>（需要 Authorization header）。</p>
            </body></html>
            """;

    @Override
    public void handle(final HttpExchange ex) throws IOException {
        try {
            final String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.contains("..") || path.contains("\\")) {
                send(ex, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            byte[] body = read(path);
            String served = path;
            if (body == null && !hasExtension(path)) {
                served = "/index.html";
                body = read(served);
            }
            if (body == null) {
                if (served.equals("/index.html")) {
                    send(ex, 200, "text/html; charset=utf-8", MISSING_PAGE.getBytes(StandardCharsets.UTF_8), false);
                } else {
                    send(ex, 404, "text/plain; charset=utf-8", "Not Found".getBytes(StandardCharsets.UTF_8), false);
                }
                return;
            }
            // Vite hashes everything under assets/, so those can be cached for
            // good; index.html is what points at the current hashes and must not be.
            send(ex, 200, contentType(served), body, served.startsWith("/assets/"));
        } finally {
            ex.close();
        }
    }

    private static byte[] read(final String path) throws IOException {
        try (InputStream in = StaticHandler.class.getResourceAsStream(ROOT + path.substring(1))) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static boolean hasExtension(final String path) {
        final int slash = path.lastIndexOf('/');
        final int dot = path.lastIndexOf('.');
        return dot > slash;
    }

    private static String contentType(final String path) {
        final int dot = path.lastIndexOf('.');
        final String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return TYPES.getOrDefault(ext, "application/octet-stream");
    }

    private static void send(final HttpExchange ex, final int status, final String type, final byte[] body,
            final boolean immutable) throws IOException {
        final Headers h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Cache-Control", immutable ? "public, max-age=31536000, immutable" : "no-cache");
        h.set("X-Content-Type-Options", "nosniff");
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
