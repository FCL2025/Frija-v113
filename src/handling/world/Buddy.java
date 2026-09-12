package handling.world;

import client.BuddyList;
import client.BuddyList.BuddyAddResult;
import client.BuddyList.BuddyOperation;
import client.BuddylistEntry;
import client.MapleCharacter;
import handling.channel.ChannelServer;
import tools.packet.BuddyPacket;
import tools.packet.ChatPacket;

/**
 * 跨頻道的好友清單同步（E7 由 World 拆出）。
 */
public class Buddy {

    public static void buddyChat(int[] recipientCharacterIds, int cidFrom, String nameFrom, String chattext) {
        for (int characterId : recipientCharacterIds) {
            int ch = Find.findChannel(characterId);
            if (ch > 0) {
                MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterById(characterId);
                if (chr != null && chr.getBuddylist().containsVisible(cidFrom)) {
                    chr.getClient().send(ChatPacket.multiChat(nameFrom, chattext, 0));
                }
            }
        }
    }

    private static void updateBuddies(int characterId, int channel, int[] buddies, boolean offline, int gmLevel, boolean isHidden) {
        for (int buddy : buddies) {
            int ch = Find.findChannel(buddy);
            if (ch > 0) {
                MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterById(buddy);
                if (chr != null) {
                    BuddylistEntry ble = chr.getBuddylist().get(characterId);
                    if (ble != null && ble.isVisible()) {
                        int mcChannel;
                        if (offline || (isHidden && chr.getGMLevel() < gmLevel)) {
                            ble.setChannel(-1);
                            mcChannel = -1;
                        } else {
                            ble.setChannel(channel);
                            mcChannel = channel - 1;
                        }
                        chr.getBuddylist().put(ble);
                        chr.getClient().send(BuddyPacket.updateBuddyChannel(ble.getCharacterId(), mcChannel));
                    }
                }
            }
        }
    }

    public static void buddyChanged(int cid, int cidFrom, String name, int channel, BuddyOperation operation, int level, int job, String group) {
        int ch = Find.findChannel(cid);
        if (ch > 0) {
            final MapleCharacter addChar = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterById(cid);
            if (addChar != null) {
                final BuddyList buddylist = addChar.getBuddylist();
                switch (operation) {
                    case ADDED:
                        if (buddylist.contains(cidFrom)) {
                            buddylist.put(new BuddylistEntry(name, cidFrom, group, channel, true, level, job));
                            addChar.getClient().send(BuddyPacket.updateBuddyChannel(cidFrom, channel - 1));
                        }
                        break;
                    case DELETED:
                        if (buddylist.contains(cidFrom)) {
                            buddylist.put(new BuddylistEntry(name, cidFrom, group, -1, buddylist.get(cidFrom).isVisible(), level, job));
                            addChar.getClient().send(BuddyPacket.updateBuddyChannel(cidFrom, -1));
                        }
                        break;
                }
            }
        }
    }

    public static BuddyAddResult requestBuddyAdd(String addName, int channelFrom, int cidFrom, String nameFrom, int levelFrom, int jobFrom) {
        int ch = Find.findChannel(cidFrom);
        if (ch > 0) {
            final MapleCharacter addChar = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(addName);
            if (addChar != null) {
                final BuddyList buddylist = addChar.getBuddylist();
                if (buddylist.isFull()) {
                    return BuddyAddResult.BUDDYLIST_FULL;
                }
                if (!buddylist.contains(cidFrom)) {
                    buddylist.addBuddyRequest(addChar.getClient(), cidFrom, nameFrom, channelFrom, levelFrom, jobFrom);
                } else {
                    if (buddylist.containsVisible(cidFrom)) {
                        return BuddyAddResult.ALREADY_ON_LIST;
                    }
                }
            }
        }
        return BuddyAddResult.OK;
    }

    public static void loggedOn(String name, int characterId, int channel, int[] buddies, int gmLevel, boolean isHidden) {
        updateBuddies(characterId, channel, buddies, false, gmLevel, isHidden);
    }

    public static void loggedOff(String name, int characterId, int channel, int[] buddies, int gmLevel, boolean isHidden) {
        updateBuddies(characterId, channel, buddies, true, gmLevel, isHidden);
    }
}
