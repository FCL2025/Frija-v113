package server.panel;

import database.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import server.CashItemFactory;
import server.CashItemInfo;
import server.MapleShopFactory;
import server.life.MapleMonsterInformationProvider;

/**
 * Editors for the game-data tables that used to be hand-written SQL:
 * {@code drop_data}, {@code drop_data_global}, {@code shops} +
 * {@code shopitems}, and {@code cashshop_modified_items}.
 *
 * <p>Each table has an in-memory cache in the game that a write here would
 * otherwise leave stale, so every write ends by clearing the matching one:
 * {@code MapleMonsterInformationProvider.clearDrops()} (per-monster drop
 * lists and the global list), {@code MapleShopFactory.clear()} (shops are
 * re-read on the next open), and {@code CashItemFactory.reloadModInfo()},
 * which was added for this - the cash shop's modified-item map was only ever
 * loaded at startup.
 *
 * <p>Names come from {@link Names}, i.e. from caches the game already keeps;
 * nothing here opens a WZ file per row. Every route uses one connection for
 * its whole duration, and the paged lists are bounded, because the cash shop
 * table has 6.6k rows and the drop table 39k.
 */
final class DataApi {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;
    /** shopitems.position starts at 104 and steps by 4 - the gap leaves room to insert. */
    private static final int POSITION_FIRST = 104;
    private static final int POSITION_STEP = 4;

    private DataApi() {
    }

