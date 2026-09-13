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

import client.MapleClient;
import tools.FileoutputUtil;

/**
 *
 * @author Matze
 */
public abstract class AbstractScriptManager {

    protected ScriptScope getInvocable(String path, MapleClient c) {
        return getInvocable(path, c, false);
    }

    /**
     * The script at {@code path}, ready to be called, in a scope private to
     * {@code c} - or null if it does not exist, cannot be read or will not
     * compile, which is what every caller already handles.
     *
     * <p>This used to build a whole Nashorn engine here, per conversation, and
     * read and evaluate the file into it. It now asks {@link ScriptCache} for
     * the script's compiled form - read and parsed once for the whole server -
     * and gives the conversation only its own Nashorn Global to put that
     * script's {@code var}s in. The engine is shared; the globals are not, so
     * two players in the same NPC still cannot see each other's {@code status}.
     *
     * <p>{@code c == null} means an event script, which has no client to be
     * private to and is asked for once per channel at startup; it gets a fresh
     * scope every call, exactly as it used to get a fresh engine.
     */
    protected ScriptScope getInvocable(String path, MapleClient c, boolean npc) {
        try {
            path = ScriptCache.ROOT + path;
            ScriptScope scope = null;

            if (c != null) {
                scope = c.getScriptScope(path);
            }
            if (scope == null) {
                scope = ScriptCache.load(path);
                if (scope == null) {
                    return null;
                }
                if (c != null) {
                    c.setScriptScope(path, scope);
                }
            } else if (c != null && npc) {
                c.getPlayer().dropMessage(1,"角色狀態異常，請使用@解卡/@ea 來解除異常狀態");
            }
            return scope;
        } catch (Exception e) {
            System.err.println("Error executing script. Path: " + path + "\nException " + e);
            FileoutputUtil.log(FileoutputUtil.ScriptEx_Log, "Error executing script. Path: " + path + "\nException " + e);
            return null;
        }
    }
}
