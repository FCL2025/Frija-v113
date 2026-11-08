package handling.world;

import java.util.List;
import client.MapleCharacter;
import handling.MaplePacket;
import handling.channel.ChannelServer;

/**
 * 跨頻道廣播（E7 由 World 拆出）。
 */
public class Broadcast {

    public static void broadcastSmega(byte[] message) {
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            cs.broadcastSmega(message);
        }
    }

    public static void broadcastGMMessage(byte[] message) {
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            cs.broadcastGMMessage(message);
        }
    }

    public static void broadcastMessage(byte[] message) {
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            cs.broadcastMessage(message);
        }
    }

    public static void sendPacket(List<Integer> targetIds, MaplePacket packet, int exception) {
        MapleCharacter c;
        for (int i : targetIds) {
            if (i == exception) {
                continue;
            }
            int ch = Find.findChannel(i);
            if (ch < 0) {
                continue;
            }
            c = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterById(i);
            if (c != null) {
                c.getClient().send(packet);
            }
        }
    }

    public static void sendGuildPacket(int targetIds, MaplePacket packet, int exception, int guildid) {
        if (targetIds == exception) {
            return;
        }
        int ch = Find.findChannel(targetIds);
        if (ch < 0) {
            return;
        }
        final MapleCharacter c = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterById(targetIds);
        if (c != null && c.getGuildId() == guildid) {
            c.getClient().send(packet);
        }
    }

    public static void sendFamilyPacket(int targetIds, MaplePacket packet, int exception, int guildid) {
        if (targetIds == exception) {
            return;
        }
        int ch = Find.findChannel(targetIds);
        if (ch < 0) {
            return;
        }
        final MapleCharacter c = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterById(targetIds);
        if (c != null && c.getFamilyId() == guildid) {
            c.getClient().send(packet);
        }
    }
}
