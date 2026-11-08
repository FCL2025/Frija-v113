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
package client;

import handling.world.PlayerBuffValueHolder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import server.MapleStatEffect;
import tools.ConcurrentEnumMap;

/**
 * 一個角色身上的 buff 登錄表（E4 第二刀，從 {@link MapleCharacter} 拆出）。
 *
 * <p>收的是 {@code effects} 這張表本身，以及只靠它就能完成的事：查詢（{@code getBuffedValue}、
 * {@code getStatForBuff}…）、批次取消的迴圈（{@code dispel*}、{@code cancelAllBuffs}、
 * {@code cancelMorphs}…）、換頻時的打包與還原（{@code getAllBuffs} / {@code silentGiveBuffs}），
 * 以及取消時的簿記（{@link #deregisterBuffStats}）。
 *
 * <p>刻意<b>沒有</b>搬過來的是 {@code registerEffect}、{@code cancelEffect}、
 * {@code cancelBuffStats} 與送封包的 {@code cancelPlayerBuffs}：它們八成的內容是角色本身的副作用
 * （隱身與重新現身的廣播、時空門、坐騎、連擊數、寵物、分身、召喚獸），搬過來就得讓這個類別
 * 伸手進角色十幾個私有欄位，邊界只是名義上的。它們留在 {@code MapleCharacter} 當協調者，
 * 對這張表的讀寫一律經過這裡；{@code deregisterBuffStats} 每移除一個 stat 就回呼
 * {@code MapleCharacter.buffStatDeregistered} 做角色那一半的善後，順序與搬家前相同。
 *
 * <p>{@code MapleCharacter} 上同名同簽章的方法全部保留成轉呼叫，Java 與 Nashorn 腳本的呼叫端
 * 一個字都沒改。併發性質也沒變：表仍是 {@link ConcurrentEnumMap}（C12 的快照語意），
 * 方法內容逐字搬移，只把「呼叫角色的方法」改成經由 {@code chr}。
 */
public final class PlayerBuffs {

    private final MapleCharacter chr;
    private final Map<MapleBuffStat, MapleBuffStatValueHolder> effects = new ConcurrentEnumMap<>(MapleBuffStat.class);

    PlayerBuffs(final MapleCharacter chr) {
        this.chr = chr;
    }

    /** registerEffect 用。 */
    void put(final MapleBuffStat stat, final MapleBuffStatValueHolder holder) {
        effects.put(stat, holder);
    }

    /** cancelAllBuffs_ 用：斷線時整張表直接清掉，不送任何封包。 */
    void clear() {
        effects.clear();
    }

    /** cloneLooks() 用：把 {@code from} 的 buff 照搬給分身，但不帶分身術、召喚獸類的 stat。 */
    void copyForClone(final PlayerBuffs from) {
        // C12：putAll 對 EnumMap 來源會走繞過讀鎖的快速路徑，所以逐筆從
        // entrySet() 的快照複製。分身只有一隻、buff 數量是個位數，成本可以忽略。
        for (Entry<MapleBuffStat, MapleBuffStatValueHolder> buff : from.effects.entrySet()) {
            effects.put(buff.getKey(), buff.getValue());
        }
        if (effects.get(MapleBuffStat.ILLUSION) != null) {
            effects.remove(MapleBuffStat.ILLUSION);
        }
        if (effects.get(MapleBuffStat.SUMMON) != null) {
            effects.remove(MapleBuffStat.SUMMON);
        }
        if (effects.get(MapleBuffStat.REAPER) != null) {
            effects.remove(MapleBuffStat.REAPER);
        }
        if (effects.get(MapleBuffStat.PUPPET) != null) {
            effects.remove(MapleBuffStat.PUPPET);
        }
    }

