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

import constants.GameConstants;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import java.util.Map.Entry;
import server.life.MapleLifeFactory;
import server.quest.MapleQuest;

public class MapleQuestStatus {

    private MapleQuest quest;
    private byte status;
    // C11：killedMobs 是共用的。怪物死亡時 MapleMonster.killBy:434 在「擊殺者」的 handler
    // thread 上對每一個攻擊者呼叫 MapleCharacter.mobKilled()，走進來的是別人角色的
    // MapleQuestStatus；存檔（CharacterRepository:574 / :857，!saveall 或自動存檔）與換頻時的
    // CharacterTransfer 重建（MapleCharacter:486）又會在第三條執行緒上走訪它。C4 只把
    // MapleCharacter.quests 換成並行 Map，這一層是同一個競態的下一層：mobKilled() 本身是
    // 「get → maxMob → put」的讀改寫，兩個人同時打同一種怪就會掉掉其中一隻。
    //
    // **刻意維持 LinkedHashMap，改用 monitor 保護。** QuestPacket.updateQuestMobKills:99 把
    // 每一個值補零成三位數之後直接串成一個字串送出去，客戶端是照位置解析的——換成
    // ConcurrentHashMap 會把每一個多目標任務的進度顯示打亂（和 C7 的 MapleSquad 同一個理由）。
    private final Object killedMobsLock = new Object();
    private Map<Integer, Integer> killedMobs = null;
    private int npc;
    private long completionTime;
    private int forfeited = 0;
    private String customData;

    /**
     * Creates a new instance of MapleQuestStatus
     */
    public MapleQuestStatus(final MapleQuest quest, final byte status) {
        this.quest = quest;
        this.setStatus(status);
        this.completionTime = System.currentTimeMillis();
        if (status == 1) { // Started
            if (!quest.getRelevantMobs().isEmpty()) {
                registerMobs();
            }
        }
    }

    public MapleQuestStatus(final MapleQuest quest, final byte status, final int npc) {
        this.quest = quest;
        this.setStatus(status);
        this.setNpc(npc);
        this.completionTime = System.currentTimeMillis();
        if (status == 1) { // Started
            if (!quest.getRelevantMobs().isEmpty()) {
                registerMobs();
            }
        }
    }

    public final MapleQuest getQuest() {
        return quest;
    }

    public final byte getStatus() {
        return status;
    }

    public final void setStatus(final byte status) {
        this.status = status;
    }

    public final int getNpc() {
        return npc;
    }

    public final void setNpc(final int npc) {
        this.npc = npc;
    }

    public boolean isCustom() {
        return GameConstants.isCustomQuest(quest.getId());
    }

    private final void registerMobs() {
        synchronized (killedMobsLock) {
            killedMobs = new LinkedHashMap<>();
            for (final int i : quest.getRelevantMobs().keySet()) {
                killedMobs.put(i, 0);
            }
        }
    }

    private final int maxMob(final int mobid) {
        for (final Map.Entry<Integer, Integer> qs : quest.getRelevantMobs().entrySet()) {
            if (qs.getKey() == mobid) {
                return qs.getValue();
            }
        }
        return 0;
    }

    public final boolean mobKilled(final int id, final int skillID) {
        if (quest != null && quest.getSkillID() > 0) {
            if (quest.getSkillID() != skillID) {
                return false;
            }
        }
        // C11：整段「查現有計數 → 比上限 → 寫回」在同一把鎖底下，否則兩個攻擊者同時打死
        // 同一種怪會各自讀到同一個舊值、各自寫回同一個新值，少算一隻。
        // 鎖裡只有 maxMob()（讀 WZ 來的不可變 Map）與 questCount()（MapleLifeFactory 的
        // 快取查表），沒有 broadcast 也沒有資料庫借用。
        synchronized (killedMobsLock) {
            final Integer mob = killedMobs.get(id);
            if (mob != null) {
                final int mo = maxMob(id);
                if (mob >= mo) {
                    return false; //nothing happened
                }
                killedMobs.put(id, Math.min(mob + 1, mo));
                return true;
            }
            for (Entry<Integer, Integer> mo : killedMobs.entrySet()) {
                if (questCount(mo.getKey(), id)) {
                    final int mobb = maxMob(mo.getKey());
                    if (mo.getValue() >= mobb) {
                        return false; //nothing
                    }
                    killedMobs.put(mo.getKey(), Math.min(mo.getValue() + 1, mobb));
                    return true;
                }
            } //i doubt this
            return false;
        }
    }

    private final boolean questCount(final int mo, final int id) {
        if (MapleLifeFactory.getQuestCount(mo) != null) {
            for (int i : MapleLifeFactory.getQuestCount(mo)) {
                if (i == id) {
                    return true;
                }
            }
        }
        return false;
    }

    public final void setMobKills(final int id, final int count) {
        synchronized (killedMobsLock) {
            if (killedMobs == null) {
                registerMobs(); //lol
            }
            killedMobs.put(id, count);
        }
    }

    public final boolean hasMobKills() {
        synchronized (killedMobsLock) {
            if (killedMobs == null) {
                return false;
            }
            return killedMobs.size() > 0;
        }
    }

    public final int getMobKills(final int id) {
        synchronized (killedMobsLock) {
            final Integer mob = killedMobs.get(id);
            if (mob == null) {
                return 0;
            }
            return mob;
        }
    }

    /**
     * C11：回傳的是「插入順序的快照」，不是活的檢視。
     *
     * <p>三個呼叫端都在別的執行緒上走訪它——{@code QuestPacket.updateQuestMobKills}（送封包）、
     * {@code CharacterRepository} 的兩處存檔、{@code MapleCharacter.ReconstructChr} 的換頻重建
     * ——而擊殺者的執行緒同時可能在 {@code mobKilled()} 裡寫。回傳活的 {@code LinkedHashMap}
     * 等於把走訪暴露在結構性修改底下。查證過沒有任何呼叫端透過這個回傳值做寫入。
     *
     * <p>{@code null} 的語意保留：沒有目標怪的任務本來就回 {@code null}，
     * {@code MapleCharacter:486} 靠這個判斷。
     */
    public final Map<Integer, Integer> getMobKills() {
        synchronized (killedMobsLock) {
            return killedMobs == null ? null : new LinkedHashMap<>(killedMobs);
        }
    }

    public final long getCompletionTime() {
        return completionTime;
    }

    public final void setCompletionTime(final long completionTime) {
        this.completionTime = completionTime;
    }

    public final int getForfeited() {
        return forfeited;
    }

    public final void setForfeited(final int forfeited) {
        if (forfeited >= this.forfeited) {
            this.forfeited = forfeited;
        } else {
            throw new IllegalArgumentException("Can't set forfeits to something lower than before.");
        }
    }

    public final void setCustomData(final String customData) {
        this.customData = customData;
    }

    public final String getCustomData() {
        return customData;
    }
}
