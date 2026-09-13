package server.quest;

import client.ISkill;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.LinkedList;

import client.inventory.IItem;
import client.SkillFactory;
import constants.GameConstants;
import client.MapleCharacter;
import client.inventory.MaplePet;
import client.inventory.MapleInventoryType;
import client.MapleQuestStatus;
import provider.MapleData;
import provider.MapleDataTool;

import tools.Pair;

public class MapleQuestRequirement {

    private MapleQuest quest;
    private MapleQuestRequirementType type;
    private int intStore;
    private String stringStore;
    private List<Pair<Integer, Integer>> dataStore;

    /**
     * Creates a new instance of MapleQuestRequirement
     */
    public MapleQuestRequirement(MapleQuest quest, MapleQuestRequirementType type, MapleData data) {
        this.type = type;
        this.quest = quest;

        switch (type) {
            case job: {
                final List<MapleData> child = data.getChildren();
                dataStore = new LinkedList<>();

                for (int i = 0; i < child.size(); i++) {
                    dataStore.add(new Pair<>(i, MapleDataTool.getInt(child.get(i), -1)));
                }
                break;
            }
            case skill: {
                final List<MapleData> child = data.getChildren();
                dataStore = new LinkedList<>();

                for (int i = 0; i < child.size(); i++) {
                    final MapleData childdata = child.get(i);
                    dataStore.add(new Pair<>(MapleDataTool.getInt(childdata.getChildByPath("id"), 0),
                            MapleDataTool.getInt(childdata.getChildByPath("acquire"), 0)));
                }
                break;
            }
            case quest: {
                final List<MapleData> child = data.getChildren();
                dataStore = new LinkedList<>();

                for (int i = 0; i < child.size(); i++) {
                    final MapleData childdata = child.get(i);
                    dataStore.add(new Pair<>(MapleDataTool.getInt(childdata.getChildByPath("id")),
                            MapleDataTool.getInt(childdata.getChildByPath("state"), 0)));
                }
                break;
            }
            case item: {
                final List<MapleData> child = data.getChildren();
                dataStore = new LinkedList<>();

                for (int i = 0; i < child.size(); i++) {
                    final MapleData childdata = child.get(i);
                    dataStore.add(new Pair<>(MapleDataTool.getInt(childdata.getChildByPath("id")),
                            MapleDataTool.getInt(childdata.getChildByPath("count"), 0)));
                }
                break;
            }
            case pettamenessmin:
            case npc:
            case questComplete:
            case pop:
            case interval:
            case mbmin:
            case lvmax:
            case lvmin: {
                intStore = MapleDataTool.getInt(data, -1);
                break;
            }
            case end: {
                stringStore = MapleDataTool.getString(data, null);
                break;
            }
            case mob: {
                final List<MapleData> child = data.getChildren();
                dataStore = new LinkedList<>();

                for (int i = 0; i < child.size(); i++) {
                    final MapleData childdata = child.get(i);
                    dataStore.add(new Pair<>(MapleDataTool.getInt(childdata.getChildByPath("id"), 0),
                            MapleDataTool.getInt(childdata.getChildByPath("count"), 0)));
                }
                break;
            }
            case fieldEnter: {
                final MapleData zeroField = data.getChildByPath("0");
                if (zeroField != null) {
                    intStore = MapleDataTool.getInt(zeroField);
                } else {
                    intStore = -1;
                }
                break;
            }
            case mbcard: {
                final List<MapleData> child = data.getChildren();
                dataStore = new LinkedList<>();

                for (int i = 0; i < child.size(); i++) {
                    final MapleData childdata = child.get(i);
                    dataStore.add(new Pair<>(MapleDataTool.getInt(childdata.getChildByPath("id"), 0),
                            MapleDataTool.getInt(childdata.getChildByPath("min"), 0)));
                }
                break;
            }
            case pet: {
                dataStore = new LinkedList<>();

                for (MapleData child : data) {
                    dataStore.add(new Pair<>(-1, MapleDataTool.getInt("id", child, 0)));
                }
                break;
            }
        }
    }

