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
 * 單一怪物的掉寶項目。D6 改成 record：欄位本來就只在建構子裡寫入，
 * 全樹沒有任何地方事後改它們。欄位名稱維持原樣（含大寫開頭的
 * {@code Minimum} / {@code Maximum}），改名是 D13 的範圍。
 */
public record MonsterDropEntry(int itemId, int chance, int Minimum, int Maximum, short questid) {
}
