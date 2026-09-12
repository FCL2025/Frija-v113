package server;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class MapleShopFactory {

    /**
     * Filled in lazily by whichever Maple-Handler-N thread first opens a given
     * shop, and emptied by {@code !reloadshops} or the panel's shop editor on a
     * Panel-Worker-N thread - two pools on the same map, so it cannot be a
     * plain HashMap.
     *
     * <p>The value is an {@link Optional} because "we asked the database for
     * this id and it has no shop" is itself worth caching, and a
     * ConcurrentHashMap will not hold the null the old code used to say that
     * with. Nothing here is loaded under a lock: two threads racing on the same
     * id both run {@code createFromDB} and store equivalent shops, which is
     * cheaper than serialising every miss behind the database call.
     */
    private final Map<Integer, Optional<MapleShop>> shops = new ConcurrentHashMap<Integer, Optional<MapleShop>>();
    /** The same shops again, keyed by the NPC that opens them; see {@link #shops}. */
    private final Map<Integer, Optional<MapleShop>> npcShops = new ConcurrentHashMap<Integer, Optional<MapleShop>>();
    private static final MapleShopFactory instance = new MapleShopFactory();

    public static MapleShopFactory getInstance() {
        return instance;
    }

    public void clear() {
        shops.clear();
        npcShops.clear();
    }

    public MapleShop getShop(int shopId) {
        final Optional<MapleShop> cached = shops.get(shopId);
        if (cached != null) {
            return cached.orElse(null);
        }
        return loadShop(shopId, true);
    }

    public MapleShop getShopForNPC(int npcId) {
        final Optional<MapleShop> cached = npcShops.get(npcId);
        if (cached != null) {
            return cached.orElse(null);
        }
        return loadShop(npcId, false);
    }

    private MapleShop loadShop(int id, boolean isShopId) {
        MapleShop ret = MapleShop.createFromDB(id, isShopId);
        if (ret != null) {
            final Optional<MapleShop> loaded = Optional.of(ret);
            shops.put(ret.getId(), loaded);
            npcShops.put(ret.getNpcId(), loaded);
        } else if (isShopId) {
            shops.put(id, Optional.<MapleShop>empty());
        } else {
            npcShops.put(id, Optional.<MapleShop>empty());
        }
        return ret;
    }
}
