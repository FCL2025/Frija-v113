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

import constants.ItemConstants;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一個角色的一格背包（EQUIP／USE／SETUP／ETC／CASH／EQUIPPED 各一份）。
 *
 * <p><b>這個類別會被好幾條執行緒同時走訪。</b>除了物主自己的 {@code Maple-Handler-N} 之外，
 * 至少還有：
 * <ul>
 * <li>定時自動存檔的計時器執行緒 —— {@code ChannelServer.saveAll()} 一路走到
 * {@code CharacterRepository.saveInventory}，對每個線上玩家的六格背包做
 * {@code for (IItem item : iv.list())}。它不是在物主的 handler thread 上跑的。</li>
 * <li>{@code Timer.WORLD} —— {@code handling.world.Respawn} →
 * {@code World.handleCooldowns} 讀 CASH（寵物飽食度）與 EQUIPPED（{@code getHPDecProtect}）。</li>
 * <li>{@code Timer.BUFF} —— {@code MapleStatEffect.CancelEffectAction} →
 * {@code MapleCharacter.cancelPlayerBuffs} → {@code PlayerStats.recalcLocalStats}
 * 走訪 EQUIPPED／CASH／ETC 三格。</li>
 * <li>別的玩家的 handler thread —— 交易成立時 {@code MapleTrade.completeTrade}
 * 在「後鎖定的那一方」的執行緒上呼叫 {@code partner.check()}（{@code findById}、
 * {@code getNumFreeSlot}）與 {@code partner.CompleteTrade()}
 * （{@code MapleInventoryManipulator.addFromDrop}），也就是在別人的執行緒上讀寫
 * 對方的背包；別人進地圖時 {@code PacketHelper.addCharLook} 與
 * {@code MapleCharacter.getRings} 也會在進場者的執行緒上走訪被看到那個人的 EQUIPPED。</li>
 * <li>GM 的 handler thread —— {@code AdminServerCommands.saveall} 與
 * {@code AdminItemCommands} 的 {@code !unequipall}。</li>
 * <li>登入中的另一個帳號的 handler thread —— {@code MapleClient.unlockAcc}
 * 會替卡住的角色呼叫 {@code saveToDB}。</li>
 * </ul>
 *
 * <p>所以 {@link #inventory} 與 {@link #slotLimit} 一律在 {@link #lock} 之下存取，
 * 而且 {@link #list()} 與 {@link #iterator()} 回傳的是快照而不是活的 {@code values()}
 * 檢視 —— 上面那些走訪都在鎖外面跑，活檢視遲早會在存檔中途丟
 * {@code ConcurrentModificationException}，把整筆 {@code saveToDB} 交易打斷。
 *
 * <p>底層仍然是 {@code LinkedHashMap}：查證過插入順序不是語意的一部分
 * （{@code PacketHelper.addItemInfo} 每一筆都先寫 position，EQUIPPED 出場前還另外
 * {@code Collections.sort} 過，{@code ItemLoader} 存檔／讀檔也以 position 為準，
 * {@link #listById} 自己會排序），但保留它可以讓 {@link #findById} 在有重複道具時
 * 回傳的仍然是同一顆，行為與改動前逐位元組相同。
 */
public class MapleInventory implements Iterable<IItem> {

    /**
     * 保護 {@link #inventory} 與 {@link #slotLimit} 的葉節點鎖。
     *
     * <p>是一個私有物件而不是 {@code this}，呼叫端沒有辦法從外面把它鎖住。
     * 鎖住期間只會碰 {@code IItem} 的欄位存取子與 {@code ItemConstants} 的純函式，
     * 不會廣播、不會進 {@code map.killMonster}、也不會借資料庫連線，
     * 而且不會再去拿第二把鎖 —— 它是葉節點，因此不可能參與死結環。
     */
    private final Object lock = new Object();
    private final Map<Short, IItem> inventory;
    private byte slotLimit = 0;
    private final MapleInventoryType type;

    /**
     * Creates a new instance of MapleInventory
     */
    public MapleInventory(MapleInventoryType type) {
        this.inventory = new LinkedHashMap<>();
        this.type = type;
    }

    public void addSlot(byte slot) {
        synchronized (lock) {
            this.slotLimit += slot;

            if (slotLimit > 96) {
                slotLimit = 96;
            }
        }
    }

    public byte getSlotLimit() {
        synchronized (lock) {
            return slotLimit;
        }
    }

    public void setSlotLimit(byte slot) {
        synchronized (lock) {
            if (slot > 96) {
                slot = 96;
            }
            slotLimit = slot;
        }
    }

    /**
     * Returns the item with its slot id if it exists within the inventory,
     * otherwise null is returned
     */
    public IItem findById(int itemId) {
        synchronized (lock) {
            for (IItem item : inventory.values()) {
                if (item.getItemId() == itemId) {
                    return item;
                }
            }
            return null;
        }
    }

    public IItem findByUniqueId(int itemId) {
        synchronized (lock) {
            for (IItem item : inventory.values()) {
                if (item.getUniqueId() == itemId) {
                    return item;
                }
            }
            return null;
        }
    }

    public int countById(int itemId) {
        synchronized (lock) {
            int possesed = 0;
            for (IItem item : inventory.values()) {
                if (item.getItemId() == itemId) {
                    possesed += item.getQuantity();
                }
            }
            return possesed;
        }
    }

    public List<IItem> listById(int itemId) {
        List<IItem> ret = new ArrayList<>();
        synchronized (lock) {
            for (IItem item : inventory.values()) {
                if (item.getItemId() == itemId) {
                    ret.add(item);
                }
            }
        }
        // the linkedhashmap does impose insert order as returned order but we can not guarantee that this is still the
        // correct order - blargh, we could empty the map and reinsert in the correct order after each inventory
        // addition, or we could use an array/list, it's only 255 entries anyway...
        if (ret.size() > 1) {
            Collections.sort(ret);
        }
        return ret;
    }

    /**
     * 回傳的是快照，不是活的 {@code values()} 檢視 —— 呼叫端（存檔、封包組裝）大多在
     * 別的執行緒上走訪，見類別註解。所有現有呼叫端都只讀不改，而且早就習慣先複製再改
     * （{@code InventoryHandler.ItemGather}、{@code NPCConversationManager.unequipEverything}、
     * {@code AdminItemCommands} 的 unequip-all 都是先抄成 local list 再動手）。
     */
    public Collection<IItem> list() {
        synchronized (lock) {
            return new ArrayList<>(inventory.values());
        }
    }

    /**
     * Adds the item to the inventory and returns the assigned slot id
     */
    public short addItem(IItem item) {
        synchronized (lock) {
            short slotId = getNextFreeSlot();
            if (slotId < 0) {
                return -1;
            }
            inventory.put(slotId, item);
            item.setPosition(slotId);
            return slotId;
        }
    }

    public void addFromDB(IItem item) {
        if (item.getPosition() < 0 && !type.equals(MapleInventoryType.EQUIPPED)) {
            // This causes a lot of stuck problem, until we are done with position checking
            return;
        }
        synchronized (lock) {
            inventory.put(item.getPosition(), item);
        }
    }

    public void move(short sSlot, short dSlot, short slotMax) {
        synchronized (lock) {
            if (dSlot > slotLimit) {
                return;
            }
            Item source = (Item) inventory.get(sSlot);
            Item target = (Item) inventory.get(dSlot);
            if (source == null) {
                throw new InventoryException("Trying to move empty slot");
            }
            if (target == null) {
                source.setPosition(dSlot);
                inventory.put(dSlot, source);
                inventory.remove(sSlot);
            } else if (target.getItemId() == source.getItemId() && !ItemConstants.isThrowingStar(source.getItemId()) && !ItemConstants.isBullet(source.getItemId()) && target.getOwner().equals(source.getOwner()) && target.getExpiration() == source.getExpiration()) {
                if (type.getType() == MapleInventoryType.EQUIP.getType() || type.getType() == MapleInventoryType.CASH.getType()) {
                    swap(target, source);
                } else if (source.getQuantity() + target.getQuantity() > slotMax) {
                    source.setQuantity((short) ((source.getQuantity() + target.getQuantity()) - slotMax));
                    target.setQuantity(slotMax);
                } else {
                    target.setQuantity((short) (source.getQuantity() + target.getQuantity()));
                    inventory.remove(sSlot);
                }
            } else {
                swap(target, source);
            }
        }
    }

    /** 只從已經持有 {@link #lock} 的 {@link #move} 進來。 */
    private void swap(IItem source, IItem target) {
        inventory.remove(source.getPosition());
        inventory.remove(target.getPosition());
        short swapPos = source.getPosition();
        source.setPosition(target.getPosition());
        target.setPosition(swapPos);
        inventory.put(source.getPosition(), source);
        inventory.put(target.getPosition(), target);
    }

    public IItem getItem(short slot) {
        synchronized (lock) {
            return inventory.get(slot);
        }
    }

    public void removeItem(short slot) {
        removeItem(slot, (short) 1, false);
    }

    public void removeItem(short slot, short quantity, boolean allowZero) {
        synchronized (lock) {
            IItem item = inventory.get(slot);
            if (item == null) { // TODO is it ok not to throw an exception here?
                return;
            }
            item.setQuantity((short) (item.getQuantity() - quantity));
            if (item.getQuantity() < 0) {
                item.setQuantity((short) 0);
            }
            if (item.getQuantity() == 0 && !allowZero) {
                removeSlot(slot);
            }
        }
    }

    public void removeSlot(short slot) {
        synchronized (lock) {
            inventory.remove(slot);
        }
    }

    public boolean isFull() {
        synchronized (lock) {
            return inventory.size() >= slotLimit;
        }
    }

    public boolean isFull(int margin) {
        synchronized (lock) {
            return inventory.size() + margin >= slotLimit;
        }
    }

    /**
     * Returns the next empty slot id, -1 if the inventory is full
     */
    public short getNextFreeSlot() {
        synchronized (lock) {
            if (isFull()) {
                return -1;
            }
            for (short i = 1; i <= slotLimit; i++) {
                if (!inventory.keySet().contains(i)) {
                    return i;
                }
            }
            return -1;
        }
    }

    public short getNumFreeSlot() {
        synchronized (lock) {
            if (isFull()) {
                return 0;
            }
            byte free = 0;
            for (short i = 1; i <= slotLimit; i++) {
                if (!inventory.keySet().contains(i)) {
                    free++;
                }
            }
            return free;
        }
    }

    public MapleInventoryType getType() {
        return type;
    }

    /**
     * 走訪的是 {@link #list()} 的快照。維持 unmodifiable 包裝，是為了讓
     * {@code remove()} 仍然丟 {@code UnsupportedOperationException}
     * ——查證過樹裡沒有任何呼叫端靠 iterator 移除元素，行為與改動前相同。
     */
    @Override
    public Iterator<IItem> iterator() {
        return Collections.unmodifiableCollection(list()).iterator();
    }
}
