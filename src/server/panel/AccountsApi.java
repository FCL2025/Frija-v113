package server.panel;

import client.MapleCharacter;
import client.MapleClient;
import database.DatabaseConnection;
import handling.channel.ChannelServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import server.MapleCarnivalChallenge;

/**
 * Accounts, characters and the IP / MAC ban lists.
 *
 * <p>The one rule that shapes everything here: <b>a character who is online
 * lives in memory, and the database row is a stale copy that the next
 * autosave overwrites.</b> {@code MapleCharacter.saveToDB} writes back
 * nearly every column of {@code characters} and the four currency columns of
 * {@code accounts} ({@code ACash}, {@code mPoints}, {@code points},
 * {@code vpoints}). So every write first asks the channels whether the target
 * is online:
 * <ul>
 * <li>{@code characters}: online → 409, with the channel, and a kick action
 * next to it. There is no field on that table worth a race with the save.</li>
 * <li>{@code accounts}: the currency columns go through the online
 * character's own setters as well as the row, so the next save writes the
 * same numbers; the rest of the row (ban state, GM flag, email) is only read
 * at login, and a ban also closes the session so that it takes effect now.</li>
 * </ul>
 *
 * <p>Password material - {@code password}, {@code salt}, {@code 2ndpassword},
 * {@code salt2} - is left out of every {@code SELECT} list, not merely hidden
 * by the UI. {@code tempban} and {@code birthday} carry zero dates that
 * Connector/J refuses to convert, so they are read as strings.
 *
 * <p>A kick is {@code session.close()}: MINA then runs
 * {@code MapleServerHandler.sessionClosed}, which calls
 * {@code client.disconnect(true, …)} with the party, guild, buddy and
 * messenger clean-up. That is what {@code !dc} does, from a thread that is
 * likewise not the victim's own; {@code PlayerStorage.disconnectAll()} is
 * <i>not</i> used because it takes the {@code shutdown=true} path that skips
 * all of that.
 *
 * <p>Every route holds at most one connection for its whole duration - the
 * pool is shared with the game.
 */
final class AccountsApi {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    /** Everything on {@code accounts} except the password columns. */
    private static final String ACCOUNT_COLUMNS = "id, name, loggedin, lastlogin, createdat, birthday, banned, banreason, gm, "
            + "email, macs, tempban, greason, ACash, mPoints, gender, SessionIP, points, vpoints, lastlogon, expiration";
    private static final String CHARACTER_COLUMNS = "id, accountid, world, name, level, exp, str, dex, luk, `int`, hp, mp, maxhp, "
            + "maxmp, meso, job, skincolor, gender, fame, hair, face, ap, sp, map, spawnpoint, gm, party, guildid, guildrank, "
            + "createdate, marriageId, familyid, beans, gachexp";
    /** Column → (has to be an integer) for {@code PUT /api/characters}. */
    private static final Map<String, Boolean> CHARACTER_EDITABLE = new LinkedHashMap<>();
    /** Column → integer? for {@code PUT /api/accounts}; the currency ones also go through memory. */
    private static final Map<String, Boolean> ACCOUNT_EDITABLE = new LinkedHashMap<>();
    private static final Set<String> ACCOUNT_CURRENCY = Set.of("ACash", "mPoints", "points", "vpoints");
    /** "Never" for {@code tempban}: the column is NOT NULL and a zero date cannot be written under NO_ZERO_DATE. */
    private static final String TEMPBAN_NONE = "1970-01-02 00:00:00";

    static {
        for (final String c : List.of("level", "exp", "str", "dex", "luk", "int", "hp", "mp", "maxhp", "maxmp", "meso",
                "job", "fame", "ap", "map", "spawnpoint", "gm", "hair", "face", "skincolor", "gender", "beans")) {
            CHARACTER_EDITABLE.put(c, Boolean.TRUE);
        }
        CHARACTER_EDITABLE.put("sp", Boolean.FALSE);
        for (final String c : List.of("banned", "gm", "greason", "ACash", "mPoints", "points", "vpoints", "gender")) {
            ACCOUNT_EDITABLE.put(c, Boolean.TRUE);
        }
        for (final String c : List.of("banreason", "email", "tempban", "birthday")) {
            ACCOUNT_EDITABLE.put(c, Boolean.FALSE);
        }
    }

