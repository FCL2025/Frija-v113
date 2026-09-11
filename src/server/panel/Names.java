package server.panel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import server.MapleItemInformationProvider;
import tools.Pair;

/**
 * Item, monster and NPC names for the data editors, and a substring search
 * over them.
 *
 * <p>Items come from {@code MapleItemInformationProvider.getAllItems()},
 * which the game already caches. Monsters and NPCs are read once from
 * {@code String.wz/Mob.img} and {@code Npc.img} - the same files
 * {@code MapleLifeFactory} reads, but that class only exposes them one id at
 * a time and through objects far heavier than a name. Everything here is a
 * few thousand strings, built lazily on the first request and kept.
 */
final class Names {

    private static volatile Map<Integer, String> items;
    private static volatile Map<Integer, String> mobs;
    private static volatile Map<Integer, String> npcs;

    private Names() {
    }

    static Map<Integer, String> items() {
        Map<Integer, String> m = items;
        if (m == null) {
            synchronized (Names.class) {
                m = items;
                if (m == null) {
                    final Map<Integer, String> fresh = new LinkedHashMap<>();
                    for (final Pair<Integer, String> p : MapleItemInformationProvider.getInstance().getAllItems()) {
                        fresh.put(p.getLeft(), p.getRight());
                    }
                    items = m = Collections.unmodifiableMap(fresh);
                }
            }
        }
        return m;
    }

    static Map<Integer, String> mobs() {
        Map<Integer, String> m = mobs;
        if (m == null) {
            synchronized (Names.class) {
                m = mobs;
                if (m == null) {
                    mobs = m = readStrings("Mob.img");
                }
            }
        }
        return m;
    }

    static Map<Integer, String> npcs() {
        Map<Integer, String> m = npcs;
        if (m == null) {
            synchronized (Names.class) {
                m = npcs;
                if (m == null) {
                    npcs = m = readStrings("Npc.img");
                }
            }
        }
        return m;
    }

    private static Map<Integer, String> readStrings(final String img) {
        final Map<Integer, String> out = new LinkedHashMap<>();
        final MapleDataProvider wz = MapleDataProviderFactory.getDataProvider(
                new File(System.getProperty("net.sf.odinms.wzpath") + "/String.wz"));
        final MapleData root = wz.getData(img);
        if (root != null) {
            for (final MapleData child : root.getChildren()) {
                try {
                    out.put(Integer.parseInt(child.getName()), MapleDataTool.getString("name", child, ""));
                } catch (final NumberFormatException e) {
                    // Not an id-keyed entry.
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    static String item(final int id) {
        final String n = items().get(id);
        return n == null ? MapleItemInformationProvider.getInstance().getName(id) : n;
    }

    static String mob(final int id) {
        return mobs().get(id);
    }

    static String npc(final int id) {
        return npcs().get(id);
    }

    /** Case-insensitive substring match on the name, or an exact id. */
    static List<Object> search(final Map<Integer, String> names, final String q, final int limit) {
        final List<Object> out = new ArrayList<>();
        final String needle = q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return out;
        }
        if (needle.matches("\\d+")) {
            final int id = Integer.parseInt(needle);
            final String n = names.get(id);
            if (n != null) {
                out.add(Json.obj("id", id, "name", n));
            }
            // Also prefix matches on the id, which is how people remember them.
            for (final Map.Entry<Integer, String> e : names.entrySet()) {
                if (out.size() >= limit) {
                    break;
                }
                if (e.getKey() != id && String.valueOf(e.getKey()).startsWith(needle)) {
                    out.add(Json.obj("id", e.getKey(), "name", e.getValue()));
                }
            }
            return out;
        }
        for (final Map.Entry<Integer, String> e : names.entrySet()) {
            if (out.size() >= limit) {
                break;
            }
            if (e.getValue() != null && e.getValue().toLowerCase(Locale.ROOT).contains(needle)) {
                out.add(Json.obj("id", e.getKey(), "name", e.getValue()));
            }
        }
        return out;
    }
}
