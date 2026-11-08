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
package server.life;

/**
 * 全域掉寶項目。D6 改成 record，理由同 {@link MonsterDropEntry}。
 * 原本的七參數建構子（不帶 {@code onlySelf}）保留為委派建構子，
 * 所以既有呼叫端一行都不用改。
 */
public record MonsterGlobalDropEntry(int itemId, int chance, int continent, byte dropType,
        int Minimum, int Maximum, short questid, boolean onlySelf) {

    public MonsterGlobalDropEntry(int itemId, int chance, int continent, byte dropType,
            int Minimum, int Maximum, short questid) {
        this(itemId, chance, continent, dropType, Minimum, Maximum, questid, false);
    }
}