    private AccountsApi() {
    }

    static void register(final Router r) {
        r.get("/api/accounts", req -> listAccounts(req.query("q", ""), req.query("filter", ""),
                req.queryInt("page", 1, 1, 100000), req.queryInt("size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE)));
        r.get("/api/accounts/{id}", req -> account(id(req.param("id"))));
        r.put("/api/accounts/{id}", req -> updateAccount(id(req.param("id")), req.bodyObject()));
        r.post("/api/accounts/{id}/kick", req -> kickAccount(id(req.param("id"))));

        r.get("/api/characters", req -> listCharacters(req.query("q", ""),
                req.queryInt("page", 1, 1, 100000), req.queryInt("size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE)));
        r.get("/api/characters/{id}", req -> character(id(req.param("id"))));
        r.put("/api/characters/{id}", req -> updateCharacter(id(req.param("id")), req.bodyObject()));
        r.post("/api/characters/{id}/kick", req -> kickCharacter(id(req.param("id"))));

        r.get("/api/bans/ip", req -> bans("ipbans", "ipbanid", "ip"));
        r.post("/api/bans/ip", req -> addBan("ipbans", "ip", ActionsApi.string(req.bodyObject(), "ip")));
        r.delete("/api/bans/ip/{id}", req -> removeBan("ipbans", "ipbanid", id(req.param("id"))));
        r.get("/api/bans/mac", req -> bans("macbans", "macbanid", "mac"));
        r.post("/api/bans/mac", req -> addBan("macbans", "mac", ActionsApi.string(req.bodyObject(), "mac")));
        r.delete("/api/bans/mac/{id}", req -> removeBan("macbans", "macbanid", id(req.param("id"))));
    }

    private static int id(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            throw new Router.HttpError(400, "id 必須是整數");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Who is online                                                        */
    /* ------------------------------------------------------------------ */

    private record Online(MapleCharacter chr, int channel) {
    }

    /** Snapshot of every online character, by character id and by account id. */
    private static final class OnlineIndex {

        final Map<Integer, Online> byCharacter = new HashMap<>();
        final Map<Integer, Online> byAccount = new HashMap<>();

        OnlineIndex() {
            for (final ChannelServer cs : ChannelServer.getAllInstances()) {
                for (final MapleCharacter chr : cs.getPlayerStorage().getAllCharacters()) {
                    final Online o = new Online(chr, cs.getChannel());
                    byCharacter.put(chr.getId(), o);
                    final MapleClient c = chr.getClient();
                    if (c != null) {
                        byAccount.put(c.getAccID(), o);
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Accounts                                                             */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> listAccounts(final String q, final String filter, final int page, final int size)
            throws SQLException {
        final StringBuilder where = new StringBuilder(" WHERE 1=1");
        final List<Object> args = new ArrayList<>();
        if (!q.isBlank()) {
            where.append(" AND (name LIKE ? OR email LIKE ? OR SessionIP LIKE ? OR id = ?)");
            final String like = "%" + q.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(q.trim().matches("\\d+") ? Integer.parseInt(q.trim()) : -1);
        }
        switch (filter) {
            case "banned" -> where.append(" AND banned > 0");
            case "gm" -> where.append(" AND gm > 0");
            case "online" -> where.append(" AND loggedin > 0");
            default -> {
            }
        }
        final OnlineIndex online = new OnlineIndex();
        try (Connection con = DatabaseConnection.getConnection()) {
            final int total;
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM accounts" + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            final List<Object> items = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT " + ACCOUNT_COLUMNS + " FROM accounts" + where + " ORDER BY id LIMIT ? OFFSET ?")) {
                bind(ps, args);
                ps.setInt(args.size() + 1, size);
                ps.setInt(args.size() + 2, (page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(accountRow(rs, online));
                    }
                }
            }
            return Json.obj("items", items, "total", total, "page", page, "size", size);
        }
    }

    private static Map<String, Object> account(final int id) throws SQLException {
        final OnlineIndex online = new OnlineIndex();
        try (Connection con = DatabaseConnection.getConnection()) {
            final Map<String, Object> account;
            try (PreparedStatement ps = con.prepareStatement("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new Router.HttpError(404, "找不到這個帳號");
                    }
                    account = accountRow(rs, online);
                }
            }
            final List<Object> characters = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT " + CHARACTER_COLUMNS + " FROM characters WHERE accountid = ? ORDER BY id")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        characters.add(characterRow(rs, online));
                    }
                }
            }
            return Json.obj("account", account, "characters", characters);
        }
    }

    private static Map<String, Object> accountRow(final ResultSet rs, final OnlineIndex online) throws SQLException {
        final int id = rs.getInt("id");
        final Online o = online.byAccount.get(id);
        final String tempban = rs.getString("tempban");
        return Json.obj(
                "id", id,
                "name", rs.getString("name"),
                "loggedin", rs.getInt("loggedin"),
                "online", o != null,
                "onlineCharacter", o == null ? null : o.chr().getName(),
                "onlineChannel", o == null ? null : o.channel(),
                "lastlogin", millis(rs, "lastlogin"),
                "createdat", millis(rs, "createdat"),
                "birthday", zeroToNull(rs.getString("birthday")),
                "banned", rs.getInt("banned"),
                "banreason", rs.getString("banreason"),
                "gm", rs.getInt("gm"),
                "email", rs.getString("email"),
                "macs", rs.getString("macs"),
                "tempban", tempban == null || tempban.startsWith("0000-00-00") || tempban.startsWith("1970-01-0") ? null : tempban,
                "greason", rs.getObject("greason") == null ? null : rs.getInt("greason"),
                "ACash", rs.getObject("ACash") == null ? 0 : rs.getInt("ACash"),
                "mPoints", rs.getObject("mPoints") == null ? 0 : rs.getInt("mPoints"),
                "points", rs.getInt("points"),
                "vpoints", rs.getInt("vpoints"),
                "gender", rs.getInt("gender"),
                "sessionIP", rs.getString("SessionIP"),
                "lastlogon", millis(rs, "lastlogon"),
                "expiration", millis(rs, "expiration"));
    }

    /**
     * Writes the given columns. Currency columns are also pushed into the
     * online character so the next autosave agrees; a ban of an online
     * account closes its session. Reply: {@code saved} (row), {@code applied}
     * (memory), {@code kicked}.
     */
    private static Map<String, Object> updateAccount(final int id, final Map<String, Object> body) throws SQLException {
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Map.Entry<String, Boolean> e : ACCOUNT_EDITABLE.entrySet()) {
            if (!body.containsKey(e.getKey())) {
                continue;
            }
            final Object v = body.get(e.getKey());
            if (e.getValue()) {
                values.put(e.getKey(), toInt(e.getKey(), v));
            } else if (e.getKey().equals("tempban")) {
                values.put("tempban", v == null || String.valueOf(v).isBlank() ? TEMPBAN_NONE : parseDateTime(String.valueOf(v)));
            } else if (e.getKey().equals("birthday")) {
                values.put("birthday", v == null ? null : String.valueOf(v));
            } else {
                values.put(e.getKey(), v == null ? "" : String.valueOf(v));
            }
        }
        if (values.isEmpty()) {
            throw new Router.HttpError(400, "沒有可寫入的欄位，可用：" + String.join(", ", ACCOUNT_EDITABLE.keySet()));
        }
        if (values.containsKey("banned") && (Integer) values.get("banned") < -1) {
            throw new Router.HttpError(400, "banned 必須是 -1（下次登入自動解封）、0 或正數");
        }
        if (values.containsKey("birthday")) {
            final String b = (String) values.get("birthday");
            if (b != null && !b.matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new Router.HttpError(400, "birthday 格式是 yyyy-MM-dd");
            }
            if (b == null) {
                values.remove("birthday");
            }
        }

        final StringBuilder sql = new StringBuilder("UPDATE accounts SET ");
        boolean first = true;
        for (final String col : values.keySet()) {
            sql.append(first ? "" : ", ").append('`').append(col).append("` = ?");
            first = false;
        }
        sql.append(" WHERE id = ?");
        final int rows;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int i = 1;
            for (final Object v : values.values()) {
                ps.setObject(i++, v);
            }
            ps.setInt(i, id);
            rows = ps.executeUpdate();
        }
        if (rows == 0) {
            throw new Router.HttpError(404, "找不到這個帳號");
        }

        final Online o = new OnlineIndex().byAccount.get(id);
        final List<String> applied = new ArrayList<>();
        final List<String> deferred = new ArrayList<>();
        boolean kicked = false;
        if (o != null) {
            final MapleCharacter chr = o.chr();
            for (final Map.Entry<String, Object> e : values.entrySet()) {
                switch (e.getKey()) {
                    // C10：本來是「讀一次 getCSPoints、算差額、再 modifyCSPoints」——中間玩家
                    // 在商城買一件東西，差額就是照過期的值算的，資料庫那一列和線上角色物件會停
                    // 在兩個不同的數字上。面板的語意是絕對值（同一個值在上面已經 UPDATE 進
                    // accounts 了），所以改用一次到位的 setCSPoints。
                    case "ACash" -> {
                        chr.setCSPoints(1, (Integer) e.getValue());
                        applied.add("ACash");
                    }
                    case "mPoints" -> {
                        chr.setCSPoints(2, (Integer) e.getValue());
                        applied.add("mPoints");
                    }
                    case "points" -> {
                        chr.setPoints((Integer) e.getValue());
                        applied.add("points");
                    }
                    case "vpoints" -> {
                        chr.setVPoints((Integer) e.getValue());
                        applied.add("vpoints");
                    }
                    default -> deferred.add(e.getKey());
                }
            }
            if (values.containsKey("banned") && (Integer) values.get("banned") > 0) {
                kicked = kick(chr);
            } else if (values.containsKey("tempban") && !TEMPBAN_NONE.equals(values.get("tempban"))) {
                kicked = kick(chr);
            }
        }
        return Json.obj(
                "id", id,
                "saved", Json.obj("ok", true, "columns", new ArrayList<>(values.keySet())),
                "applied", Json.obj("ok", o == null || deferred.isEmpty(), "online", o != null,
                        "live", applied, "nextLogin", deferred),
                "kicked", kicked);
    }

    private static Map<String, Object> kickAccount(final int id) {
        final Online o = new OnlineIndex().byAccount.get(id);
        if (o == null) {
            throw new Router.HttpError(409, "這個帳號目前沒有角色在線上");
        }
        return Json.obj("ok", kick(o.chr()), "character", o.chr().getName(), "channel", o.channel());
    }

    /* ------------------------------------------------------------------ */
    /* Characters                                                           */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> listCharacters(final String q, final int page, final int size) throws SQLException {
        final StringBuilder where = new StringBuilder(" WHERE 1=1");
        final List<Object> args = new ArrayList<>();
        if (!q.isBlank()) {
            where.append(" AND (name LIKE ? OR id = ? OR accountid = ?)");
            args.add("%" + q.trim() + "%");
            final int n = q.trim().matches("\\d+") ? Integer.parseInt(q.trim()) : -1;
            args.add(n);
            args.add(n);
        }
        final OnlineIndex online = new OnlineIndex();
        try (Connection con = DatabaseConnection.getConnection()) {
            final int total;
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM characters" + where)) {
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            final List<Object> items = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT " + CHARACTER_COLUMNS + " FROM characters" + where + " ORDER BY id LIMIT ? OFFSET ?")) {
                bind(ps, args);
                ps.setInt(args.size() + 1, size);
                ps.setInt(args.size() + 2, (page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(characterRow(rs, online));
                    }
                }
            }
            return Json.obj("items", items, "total", total, "page", page, "size", size);
        }
    }

    private static Map<String, Object> character(final int id) throws SQLException {
        final OnlineIndex online = new OnlineIndex();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT " + CHARACTER_COLUMNS + " FROM characters WHERE id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new Router.HttpError(404, "找不到這個角色");
                }
                final Map<String, Object> row = characterRow(rs, online);
                row.put("editable", new ArrayList<>(CHARACTER_EDITABLE.keySet()));
                return row;
            }
        }
    }

