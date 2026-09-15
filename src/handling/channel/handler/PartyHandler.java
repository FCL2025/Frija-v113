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
package handling.channel.handler;

import client.MapleCharacter;
import client.MapleClient;
import handling.world.MapleParty;
import handling.world.MaplePartyCharacter;
import handling.world.Party;
import handling.world.PartyOperation;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.packet.ChatPacket;
import tools.packet.PartyPacket;

public class PartyHandler {

    private static final System.Logger LOG = System.getLogger(PartyHandler.class.getName());

    public static final void DenyPartyRequest(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        final int action = slea.readByte();
        final int partyid = slea.readInt();
        if (c.getPlayer().getParty() == null) {
            MapleParty party = Party.getParty(partyid);
            if (party != null) {
                if (action == 0x1B) { //accept
                    if (party.getMembers().size() < 6) {
                        Party.updateParty(partyid, PartyOperation.JOIN, new MaplePartyCharacter(c.getPlayer()));
                        c.getPlayer().receivePartyMemberHP();
                        c.getPlayer().updatePartyMemberHP();
                    } else {
                        c.send(PartyPacket.partyStatusMessage(17));
                    }
                } else if (action != 0x16) {
                    final MapleCharacter cfrom = c.getChannelServer().getPlayerStorage().getCharacterById(party.getLeader().getId());
                    if (cfrom != null) {
                        cfrom.getClient().send(PartyPacket.partyStatusMessage(23, c.getPlayer().getName()));
                    }
                }
            } else {
                c.getPlayer().dropMessage(5, "該隊伍不存在");
            }
        } else {
            c.getPlayer().dropMessage(5, "你無法重複加入隊伍");
        }

    }

    public static final void PartyOperatopn(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        final int operation = slea.readByte();
        MapleParty party = c.getPlayer().getParty();
        MaplePartyCharacter partyplayer = new MaplePartyCharacter(c.getPlayer());

        switch (operation) {
            case 1: // create
                if (c.getPlayer().getParty() == null) {
                    party = Party.createParty(partyplayer);
                    c.getPlayer().setParty(party);
                    c.send(PartyPacket.partyCreated(party.getId()));

                } else {
                    if (partyplayer.equals(party.getLeader()) && party.getMembers().size() == 1) { //only one, reupdate
                        c.send(PartyPacket.partyCreated(party.getId()));
                    } else {
                        c.getPlayer().dropMessage(5, "你無法重複創建隊伍");
                    }
                }
                break;
            case 2: // leave
                if (party != null) { //are we in a party? o.O"
                    if (partyplayer.equals(party.getLeader())) { // disband
                        Party.updateParty(party.getId(), PartyOperation.DISBAND, partyplayer);
                        if (c.getPlayer().getEventInstance() != null) {
                            c.getPlayer().getEventInstance().disbandParty();
                        }
                        if (c.getPlayer().getPyramidSubway() != null) {
                            c.getPlayer().getPyramidSubway().fail(c.getPlayer());
                        }
                    } else {
                        Party.updateParty(party.getId(), PartyOperation.LEAVE, partyplayer);
                        if (c.getPlayer().getEventInstance() != null) {
                            c.getPlayer().getEventInstance().leftParty(c.getPlayer());
                        }
                        if (c.getPlayer().getPyramidSubway() != null) {
                            c.getPlayer().getPyramidSubway().fail(c.getPlayer());
                        }
                    }
                    c.getPlayer().setParty(null);
                }
                break;
            case 3: // accept invitation
                final int partyid = slea.readInt();
                if (c.getPlayer().getParty() == null) {
                    party = Party.getParty(partyid);
                    if (party != null) {
                        if (party.getMembers().size() < 6) {
                            Party.updateParty(party.getId(), PartyOperation.JOIN, partyplayer);
                            c.getPlayer().receivePartyMemberHP();
                            c.getPlayer().updatePartyMemberHP();
                        } else {
                            c.send(PartyPacket.partyStatusMessage(17));
                        }
                    } else {
                        c.getPlayer().dropMessage(5, "該隊伍不存在");
                    }
                } else {
                    c.getPlayer().dropMessage(5, "你無法重複加入隊伍");
                }
                break;
            case 4: // invite
                // TODO store pending invitations and check against them
                final MapleCharacter invited = c.getChannelServer().getPlayerStorage().getCharacterByName(slea.readMapleAsciiString());
                if (invited != null) {
                    if (invited.getParty() == null && party != null) {
                        if (party.getMembers().size() < 6) {
                            // 保留作為紀錄：原本用 partyStatusMessage(22, name) 這個系統訊息，
                            // 本服改成自己送一則中文公告；兩者擇一，不要同時送。
                            //c.send(PartyPacket.partyStatusMessage(22, invited.getName()));
                            c.send(ChatPacket.serverNotice(5,"發出隊伍邀請給'" + invited.getName()+"'"));
                            invited.getClient().send(PartyPacket.partyInvite(c.getPlayer()));
                        } else {
                            c.send(PartyPacket.partyStatusMessage(16));
                        }
                    } else {
                        c.send(PartyPacket.partyStatusMessage(17));
                    }
                } else {
                    c.send(PartyPacket.partyStatusMessage(19));
                }
                break;
            case 5: // expel
                if (partyplayer.equals(party.getLeader())) {
                    final MaplePartyCharacter expelled = party.getMemberById(slea.readInt());
                    if (expelled != null) {
                        Party.updateParty(party.getId(), PartyOperation.EXPEL, expelled);
                        if (c.getPlayer().getEventInstance() != null) {
                            /*if leader wants to boot someone, then the whole party gets expelled
                             TODO: Find an easier way to get the character behind a MaplePartyCharacter
                             possibly remove just the expellee.*/
                            if (expelled.isOnline()) {
                                c.getPlayer().getEventInstance().disbandParty();
                            }
                        }
                        if (c.getPlayer().getPyramidSubway() != null && expelled.isOnline()) {
                            c.getPlayer().getPyramidSubway().fail(c.getPlayer());
                        }
                    }
                }
                break;
            case 6: // change leader
                if (party != null) {
                    final MaplePartyCharacter newleader = party.getMemberById(slea.readInt());
                    if (newleader != null && partyplayer.equals(party.getLeader())) {
                        Party.updateParty(party.getId(), PartyOperation.CHANGE_LEADER, newleader);
                    }
                }
                break;
            default:
                LOG.log(System.Logger.Level.INFO, "Unhandled Party function." + operation);
                break;
        }
    }
}
