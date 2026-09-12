package handling.world;

import handling.channel.ChannelServer;
import server.maps.MapleMap;

/**
 * WorldTimer 上的重生／掉落物過期／冷卻掃描工作（E7 由 World 拆出）。
 */
public class Respawn implements Runnable { //is putting it here a good idea?

    private int numTimes = 0;

    @Override
    public void run() {
        numTimes++;
        for (ChannelServer cserv : ChannelServer.getAllInstances()) {
            for (MapleMap map : cserv.getMapFactory().getAllMaps()) { //iterating through each map o_x
                World.handleMap(map, numTimes, map.getCharactersSize());
            }
            for (MapleMap map : cserv.getMapFactory().getAllInstanceMaps()) {
                World.handleMap(map, numTimes, map.getCharactersSize());
            }
        }
    }
}
