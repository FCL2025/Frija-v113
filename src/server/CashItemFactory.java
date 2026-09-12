package server;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.PreparedStatement;
import database.DatabaseConnection;
import handling.cashshop.CashShopServer;
import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import provider.MapleData;
import provider.MapleDataProvider;
import provider.MapleDataProviderFactory;
import provider.MapleDataTool;
import server.CashItemInfo.CashModInfo;

public class CashItemFactory {

    private final static CashItemFactory instance = new CashItemFactory();
    private final static int[] bestItems = new int[]{50400003, 50300092, 30200034, 50100010, 50300099};
    // volatile: read unsynchronized by getModInfo()/getAllModInfo() from any
    // handler thread, written under the initialize() lock below.
    private volatile boolean initialized = false;
    // These three are fully populated by initialize() and never written again,
    // so they're a load-once/read-many cache. Still ConcurrentHashMap:
    // getAllModInfo()/getPackageItems()'s "if (!initialized) initialize();" is
    // an unsynchronized check-then-act, so a handler thread can be reading one
    // of these while the startup thread is still filling it.
    private final Map<Integer, CashItemInfo> itemStats = new ConcurrentHashMap<>();
    private final Map<Integer, List<CashItemInfo>> itemPackage = new ConcurrentHashMap<>();
    private final Map<Integer, CashModInfo> itemMods = new ConcurrentHashMap<>();
    private final MapleDataProvider data = MapleDataProviderFactory.getDataProvider(new File(System.getProperty("net.sf.odinms.wzpath") + "/Etc.wz"));

    public static final CashItemFactory getInstance() {
        return instance;
    }

    protected CashItemFactory() {
    }

    public synchronized void initialize() {
        // getAllModInfo() calls this lazily via "if (!initialized) initialize();",
        // an unsynchronized check-then-act; guard against two threads racing
        // through that and both re-running the full load concurrently.
        if (initialized) {
            return;
        }
        System.out.println("Loading CashItemFactory :::");
        for (MapleData field : data.getData("Commodity.img").getChildren()) {
            final int itemId = MapleDataTool.getIntConvert("ItemId", field, 0);
            final int SN = MapleDataTool.getIntConvert("SN", field, 0);

            final CashItemInfo stats = new CashItemInfo(itemId,
                    MapleDataTool.getIntConvert("Count", field, 1),
                    MapleDataTool.getIntConvert("Price", field, 0), SN,
                    MapleDataTool.getIntConvert("Period", field, 0),
                    MapleDataTool.getIntConvert("Gender", field, 2),
                    MapleDataTool.getIntConvert("OnSale", field, 0) > 0);

            if (SN > 0) {
                itemStats.put(SN, stats);
            }
        }
        loadPackages();
        loadModInfo();
        for (int i : itemStats.keySet()) {
            getItem(i); //init the modinfo's citem
        }
        initialized = true;
    }

    /**
     * Reads every package in CashPackage.img in one pass.
     *
     * <p>This used to be a {@code getPackageItems(itemId)} call per ItemId in
     * Commodity.img - about 7800 of them - and each of those re-opened and
     * re-parsed the whole of CashPackage.img into a fresh DOM before scanning
     * it linearly for one entry. Reading the file once and keying the result
     * by ItemId is the same data for one parse instead of thousands, and it
     * also means no handler thread ever has to touch that DOM again.
     */
    private void loadPackages() {
        final MapleData packages = data.getData("CashPackage.img");
        if (packages == null) {
            return;
        }
        for (MapleData pack : packages.getChildren()) {
            final MapleData sns = pack.getChildByPath("SN");
            if (sns == null) {
                continue;
            }
            final int itemId;
            try {
                itemId = Integer.parseInt(pack.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            final List<CashItemInfo> packageItems = new ArrayList<>();
            for (MapleData d : sns.getChildren()) {
                // itemStats may not know the SN; the old code added the null too.
                packageItems.add(itemStats.get(Integer.valueOf(MapleDataTool.getIntConvert(d))));
            }
            itemPackage.put(itemId, packageItems);
        }
    }

    /**
     * Loads cashshop_modified_items in one query.
     *
     * <p>The old code asked {@code WHERE serial = ?} once per SN found in
     * Commodity.img, so a 6.6k-row table cost ~7800 round trips - by far the
     * slowest thing in the whole startup. The table is small enough to read
     * whole, and the rows it does not have were being looked up for nothing.
     *
     * <p>Serials Commodity.img does not know are skipped, because the old
     * per-serial loop could only ever have found the ones it asked about, and
     * the size of this map goes out on the wire in the CS_OPEN packet.
     */
    private void loadModInfo() {
        loadModInfo(itemMods);
    }

    /**
     * Re-reads cashshop_modified_items into the live map, for the panel's cash
     * shop editor. Rows are loaded into a scratch map first so that a reader
     * on a handler thread never sees the map half-empty; then the serials the
     * table no longer has are dropped and the rest replaced in place.
     */
    public synchronized void reloadModInfo() {
        if (!initialized) {
            initialize();
            return;
        }
        final Map<Integer, CashModInfo> fresh = new java.util.HashMap<>();
        loadModInfo(fresh);
        itemMods.keySet().retainAll(fresh.keySet());
        itemMods.putAll(fresh);
        for (int i : itemStats.keySet()) {
            getItem(i);
        }
    }

    private void loadModInfo(final Map<Integer, CashModInfo> into) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM cashshop_modified_items");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final int sn = rs.getInt("serial");
                if (!itemStats.containsKey(sn)) {
                    continue;
                }
                into.put(sn, new CashModInfo(sn, rs.getInt("discount_price"), rs.getInt("mark"), rs.getInt("showup") > 0, rs.getInt("itemid"), rs.getInt("priority"), rs.getInt("package") > 0, rs.getInt("period"), rs.getInt("gender"), rs.getInt("count"), rs.getInt("meso"), rs.getInt("unk_1"), rs.getInt("unk_2"), rs.getInt("unk_3"), rs.getInt("extra_flags")));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public final CashItemInfo getItem(int sn) {
        final CashItemInfo stats = itemStats.get(Integer.valueOf(sn));
        final CashModInfo z = getModInfo(sn);
        if (z != null && z.showUp) {
            return z.toCItem(stats); //null doesnt matter
        }
        if (stats == null || !stats.onSale()) {
            return null;
        }
        //hmm
        return stats;
    }

    /** Null when the item is not a cash package. Loaded by {@link #loadPackages()}. */
    public final List<CashItemInfo> getPackageItems(int itemId) {
        if (!initialized) {
            initialize();
        }
        return itemPackage.get(itemId);
    }

    /**
     * Null when the serial has no row in cashshop_modified_items.
     *
     * <p>Reads only the map {@link #loadModInfo()} filled: this is called from
     * {@link CashItemInfo#onSale()} on the packet path, so it must not go near
     * the database. It could not usefully do so anyway - the old fallback query
     * here was already dead once {@code initialized} flipped true.
     */
    public final CashModInfo getModInfo(int sn) {
        return itemMods.get(sn);
    }

    public final Collection<CashModInfo> getAllModInfo() {
        if (!initialized) {
            initialize();
        }
        return itemMods.values();
    }

    public final int[] getBestItems() {
        return bestItems;
    }
}
