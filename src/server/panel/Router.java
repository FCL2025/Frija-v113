package server.panel;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches {@code /api/*} requests, and turns every one of them away that
 * does not carry the panel token.
 *
 * <p>Binding to {@code 127.0.0.1} keeps other machines out; it does nothing
 * about the browser on this machine. While the administrator has the panel
 * open, any page they visit can {@code fetch('http://127.0.0.1:8080/api/…')}
 * and the browser will send it - reading the reply needs CORS, but a
 * {@code POST} that changes something does not. So three checks run before any
 * route, in this order:
 *
 * <ol>
 * <li>{@code Host} must be the loopback address the panel is bound to, which
 * defeats DNS rebinding (a hostname that resolves to 127.0.0.1 arrives with
 * that hostname in {@code Host}).</li>
 * <li>{@code Origin}, when a browser sends one, must be the panel's own; and
 * {@code Sec-Fetch-Site}, when present, must not say cross-site.</li>
 * <li>{@code Authorization: Bearer <token>} must match. A cross-origin page
 * cannot add that header without a CORS preflight, and this server never
 * answers a preflight with {@code Access-Control-Allow-*}, so the browser
 * refuses to send the real request.</li>
 * </ol>
 *
 * <p>Every reply is {@code application/json; charset=utf-8}. Errors are
 * {@code {"error": "…"}} with the matching status, including for exceptions a
 * route lets escape - those go to stderr as well, because the panel is the
 * place the administrator looks when the game is silent, and it should not be
 * silent itself.
 */
public final class Router implements HttpHandler {

    private static final System.Logger LOG = System.getLogger(Router.class.getName());

    /** One route body. Return a value for {@link Json#write}; throw {@link HttpError} for a non-200. */
    @FunctionalInterface
    public interface Route {

        Object handle(Request req) throws Exception;
    }

    /** Fails a request with a specific status. The message becomes the {@code error} field. */
    public static final class HttpError extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final int status;
        /** Optional structured payload, sent as {@code details} beside {@code error}. */
        private final transient Object details;

        public HttpError(final int status, final String message) {
            this(status, message, null);
        }

        public HttpError(final int status, final String message, final Object details) {
            super(message);
            this.status = status;
            this.details = details;
        }

        public int getStatus() {
            return status;
        }

        public Object getDetails() {
            return details;
        }
    }

    /** What a route sees: path parameters, query string and (on demand) the JSON body. */
    public static final class Request {

        private final HttpExchange exchange;
        private final Map<String, String> pathParams;
        private Map<String, String> query;
        private Object body;
        private boolean bodyRead;

        Request(final HttpExchange exchange, final Map<String, String> pathParams) {
            this.exchange = exchange;
            this.pathParams = pathParams;
        }

        public HttpExchange exchange() {
            return exchange;
        }

        public String param(final String name) {
            return pathParams.get(name);
        }

        public Map<String, String> query() {
            if (query == null) {
                query = parseQuery(exchange.getRequestURI().getRawQuery());
            }
            return query;
        }

        public String query(final String name, final String def) {
            final String v = query().get(name);
            return v == null ? def : v;
        }

        public int queryInt(final String name, final int def, final int min, final int max) {
            final String v = query().get(name);
            if (v == null || v.isBlank()) {
                return def;
            }
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
            } catch (final NumberFormatException e) {
                throw new HttpError(400, "查詢參數 " + name + " 必須是整數");
            }
        }

        /** The request body parsed as JSON, or {@code null} when there is none. Read once, cached. */
        public Object body() throws IOException {
            if (!bodyRead) {
                bodyRead = true;
                final String text;
                try (InputStream in = exchange.getRequestBody()) {
                    text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (text.isBlank()) {
                    body = null;
                } else {
                    try {
                        body = Json.read(text);
                    } catch (final IllegalArgumentException e) {
                        throw new HttpError(400, "請求內容不是合法的 JSON：" + e.getMessage());
                    }
                }
            }
            return body;
        }

        /** The body as an object, or a 400 if it is anything else. */
        @SuppressWarnings("unchecked")
        public Map<String, Object> bodyObject() throws IOException {
            final Object b = body();
            if (!(b instanceof Map)) {
                throw new HttpError(400, "請求內容必須是 JSON 物件");
            }
            return (Map<String, Object>) b;
        }
    }

    private record Entry(String method, String[] segments, Route route) {
    }

    /** Replaced whole by {@link #setToken}; read once per request. */
    private volatile byte[] token;
    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;
    private final List<Entry> routes = new ArrayList<>();

    Router(final String token, final int port) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.allowedHosts = Set.of("127.0.0.1:" + port, "localhost:" + port, "[::1]:" + port);
        this.allowedOrigins = Set.of("http://127.0.0.1:" + port, "http://localhost:" + port, "http://[::1]:" + port);
    }

    void setToken(final String token) {
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code pattern} is a path such as {@code /api/logs/{name}}; segments in braces bind to {@link Request#param}. */
    public void get(final String pattern, final Route route) {
        add("GET", pattern, route);
    }

    public void post(final String pattern, final Route route) {
        add("POST", pattern, route);
    }

    public void put(final String pattern, final Route route) {
        add("PUT", pattern, route);
    }

    public void delete(final String pattern, final Route route) {
        add("DELETE", pattern, route);
    }

    private void add(final String method, final String pattern, final Route route) {
        routes.add(new Entry(method, split(pattern), route));
    }

    @Override
    public void handle(final HttpExchange ex) throws IOException {
        try {
            checkOrigin(ex);
            checkToken(ex);
            final String path = ex.getRequestURI().getPath();
            final String[] segs = split(path);
            final String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            boolean pathMatched = false;
            for (final Entry e : routes) {
                final Map<String, String> params = match(e.segments, segs);
                if (params == null) {
                    continue;
                }
                pathMatched = true;
                if (!e.method.equals(method)) {
                    continue;
                }
                final Object result = e.route.handle(new Request(ex, params));
                sendJson(ex, 200, result);
                return;
            }
            throw new HttpError(pathMatched ? 405 : 404, pathMatched ? "不支援的方法" : "找不到此端點");
        } catch (final HttpError e) {
            sendJson(ex, e.getStatus(), e.getDetails() == null
                    ? Json.obj("error", e.getMessage())
                    : Json.obj("error", e.getMessage(), "details", e.getDetails()));
        } catch (final Exception e) {
            // One record where there were two prints: the message and the
            // trace are the same event. Only the console half moved - the reply
            // below is still a 500 carrying the same {"error": …} body.
            LOG.log(System.Logger.Level.ERROR,
                    "[面板] " + ex.getRequestMethod() + " " + ex.getRequestURI() + " 發生例外", e);
            sendJson(ex, 500, Json.obj("error", "伺服器內部錯誤：" + e));
        } finally {
            ex.close();
        }
    }

    private void checkOrigin(final HttpExchange ex) {
        final Headers h = ex.getRequestHeaders();
        final String host = h.getFirst("Host");
        if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            throw new HttpError(403, "Host 不符");
        }
        final String origin = h.getFirst("Origin");
        if (origin != null && !allowedOrigins.contains(origin.toLowerCase(Locale.ROOT))) {
            throw new HttpError(403, "Origin 不符");
        }
        final String site = h.getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            throw new HttpError(403, "跨站請求已拒絕");
        }
    }

    private void checkToken(final HttpExchange ex) {
        final String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new HttpError(401, "需要登入 Token");
        }
        final byte[] given = auth.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, token)) {
            throw new HttpError(401, "Token 錯誤");
        }
    }

    /** Writes {@code value} as the whole response. Safe to call for any status. */
    public static void sendJson(final HttpExchange ex, final int status, final Object value) throws IOException {
        final byte[] body = Json.write(value).getBytes(StandardCharsets.UTF_8);
        final Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static String[] split(final String path) {
        final List<String> parts = new ArrayList<>();
        for (final String p : path.split("/")) {
            if (!p.isEmpty()) {
                parts.add(p);
            }
        }
        return parts.toArray(new String[0]);
    }

    /** Returns the bound path parameters, or {@code null} when the pattern does not match. */
    private static Map<String, String> match(final String[] pattern, final String[] path) {
        if (pattern.length != path.length) {
            return null;
        }
        Map<String, String> params = null;
        for (int i = 0; i < pattern.length; i++) {
            final String p = pattern[i];
            if (p.startsWith("{") && p.endsWith("}")) {
                if (params == null) {
                    params = new LinkedHashMap<>();
                }
                params.put(p.substring(1, p.length() - 1), URLDecoder.decode(path[i], StandardCharsets.UTF_8));
            } else if (!p.equals(path[i])) {
                return null;
            }
        }
        return params == null ? Collections.emptyMap() : params;
    }

    private static Map<String, String> parseQuery(final String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> m = new LinkedHashMap<>();
        for (final String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            final int eq = pair.indexOf('=');
            final String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            final String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            m.putIfAbsent(k, v);
        }
        return m;
    }
}
