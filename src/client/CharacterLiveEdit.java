package client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import server.maps.MapleMap;
import tools.packet.CharacterPacket;

/**
 * Puts the panel's character edits into an <em>online</em> character.
 *
 * <p>The panel used to refuse to edit an online character at all: the
 * character lives in memory, the {@code characters} row is a stale copy, and
 * the next save would have written the old values straight back over the
 * edit. This changes the live object instead - through the same setters and
 * stat packets the game's own code and GM commands use - so the player sees
 * the change at once and the next save writes the new values. The panel still
 * writes the row as well, so the database is right even if the server goes
 * down before that save.
 *
 * <p>It lives in {@code client} because half the fields have no public setter
 * - {@code CharacterRepository} writes them directly too.
 *
 * <p>Threading: this runs on a {@code Panel-Worker-N} thread, not on the
 * player's own handler thread. That is the same position a GM command aimed
 * at another player is in ({@code !warphere}, {@code !level} on someone else),
 * and it takes the same care: meso and exp go through the locked setters, and
 * everything else is a single field write followed by a packet.
 */
public final class CharacterLiveEdit {

    /** What happened to each column. */
    public record Result(List<String> live, List<String> nextLogin, Map<String, String> failed) {
    }

    private CharacterLiveEdit() {
    }

    /**
     * Applies {@code values} - column name to value, integers already parsed,
     * {@code sp} as its comma-separated string - to {@code chr}.
     *
     * @return which columns took effect now, which are stored but only show
     * after the player logs in again, and which failed (and why). A failed
     * column must not be written to the database either.
     */
    public static Result apply(final MapleCharacter chr, final Map<String, Object> values) {
        final List<String> live = new ArrayList<>();
        final List<String> nextLogin = new ArrayList<>();
        final Map<String, String> failed = new LinkedHashMap<>();
        boolean recalc = false;
        boolean looks = false;

        for (final Map.Entry<String, Object> e : values.entrySet()) {
            final String col = e.getKey();
            try {
                switch (col) {
                    case "level" -> {
                        final int v = (Integer) e.getValue();
                        chr.level = (short) v;
                        chr.updateSingleStat(MapleStat.LEVEL, v);
                        recalc = true;
                    }
                    case "exp" -> {
                        final int v = (Integer) e.getValue();
                        chr.setExp(v);
                        chr.updateSingleStat(MapleStat.EXP, v);
                    }
                    case "str" -> {
                        chr.getStat().setStr(asShort(e.getValue()));
                        chr.updateSingleStat(MapleStat.STR, chr.getStat().getStr());
                    }
                    case "dex" -> {
                        chr.getStat().setDex(asShort(e.getValue()));
                        chr.updateSingleStat(MapleStat.DEX, chr.getStat().getDex());
                    }
                    case "luk" -> {
                        chr.getStat().setLuk(asShort(e.getValue()));
                        chr.updateSingleStat(MapleStat.LUK, chr.getStat().getLuk());
                    }
                    case "int" -> {
                        chr.getStat().setInt(asShort(e.getValue()));
                        chr.updateSingleStat(MapleStat.INT, chr.getStat().getInt());
                    }
                    case "maxhp" -> {
                        chr.getStat().setMaxHp(asShort(e.getValue()));
                        chr.updateSingleStat(MapleStat.MAXHP, chr.getStat().getMaxHp());
                    }
                    case "maxmp" -> {
                        chr.getStat().setMaxMp(asShort(e.getValue()));
                        chr.updateSingleStat(MapleStat.MAXMP, chr.getStat().getMaxMp());
                    }
                    // HP / MP after the maxima, so a raised maximum is already in
                    // place when the current value is clamped against it.
                    case "hp", "mp" -> {
                        // handled below
                    }
                    case "meso" -> chr.setMeso((Integer) e.getValue());
                    case "job" -> chr.changeJob((Integer) e.getValue());
                    case "fame" -> {
                        final short v = asShort(e.getValue());
                        chr.setFame(v);
                        chr.updateSingleStat(MapleStat.FAME, v);
                    }
                    case "ap" -> {
                        final short v = asShort(e.getValue());
                        chr.setRemainingAp(v);
                        chr.updateSingleStat(MapleStat.AVAILABLEAP, v);
                    }
                    case "sp" -> {
                        final String[] parts = String.valueOf(e.getValue()).split(",");
                        for (int i = 0; i < chr.remainingSp.length; i++) {
                            chr.remainingSp[i] = i < parts.length ? Integer.parseInt(parts[i].trim()) : 0;
                        }
                        chr.getClient().send(CharacterPacket.updateSp(chr, false));
                    }
                    case "gm" -> chr.setGMLevel((Integer) e.getValue());
                    case "hair" -> {
                        final int v = (Integer) e.getValue();
                        chr.setHair(v);
                        chr.updateSingleStat(MapleStat.HAIR, v);
                        looks = true;
                    }
                    case "face" -> {
                        final int v = (Integer) e.getValue();
                        chr.setFace(v);
                        chr.updateSingleStat(MapleStat.FACE, v);
                        looks = true;
                    }
                    case "skincolor" -> {
                        final int v = (Integer) e.getValue();
                        chr.setSkinColor((byte) v);
                        chr.updateSingleStat(MapleStat.SKIN, v);
                        looks = true;
                    }
                    case "gender" -> {
                        // There is no stat packet for gender in v113; the client
                        // only reads it when the character is loaded.
                        chr.setGender((byte) (int) (Integer) e.getValue());
                        nextLogin.add(col);
                        continue;
                    }
                    case "beans" -> chr.setBeans((Integer) e.getValue());
                    case "map", "spawnpoint" -> {
                        // handled below, together
                    }
                    default -> {
                        failed.put(col, "這個欄位沒有即時路徑");
                        continue;
                    }
                }
                if (!col.equals("hp") && !col.equals("mp") && !col.equals("map") && !col.equals("spawnpoint")) {
                    live.add(col);
                }
            } catch (final RuntimeException ex) {
                failed.put(col, String.valueOf(ex));
            }
        }

        if (recalc) {
            chr.getStat().recalcLocalStats();
        }
        for (final String col : new String[]{"hp", "mp"}) {
            if (!values.containsKey(col)) {
                continue;
            }
            try {
                final int v = (Integer) values.get(col);
                if (col.equals("hp")) {
                    chr.getStat().setHp(v);
                    chr.updateSingleStat(MapleStat.HP, chr.getStat().getHp());
                } else {
                    chr.getStat().setMp(v);
                    chr.updateSingleStat(MapleStat.MP, chr.getStat().getMp());
                }
                live.add(col);
            } catch (final RuntimeException ex) {
                failed.put(col, String.valueOf(ex));
            }
        }
        if (looks) {
            chr.equipChanged();
        }
        if (values.containsKey("map") || values.containsKey("spawnpoint")) {
            warp(chr, values, live, failed);
        }
        return new Result(live, nextLogin, failed);
    }

