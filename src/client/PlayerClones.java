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

import java.lang.ref.WeakReference;
import tools.packet.CharacterPacket;

/**
 * 一個角色的分身（E4 第四刀，從 {@link MapleCharacter} 拆出）。
 *
 * <p>分身術（ILLUSION）與 GM 指令產生的分身，以及換頻時記住「到了新頻道要重生幾隻」的
 * {@code numClones}。分身本身仍是由 {@code MapleCharacter.cloneLooks()} 建出來的
 * MapleCharacter——那是一個要讀寫角色幾十個私有欄位的工廠，所以留在原處，這裡只管
 * 「手上有哪幾隻、要生、要收」。方法內容逐字搬移，只把讀角色的地方改成經由 {@code chr}。
 * {@code MapleCharacter} 上同名同簽章的方法全部保留成轉呼叫（{@code disposeClones} 有兩支
 * 事件腳本在叫）。
 */
public final class PlayerClones {

    private final MapleCharacter chr;
    // 固定長度陣列，元素只是參考覆寫（addClone/disposeClones），沒有結構性改動 → 不需要同步。
    private final WeakReference<MapleCharacter>[] clones;
    private byte numClones;

    PlayerClones(final MapleCharacter chr, final boolean channelServer) {
        this.chr = chr;
        if (channelServer) {
            // 泛型陣列無法直接 new；陣列元素只由本類別以 WeakReference<MapleCharacter> 寫入，故轉型安全
            @SuppressWarnings("unchecked")
            final WeakReference<MapleCharacter>[] clonesArray = (WeakReference<MapleCharacter>[]) new WeakReference<?>[25];
            for (int i = 0; i < clonesArray.length; i++) {
                clonesArray[i] = new WeakReference<>(null);
            }
            clones = clonesArray;
        } else {
            clones = null; // 登入伺服器上的角色從來沒有分身，與搬家前一樣是 null
        }
    }

    /** reconstructChr 用：換頻前身上有幾隻分身，到了新頻道由 spawnClones() 重生。 */
    void setNumClones(final byte numClones) {
        this.numClones = numClones;
    }

    final void cloneLook() {
        if (chr.isClone()) {
            return;
        }
        for (int i = 0; i < clones.length; i++) {
            if (clones[i].get() == null) {
                final MapleCharacter newp = chr.cloneLooks();
                chr.getMap().addPlayer(newp);
                chr.getMap().broadcastMessage(CharacterPacket.updateCharLook(newp));
                chr.getMap().movePlayer(newp, chr.getPosition());
                clones[i] = new WeakReference<>(newp);
                return;
            }
        }
    }

    final void disposeClones() {
        numClones = 0;
        for (int i = 0; i < clones.length; i++) {
            if (clones[i].get() != null) {
                chr.getMap().removePlayer(clones[i].get());
                clones[i].get().getClient().disconnect(false, false);
                clones[i] = new WeakReference<>(null);
                numClones++;
            }
        }
    }

    final int getCloneSize() {
        int z = 0;
        for (int i = 0; i < clones.length; i++) {
            if (clones[i].get() != null) {
                z++;
            }
        }
        return z;
    }

    void spawnClones() {
        if (numClones == 0 && chr.getStat().hasClone) {
            cloneLook(); //once and never again
        }
        for (int i = 0; i < numClones; i++) {
            cloneLook();
        }
        numClones = 0;
    }

    byte getNumClones() {
        return numClones;
    }

    WeakReference<MapleCharacter>[] getClones() {
        return clones;
    }
}
