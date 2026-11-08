package handling.cashshop.handler;

import client.inventory.Equip;
import constants.ItemConstants;
import client.inventory.IItem;
import client.MapleClient;
import client.inventory.MapleInventoryType;
import constants.ServerConstants;
import server.MTSCart;
import server.MTSStorage;
import server.MTSStorage.MTSItemInfo;
import server.MapleInventoryManipulator;
import server.MapleItemInformationProvider;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.packet.MTSCSPacket;

public class MTSOperation {

    public static void mtsOperation(final SeekableLittleEndianAccessor slea, final MapleClient c) {
        // E19：MTSStorage.load() 全樹零呼叫端，拍賣場從來沒有載入；ENTER_MTS 也只回「目前暫停使用」，
        // 正常客戶端走不到這裡。以前偽造的 MTS_TAB 封包會在 getInstance() 的 null 上 NPE，
        // 現在直接忽略。哪天真的要開拍賣場，在 Start 裡呼叫 MTSStorage.load() 就會自動生效。
        if (MTSStorage.getInstance() == null) {
            return;
        }
        final MTSCart cart = MTSStorage.getInstance().getCart(c.getPlayer().getId());
        if (slea.available() <= 0) {
            doMTSPackets(cart, c);
            return;
        }
        final byte op = slea.readByte();
        if (op == 1) { //put up for sale
            final byte invType = slea.readByte(); //1 = equip 2 = everything else
            if (invType != 1 && invType != 2) { //pet?
                c.send(MTSCSPacket.getMTSFailSell());
                doMTSPackets(cart, c);
                return;
            }
            final int itemid = slea.readInt(); //itemid
            if (slea.readByte() != 0) {
                c.send(MTSCSPacket.getMTSFailSell());
                doMTSPackets(cart, c);
                return;//we don't like uniqueIDs
            }
            slea.skip(8); //expiration, don't matter
            short stars = 1, quantity = 1;
            byte slot = 0;
            if (invType == 1) {
                slea.skip(32);
            } else {
                stars = slea.readShort(); //the entire quantity of the item
            }
            slea.readMapleAsciiString();//owner
            //again? =/
            if (invType == 1) {
                slea.skip(32);
//                slea.skip(4); //skip the quantity int, equips are always 1
            } else {
                slea.readShort(); //flag
                if (ItemConstants.isThrowingStar(itemid) || ItemConstants.isBullet(itemid)) {
                    slea.skip(8);//recharge ID thing
                }
                slot = (byte) slea.readInt();
                if (ItemConstants.isThrowingStar(itemid) || ItemConstants.isBullet(itemid)) {
                    quantity = stars; //this is due to stars you need to use the entire quantity, not specified
                    slea.skip(4); //so just skip the quantity int
                } else {
                    quantity = (short) slea.readInt(); //specified quantity
                }
            }
            final int price = slea.readInt();
            final MapleItemInformationProvider ii = MapleItemInformationProvider.getInstance();
            final MapleInventoryType type = ItemConstants.getInventoryType(itemid);
            final IItem item = c.getPlayer().getInventory(type).getItem(slot).copy();
            if (ii.isCash(itemid) || quantity <= 0 || item == null || item.getQuantity() <= 0 || item.getItemId() != itemid || item.getUniqueId() > 0 || item.getQuantity() < quantity || price < ServerConstants.MIN_MTS || c.getPlayer().getMeso() < ServerConstants.MTS_MESO || cart.getNotYetSold().size() >= 10 || ii.isDropRestricted(itemid) || ii.isAccountShared(itemid) || item.getExpiration() > -1 || item.getFlag() > 0) {
                c.send(MTSCSPacket.getMTSFailSell());
                doMTSPackets(cart, c);
                return;
            }
            if (type == MapleInventoryType.EQUIP) {
                final Equip eq = (Equip) item;
                if (eq.getState() > 0 || eq.getEnhance() > 0 || eq.getDurability() > -1) {
                    c.send(MTSCSPacket.getMTSFailSell());
                    doMTSPackets(cart, c);
                    return;
                }
            }
            if (quantity >= 50 && ItemConstants.isUpgradeScroll(item.getItemId())) {
                c.setMonitored(true); //hack check
            }
            final long expiration = (System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000));
            item.setQuantity(quantity);
            MTSStorage.getInstance().addToBuyNow(cart, item, price, c.getPlayer().getId(), c.getPlayer().getName(), expiration);
            MapleInventoryManipulator.removeFromSlot(c, type, slot, quantity, false);
            c.getPlayer().gainMeso(-ServerConstants.MTS_MESO, false);
            c.send(MTSCSPacket.getMTSConfirmSell());
        } else if (op == 4) { //change page/tab
            cart.changeInfo(slea.readInt(), slea.readInt(), slea.readInt());
        } else if (op == 7) { //cancel sale
            if (!MTSStorage.getInstance().removeFromBuyNow(slea.readInt(), c.getPlayer().getId(), true)) {
                c.send(MTSCSPacket.getMTSFailCancel());
            } else {
                c.send(MTSCSPacket.getMTSConfirmCancel());
                sendMTSPackets(cart, c, true);
                return;
            }
        } else if (op == 8) { //transfer item
            final int id = Integer.MAX_VALUE - slea.readInt(); //fake id
            if (id >= cart.getInventory().size()) {
                c.getPlayer().dropMessage(1, "Please try it again later.");
                sendMTSPackets(cart, c, true);
                return;
            }
            final IItem item = cart.getInventory().get(id); //by index
            if (item != null && item.getQuantity() > 0 && MapleInventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
                IItem item_ = item.copy();
                short pos = MapleInventoryManipulator.addbyItem(c, item_, true);
                if (pos >= 0) {
                    if (item_.getPet() != null) {
                        item_.getPet().setInventoryPosition(pos);
                        c.getPlayer().addPet(item_.getPet());
                    }
                    cart.removeFromInventory(item);
                    c.send(MTSCSPacket.getMTSConfirmTransfer(item_.getQuantity(), pos)); //IF this is actually pos and pos
                    sendMTSPackets(cart, c, true);
                    return;
                } else {
                    c.send(MTSCSPacket.getMTSFailBuy());
                }
            } else {
                c.send(MTSCSPacket.getMTSFailBuy());
            }
        } else if (op == 9) { //add to cart
            final int id = slea.readInt();
            if (MTSStorage.getInstance().checkCart(id, c.getPlayer().getId()) && cart.addToCart(id)) {
                c.send(MTSCSPacket.addToCartMessage(false, false));
            } else {
                c.send(MTSCSPacket.addToCartMessage(true, false));
            }
        } else if (op == 10) { //delete from cart
            final int id = slea.readInt();
            if (cart.getCart().contains(id)) {
                cart.removeFromCart(id);
                c.send(MTSCSPacket.addToCartMessage(false, true));
            } else {
                c.send(MTSCSPacket.addToCartMessage(true, true));
            }
        } else if (op == 16 || op == 17) { //buyNow, buy from cart
            final MTSItemInfo mts = MTSStorage.getInstance().getSingleItem(slea.readInt());
            if (mts != null && mts.getCharacterId() != c.getPlayer().getId()) {
                if (c.getPlayer().getCSPoints(1) > mts.getRealPrice()) {
                    if (MTSStorage.getInstance().removeFromBuyNow(mts.getId(), c.getPlayer().getId(), false)) {
                        c.getPlayer().modifyCSPoints(1, -mts.getRealPrice(), false);
                        MTSStorage.getInstance().getCart(mts.getCharacterId()).increaseOwedNX(mts.getPrice());
                        c.send(MTSCSPacket.getMTSConfirmBuy());
                        sendMTSPackets(cart, c, true);
                        return;
                    } else {
                        c.send(MTSCSPacket.getMTSFailBuy());
                    }
                } else {
                    c.send(MTSCSPacket.getMTSFailBuy());
                }
            } else {
                c.send(MTSCSPacket.getMTSFailBuy());
            }
        } else if (c.getPlayer().isAdmin()) {
        }
        doMTSPackets(cart, c);
    }

    public static void mtsUpdate(final MTSCart cart, final MapleClient c) {
        c.getPlayer().modifyCSPoints(1, MTSStorage.getInstance().getCart(c.getPlayer().getId()).getSetOwedNX(), false);
        c.send(MTSCSPacket.getMTSWantedListingOver(0, 0));
        doMTSPackets(cart, c);
    }

    private static void doMTSPackets(final MTSCart cart, final MapleClient c) {
        sendMTSPackets(cart, c, false);
    }

    private static void sendMTSPackets(final MTSCart cart, final MapleClient c, final boolean changed) {
        c.send(MTSStorage.getInstance().getCurrentMTS(cart));
        c.send(MTSStorage.getInstance().getCurrentNotYetSold(cart));
        c.send(MTSStorage.getInstance().getCurrentTransfer(cart, changed));
        c.send(MTSCSPacket.showMTSCash(c.getPlayer()));
        c.send(MTSCSPacket.enableCSUse());
        MTSStorage.getInstance().checkExpirations();
    }
}
