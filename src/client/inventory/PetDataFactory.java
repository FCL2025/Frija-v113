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
package client.inventory;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import tools.Pair;

public class PetDataFactory {

    private static MapleDataProvider dataRoot = MapleDataProviderFactory.getDataProvider(new File(System.getProperty("net.sf.odinms.wzpath") + "/Item.wz"));
    private static final Map<Pair<Integer, Integer>, PetCommand> petCommands = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> petHunger = new ConcurrentHashMap<>();

    public static final PetCommand getPetCommand(final int petId, final int skillId) {
        // Neither loader re-enters petCommands/petHunger, so computeIfAbsent is safe here.
        return petCommands.computeIfAbsent(new Pair<>(Integer.valueOf(petId), Integer.valueOf(skillId)), key -> {
            final MapleData skillData = dataRoot.getData("Pet/" + petId + ".img");
            int prob = 0;
            int inc = 0;
            if (skillData != null) {
                prob = MapleDataTool.getInt("interact/" + skillId + "/prob", skillData, 0);
                inc = MapleDataTool.getInt("interact/" + skillId + "/inc", skillData, 0);
            }
            return new PetCommand(petId, skillId, prob, inc);
        });
    }

    public static final int getHunger(final int petId) {
        return petHunger.computeIfAbsent(Integer.valueOf(petId), key -> {
            final MapleData hungerData = dataRoot.getData("Pet/" + petId + ".img").getChildByPath("info/hungry");
            return Integer.valueOf(MapleDataTool.getInt(hungerData, 1));
        });
    }
}
