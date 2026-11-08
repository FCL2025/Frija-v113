package handling.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import client.MapleCharacter;
import client.MapleCoolDownValueHolder;
import client.MapleDiseaseValueHolder;
import client.inventory.MapleInventoryType;
import client.inventory.MaplePet;
import client.inventory.PetDataFactory;
import handling.cashshop.CashShopServer;
import handling.channel.ChannelServer;
import handling.channel.PlayerStorage;
import server.Timer;
import server.maps.MapleMap;
import server.maps.MapleMapItem;
import tools.CollectionUtil;
import tools.packet.CharacterPacket;
import tools.packet.PetPacket;

public class World {

    public static volatile boolean isShutDown = false;
    //Touch everything...
    public static void init() {
        Find.findChannel(0);
        Guild.init();
        Alliance.init();
        Family.init();
        Messenger.getMessenger(0);
        Party.getParty(0);
    }

    public static Map<Integer, Integer> getConnected() {
        Map<Integer, Integer> ret = new HashMap<>();
        int total = 0;
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            int curConnected = cs.getConnectedClients();
            ret.put(cs.getChannel(), curConnected);
            total += curConnected;
        }
        ret.put(0, total);
        return ret;
    }

    public static List<CheaterData> getCheaters() {
        List<CheaterData> allCheaters = new ArrayList<>();
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            allCheaters.addAll(cs.getCheaters());
        }
        Collections.sort(allCheaters);
        return CollectionUtil.copyFirst(allCheaters, 10);
    }

    public static boolean isConnected(String charName) {
        return Find.findChannel(charName) > 0;
    }

    public static void toggleMegaphoneMuteState() {
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            cs.toggleMegaphoneMuteState();
        }
    }

    public static void channelChange_Data(CharacterTransfer Data, int characterid, int toChannel) {
        getStorage(toChannel).registerPendingPlayer(Data, characterid);
    }

    public static boolean isCharacterListConnected(List<String> charName) {
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            for (final String c : charName) {
                if (cs.getPlayerStorage().getCharacterByName(c) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean hasMerchant(int accountID) {
        for (ChannelServer cs : ChannelServer.getAllInstances()) {
            if (cs.containsMerchant(accountID)) {
                return true;
            }
        }
        return false;
    }

    public static PlayerStorage getStorage(int channel) {
        if (channel == -20) {
            return CashShopServer.getPlayerStorageMTS();
        } else if (channel == -10) {
            return CashShopServer.getPlayerStorage();
        }
        return ChannelServer.getInstance(channel).getPlayerStorage();
    }

    public static void registerRespawn() {
        Timer.WORLD.register(new Respawn(), 3000); //divisible by 9000 if possible.
        //3000 good or bad? ive no idea >_>
        //buffs can also be done, but eh
    }

    public static void handleMap(final MapleMap map, final int numTimes, final int size) {
        if (map.getItemsSize() > 0) {
            for (MapleMapItem item : map.getAllItemsThreadsafe()) {
                if (item.shouldExpire()) {
                    item.expire(map);
                } else if (item.shouldFFA()) {
                    item.setDropType((byte) 2);
                }
            }
        }
        if (map.characterSize() > 0) {
            if (map.canSpawn()) {
                map.respawn(false);
            }
            boolean hurt = map.canHurt();
            for (MapleCharacter chr : map.getCharactersThreadsafe()) {
                handleCooldowns(chr, numTimes, hurt);
            }
        }
    }

    public static void handleCooldowns(final MapleCharacter chr, final int numTimes, final boolean hurt) { //is putting it here a good idea? expensive?
        final long now = System.currentTimeMillis();
        for (MapleCoolDownValueHolder m : chr.getCooldowns()) {
            if (m.startTime + m.length < now) {
                final int skil = m.skillId;
                chr.removeCooldown(skil);
                chr.getClient().send(CharacterPacket.skillCooldown(skil, 0));
            }
        }
        for (MapleDiseaseValueHolder m : chr.getAllDiseases()) {
            if (m.startTime + m.length < now) {
                chr.dispelDebuff(m.disease);
            }
        }
        if (numTimes % 20 == 0) { //we're parsing through the characters anyway (:
            for (MaplePet pet : chr.getPets()) {
                if (pet.getSummoned()) {
                    if (pet.getPetItemId() == 5000054 && pet.getSecondsLeft() > 0) {
                        pet.setSecondsLeft(pet.getSecondsLeft() - 1);
                        if (pet.getSecondsLeft() <= 0) {
                            chr.unequipPet(pet, true, true);
                            return;
                        }
                    }
                    int newFullness = pet.getFullness() - PetDataFactory.getHunger(pet.getPetItemId());
                    if (newFullness <= 5) {
                        pet.setFullness(15);
                        chr.unequipPet(pet, true, true);
                    } else {
                        pet.setFullness(newFullness);
                        chr.getClient().send(PetPacket.updatePet(pet, chr.getInventory(MapleInventoryType.CASH).getItem(pet.getInventoryPosition())));
                    }
                }
            }
        }
        if (hurt && chr.isAlive()) {
            if (chr.getInventory(MapleInventoryType.EQUIPPED).findById(chr.getMap().getHPDecProtect()) == null) {
                if (chr.getMapId() == 749040100 && chr.getInventory(MapleInventoryType.CASH).findById(5451000) == null) { //minidungeon
                    chr.addHP(-chr.getMap().getHPDec());
                } else if (chr.getMapId() != 749040100) {
                    chr.addHP(-chr.getMap().getHPDec());
                }
            }
        }
    }
}