    public boolean check(MapleCharacter c, Integer npcid) {
        switch (type) {
            case job:
                for (Pair<Integer, Integer> a : dataStore) {
                    if (a.getRight() == c.getJob() || c.isGM()) {
                        return true;
                    }
                }
                return false;
            case skill: {
                for (Pair<Integer, Integer> a : dataStore) {
                    final boolean acquire = a.getRight() > 0;
                    final int skill = a.getLeft();
                    final ISkill skil = SkillFactory.getSkill(skill);
                    if (acquire) {
                        if (skil.isFourthJob()) {
                            if (c.getMasterLevel(skil) == 0) {
                                return false;
                            }
                        } else {
                            if (c.getSkillLevel(skil) == 0) {
                                return false;
                            }
                        }
                    } else {
                        if (c.getSkillLevel(skil) > 0 || c.getMasterLevel(skil) > 0) {
                            return false;
                        }
                    }
                }
                return true;
            }
            case quest:
                for (Pair<Integer, Integer> a : dataStore) {
                    final MapleQuestStatus q = c.getQuest(MapleQuest.getInstance(a.getLeft()));
                    final int state = a.getRight();
                    if (state != 0) {
                        if (q == null && state == 0) {
                            continue;
                        }
                        if (q == null || q.getStatus() != state) {
                            return false;
                        }
                    }
                }
                return true;
            case item:
                MapleInventoryType iType;
                int itemId;
                short quantity;

                for (Pair<Integer, Integer> a : dataStore) {
                    itemId = a.getLeft();
                    quantity = 0;
                    iType = GameConstants.getInventoryType(itemId);
                    for (IItem item : c.getInventory(iType).listById(itemId)) {
                        quantity += item.getQuantity();
                    }
                    final int count = a.getRight();
                    if (quantity < count || count <= 0 && quantity > 0) {
                        return false;
                    }
                }
                return true;
            case lvmin:
                return c.getLevel() >= intStore;
            case lvmax:
                return c.getLevel() <= intStore;
            case end: {
                // Quest.wz stores this as yyyyMMddHH in server local time.
                //
                // Two quirks of the Calendar code this replaces are preserved on
                // purpose, because changing either one changes which quests are
                // available and that is a gameplay decision, not a refactor:
                //
                //  1. cal.set(year, month, ..) fed the 1-based month from the
                //     string into Calendar's 0-based MONTH field, so every
                //     deadline is read one month late (and a "12" rolls into
                //     January of the next year). plusMonths on the 1st of
                //     January reproduces that, including the roll.
                //  2. Calendar.set leaves SECOND and MILLISECOND alone, so the
                //     deadline inherited whatever second it happened to be when
                //     the check ran. Starting from now() and overwriting only
                //     the fields set() overwrote keeps that.
                //
                // Days are added rather than assigned for the same reason: the
                // lenient Calendar rolled a "31" in a 30-day month into the 1st
                // of the next, where withDayOfMonth(31) would throw.
                final String timeStr = stringStore;
                final ZonedDateTime now = ZonedDateTime.now();
                final ZonedDateTime end = now.withDayOfMonth(1).withMonth(1)
                        .withYear(Integer.parseInt(timeStr.substring(0, 4)))
                        .plusMonths(Integer.parseInt(timeStr.substring(4, 6)))
                        .plusDays(Integer.parseInt(timeStr.substring(6, 8)) - 1L)
                        .withHour(Integer.parseInt(timeStr.substring(8, 10)))
                        .withMinute(0);
                return end.toInstant().toEpochMilli() >= System.currentTimeMillis();
            }
            case mob:
                for (Pair<Integer, Integer> a : dataStore) {
                    final int mobId = a.getLeft();
                    final int killReq = a.getRight();
                    if (c.getQuest(quest).getMobKills(mobId) < killReq) {
                        return false;
                    }
                }
                return true;
            case npc:
                return npcid == null || npcid == intStore;
            case fieldEnter:
                if (intStore != -1) {
                    return intStore == c.getMapId();
                }
                return false;
            case mbmin:
                if (c.getMonsterBook().getTotalCards() >= intStore) {
                    return true;
                }
                return false;
            case mbcard:
                for (Pair<Integer, Integer> a : dataStore) {
                    final int cardId = a.getLeft();
                    final int killReq = a.getRight();
                    if (c.getMonsterBook().getLevelByCard(cardId) < killReq) {
                        return false;
                    }
                }
                return true;
            case pop:
                return c.getFame() <= intStore;
            case questComplete:
                return c.getNumQuest() >= intStore;
            case interval:
                return c.getQuest(quest).getStatus() != 2 || c.getQuest(quest).getCompletionTime() <= System.currentTimeMillis() - intStore * 60 * 1000L;
            case pet:
                for (Pair<Integer, Integer> a : dataStore) {
                    if (c.getPetById(a.getRight()) == -1) {
                        return false;
                    }
                }
                return true;
            case pettamenessmin:
                for (MaplePet pet : c.getPets()) {
                    if (pet.getSummoned() && pet.getCloseness() >= intStore) {
                        return true;
                    }
                }
                return false;
            default:
                return true;
        }
    }

    public MapleQuestRequirementType getType() {
        return type;
    }

    @Override
    public String toString() {
        return type.toString();
    }
}
