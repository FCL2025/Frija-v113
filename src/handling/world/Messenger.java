package handling.world;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import client.MapleCharacter;
import handling.channel.ChannelServer;
import tools.packet.MessengerPacket;

/**
 * 跨頻道的楓葉通訊（Messenger）視窗（E7 由 World 拆出）。
 */
public class Messenger {

    private static final Map<Integer, MapleMessenger> messengers = new HashMap<>();
    private static final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static final AtomicInteger runningMessengerId = new AtomicInteger();

    static {
        runningMessengerId.set(1);
    }

    public static MapleMessenger createMessenger(MapleMessengerCharacter chrfor) {
        int messengerid = runningMessengerId.getAndIncrement();
        MapleMessenger messenger = new MapleMessenger(messengerid, chrfor);
        lock.writeLock().lock();
        try {
            messengers.put(messenger.getId(), messenger);
        } finally {
            lock.writeLock().unlock();
        }
        return messenger;
    }

    public static void declineChat(String target, String namefrom) {
        int ch = Find.findChannel(target);
        if (ch > 0) {
            ChannelServer cs = ChannelServer.getInstance(ch);
            MapleCharacter chr = cs.getPlayerStorage().getCharacterByName(target);
            if (chr != null) {
                MapleMessenger messenger = chr.getMessenger();
                if (messenger != null) {
                    chr.getClient().send(MessengerPacket.messengerNote(namefrom, 5, 0));
                }
            }
        }
    }

    public static MapleMessenger getMessenger(int messengerid) {
        lock.readLock().lock();
        try {
            return messengers.get(messengerid);
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void leaveMessenger(int messengerid, MapleMessengerCharacter target) {
        // Lookup, member removal and the resulting broadcast are kept under one
        // lock: a leave racing a join (or another leave) on the same messenger
        // window must not hand out a position computed against a member list
        // that has since changed.
        lock.writeLock().lock();
        try {
            MapleMessenger messenger = messengers.get(messengerid);
            if (messenger == null) {
                throw new IllegalArgumentException("No messenger with the specified messengerid exists");
            }
            int position = messenger.getPositionByName(target.getName());
            messenger.removeMember(target);

            for (MapleMessengerCharacter mmc : messenger.getMembers()) {
                if (mmc != null) {
                    int ch = Find.findChannel(mmc.getId());
                    if (ch > 0) {
                        MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(mmc.getName());
                        if (chr != null) {
                            chr.getClient().send(MessengerPacket.removeMessengerPlayer(position));
                        }
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void silentLeaveMessenger(int messengerid, MapleMessengerCharacter target) {
        lock.writeLock().lock();
        try {
            MapleMessenger messenger = messengers.get(messengerid);
            if (messenger == null) {
                throw new IllegalArgumentException("No messenger with the specified messengerid exists");
            }
            messenger.silentRemoveMember(target);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void silentJoinMessenger(int messengerid, MapleMessengerCharacter target) {
        lock.writeLock().lock();
        try {
            MapleMessenger messenger = messengers.get(messengerid);
            if (messenger == null) {
                throw new IllegalArgumentException("No messenger with the specified messengerid exists");
            }
            messenger.silentAddMember(target);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void updateMessenger(int messengerid, String namefrom, int fromchannel) {
        lock.readLock().lock();
        try {
            MapleMessenger messenger = messengers.get(messengerid);
            int position = messenger.getPositionByName(namefrom);

            for (MapleMessengerCharacter messengerchar : messenger.getMembers()) {
                if (messengerchar != null && !messengerchar.getName().equals(namefrom)) {
                    int ch = Find.findChannel(messengerchar.getName());
                    if (ch > 0) {
                        MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(messengerchar.getName());
                        if (chr != null) {
                            MapleCharacter from = ChannelServer.getInstance(fromchannel).getPlayerStorage().getCharacterByName(namefrom);
                            chr.getClient().send(MessengerPacket.updateMessengerPlayer(namefrom, from, position, fromchannel - 1));
                        }
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void joinMessenger(int messengerid, MapleMessengerCharacter target, String from, int fromchannel) {
        lock.writeLock().lock();
        try {
            MapleMessenger messenger = messengers.get(messengerid);
            if (messenger == null) {
                throw new IllegalArgumentException("No messenger with the specified messengerid exists");
            }
            messenger.addMember(target);
            int position = messenger.getPositionByName(target.getName());
            for (MapleMessengerCharacter messengerchar : messenger.getMembers()) {
                if (messengerchar != null) {
                    int mposition = messenger.getPositionByName(messengerchar.getName());
                    int ch = Find.findChannel(messengerchar.getName());
                    if (ch > 0) {
                        MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(messengerchar.getName());
                        if (chr != null) {
                            if (!messengerchar.getName().equals(from)) {
                                MapleCharacter fromCh = ChannelServer.getInstance(fromchannel).getPlayerStorage().getCharacterByName(from);
                                chr.getClient().send(MessengerPacket.addMessengerPlayer(from, fromCh, position, fromchannel - 1));
                                fromCh.getClient().send(MessengerPacket.addMessengerPlayer(chr.getName(), chr, mposition, messengerchar.getChannel() - 1));
                            } else {
                                chr.getClient().send(MessengerPacket.joinMessenger(mposition));
                            }
                        }
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static void messengerChat(int messengerid, String chattext, String namefrom) {
        lock.readLock().lock();
        try {
            MapleMessenger messenger = messengers.get(messengerid);
            if (messenger == null) {
                throw new IllegalArgumentException("No messenger with the specified messengerid exists");
            }

            for (MapleMessengerCharacter messengerchar : messenger.getMembers()) {
                if (messengerchar != null && !messengerchar.getName().equals(namefrom)) {
                    int ch = Find.findChannel(messengerchar.getName());
                    if (ch > 0) {
                        MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(messengerchar.getName());
                        if (chr != null) {

                            chr.getClient().send(MessengerPacket.messengerChat(chattext));
                        }
                    }
                } //Whisp Monitor Code
                else if (messengerchar != null) {
                    int ch = Find.findChannel(messengerchar.getName());
                    if (ch > 0) {
                        MapleCharacter chr = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(messengerchar.getName());
                    }
                }
                //
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public static void messengerInvite(String sender, int messengerid, String target, int fromchannel, boolean gm) {

        if (World.isConnected(target)) {

            int ch = Find.findChannel(target);
            if (ch > 0) {
                MapleCharacter from = ChannelServer.getInstance(fromchannel).getPlayerStorage().getCharacterByName(sender);
                MapleCharacter targeter = ChannelServer.getInstance(ch).getPlayerStorage().getCharacterByName(target);
                if (targeter != null && targeter.getMessenger() == null) {
                    if (!targeter.isGM() || gm) {
                        targeter.getClient().send(MessengerPacket.messengerInvite(sender, messengerid));
                        from.getClient().send(MessengerPacket.messengerNote(target, 4, 1));
                    } else {
                        from.getClient().send(MessengerPacket.messengerNote(target, 4, 0));
                    }
                } else {
                    from.getClient().send(MessengerPacket.messengerChat(sender + " : " + target + " is already using Maple Messenger"));
                }
            }
        }

    }
}