    private static Map<String, Object> characterRow(final ResultSet rs, final OnlineIndex online) throws SQLException {
        final int id = rs.getInt("id");
        final Online o = online.byCharacter.get(id);
        final int job = rs.getInt("job");
        return Json.obj(
                "id", id,
                "accountId", rs.getInt("accountid"),
                "name", rs.getString("name"),
                "level", rs.getInt("level"),
                "exp", rs.getInt("exp"),
                "job", job,
                "jobName", MapleCarnivalChallenge.getJobNameById(job),
                "str", rs.getInt("str"),
                "dex", rs.getInt("dex"),
                "int", rs.getInt("int"),
                "luk", rs.getInt("luk"),
                "hp", rs.getInt("hp"),
                "mp", rs.getInt("mp"),
                "maxhp", rs.getInt("maxhp"),
                "maxmp", rs.getInt("maxmp"),
                "meso", rs.getInt("meso"),
                "fame", rs.getInt("fame"),
                "ap", rs.getInt("ap"),
                "sp", rs.getString("sp"),
                "map", rs.getInt("map"),
                "spawnpoint", rs.getInt("spawnpoint"),
                "gm", rs.getInt("gm"),
                "hair", rs.getInt("hair"),
                "face", rs.getInt("face"),
                "skincolor", rs.getInt("skincolor"),
                "gender", rs.getInt("gender"),
                "party", rs.getInt("party"),
                "guildid", rs.getInt("guildid"),
                "guildrank", rs.getInt("guildrank"),
                "familyid", rs.getInt("familyid"),
                "marriageId", rs.getInt("marriageId"),
                "beans", rs.getInt("beans"),
                "gachexp", rs.getInt("gachexp"),
                "createdate", millis(rs, "createdate"),
                "online", o != null,
                "onlineChannel", o == null ? null : o.channel(),
                "onlineMap", o == null ? null : o.chr().getMapId());
    }