    static void register(final Router r) {
        r.get("/api/names/items", req -> Names.search(Names.items(), req.query("q", ""), req.queryInt("limit", 30, 1, 200)));
        r.get("/api/names/mobs", req -> Names.search(Names.mobs(), req.query("q", ""), req.queryInt("limit", 30, 1, 200)));
        r.get("/api/names/npcs", req -> Names.search(Names.npcs(), req.query("q", ""), req.queryInt("limit", 30, 1, 200)));

        r.get("/api/drops", req -> drops(req.queryInt("mob", 0, 0, Integer.MAX_VALUE), req.queryInt("item", 0, 0, Integer.MAX_VALUE),
                req.queryInt("page", 1, 1, 1_000_000), req.queryInt("size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE)));
        r.post("/api/drops", req -> insertDrop(req.bodyObject()));
        r.put("/api/drops/{id}", req -> updateDrop(id(req.param("id")), req.bodyObject()));
        r.delete("/api/drops/{id}", req -> deleteRow("drop_data", "id", id(req.param("id")), DataApi::reloadDrops));

        r.get("/api/drops/global", req -> globalDrops());
        r.post("/api/drops/global", req -> insertGlobalDrop(req.bodyObject()));
        r.put("/api/drops/global/{id}", req -> updateGlobalDrop(id(req.param("id")), req.bodyObject()));
        r.delete("/api/drops/global/{id}", req -> deleteRow("drop_data_global", "id", id(req.param("id")), DataApi::reloadDrops));

        r.get("/api/shops", req -> shops(req.query("q", "")));
        r.get("/api/shops/{shopid}", req -> shop(id(req.param("shopid"))));
        r.post("/api/shops", req -> insertShop(req.bodyObject()));
        r.delete("/api/shops/{shopid}", req -> deleteShop(id(req.param("shopid"))));
        r.post("/api/shops/{shopid}/items", req -> insertShopItem(id(req.param("shopid")), req.bodyObject()));
        r.post("/api/shops/{shopid}/reorder", req -> reorderShop(id(req.param("shopid")), req.bodyObject()));
        r.put("/api/shopitems/{id}", req -> updateShopItem(id(req.param("id")), req.bodyObject()));
        r.delete("/api/shopitems/{id}", req -> deleteRow("shopitems", "shopitemid", id(req.param("id")), DataApi::reloadShops));

        r.get("/api/cashshop", req -> cashItems(req.query("q", ""), req.query("filter", ""),
                req.queryInt("page", 1, 1, 1_000_000), req.queryInt("size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE)));
        r.post("/api/cashshop", req -> insertCashItem(req.bodyObject()));
        r.put("/api/cashshop/{serial}", req -> updateCashItem(id(req.param("serial")), req.bodyObject()));
        r.delete("/api/cashshop/{serial}", req -> deleteRow("cashshop_modified_items", "serial", id(req.param("serial")), DataApi::reloadCash));
    }

    private static int id(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            throw new Router.HttpError(400, "id 必須是整數");
        }
    }

    private static void reloadDrops() {
        MapleMonsterInformationProvider.getInstance().clearDrops();
    }

    private static void reloadShops() {
        MapleShopFactory.getInstance().clear();
    }

    private static void reloadCash() {
        CashItemFactory.getInstance().reloadModInfo();
    }

    /* ------------------------------------------------------------------ */
    /* drop_data                                                            */
    /* ------------------------------------------------------------------ */

    private static final List<String> DROP_COLUMNS = List.of("dropperid", "itemid", "minimum_quantity", "maximum_quantity", "questid", "chance");

    private static Map<String, Object> drops(final int mob, final int item, final int page, final int size) throws SQLException {
        final StringBuilder where = new StringBuilder(" WHERE 1=1");
        final List<Object> args = new ArrayList<>();
        if (mob > 0) {
            where.append(" AND dropperid = ?");
            args.add(mob);
        }
        if (item > 0) {
            where.append(" AND itemid = ?");
            args.add(item);
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            final int total = count(con, "drop_data", where.toString(), args);
            final List<Object> items = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM drop_data" + where + " ORDER BY dropperid, id LIMIT ? OFFSET ?")) {
                bind(ps, args);
                ps.setInt(args.size() + 1, size);
                ps.setInt(args.size() + 2, (page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(dropRow(rs));
                    }
                }
            }
            return Json.obj("items", items, "total", total, "page", page, "size", size,
                    "mob", mob > 0 ? Json.obj("id", mob, "name", Names.mob(mob)) : null,
                    "item", item > 0 ? Json.obj("id", item, "name", Names.item(item)) : null);
        }
    }

    private static Map<String, Object> dropRow(final ResultSet rs) throws SQLException {
        final int mob = rs.getInt("dropperid");
        final int item = rs.getInt("itemid");
        return Json.obj(
                "id", rs.getLong("id"),
                "dropperid", mob,
                "mobName", Names.mob(mob),
                "itemid", item,
                "itemName", Names.item(item),
                "minimum_quantity", rs.getInt("minimum_quantity"),
                "maximum_quantity", rs.getInt("maximum_quantity"),
                "questid", rs.getInt("questid"),
                "chance", rs.getInt("chance"));
    }

    private static Map<String, Object> insertDrop(final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, DROP_COLUMNS, true);
        validateDrop(v);
        final long id = insert("drop_data", v);
        reloadDrops();
        return Json.obj("ok", true, "id", id, "reloaded", "drops");
    }

    private static Map<String, Object> updateDrop(final int id, final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, DROP_COLUMNS, false);
        validateDrop(v);
        update("drop_data", "id", id, v);
        reloadDrops();
        return Json.obj("ok", true, "id", id, "reloaded", "drops");
    }

    private static void validateDrop(final Map<String, Object> v) {
        if (v.containsKey("chance") && ((Integer) v.get("chance") < 0 || (Integer) v.get("chance") > 999_999)) {
            throw new Router.HttpError(400, "chance 範圍 0–999999（999999 = 必掉）");
        }
        if (v.containsKey("minimum_quantity") && v.containsKey("maximum_quantity")
                && (Integer) v.get("minimum_quantity") > (Integer) v.get("maximum_quantity")) {
            throw new Router.HttpError(400, "最小數量不可大於最大數量");
        }
    }

    /* ------------------------------------------------------------------ */
    /* drop_data_global                                                     */
    /* ------------------------------------------------------------------ */

    private static final List<String> GLOBAL_INT_COLUMNS = List.of("continent", "dropType", "itemid", "minimum_quantity", "maximum_quantity", "questid", "chance");

    private static List<Object> globalDrops() throws SQLException {
        final List<Object> out = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM drop_data_global ORDER BY id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final int item = rs.getInt("itemid");
                out.add(Json.obj(
                        "id", rs.getLong("id"),
                        "continent", rs.getInt("continent"),
                        "dropType", rs.getInt("dropType"),
                        "itemid", item,
                        "itemName", Names.item(item),
                        "minimum_quantity", rs.getInt("minimum_quantity"),
                        "maximum_quantity", rs.getInt("maximum_quantity"),
                        "questid", rs.getInt("questid"),
                        "chance", rs.getInt("chance"),
                        "comments", rs.getString("comments")));
            }
        }
        return out;
    }

