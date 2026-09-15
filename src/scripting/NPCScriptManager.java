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
package scripting;

import java.util.Map;
import client.MapleClient;
import java.util.WeakHashMap;
import java.util.concurrent.locks.Lock;
import server.quest.MapleQuest;
import tools.FileoutputUtil;

public class NPCScriptManager extends AbstractScriptManager {

    private static final System.Logger LOG = System.getLogger(NPCScriptManager.class.getName());

    private final Map<MapleClient, NPCConversationManager> cms = new WeakHashMap<>();
    private static final NPCScriptManager instance = new NPCScriptManager();

    public static final NPCScriptManager getInstance() {
        return instance;
    }

    public final void start(final MapleClient c, final int npc) {
        final Lock lock = c.getNPCLock();
        lock.lock();
        try {
            if (c.getPlayer().isGM()) {
                c.getPlayer().dropMessage("[系統提示]您已經建立與NPC:" + npc + "的對話(start)。");
            }
            if (!cms.containsKey(c)) {
                ScriptScope iv = getInvocable("npc/" + npc + ".js", c, true);
                if (iv == null) {

                    iv = getInvocable("npc/notcoded.js", c, true); //safe disposal

                    if (iv == null) {
                        dispose(c);
                        return;
                    }
                }
                final NPCConversationManager cm = new NPCConversationManager(c, npc, -1, (byte) -1, iv);
                cms.put(c, cm);

                iv.put("cm", cm);
                iv.put("npcid", npc);
                c.getPlayer().setConversation(1);

                try {
                    iv.invokeFunction("start"); // Temporary until I've removed all of start
                } catch (NoSuchMethodException nsme) {
                    iv.invokeFunction("action", (byte) 1, (byte) 0, 0);
                }
            } else {
                c.getPlayer().dropMessage(1,"角色狀態異常，請使用@解卡/@ea 來解除異常狀態");
            }

        } catch (final Exception e) {
            LOG.log(System.Logger.Level.ERROR, "NPC 腳本錯誤, 它ID為 : " + npc + "." + e);
            if (c.getPlayer().isGM()) {
                c.getPlayer().dropMessage("[系統提示] NPC " + npc + "腳本錯誤 " + e + "");
            }
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing NPC script, NPC ID : " + npc + "." + e);
            dispose(c);
        } finally {
            lock.unlock();
        }
    }

    public final void action(final MapleClient c, final byte mode, final byte type, final int selection) {
        if (mode != -1) {
            final NPCConversationManager cm = cms.get(c);
            if (cm == null || cm.getLastMsg() > -1) {
                return;
            }
            final Lock lock = c.getNPCLock();
            lock.lock();
            try {

                if (cm.pendingDisposal) {
                    dispose(c);
                } else {
                    cm.getIv().invokeFunction("action", mode, type, selection);
                }
            } catch (final Exception e) {
                if (c.getPlayer().isGM()) {
                    c.getPlayer().dropMessage("[系統提示] NPC " + cm.getNpc() + "腳本錯誤 " + e + "");
                }
                LOG.log(System.Logger.Level.ERROR, "NPC 腳本錯誤. 它ID為 : " + cm.getNpc() + ":" + e);
                dispose(c);
                FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing NPC script, NPC ID : " + cm.getNpc() + "." + e);
            } finally {
                lock.unlock();
            }
        }
    }