    /**
     * Refused outright for an online character: the in-memory object is the
     * truth and would overwrite this write on its next save. The 409 carries
     * the channel so the UI can offer the kick.
     */
    private static Map<String, Object> updateCharacter(final int id, final Map<String, Object> body) throws SQLException {
        final Online o = new OnlineIndex().byCharacter.get(id);
        if (o != null) {
            throw new Router.HttpError(409, "角色「" + o.chr().getName() + "」正在頻道 " + o.channel()
                    + " 線上；線上角色的資料庫寫入會被下一次自動存檔蓋掉。請先踢下線再編輯。",
                    Json.obj("online", true, "channel", o.channel()));
        }
        final Map<String, Object> values = new LinkedHashMap<>();
        for (final Map.Entry<String, Boolean> e : CHARACTER_EDITABLE.entrySet()) {
            if (!body.containsKey(e.getKey())) {
                continue;
            }
            final Object v = body.get(e.getKey());
            if (e.getValue()) {
                values.put(e.getKey(), toInt(e.getKey(), v));
            } else {
                final String s = v == null ? "" : String.valueOf(v);
                if (!s.matches("(-?\\d+)(,-?\\d+)*")) {
                    throw new Router.HttpError(400, "sp 必須是逗號分隔的整數");
                }
                values.put(e.getKey(), s);
            }
        }
        if (values.isEmpty()) {
            throw new Router.HttpError(400, "沒有可寫入的欄位，可用：" + String.join(", ", CHARACTER_EDITABLE.keySet()));
        }
        final StringBuilder sql = new StringBuilder("UPDATE characters SET ");
        boolean first = true;
        for (final String col : values.keySet()) {
            sql.append(first ? "" : ", ").append('`').append(col).append("` = ?");
            first = false;
        }
        sql.append(" WHERE id = ?");
        final int rows;
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int i = 1;
            for (final Object v : values.values()) {
                ps.setObject(i++, v);
            }
            ps.setInt(i, id);
            rows = ps.executeUpdate();
        }
        if (rows == 0) {
            throw new Router.HttpError(404, "找不到這個角色");
        }
        // Raced: someone logged in between the check and the write. Say so
        // rather than pretend the row will stick.
        final Online now = new OnlineIndex().byCharacter.get(id);
        return Json.obj(
                "id", id,
                "saved", Json.obj("ok", true, "columns", new ArrayList<>(values.keySet())),
                "applied", Json.obj("ok", now == null, "online", now != null,
                        "error", now == null ? null : "角色在寫入期間登入了，這次的變更會被自動存檔蓋掉"));
    }

    private static Map<String, Object> kickCharacter(final int id) {
        final Online o = new OnlineIndex().byCharacter.get(id);
        if (o == null) {
            throw new Router.HttpError(409, "這個角色不在線上");
        }
        return Json.obj("ok", kick(o.chr()), "character", o.chr().getName(), "channel", o.channel());
    }

    /** Closes the session; MINA's sessionClosed does the full disconnect on its own thread. */
    private static boolean kick(final MapleCharacter chr) {
        final MapleClient c = chr.getClient();
        if (c == null || c.getSession() == null) {
            return false;
        }
        c.getSession().close();
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* IP / MAC bans                                                        */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> bans(final String table, final String idCol, final String valueCol) throws SQLException {
        final List<Object> items = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT " + idCol + ", " + valueCol + " FROM " + table + " ORDER BY " + idCol);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                items.add(Json.obj("id", rs.getInt(1), "value", rs.getString(2)));
            }
        }
        return Json.obj("items", items,
                // MEMORY engine: gone the next time MySQL restarts, and the UI has to say so.
                "volatile", table.equals("macbans"));
    }

    private static Map<String, Object> addBan(final String table, final String valueCol, final String raw) throws SQLException {
        if (raw == null || raw.isBlank()) {
            throw new Router.HttpError(400, "缺少 " + valueCol);
        }
        String value = raw.trim();
        if (valueCol.equals("ip")) {
            // Stored the way MINA renders a remote address, "/1.2.3.4", and matched
            // with LIKE CONCAT(ip, '%') against "/1.2.3.4:port" at login.
            if (!value.startsWith("/")) {
                value = "/" + value;
            }
            if (!value.matches("/[0-9.]+")) {
                throw new Router.HttpError(400, "IP 格式不對（例如 1.2.3.4，可以只寫前綴 1.2.）");
            }
        } else {
            value = value.toUpperCase();
            if (!value.matches("[0-9A-F]{2}(-[0-9A-F]{2}){5}")) {
                throw new Router.HttpError(400, "MAC 格式是 00-11-22-33-44-55");
            }
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO " + table + " VALUES (DEFAULT, ?)",
                     DatabaseConnection.RETURN_GENERATED_KEYS)) {
            ps.setString(1, value);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return Json.obj("ok", true, "id", keys.getInt(1), "value", value);
            }
        }
    }

    private static Map<String, Object> removeBan(final String table, final String idCol, final int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE " + idCol + " = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() == 0) {
                throw new Router.HttpError(404, "沒有這筆封鎖");
            }
        }
        return Json.obj("ok", true, "id", id);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                              */
    /* ------------------------------------------------------------------ */

    private static void bind(final PreparedStatement ps, final List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }

    private static Long millis(final ResultSet rs, final String col) throws SQLException {
        final String raw = rs.getString(col);
        if (raw == null || raw.startsWith("0000-00-00")) {
            return null;
        }
        final Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.getTime();
    }

    private static String zeroToNull(final String s) {
        return s == null || s.startsWith("0000-00-00") ? null : s;
    }

    private static int toInt(final String field, final Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (final NumberFormatException e) {
            throw new Router.HttpError(400, field + " 必須是整數");
        }
    }

    /** Accepts {@code yyyy-MM-dd HH:mm[:ss]} or ISO {@code yyyy-MM-ddTHH:mm[:ss]}, local time. */
    private static String parseDateTime(final String s) {
        final String t = s.trim().replace(' ', 'T');
        try {
            final LocalDateTime dt = LocalDateTime.parse(t.length() == 16 ? t + ":00" : t);
            return Timestamp.valueOf(dt).toString();
        } catch (final DateTimeParseException e) {
            throw new Router.HttpError(400, "tempban 格式是 yyyy-MM-dd HH:mm（本機時間），留空表示解除");
        }
    }
}
