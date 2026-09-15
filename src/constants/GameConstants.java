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

/**
 * 拆剩下的雜項常數。
 *
 * <p>{@code GameConstants} 原本有 2,033 行、126 個成員；物品的搬到
 * {@code ItemConstants}、職業與技能的搬到 {@code JobConstants}、地圖的搬到
 * {@code MapConstants}。留在這裡的是不屬於那三類的東西：等級經驗表
 * （{@link #getExpNeededForLevel}）、寵物親密度與騎寵經驗
 * （{@link #getClosenessNeededForLevel}、{@link #getMountExpNeededForLevel}）、
 * 怪物圖鑑等級、交易稅、自訂任務判斷與釣魚間隔。
 */
public class GameConstants {

    private static final int[] exp = {0, 15, 34, 57, 92, 135, 372, 560, 840, 1242, 1716,
        2360, 3216, 4200, 5460, 7050, 8840, 11040, 13716, 16680, 20216,
        24402, 28980, 34320, 40512, 47216, 54900, 63666, 73080, 83720, 95700,
        108480, 122760, 138666, 155540, 174216, 194832, 216600, 240500, 266682, 294216,
        324240, 356916, 391160, 428280, 468450, 510420, 555680, 604416, 655200, 709716, // 51等到這
        748608, 789631, 832902, 878545, 926689, 977471, 1031036, 1087536, 1147132, 1209994,
        1276301, 1346242, 1420016, 1497832, 1579913, 1666492, 1757815, 1854143, 1955750, 2062925, // 71等到這
        2175973, 2295216, 2410993, 2553663, 2693603, 2841212, 2996910, 3161140, 3334370, 3517093,
        3709829, 3913127, 4127566, 4353756, 4592341, 4844001, 5109452, 5389449, 5684790, 5996316,
        6324914, 6671519, 7037118, 7422752, 7829518, 8258575, 8711144, 9188514, 9692044, 10223168, // 101等到這
        10783397, 11374327, 11997640, 12655110, 13348610, 14080113, 14851703, 15665576, 16524049, 17429566,
        18384706, 19392187, 20454878, 21575805, 22758159, 24005306, 25320796, 26708375, 28171993, 29715818,//121等到這
        31344244, 33061908, 34873700, 36784778, 38800583, 40926854, 43169645, 45535341, 48030677, 50662758,//131等到這
        53439077, 56367538, 59456479, 62714694, 66151459, 69776558, 73600313, 77633610, 81887931, 86375389,//141等到這
        91108760, 96101520, 101367883, 106922842, 112782213, 118962678, 125481832, 132358236, 139611467, 147262175,//151等到這
        155332142, 163844343, 172823012, 182293713, 192283408, 202820538, 213935103, 225658746, 238024845, 251068606, //160
        264827165, 279339693, 294647508, 310794191, 327825712, 345790561, 364739883, 384727628, 405810702, 428049128, //170
        451506220, 476248760, 502347192, 529875818, 558913012, 589541445, 621848316, 655925603, 691870326, 729784819,
        769777027, 811960808, 856456260, 903390063, 952895838, 1005114529, 1060194805, 1118293480, 1179575962, 1244216724,
        1312399800, 1384319309, 1460180007, 1540197871, 1624600714, 1713628833, 1807535693, 1906588648, 2011069705, 2121276324};
    private static final int[] closeness = {0, 1, 3, 6, 14, 31, 60, 108, 181, 287, 434, 632, 891, 1224, 1642, 2161, 2793,
        3557, 4467, 5542, 6801, 8263, 9950, 11882, 14084, 16578, 19391, 22547, 26074,
        30000};
    private static final int[] mountexp = {0, 6, 25, 50, 105, 134, 196, 254, 263, 315, 367, 430, 543, 587, 679, 725, 897, 1146, 1394, 1701, 2247,
        2543, 2898, 3156, 3313, 3584, 3923, 4150, 4305, 4550};

    public static final int OMOK_SCORE = 122200;
    public static final int MATCH_SCORE = 122210;

    public static final String[] RESERVED = {"Rental"};

    public static int getExpNeededForLevel(final int level) {
        if (level < 0 || level >= exp.length) {
            return Integer.MAX_VALUE;
        }
        return exp[level];
    }

    public static int getClosenessNeededForLevel(final int level) {
        return closeness[level - 1];
    }

    public static int getMountExpNeededForLevel(final int level) {
        return mountexp[level - 1];
    }

    public static int getBookLevel(final int level) {
        return ((5 * level) * (level + 1));
    }

    public static boolean isCustomQuest(final int id) {
        return id > 99999;
    }

    public static int getTaxAmount(final int meso) {
        if (meso >= 100000000) {
            return (int) Math.round(0.06 * meso);
        } else if (meso >= 25000000) {
            return (int) Math.round(0.05 * meso);
        } else if (meso >= 10000000) {
            return (int) Math.round(0.04 * meso);
        } else if (meso >= 5000000) {
            return (int) Math.round(0.03 * meso);
        } else if (meso >= 1000000) {
            return (int) Math.round(0.018 * meso);
        } else if (meso >= 100000) {
            return (int) Math.round(0.008 * meso);
        }
        return 0;
    }

    public static int EntrustedStoreTax(final int meso) {
        if (meso >= 100000000) {
            return (int) Math.round(0.03 * meso);
        } else if (meso >= 25000000) {
            return (int) Math.round(0.025 * meso);
        } else if (meso >= 10000000) {
            return (int) Math.round(0.02 * meso);
        } else if (meso >= 5000000) {
            return (int) Math.round(0.015 * meso);
        } else if (meso >= 1000000) {
            return (int) Math.round(0.009 * meso);
        } else if (meso >= 100000) {
            return (int) Math.round(0.004 * meso);
        }
        return 0;
    }

    public static int getExpRate_Quest(final int level) {
        return 1;
    }

    public static int getFishingTime(boolean vip, boolean gm) {
        return gm ? 1000 : (vip ? 30000 : 60000);
    }

    public static boolean canForfeit(int questid) {
        switch (questid) {
            case 20000:
            case 20010:
            case 20015: //cygnus quests
            case 20020:
                return false;
            default:
                return true;
        }
    }

    /**
     * 活動地圖判斷——E8 拆分時刻意沒有跟著搬到 {@code MapConstants}。
     *
     * <p>{@code MapConstants} 早就有一個同簽章的 {@code isEventMap}，實作卻
     * 不一樣：這一個多了 {@code 809040000..809040100}（婚禮地圖）那一段。
     * 兩者無法共存於同一個類別，而唯一的呼叫端
     * {@code handling.channel.handler.PlayerHandler} 用的是
     * {@code MapConstants} 的版本——搬過去就會覆蓋掉它，變成行為變更。
     *
     * <p>這一個在整棵樹（含 {@code Libs/scripts}）零呼叫端，所以原地留著。
     * 要收掉它請先確認 {@code PlayerHandler} 那行想要的是哪一種範圍。
     */
    public static boolean isEventMap(final int mapid) {
        return (mapid >= 109010000 && mapid < 109050000) || (mapid > 109050001 && mapid < 109090000) || (mapid >= 809040000 && mapid <= 809040100);
    }
}
