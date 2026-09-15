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
package constants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import server.maps.MapleMapObjectType;

/**
 * 地圖相關的常數與判斷。
 *
 * <p>從 {@code GameConstants} 拆出來的一部分，加上這個類別原本就有的兩個
 * 活動地圖判斷。收的是拿 mapId 去分類的東西（{@link #isForceRespawn}、
 * {@link #isEventMap}、{@link #blockedMaps}）、視野範圍
 * （{@link #maxViewRangeSq()}），以及地圖物件的廣播型別清單
 * （{@link #rangedMapobjectTypes}）。
 *
 * <p>與物品有關的判斷在 {@code ItemConstants}，與職業有關的在
 * {@code JobConstants}。
 */
public class MapConstants {

    public static boolean isStartingEventMap(final int mapid) {
        switch (mapid) {
            case 109010000:
            case 109020001:
            case 109030001:
            case 109030101:
            case 109030201:
            case 109030301:
            case 109030401:
            case 109040000:
            case 109060001:
            case 109060002:
            case 109060003:
            case 109060004:
            case 109060005:
            case 109060006:
            case 109080000:
            case 109080001:
            case 109080002:
            case 109080003:
                return true;
        }
        return false;
    }

    public static boolean isEventMap(final int mapid) {
        return mapid >= 109010000 && mapid < 109050000 || mapid > 109050001 && mapid < 109090000;
    }

    public static final List<MapleMapObjectType> rangedMapobjectTypes = Collections.unmodifiableList(Arrays.asList(
            MapleMapObjectType.ITEM,
            MapleMapObjectType.MONSTER,
            MapleMapObjectType.DOOR,
            MapleMapObjectType.REACTOR,
            MapleMapObjectType.SUMMON,
            MapleMapObjectType.NPC,
            MapleMapObjectType.MIST));

    /** 視野半徑的平方，換算半徑 ≈ 894.4。詳見 {@link #maxViewRangeSq()}。 */
    private static final int MAX_VIEW_RANGE_SQ = 800000;

    /**
     * 視野半徑的平方，值刻意維持 800000。
     *
     * <p>這裡原本掛著一行 {@code // 800 * 800} 的註解，而 800 * 800 是
     * 640000，不是 800000——註解是錯的，值不是。七個呼叫端（全部在
     * {@code server.maps.MapleMap}）都拿它跟 {@code Point.distanceSq} 比，
     * 所以這台伺服器的實際視野半徑是 sqrt(800000) ≈ 894.4，比那行註解
     * 宣稱的 800 大約多了 11.8%。
     *
     * <p>要讓它符合舊註解就是把值改成 {@code 640000}，但那是<b>遊戲行為
     * 變更</b>不是修 bug：視野半徑會縮小約 11%（面積少 20%），玩家能看見
     * 的怪物／其他玩家／掉落物會變少，範圍廣播（攻擊動作、技能特效、
     * 聊天氣泡）的收件人也會跟著變少。要動請當成一次平衡調整來評估。
     */
    public static int maxViewRangeSq() {
        return MAX_VIEW_RANGE_SQ;
    }

    public static int[] blockedMaps = {109050000, 280030000, 240060200, 280090000, 280030001, 240060201, 950101100, 950101010};
    //If you can think of more maps that could be exploitable via npc,block nao pliz!

    public static boolean isForceRespawn(int mapid) {
        switch (mapid) {
            case 925100100: //crocs and stuff
                return true;
            default:
                return mapid / 100000 == 9800 && (mapid % 10 == 1 || mapid % 1000 == 100);
        }
    }

    public static int getCustomSpawnID(int summoner, int def) {
        switch (summoner) {
            case 9400589:
            case 9400748: //MV
                return 9400706; //jr
            default:
                return def;
        }
    }
}
