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
package server.quest;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import database.DatabaseConnection;

// final: the constructor hands 'this' to every MapleQuestRequirement and
// MapleQuestAction it builds, before the quest is loaded. Nothing extends it
// (-Xlint:this-escape).
public final class MapleCustomQuest extends MapleQuest {

    private static final System.Logger LOG = System.getLogger(MapleCustomQuest.class.getName());

    // questrequirements/questactions.data are ObjectOutputStream blobs holding a
    // MapleCustomQuestData tree (its own class, the java.util.LinkedList it keeps
    // its children in, the quest-value it wraps, and a String name) written by a
    // tool outside this tree. Nothing in this codebase serialises one, so on
    // deserialisation we cannot trust the blob's class graph any further than
    // that shape - this filter rejects everything else instead of letting
    // readObject() instantiate whatever class name the blob names.
    private static final ObjectInputFilter CUSTOM_QUEST_DATA_FILTER = ObjectInputFilter.Config.createFilter(
            "server.quest.MapleCustomQuestData;"
            + "java.lang.String;"
            + "java.lang.Integer;java.lang.Long;java.lang.Double;java.lang.Float;"
            + "java.lang.Boolean;java.lang.Byte;java.lang.Short;java.lang.Character;"
            + "java.util.LinkedList;"
            + "maxdepth=64;maxarray=10000;maxrefs=10000;maxbytes=10000000;"
            + "!*");

    public MapleCustomQuest(int id) {
        super(id);
        try (Connection con = DatabaseConnection.getConnection()) {
            MapleCustomQuestData data;

            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM questrequirements WHERE questid = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    MapleQuestRequirement req;
                    while (rs.next()) {
                        Blob blob = rs.getBlob("data");
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(blob.getBytes(1, (int) blob.length())));
                        ois.setObjectInputFilter(CUSTOM_QUEST_DATA_FILTER);
                        data = (MapleCustomQuestData) ois.readObject();
                        req = new MapleQuestRequirement(this, MapleQuestRequirementType.getByWZName(data.getName()), data);
                        final byte status = rs.getByte("status");
                        if (status == 0) {
                            startReqs.add(req);
                        } else if (status == 1) {
                            completeReqs.add(req);
                        }
                    }
                }
            }

            try (PreparedStatement ps = con.prepareStatement("SELECT * FROM questactions WHERE questid = ?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    MapleQuestAction act;
                    while (rs.next()) {
                        Blob blob = rs.getBlob("data");
                        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(blob.getBytes(1, (int) blob.length())));
                        ois.setObjectInputFilter(CUSTOM_QUEST_DATA_FILTER);
                        data = (MapleCustomQuestData) ois.readObject();
                        act = new MapleQuestAction(MapleQuestActionType.getByWZName(data.getName()), data, this);
                        final byte status = rs.getByte("status");
                        if (status == 0) {
                            startActs.add(act);
                        } else if (status == 1) {
                            completeActs.add(act);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            // One record where there were two prints: the trace and the message
            // are the same event, and the attached Throwable's first line is the
            // ex.toString() the message used to concatenate. The quest keeps only
            // the actions read before the failure.
            LOG.log(System.Logger.Level.ERROR, "Error loading custom quest from SQL.", ex);
        }
    }
}