    public final void startQuest(final MapleClient c, final int npc, final int quest) {
        if (!MapleQuest.getInstance(quest).canStart(c.getPlayer(), null)) {
            return;
        }
        final Lock lock = c.getNPCLock();
        lock.lock();
        try {
            if (!cms.containsKey(c)) {
                final ScriptScope iv = getInvocable("quest/" + quest + ".js", c, true);
                if (iv == null) {
                    dispose(c);
                    return;
                }
                final NPCConversationManager cm = new NPCConversationManager(c, npc, quest, (byte) 0, iv);
                cms.put(c, cm);
                iv.put("qm", cm);

                c.getPlayer().setConversation(1);
                if (c.getPlayer().isGM()) {
                    c.getPlayer().dropMessage("[系統提示]您已經建立與任務腳本:" + quest + "的往來。");
                }
                iv.invokeFunction("start", (byte) 1, (byte) 0, 0); // start it off as something
            } else {
                // 保留作為紀錄：已經在對話中時原本會提示玩家用 @ea 解卡，這則提示被關掉了，
                // 所以這個 else 分支現在完全沒有回應（訊息也是英文的，BIG5 客戶端看得到但不合本服語系）。
                // c.getPlayer().dropMessage(-1, "You already are talking to an NPC. Use @ea if this is not intended.");
            }
        } catch (final Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Error executing Quest script. (" + quest + ")..NPCID: " + npc + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing Quest script. (" + quest + ")..NPCID: " + npc + ":" + e);
            dispose(c);
        } finally {
            lock.unlock();
        }
    }

    public final void startQuest(final MapleClient c, final byte mode, final byte type, final int selection) {
        final Lock lock = c.getNPCLock();
        final NPCConversationManager cm = cms.get(c);
        if (cm == null || cm.getLastMsg() > -1) {
            return;
        }
        lock.lock();
        try {
            if (cm.pendingDisposal) {
                dispose(c);
            } else {
                cm.getIv().invokeFunction("start", mode, type, selection);
            }
        } catch (Exception e) {
            if (c.getPlayer().isGM()) {
                c.getPlayer().dropMessage("[系統提示]任務腳本:" + cm.getQuest() + "錯誤...NPC: " + cm.getNpc() + ":" + e);
            }
            LOG.log(System.Logger.Level.ERROR, "Error executing Quest script. (" + cm.getQuest() + ")...NPC: " + cm.getNpc() + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing Quest script. (" + cm.getQuest() + ")..NPCID: " + cm.getNpc() + ":" + e);
            dispose(c);
        } finally {
            lock.unlock();
        }
    }

    public final void endQuest(final MapleClient c, final int npc, final int quest, final boolean customEnd) {
        if (!customEnd && !MapleQuest.getInstance(quest).canComplete(c.getPlayer(), null)) {
            return;
        }
        final Lock lock = c.getNPCLock();
        // 保留作為紀錄：其他幾個方法（action / endQuest 以外）在鎖之後都會先取出既有的 cm，
        // endQuest 刻意不取——它只在 cms 裡沒有東西時才自己建一個新的。
        //final NPCConversationManager cm = cms.get(c);
        lock.lock();
        try {
            if (!cms.containsKey(c)) {
                final ScriptScope iv = getInvocable("quest/" + quest + ".js", c, true);
                if (iv == null) {
                    dispose(c);
                    return;
                }
                final NPCConversationManager cm = new NPCConversationManager(c, npc, quest, (byte) 1, iv);
                cms.put(c, cm);
                iv.put("qm", cm);

                c.getPlayer().setConversation(1);
                iv.invokeFunction("end", (byte) 1, (byte) 0, 0); // start it off as something
            } else {
                // 保留作為紀錄：已經在對話中時原本會提示玩家用 @ea 解卡，這則提示被關掉了，
                // 所以這個 else 分支現在完全沒有回應（訊息也是英文的，BIG5 客戶端看得到但不合本服語系）。
                // c.getPlayer().dropMessage(-1, "You already are talking to an NPC. Use @ea if this is not intended.");
            }
        } catch (Exception e) {
            if (c.getPlayer().isGM()) {
                c.getPlayer().dropMessage("[系統提示]任務腳本:" + quest + "錯誤...NPC: " + quest + ":" + e);
            }
            LOG.log(System.Logger.Level.ERROR, "Error executing Quest script. (" + quest + ")..NPCID: " + npc + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing Quest script. (" + quest + ")..NPCID: " + npc + ":" + e);
            dispose(c);
        } finally {
            lock.unlock();
        }
    }

    public final void endQuest(final MapleClient c, final byte mode, final byte type, final int selection) {
        final Lock lock = c.getNPCLock();
        final NPCConversationManager cm = cms.get(c);
        if (cm == null || cm.getLastMsg() > -1) {
            return;
        }
        lock.lock();
        try {
            if (cm.pendingDisposal) {
                dispose(c);
            } else {
                cm.getIv().invokeFunction("end", mode, type, selection);
            }
        } catch (Exception e) {
            if (c.getPlayer().isGM()) {
                c.getPlayer().dropMessage("[系統提示]任務腳本:" + cm.getQuest() + "錯誤...NPC: " + cm.getNpc() + ":" + e);
            }
            LOG.log(System.Logger.Level.ERROR, "Error executing Quest script. (" + cm.getQuest() + ")...NPC: " + cm.getNpc() + ":" + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing Quest script. (" + cm.getQuest() + ")..NPCID: " + cm.getNpc() + ":" + e);
            dispose(c);
        } finally {
            lock.unlock();
        }
    }

    public final void dispose(final MapleClient c) {
        final NPCConversationManager npccm = cms.get(c);
        if (npccm != null) {
            cms.remove(c);
            if (npccm.getType() == -1) {
                c.removeScriptScope("Libs/scripts/npc/" + npccm.getNpc() + ".js");
                c.removeScriptScope("Libs/scripts/npc/notcoded.js");
            } else {
                c.removeScriptScope("Libs/scripts/quest/" + npccm.getQuest() + ".js");
            }
        }
        if (c.getPlayer() != null && c.getPlayer().getConversation() == 1) {
            c.getPlayer().setConversation(0);
        }
    }

    public final NPCConversationManager getCM(final MapleClient c) {
        return cms.get(c);
    }
}
