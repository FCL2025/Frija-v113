package handling.world;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import client.MapleClient;

/**
 * 目前連線中的 MapleClient 清單（E7 由 World 拆出）。
 */
public class Client {

    // Was a raw (ungenerified) ArrayList mutated from MapleServerHandler's
    // sessionOpened/sessionClosed, i.e. concurrently from every MINA I/O
    // thread, with no synchronization at all - live structural-corruption bug.
    // CopyOnWriteArrayList makes individual add/remove/iterate calls safe;
    // addClient is additionally synchronized so the "contains, then add"
    // check-then-act stays a single atomic step.
    private static final List<MapleClient> clients = new CopyOnWriteArrayList<>();

    public static synchronized void addClient(MapleClient c) {
        if (!clients.contains(c)) {
            clients.add(c);
        }
    }

    public static boolean removeClient(MapleClient c) {
        return clients.remove(c);
    }

    public static List<MapleClient> getClients() {
        return clients;
    }
}
