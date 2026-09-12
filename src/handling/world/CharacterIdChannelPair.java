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
package handling.world;

/**
 * D6 改成 record。{@code compareTo} 原樣保留（只比頻道），
 * 全樹沒有把它放進雜湊或有序集合的地方，所以 record 自動產生的
 * {@code equals} / {@code hashCode} 不會改變任何現有行為。
 */
public record CharacterIdChannelPair(int characterId, int channel) implements Comparable<CharacterIdChannelPair> {

    @Override
    public int compareTo(CharacterIdChannelPair o) {
        return channel - o.channel;
    }
}
