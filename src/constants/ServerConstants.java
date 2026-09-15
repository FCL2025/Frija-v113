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

public class ServerConstants {

    /*
     * Specifics which job gives an additional EXP to party
     * returns the percentage of EXP to increase
     */
    public static final byte Class_Bonus_EXP(final int job) {
        switch (job) {
            case 3000: //whenever these arrive, they'll give bonus
            case 3200:
            case 3210:
            case 3211:
            case 3212:
            case 3300:
            case 3310:
            case 3311:
            case 3312:
            case 3500:
            case 3510:
            case 3511:
            case 3512:
                return 10;
        }
        return 0;
    }
    // Start of Poll
    public static final boolean PollEnabled = false;
    public static final String Poll_Question = "Are you mudkiz?";
    public static final String[] Poll_Answers = {"test1", "test2", "test3"};
    // End of Poll
    public static final short MAPLE_VERSION = 113;
    public static final String MAPLE_PATCH = "1";
    public static final boolean Use_Fixed_IV = false;
    public static final int MIN_MTS = 110;
    public static final int MTS_BASE = 100; //+1000 to everything in MSEA but cash is costly here
    public static final int MTS_TAX = 10; //+% to everything
    public static final int MTS_MESO = 5000; //mesos needed
    public static final int CHANNEL_COUNT = 200;

    public static enum PlayerGMRank {

        NORMAL('@', 0),
        INTERN('!', 1),
        GM('!', 2),
        ADMIN('!', 3);
        // 保留作為紀錄：其他分支有第四個等級 SUPERADMIN，這棵樹沒有。
        // 它與 ADMIN 同為 level 3、同一個字首，加回來不會多出任何權限。
        //SUPERADMIN('!', 3);
        private char commandPrefix;
        private int level;

        PlayerGMRank(char ch, int level) {
            commandPrefix = ch;
            this.level = level;
        }

        public char getCommandPrefix() {
            return commandPrefix;
        }

        public int getLevel() {
            return level;
        }
    }

    public static enum CommandType {

        NORMAL(0),
        TRADE(1);
        private int level;

        CommandType(int level) {
            this.level = level;
        }

        public int getType() {
            return level;
        }
    }
}