    /**
     * {@code map} and {@code spawnpoint} on an online character can only mean
     * "move them there" - the row's values are where they will appear on the
     * next login, and the next save recomputes both from where they stand.
     */
    private static void warp(final MapleCharacter chr, final Map<String, Object> values, final List<String> live,
            final Map<String, String> failed) {
        final int mapId = values.containsKey("map") ? (Integer) values.get("map") : chr.getMapId();
        final int portal = values.containsKey("spawnpoint") ? (Integer) values.get("spawnpoint") : 0;
        try {
            final MapleMap target = chr.getClient().getChannelServer().getMapFactory().getMap(mapId);
            if (target == null) {
                failMap(values, failed, "地圖 " + mapId + " 不存在");
                return;
            }
            chr.changeMap(target, target.getPortal(portal) != null ? target.getPortal(portal) : target.getPortal(0));
            if (values.containsKey("map")) {
                live.add("map");
            }
            if (values.containsKey("spawnpoint")) {
                live.add("spawnpoint");
            }
        } catch (final RuntimeException ex) {
            failMap(values, failed, String.valueOf(ex));
        }
    }

    private static void failMap(final Map<String, Object> values, final Map<String, String> failed, final String why) {
        if (values.containsKey("map")) {
            failed.put("map", why);
        }
        if (values.containsKey("spawnpoint")) {
            failed.put("spawnpoint", why);
        }
    }

    private static short asShort(final Object v) {
        final int n = (Integer) v;
        if (n < Short.MIN_VALUE || n > Short.MAX_VALUE) {
            throw new IllegalArgumentException("超出範圍 " + Short.MIN_VALUE + "～" + Short.MAX_VALUE);
        }
        return (short) n;
    }
}