    boolean isActiveBuffedValue(int skillid) {
        LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skillid) {
                return true;
            }
        }
        return false;
    }

    Integer getBuffedValue(MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        return mbsvh == null ? null : Integer.valueOf(mbsvh.value);
    }

    final Integer getBuffedSkill_X(final MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        if (mbsvh == null) {
            return null;
        }
        return mbsvh.effect.getX();
    }

    final Integer getBuffedSkill_Y(final MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        if (mbsvh == null) {
            return null;
        }
        return mbsvh.effect.getY();
    }

    boolean isBuffFrom(MapleBuffStat stat, ISkill skill) {
        final MapleBuffStatValueHolder mbsvh = effects.get(stat);
        if (mbsvh == null) {
            return false;
        }
        return mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skill.getId();
    }

    int getBuffSource(MapleBuffStat stat) {
        final MapleBuffStatValueHolder mbsvh = effects.get(stat);
        return mbsvh == null ? -1 : mbsvh.effect.getSourceId();
    }

    void setBuffedValue(MapleBuffStat effect, int value) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        if (mbsvh == null) {
            return;
        }
        mbsvh.value = value;
    }

    Long getBuffedStarttime(MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        return mbsvh == null ? null : Long.valueOf(mbsvh.startTime);
    }

    MapleStatEffect getStatForBuff(MapleBuffStat effect) {
        final MapleBuffStatValueHolder mbsvh = effects.get(effect);
        return mbsvh == null ? null : mbsvh.effect;
    }

    List<MapleBuffStat> getBuffStats(final MapleStatEffect effect, final long startTime) {
        final List<MapleBuffStat> bstats = new ArrayList<>();
        // C12：不能寫 new EnumMap<>(effects)。EnumMap 的複製建構子對「來源也是 EnumMap」
        // 走的是直接複製對方 vals/size 欄位的快速路徑，完全不經過 ConcurrentEnumMap 覆寫的
        // 方法，讀鎖形同不存在，會拿到 size 與陣列對不上的複本。entrySet() 現在本身就是
        // 鎖內做好的不可變快照，直接走訪它即可。
        for (Entry<MapleBuffStat, MapleBuffStatValueHolder> stateffect : effects.entrySet()) {
            final MapleBuffStatValueHolder mbsvh = stateffect.getValue();
            if (mbsvh.effect.sameSource(effect) && (startTime == -1 || startTime == mbsvh.startTime)) {
                bstats.add(stateffect.getKey());
            }
        }
        return bstats;
    }

    void cancelEffectFromBuffStat(MapleBuffStat stat) {
        if (effects.get(stat) != null) {
            chr.cancelEffect(effects.get(stat).effect, false, -1);
        }
    }

    void dispel() {
        if (!chr.isHidden()) {
            final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
            for (MapleBuffStatValueHolder mbsvh : allBuffs) {
                if (mbsvh.effect.isSkill() && mbsvh.schedule != null && !mbsvh.effect.isMorph()) {
                    chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                }
            }
        }
    }

    void dispelSkill(int skillid) {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (skillid == 0) {
                if (mbsvh.effect.isSkill() && (mbsvh.effect.getSourceId() == 4331003 || mbsvh.effect.getSourceId() == 4331002 || mbsvh.effect.getSourceId() == 4341002 || mbsvh.effect.getSourceId() == 22131001 || mbsvh.effect.getSourceId() == 1321007 || mbsvh.effect.getSourceId() == 2121005 || mbsvh.effect.getSourceId() == 2221005 || mbsvh.effect.getSourceId() == 2311006 || mbsvh.effect.getSourceId() == 2321003 || mbsvh.effect.getSourceId() == 3111002 || mbsvh.effect.getSourceId() == 3111005 || mbsvh.effect.getSourceId() == 3211002 || mbsvh.effect.getSourceId() == 3211005 || mbsvh.effect.getSourceId() == 4111002)) {
                    chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                    break;
                }
            } else {
                if (mbsvh.effect.isSkill() && mbsvh.effect.getSourceId() == skillid) {
                    chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                    break;
                }
            }
        }
    }

    void dispelBuff(int skillid) {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.getSourceId() == skillid) {
                chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                break;
            }
        }
    }

    void cancelAllBuffs() {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
        }
    }

    void cancelMorphs() {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            switch (mbsvh.effect.getSourceId()) {
                case 5101007:
                case 5111005:
                case 5121003:
                case 15111002:
                case 13111005:
                    return; // Since we can't have more than 1, save up on loops
                default:
                    if (mbsvh.effect.isMorph()) {
                        chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                        continue;
                    }
            }
        }
    }

    int getMorphState() {
        LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.isMorph()) {
                return mbsvh.effect.getSourceId();
            }
        }
        return -1;
    }

    void silentGiveBuffs(List<PlayerBuffValueHolder> buffs) {
        if (buffs == null) {
            return;
        }
        for (PlayerBuffValueHolder mbsvh : buffs) {
            mbsvh.effect.silentApplyBuff(chr, mbsvh.startTime);
        }
    }

    List<PlayerBuffValueHolder> getAllBuffs() {
        List<PlayerBuffValueHolder> ret = new ArrayList<>();
        LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());
        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            ret.add(new PlayerBuffValueHolder(mbsvh.startTime, mbsvh.effect));
        }
        return ret;
    }

    void cancelMagicDoor() {
        final LinkedList<MapleBuffStatValueHolder> allBuffs = new LinkedList<>(effects.values());

        for (MapleBuffStatValueHolder mbsvh : allBuffs) {
            if (mbsvh.effect.isMagicDoor()) {
                chr.cancelEffect(mbsvh.effect, false, mbsvh.startTime);
                break;
            }
        }
    }

    boolean deregisterBuffStats(List<MapleBuffStat> stats) {
        boolean clonez = false;
        List<MapleBuffStatValueHolder> effectsToCancel = new ArrayList<>(stats.size());
        for (MapleBuffStat stat : stats) {
            final MapleBuffStatValueHolder mbsvh = effects.remove(stat);
            if (mbsvh != null) {
                boolean addMbsvh = true;
                for (MapleBuffStatValueHolder contained : effectsToCancel) {
                    if (mbsvh.startTime == contained.startTime && contained.effect == mbsvh.effect) {
                        addMbsvh = false;
                    }
                }
                if (addMbsvh) {
                    effectsToCancel.add(mbsvh);
                }
                if (chr.buffStatDeregistered(stat, mbsvh)) {
                    clonez = true;
                }
            }
        }
        for (MapleBuffStatValueHolder cancelEffectCancelTasks : effectsToCancel) {
            if (getBuffStats(cancelEffectCancelTasks.effect, cancelEffectCancelTasks.startTime).size() == 0) {
                if (cancelEffectCancelTasks.schedule != null) {
                    cancelEffectCancelTasks.schedule.cancel(false);
                }
            }
        }
        return clonez;
    }
}