    private static Map<String, Object> insertGlobalDrop(final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, GLOBAL_INT_COLUMNS, true);
        v.put("comments", body.get("comments") == null ? null : String.valueOf(body.get("comments")));
        validateDrop(v);
        final long id = insert("drop_data_global", v);
        reloadDrops();
        return Json.obj("ok", true, "id", id, "reloaded", "drops");
    }

    private static Map<String, Object> updateGlobalDrop(final int id, final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, GLOBAL_INT_COLUMNS, false);
        if (body.containsKey("comments")) {
            v.put("comments", body.get("comments") == null ? null : String.valueOf(body.get("comments")));
        }
        validateDrop(v);
        update("drop_data_global", "id", id, v);
        reloadDrops();
        return Json.obj("ok", true, "id", id, "reloaded", "drops");
    }

    /* ------------------------------------------------------------------ */
    /* shops / shopitems                                                    */
    /* ------------------------------------------------------------------ */

    private static List<Object> shops(final String q) throws SQLException {
        final List<Object> out = new ArrayList<>();
        final String needle = q.trim().toLowerCase();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT s.shopid, s.npcid, COUNT(i.shopitemid) AS items FROM shops s LEFT JOIN shopitems i ON i.shopid = s.shopid "
                     + "GROUP BY s.shopid, s.npcid ORDER BY s.shopid");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final int npc = rs.getInt("npcid");
                final String name = Names.npc(npc);
                if (!needle.isEmpty() && !String.valueOf(rs.getInt("shopid")).startsWith(needle)
                        && !String.valueOf(npc).startsWith(needle)
                        && (name == null || !name.toLowerCase().contains(needle))) {
                    continue;
                }
                out.add(Json.obj("shopid", rs.getInt("shopid"), "npcid", npc, "npcName", name, "items", rs.getInt("items")));
            }
        }
        return out;
    }

    private static Map<String, Object> shop(final int shopId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            final int npc;
            try (PreparedStatement ps = con.prepareStatement("SELECT npcid FROM shops WHERE shopid = ?")) {
                ps.setInt(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new Router.HttpError(404, "找不到這個商店");
                    }
                    npc = rs.getInt(1);
                }
            }
            final List<Object> items = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM shopitems WHERE shopid = ? ORDER BY position, shopitemid")) {
                ps.setInt(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final int item = rs.getInt("itemid");
                        final int req = rs.getInt("reqitem");
                        items.add(Json.obj(
                                "shopitemid", rs.getInt("shopitemid"),
                                "itemid", item,
                                "itemName", Names.item(item),
                                "price", rs.getInt("price"),
                                "pitch", rs.getInt("pitch"),
                                "position", rs.getInt("position"),
                                "reqitem", req,
                                "reqitemName", req > 0 ? Names.item(req) : null,
                                "reqitemq", rs.getInt("reqitemq")));
                    }
                }
            }
            return Json.obj("shopid", shopId, "npcid", npc, "npcName", Names.npc(npc), "items", items);
        }
    }

    private static Map<String, Object> insertShop(final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, List.of("npcid"), true);
        final long id = insert("shops", v);
        reloadShops();
        return Json.obj("ok", true, "shopid", id, "reloaded", "shops");
    }

    private static Map<String, Object> deleteShop(final int shopId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM shopitems WHERE shopid = ?")) {
                ps.setInt(1, shopId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM shops WHERE shopid = ?")) {
                ps.setInt(1, shopId);
                if (ps.executeUpdate() == 0) {
                    throw new Router.HttpError(404, "找不到這個商店");
                }
            }
        }
        reloadShops();
        return Json.obj("ok", true, "shopid", shopId, "reloaded", "shops");
    }

    private static final List<String> SHOPITEM_COLUMNS = List.of("itemid", "price", "pitch", "position", "reqitem", "reqitemq");

    private static Map<String, Object> insertShopItem(final int shopId, final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, SHOPITEM_COLUMNS, false);
        if (!v.containsKey("itemid")) {
            throw new Router.HttpError(400, "缺少 itemid");
        }
        v.putIfAbsent("price", 0);
        v.putIfAbsent("pitch", 0);
        v.putIfAbsent("reqitem", 0);
        v.putIfAbsent("reqitemq", 0);
        v.put("shopid", shopId);
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("SELECT 1 FROM shops WHERE shopid = ?")) {
                ps.setInt(1, shopId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new Router.HttpError(404, "找不到這個商店");
                    }
                }
            }
            if (!v.containsKey("position")) {
                // Append: last position + 4, or 104 for an empty shop.
                try (PreparedStatement ps = con.prepareStatement("SELECT MAX(position) FROM shopitems WHERE shopid = ?")) {
                    ps.setInt(1, shopId);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        final int max = rs.getInt(1);
                        v.put("position", rs.wasNull() ? POSITION_FIRST : max + POSITION_STEP);
                    }
                }
            }
            final long id = insert(con, "shopitems", v);
            reloadShops();
            return Json.obj("ok", true, "shopitemid", id, "position", v.get("position"), "reloaded", "shops");
        }
    }

    private static Map<String, Object> updateShopItem(final int id, final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, SHOPITEM_COLUMNS, false);
        update("shopitems", "shopitemid", id, v);
        reloadShops();
        return Json.obj("ok", true, "shopitemid", id, "reloaded", "shops");
    }

    /** Renumbers the given shopitemids 104, 108, 112 … in the order given. */
    private static Map<String, Object> reorderShop(final int shopId, final Map<String, Object> body) throws SQLException {
        final Object raw = body.get("ids");
        if (!(raw instanceof List<?> ids) || ids.isEmpty()) {
            throw new Router.HttpError(400, "ids 必須是 shopitemid 陣列");
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE shopitems SET position = ? WHERE shopitemid = ? AND shopid = ?")) {
            int pos = POSITION_FIRST;
            for (final Object o : ids) {
                if (!(o instanceof Number n)) {
                    throw new Router.HttpError(400, "ids 必須是整數陣列");
                }
                ps.setInt(1, pos);
                ps.setInt(2, n.intValue());
                ps.setInt(3, shopId);
                ps.addBatch();
                pos += POSITION_STEP;
            }
            ps.executeBatch();
        }
        reloadShops();
        return Json.obj("ok", true, "shopid", shopId, "count", ids.size(), "reloaded", "shops");
    }

    /* ------------------------------------------------------------------ */
    /* cashshop_modified_items                                              */
    /* ------------------------------------------------------------------ */

    private static final List<String> CASH_INT_COLUMNS = List.of("serial", "discount_price", "mark", "showup", "itemid", "priority",
            "package", "period", "gender", "count", "meso", "unk_1", "unk_2", "unk_3", "extra_flags");

    private static Map<String, Object> cashItems(final String q, final String filter, final int page, final int size) throws SQLException {
        final StringBuilder where = new StringBuilder(" WHERE 1=1");
        final List<Object> args = new ArrayList<>();
        if (!q.isBlank()) {
            where.append(" AND (name LIKE ? OR serial = ? OR itemid = ?)");
            args.add("%" + q.trim() + "%");
            final int n = q.trim().matches("\\d+") ? Integer.parseInt(q.trim()) : -1;
            args.add(n);
            args.add(n);
        }
        switch (filter) {
            case "showup" -> where.append(" AND showup > 0");
            case "hidden" -> where.append(" AND showup = 0");
            case "discount" -> where.append(" AND discount_price >= 0");
            default -> {
            }
        }
        try (Connection con = DatabaseConnection.getConnection()) {
            final int total = count(con, "cashshop_modified_items", where.toString(), args);
            final List<Object> items = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM cashshop_modified_items" + where + " ORDER BY serial LIMIT ? OFFSET ?")) {
                bind(ps, args);
                ps.setInt(args.size() + 1, size);
                ps.setInt(args.size() + 2, (page - 1) * size);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", rs.getString("name"));
                        for (final String c : CASH_INT_COLUMNS) {
                            row.put(c, rs.getInt(c));
                        }
                        // Rows from the dumper carry itemid 0; Commodity.img knows the real item.
                        int itemId = rs.getInt("itemid");
                        if (itemId == 0) {
                            final CashItemInfo ci = CashItemFactory.getInstance().getItem(rs.getInt("serial"));
                            if (ci != null) {
                                itemId = ci.getId();
                            }
                        }
                        row.put("resolvedItemId", itemId);
                        row.put("itemName", itemId == 0 ? null : Names.item(itemId));
                        row.put("effectivePrice", effectivePrice(rs.getInt("serial")));
                        items.add(row);
                    }
                }
            }
            return Json.obj("items", items, "total", total, "page", page, "size", size);
        }
    }

    /** What the client is actually charged now: the discount or meso price if set, else Commodity.img's. */
    private static Integer effectivePrice(final int serial) {
        final CashItemInfo ci = CashItemFactory.getInstance().getItem(serial);
        return ci == null ? null : ci.getPrice();
    }

    private static Map<String, Object> insertCashItem(final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, CASH_INT_COLUMNS, false);
        if (!v.containsKey("serial") || !v.containsKey("itemid")) {
            throw new Router.HttpError(400, "缺少 serial 或 itemid");
        }
        v.put("name", body.get("name") == null ? String.valueOf(Names.item((Integer) v.get("itemid"))) : String.valueOf(body.get("name")));
        v.putIfAbsent("discount_price", -1);
        v.putIfAbsent("mark", -1);
        v.putIfAbsent("priority", -1);
        try {
            insert("cashshop_modified_items", v);
        } catch (final SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate")) {
                throw new Router.HttpError(409, "這個 serial 已經有資料");
            }
            throw e;
        }
        reloadCash();
        return Json.obj("ok", true, "serial", v.get("serial"), "reloaded", "cashshop");
    }

    private static Map<String, Object> updateCashItem(final int serial, final Map<String, Object> body) throws SQLException {
        final Map<String, Object> v = ints(body, CASH_INT_COLUMNS, false);
        v.remove("serial");
        if (body.containsKey("name")) {
            v.put("name", String.valueOf(body.get("name")));
        }
        update("cashshop_modified_items", "serial", serial, v);
        reloadCash();
        return Json.obj("ok", true, "serial", serial, "reloaded", "cashshop");
    }

    /* ------------------------------------------------------------------ */
    /* Generic SQL helpers                                                  */
    /* ------------------------------------------------------------------ */

    /** Picks the integer columns present in the body; with {@code all}, every column is required. */
    private static Map<String, Object> ints(final Map<String, Object> body, final List<String> columns, final boolean all) {
        final Map<String, Object> v = new LinkedHashMap<>();
        for (final String c : columns) {
            if (!body.containsKey(c)) {
                if (all) {
                    throw new Router.HttpError(400, "缺少 " + c);
                }
                continue;
            }
            final Object raw = body.get(c);
            if (raw instanceof Number n) {
                v.put(c, n.intValue());
            } else {
                try {
                    v.put(c, Integer.parseInt(String.valueOf(raw).trim()));
                } catch (final NumberFormatException e) {
                    throw new Router.HttpError(400, c + " 必須是整數");
                }
            }
        }
        return v;
    }

    private static long insert(final String table, final Map<String, Object> values) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            return insert(con, table, values);
        }
    }

    private static long insert(final Connection con, final String table, final Map<String, Object> values) throws SQLException {
        final StringBuilder cols = new StringBuilder();
        final StringBuilder marks = new StringBuilder();
        for (final String c : values.keySet()) {
            cols.append(cols.length() == 0 ? "" : ", ").append('`').append(c).append('`');
            marks.append(marks.length() == 0 ? "" : ", ").append('?');
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO " + table + " (" + cols + ") VALUES (" + marks + ")",
                Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            for (final Object v : values.values()) {
                ps.setObject(i++, v);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    private static void update(final String table, final String idCol, final int id, final Map<String, Object> values) throws SQLException {
        if (values.isEmpty()) {
            throw new Router.HttpError(400, "沒有要更新的欄位");
        }
        final StringBuilder sql = new StringBuilder("UPDATE " + table + " SET ");
        boolean first = true;
        for (final String c : values.keySet()) {
            sql.append(first ? "" : ", ").append('`').append(c).append("` = ?");
            first = false;
        }
        sql.append(" WHERE ").append(idCol).append(" = ?");
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql.toString())) {
            int i = 1;
            for (final Object v : values.values()) {
                ps.setObject(i++, v);
            }
            ps.setInt(i, id);
            if (ps.executeUpdate() == 0) {
                throw new Router.HttpError(404, "找不到這筆資料");
            }
        }
    }

    private static Map<String, Object> deleteRow(final String table, final String idCol, final int id, final Runnable reload) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE " + idCol + " = ?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() == 0) {
                throw new Router.HttpError(404, "找不到這筆資料");
            }
        }
        reload.run();
        return Json.obj("ok", true, "id", id);
    }

    private static int count(final Connection con, final String table, final String where, final List<Object> args) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM " + table + where)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static void bind(final PreparedStatement ps, final List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            ps.setObject(i + 1, args.get(i));
        }
    }
}
