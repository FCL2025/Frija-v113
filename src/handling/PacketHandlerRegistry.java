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
package handling;

import java.util.EnumMap;
import java.util.Map;

import client.MapleClient;
import handling.cashshop.handler.CashShopOperation;
import handling.cashshop.handler.MTSOperation;
import handling.channel.handler.AllianceHandler;
import handling.channel.handler.BBSHandler;
import handling.channel.handler.BuddyListHandler;
import handling.channel.handler.ChatHandler;
import handling.channel.handler.DueyHandler;
import handling.channel.handler.FamilyHandler;
import handling.channel.handler.GuildHandler;
import handling.channel.handler.HiredMerchantHandler;
import handling.channel.handler.InterServerHandler;
import handling.channel.handler.InventoryHandler;
import handling.channel.handler.ItemMakerHandler;
import handling.channel.handler.MobHandler;
import handling.channel.handler.MonsterCarnivalHandler;
import handling.channel.handler.NPCHandler;
import handling.channel.handler.PartyHandler;
import handling.channel.handler.PetHandler;
import handling.channel.handler.PlayerHandler;
import handling.channel.handler.PlayerInteractionHandler;
import handling.channel.handler.PlayersHandler;
import handling.channel.handler.StatsHandling;
import handling.channel.handler.SummonHandler;
import handling.channel.handler.UserInterfaceHandler;
import handling.login.handler.CharLoginHandler;
import server.MTSStorage;
import tools.FileoutputUtil;
import tools.data.input.SeekableLittleEndianAccessor;
import tools.packet.LoginPacket;

/**
 * The inbound dispatch table: one {@link PacketHandler} per {@link
 * RecvPacketOpcode}. This was a 488-line {@code switch (header)} inside
 * {@code MapleServerHandler}, which meant every new opcode was an edit to the
 * middle of the method that also does the MINA plumbing.
 *
 * <p><b>The table is keyed by the enum constant, never by the numeric header.</b>
 * That is not a style choice. Opcode numbers are data: {@code RecvPacketOpcode}
 * loads them from {@code Libs/opcode/recv.ini} at class-init and {@code
 * !reloadops} calls {@link RecvPacketOpcode#reloadValues()}, which rewrites
 * every constant's {@code code} and rebuilds the header-to-constant table
 * wholesale. A {@code Map<Short, ...>} built here at class-init would still
 * hold the numbers from before the reload and would silently dispatch the wrong
 * handler - or none - for every opcode whose number moved. Keyed by the
 * constant, nothing in this file knows or cares what the numbers are: {@code
 * MapleServerHandler} has already turned the header into a constant through
 * {@link RecvPacketOpcode#forValue(short)} before it gets here.
 *
 * <p><b>Threading.</b> The map is filled once, entirely inside this class's
 * static initialiser, and only read afterwards - see the concurrency notes in
 * CLAUDE.md: all sixteen-plus {@code Maple-Handler-N} threads read it at once.
 * Class initialisation gives every one of them a happens-before edge to the
 * last {@code put}, so an {@link EnumMap} (an array lookup, no hashing) is safe
 * without a lock. Nothing may add to it later; a table that grew from a handler
 * thread would be exactly the unguarded-HashMap bug this tree keeps finding.
 */
public final class PacketHandlerRegistry {

    private static final System.Logger LOG = System.getLogger(PacketHandlerRegistry.class.getName());

    /**
     * Opcode to handler. Written only by {@link #put} from the static
     * initialiser below, read-only from then on.
     */
    private static final Map<RecvPacketOpcode, PacketHandler> HANDLERS = new EnumMap<>(RecvPacketOpcode.class);

    private PacketHandlerRegistry() {
    }

    /**
     * Registers one handler, refusing a second registration for the same
     * opcode. The {@code switch} this replaced got that check from javac, which
     * rejects a duplicate {@code case} label outright; nothing but this would
     * notice two {@code put}s for the same constant, and the second would win
     * silently.
     */
    private static void put(final RecvPacketOpcode header, final PacketHandler handler) {
        if (HANDLERS.put(header, handler) != null) {
            throw new IllegalStateException("Duplicate packet handler for " + header);
        }
    }

    /**
     * Runs the handler for {@code header}, or reports it as unhandled - which
     * is what the {@code switch}'s {@code default:} did for an opcode that had
     * no {@code case}, and still is for one that has no entry here.
     *
     * @param cs true when the packet arrived on the cash-shop port. Two opcodes
     * mean something different there ({@code PLAYER_LOGGEDIN} and {@code
     * CHANGE_MAP}); every other handler ignores it. Login and channel both pass
     * false, exactly as they did to the old {@code handlePacket}.
     */
    public static void handle(final RecvPacketOpcode header, final SeekableLittleEndianAccessor slea, final MapleClient c, final boolean cs) throws Exception {
        final PacketHandler handler = HANDLERS.get(header);
        if (handler == null) {
            LOG.log(System.Logger.Level.INFO, "[UNHANDLED] Recv [" + header.toString() + "] found");
            return;
        }
        handler.handle(slea, c, cs);
    }

    /**
     * Forces this class to initialise, so the table is built once at startup
     * rather than on whichever handler thread happens to see the first packet.
     */
    public static void preload() {
    }

    static {
        put(RecvPacketOpcode.PONG, (slea, c, cs) -> c.pongReceived());
        put(RecvPacketOpcode.STRANGE_DATA, (slea, c, cs) -> {
            // Does nothing for now, HackShield's heartbeat
        });
        put(RecvPacketOpcode.HELLO_LOGIN, (slea, c, cs) -> {
            CharLoginHandler.welcome(c);
            // Does nothing for now, HackShield's heartbeat
        });
        put(RecvPacketOpcode.HELLO_CHANNEL, (slea, c, cs) -> CharLoginHandler.welcome(c));
        put(RecvPacketOpcode.LOGIN_PASSWORD, (slea, c, cs) -> CharLoginHandler.login(slea, c));
        put(RecvPacketOpcode.SERVERLIST_REQUEST, (slea, c, cs) -> CharLoginHandler.serverListRequest(c));
        put(RecvPacketOpcode.CHARLIST_REQUEST, (slea, c, cs) -> CharLoginHandler.charlistRequest(slea, c));
        put(RecvPacketOpcode.SERVERSTATUS_REQUEST, (slea, c, cs) -> CharLoginHandler.serverStatusRequest(c));
        put(RecvPacketOpcode.CHECK_CHAR_NAME, (slea, c, cs) -> CharLoginHandler.checkCharName(slea.readMapleAsciiString(), c));
        put(RecvPacketOpcode.CREATE_CHAR, (slea, c, cs) -> CharLoginHandler.createChar(slea, c));
        put(RecvPacketOpcode.DELETE_CHAR, (slea, c, cs) -> CharLoginHandler.deleteChar(slea, c));
        put(RecvPacketOpcode.CHAR_SELECT, (slea, c, cs) -> CharLoginHandler.character_WithoutSecondPassword(slea, c));
        put(RecvPacketOpcode.AUTH_SECOND_PASSWORD, (slea, c, cs) -> CharLoginHandler.character_WithSecondPassword(slea, c));
        put(RecvPacketOpcode.SET_GENDER, (slea, c, cs) -> CharLoginHandler.setGenderRequest(slea, c));
        put(RecvPacketOpcode.RSA_KEY, (slea, c, cs) -> {
            // Fix this somehow
            c.send(LoginPacket.strangeDATA());
        });
        // END OF LOGIN SERVER
        put(RecvPacketOpcode.CHANGE_CHANNEL, (slea, c, cs) -> InterServerHandler.changeChannel(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PLAYER_LOGGEDIN, (slea, c, cs) -> {
            final int playerid = slea.readInt();
            if (cs) {
                CashShopOperation.enterCS(playerid, c);
            } else {
                InterServerHandler.loggedin(playerid, c);
            }
        });
        put(RecvPacketOpcode.ENTER_CASH_SHOP, (slea, c, cs) -> InterServerHandler.enterCS(c, c.getPlayer(), false));
        put(RecvPacketOpcode.ENTER_MTS, (slea, c, cs) -> {
            // 刻意保留：拍賣場（MTS）是接好的，但本服刻意關閉，改成回一則「目前暫停使用」。
            //                InterServerHandler.enterCS(c, c.getPlayer(), true);
            c.getPlayer().dropMessage(1, "目前暫停使用.");
        });
        put(RecvPacketOpcode.MOVE_PLAYER, (slea, c, cs) -> PlayerHandler.movePlayer(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.CHAR_INFO_REQUEST, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            PlayerHandler.charInfoRequest(slea.readInt(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.CLOSE_RANGE_ATTACK, (slea, c, cs) -> PlayerHandler.closeRangeAttack(slea, c, c.getPlayer(), false));
        put(RecvPacketOpcode.RANGED_ATTACK, (slea, c, cs) -> PlayerHandler.rangedAttack(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MAGIC_ATTACK, (slea, c, cs) -> PlayerHandler.magicDamage(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.SPECIAL_MOVE, (slea, c, cs) -> PlayerHandler.specialMove(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PASSIVE_ENERGY, (slea, c, cs) -> PlayerHandler.closeRangeAttack(slea, c, c.getPlayer(), true));
        put(RecvPacketOpcode.FACE_EXPRESSION, (slea, c, cs) -> PlayerHandler.changeEmotion(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.TAKE_DAMAGE, (slea, c, cs) -> PlayerHandler.takeDamage(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.HEAL_OVER_TIME, (slea, c, cs) -> PlayerHandler.heal(slea, c.getPlayer()));
        put(RecvPacketOpcode.CANCEL_BUFF, (slea, c, cs) -> PlayerHandler.cancelBuffHandler(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.CANCEL_ITEM_EFFECT, (slea, c, cs) -> PlayerHandler.cancelItemEffect(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.USE_CHAIR, (slea, c, cs) -> PlayerHandler.useChair(slea.readInt(), c, c.getPlayer()));
        put(RecvPacketOpcode.CANCEL_CHAIR, (slea, c, cs) -> PlayerHandler.cancelChair(slea.readShort(), c, c.getPlayer()));
        // USE_ITEMEFFECT fell through into WHEEL_OF_FORTUNE - one body, two
        // opcodes - so the two share one handler instance here.
        final PacketHandler useItemEffect = (slea, c, cs) -> PlayerHandler.useItemEffect(slea.readInt(), c, c.getPlayer());
        put(RecvPacketOpcode.USE_ITEMEFFECT, useItemEffect);
        put(RecvPacketOpcode.WHEEL_OF_FORTUNE, useItemEffect);
        put(RecvPacketOpcode.SKILL_EFFECT, (slea, c, cs) -> PlayerHandler.skillEffect(slea, c.getPlayer()));
        put(RecvPacketOpcode.MESO_DROP, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            PlayerHandler.dropMeso(slea.readInt(), c.getPlayer());
        });
        put(RecvPacketOpcode.MONSTER_BOOK_COVER, (slea, c, cs) -> PlayerHandler.changeMonsterBookCover(slea.readInt(), c, c.getPlayer()));
        put(RecvPacketOpcode.CHANGE_KEYMAP, (slea, c, cs) -> PlayerHandler.changeKeymap(slea, c.getPlayer()));
        put(RecvPacketOpcode.CHANGE_MAP, (slea, c, cs) -> {
            if (cs) {
                CashShopOperation.leaveCS(slea, c, c.getPlayer());
            } else {
                PlayerHandler.changeMap(slea, c, c.getPlayer());
            }
        });
        put(RecvPacketOpcode.CHANGE_MAP_SPECIAL, (slea, c, cs) -> {
            slea.skip(1);
            PlayerHandler.changeMapSpecial(slea.readMapleAsciiString(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_INNER_PORTAL, (slea, c, cs) -> {
            slea.skip(1);
            PlayerHandler.innerPortal(slea, c, c.getPlayer());
        });
        put(RecvPacketOpcode.TROCK_ADD_MAP, (slea, c, cs) -> PlayerHandler.trockAddMap(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.ARAN_COMBO, (slea, c, cs) -> PlayerHandler.aranCombo(c, c.getPlayer()));
        put(RecvPacketOpcode.SKILL_MACRO, (slea, c, cs) -> PlayerHandler.changeSkillMacro(slea, c.getPlayer()));
        put(RecvPacketOpcode.GIVE_FAME, (slea, c, cs) -> PlayersHandler.giveFame(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.TRANSFORM_PLAYER, (slea, c, cs) -> PlayersHandler.transformPlayer(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.NOTE_ACTION, (slea, c, cs) -> PlayersHandler.note(slea, c.getPlayer()));
        put(RecvPacketOpcode.USE_DOOR, (slea, c, cs) -> PlayersHandler.useDoor(slea, c.getPlayer()));
        put(RecvPacketOpcode.DAMAGE_REACTOR, (slea, c, cs) -> PlayersHandler.hitReactor(slea, c));
        put(RecvPacketOpcode.TOUCH_REACTOR, (slea, c, cs) -> PlayersHandler.touchReactor(slea, c));
        put(RecvPacketOpcode.CLOSE_CHALKBOARD, (slea, c, cs) -> c.getPlayer().setChalkboard(null));
        put(RecvPacketOpcode.ITEM_MAKER, (slea, c, cs) -> ItemMakerHandler.itemMaker(slea, c));
        put(RecvPacketOpcode.ITEM_SORT, (slea, c, cs) -> InventoryHandler.itemSort(slea, c));
        put(RecvPacketOpcode.ITEM_GATHER, (slea, c, cs) -> InventoryHandler.itemGather(slea, c));
        put(RecvPacketOpcode.ITEM_MOVE, (slea, c, cs) -> InventoryHandler.itemMove(slea, c));
        put(RecvPacketOpcode.ITEM_PICKUP, (slea, c, cs) -> InventoryHandler.pickup_Player(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_CASH_ITEM, (slea, c, cs) -> InventoryHandler.useCashItem(slea, c));
        put(RecvPacketOpcode.USE_ITEM, (slea, c, cs) -> InventoryHandler.useItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_MAGNIFY_GLASS, (slea, c, cs) -> InventoryHandler.useMagnify(slea, c));
        put(RecvPacketOpcode.USE_SCRIPTED_NPC_ITEM, (slea, c, cs) -> InventoryHandler.useScriptedNPCItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_RETURN_SCROLL, (slea, c, cs) -> InventoryHandler.useReturnScroll(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_UPGRADE_SCROLL, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.useUpgradeScroll((byte) slea.readShort(), (byte) slea.readShort(), (byte) slea.readShort(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_POTENTIAL_SCROLL, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.useUpgradeScroll((byte) slea.readShort(), (byte) slea.readShort(), (byte) 0, c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_EQUIP_SCROLL, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.useUpgradeScroll((byte) slea.readShort(), (byte) slea.readShort(), (byte) 0, c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_SUMMON_BAG, (slea, c, cs) -> InventoryHandler.useSummonBag(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_TREASUER_CHEST, (slea, c, cs) -> InventoryHandler.useTreasureChest(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_SKILL_BOOK, (slea, c, cs) -> {
            // 保留作為紀錄：其他版本這個封包開頭有 tick 欄位，v113 沒有。
            // 陷阱：這一行含 slea.readInt()，取消註解會多吃 4 bytes，後面就讀錯位置。
            //                c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.useSkillBook(slea, c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_CATCH_ITEM, (slea, c, cs) -> InventoryHandler.useCatchItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_MOUNT_FOOD, (slea, c, cs) -> InventoryHandler.useMountFood(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.REWARD_ITEM, (slea, c, cs) -> InventoryHandler.useRewardItem((byte) slea.readShort(), slea.readInt(), c, c.getPlayer()));
        put(RecvPacketOpcode.HYPNOTIZE_DMG, (slea, c, cs) -> MobHandler.hypnotizeDmg(slea, c.getPlayer()));
        put(RecvPacketOpcode.MOB_NODE, (slea, c, cs) -> MobHandler.mobNode(slea, c.getPlayer()));
        put(RecvPacketOpcode.DISPLAY_NODE, (slea, c, cs) -> MobHandler.displayNode(slea, c.getPlayer()));
        put(RecvPacketOpcode.MOVE_LIFE, (slea, c, cs) -> MobHandler.moveMonster(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.AUTO_AGGRO, (slea, c, cs) -> MobHandler.autoAggro(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.FRIENDLY_DAMAGE, (slea, c, cs) -> MobHandler.friendlyDamage(slea, c.getPlayer()));
        put(RecvPacketOpcode.MONSTER_BOMB, (slea, c, cs) -> MobHandler.monsterBomb(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.NPC_SHOP, (slea, c, cs) -> NPCHandler.npcShop(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.NPC_TALK, (slea, c, cs) -> NPCHandler.npcTalk(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.NPC_TALK_MORE, (slea, c, cs) -> NPCHandler.npcMoreTalk(slea, c));
        put(RecvPacketOpcode.NPC_ACTION, (slea, c, cs) -> NPCHandler.npcAnimation(slea, c));
        put(RecvPacketOpcode.QUEST_ACTION, (slea, c, cs) -> NPCHandler.questAction(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.STORAGE, (slea, c, cs) -> NPCHandler.storage(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.GENERAL_CHAT, (slea, c, cs) -> {
            // 保留作為紀錄：其他版本這個封包開頭有 tick 欄位，v113 沒有。
            // 陷阱：這一行含 slea.readInt()，取消註解會多吃 4 bytes，聊天內容就讀錯位置。
            //                c.getPlayer().updateTick(slea.readInt());
            ChatHandler.generalChat(slea.readMapleAsciiString(), slea.readByte(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.PARTYCHAT, (slea, c, cs) -> ChatHandler.others(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.WHISPER, (slea, c, cs) -> ChatHandler.whisper_Find(slea, c));
        put(RecvPacketOpcode.MESSENGER, (slea, c, cs) -> ChatHandler.messenger(slea, c));
        put(RecvPacketOpcode.AUTO_ASSIGN_AP, (slea, c, cs) -> StatsHandling.autoAssignAP(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.DISTRIBUTE_AP, (slea, c, cs) -> StatsHandling.distributeAP(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.DISTRIBUTE_SP, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            StatsHandling.distributeSP(slea.readInt(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.PLAYER_INTERACTION, (slea, c, cs) -> PlayerInteractionHandler.playerInteraction(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.GUILD_OPERATION, (slea, c, cs) -> GuildHandler.guild(slea, c));
        put(RecvPacketOpcode.UPDATE_CHAR_INFO, (slea, c, cs) -> PlayersHandler.updateCharInfo(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.DENY_GUILD_REQUEST, (slea, c, cs) -> {
            slea.skip(1);
            GuildHandler.denyGuildRequest(slea.readMapleAsciiString(), c);
        });
        put(RecvPacketOpcode.ALLIANCE_OPERATION, (slea, c, cs) -> AllianceHandler.handleAlliance(slea, c, false));
        put(RecvPacketOpcode.DENY_ALLIANCE_REQUEST, (slea, c, cs) -> AllianceHandler.handleAlliance(slea, c, true));
        put(RecvPacketOpcode.BBS_OPERATION, (slea, c, cs) -> BBSHandler.bbsOperatopn(slea, c));
        put(RecvPacketOpcode.PARTY_OPERATION, (slea, c, cs) -> PartyHandler.partyOperatopn(slea, c));
        put(RecvPacketOpcode.DENY_PARTY_REQUEST, (slea, c, cs) -> PartyHandler.denyPartyRequest(slea, c));
        put(RecvPacketOpcode.BUDDYLIST_MODIFY, (slea, c, cs) -> BuddyListHandler.buddyOperation(slea, c));
        put(RecvPacketOpcode.CYGNUS_SUMMON, (slea, c, cs) -> UserInterfaceHandler.cygnusSummon_NPCRequest(c));
        put(RecvPacketOpcode.SHIP_OBJECT, (slea, c, cs) -> UserInterfaceHandler.shipObjectRequest(slea.readInt(), c));
        put(RecvPacketOpcode.BUY_CS_ITEM, (slea, c, cs) -> CashShopOperation.buyCashItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.COUPON_CODE, (slea, c, cs) -> {
            FileoutputUtil.log(FileoutputUtil.PacketEx_Log, "Coupon : \n" + slea.toString(true));
            LOG.log(System.Logger.Level.INFO, slea.toString());
            slea.skip(2);
            CashShopOperation.couponCode(slea.readMapleAsciiString(), c);
        });
        put(RecvPacketOpcode.CS_UPDATE, (slea, c, cs) -> CashShopOperation.csUpdate(c));
        put(RecvPacketOpcode.TOUCHING_MTS, (slea, c, cs) -> {
            // E19：拍賣場從未載入（見 MTSOperation.mtsOperation），偽造封包以前會在這裡 NPE。
            final MTSStorage mts = MTSStorage.getInstance();
            if (mts != null) {
                MTSOperation.mtsUpdate(mts.getCart(c.getPlayer().getId()), c);
            }
        });
        put(RecvPacketOpcode.MTS_TAB, (slea, c, cs) -> MTSOperation.mtsOperation(slea, c));
        put(RecvPacketOpcode.DAMAGE_SUMMON, (slea, c, cs) -> {
            slea.skip(4);
            SummonHandler.damageSummon(slea, c.getPlayer());
        });
        put(RecvPacketOpcode.MOVE_SUMMON, (slea, c, cs) -> SummonHandler.moveSummon(slea, c.getPlayer()));
        put(RecvPacketOpcode.SUMMON_ATTACK, (slea, c, cs) -> SummonHandler.summonAttack(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MOVE_DRAGON, (slea, c, cs) -> SummonHandler.moveDragon(slea, c.getPlayer()));
        put(RecvPacketOpcode.SPAWN_PET, (slea, c, cs) -> PetHandler.spawnPet(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MOVE_PET, (slea, c, cs) -> PetHandler.movePet(slea, c.getPlayer()));
        put(RecvPacketOpcode.PET_CHAT, (slea, c, cs) -> {
            if (slea.available() < 12) {
                return;
            }
            PetHandler.petChat((int) slea.readLong(), slea.readShort(), slea.readMapleAsciiString(), c.getPlayer());
        });
        put(RecvPacketOpcode.PET_COMMAND, (slea, c, cs) -> PetHandler.petCommand(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PET_FOOD, (slea, c, cs) -> PetHandler.petFood(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PET_LOOT, (slea, c, cs) -> InventoryHandler.pickup_Pet(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PET_AUTO_POT, (slea, c, cs) -> PetHandler.pet_AutoPotion(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MONSTER_CARNIVAL, (slea, c, cs) -> MonsterCarnivalHandler.monsterCarnival(slea, c));
        put(RecvPacketOpcode.DUEY_ACTION, (slea, c, cs) -> DueyHandler.dueyOperation(slea, c));
        put(RecvPacketOpcode.USE_HIRED_MERCHANT, (slea, c, cs) -> HiredMerchantHandler.useHiredMerchant(slea, c));
        put(RecvPacketOpcode.MERCH_ITEM_STORE, (slea, c, cs) -> HiredMerchantHandler.merchantItemStore(slea, c));
        put(RecvPacketOpcode.CANCEL_DEBUFF, (slea, c, cs) -> {
            // Ignore for now
        });
        put(RecvPacketOpcode.LEFT_KNOCK_BACK, (slea, c, cs) -> PlayerHandler.leftKnockBack(slea, c));
        put(RecvPacketOpcode.SNOWBALL, (slea, c, cs) -> PlayerHandler.snowBall(slea, c));
        put(RecvPacketOpcode.COCONUT, (slea, c, cs) -> PlayersHandler.hitCoconut(slea, c));
        put(RecvPacketOpcode.REPAIR, (slea, c, cs) -> NPCHandler.repair(slea, c));
        put(RecvPacketOpcode.REPAIR_ALL, (slea, c, cs) -> NPCHandler.repairAll(c));
        put(RecvPacketOpcode.GAME_POLL, (slea, c, cs) -> UserInterfaceHandler.inGame_Poll(slea, c));
        put(RecvPacketOpcode.OWL, (slea, c, cs) -> InventoryHandler.owl(slea, c));
        put(RecvPacketOpcode.OWL_WARP, (slea, c, cs) -> InventoryHandler.owlWarp(slea, c));
        put(RecvPacketOpcode.USE_OWL_MINERVA, (slea, c, cs) -> InventoryHandler.owlMinerva(slea, c));
        put(RecvPacketOpcode.RPS_GAME, (slea, c, cs) -> NPCHandler.rpsGame(slea, c));
        put(RecvPacketOpcode.UPDATE_QUEST, (slea, c, cs) -> NPCHandler.updateQuest(slea, c));
        put(RecvPacketOpcode.USE_ITEM_QUEST, (slea, c, cs) -> NPCHandler.useItemQuest(slea, c));
        put(RecvPacketOpcode.FOLLOW_REQUEST, (slea, c, cs) -> PlayersHandler.followRequest(slea, c));
        put(RecvPacketOpcode.FOLLOW_REPLY, (slea, c, cs) -> PlayersHandler.followReply(slea, c));
        put(RecvPacketOpcode.RING_ACTION, (slea, c, cs) -> PlayersHandler.ringAction(slea, c));
        put(RecvPacketOpcode.REQUEST_FAMILY, (slea, c, cs) -> FamilyHandler.requestFamily(slea, c));
        put(RecvPacketOpcode.OPEN_FAMILY, (slea, c, cs) -> FamilyHandler.openFamily(slea, c));
        put(RecvPacketOpcode.FAMILY_OPERATION, (slea, c, cs) -> FamilyHandler.familyOperation(slea, c));
        put(RecvPacketOpcode.DELETE_JUNIOR, (slea, c, cs) -> FamilyHandler.deleteJunior(slea, c));
        put(RecvPacketOpcode.DELETE_SENIOR, (slea, c, cs) -> FamilyHandler.deleteSenior(slea, c));
        put(RecvPacketOpcode.USE_FAMILY, (slea, c, cs) -> FamilyHandler.useFamily(slea, c));
        put(RecvPacketOpcode.FAMILY_PRECEPT, (slea, c, cs) -> FamilyHandler.familyPrecept(slea, c));
        put(RecvPacketOpcode.FAMILY_SUMMON, (slea, c, cs) -> FamilyHandler.familySummon(slea, c));
        put(RecvPacketOpcode.ACCEPT_FAMILY, (slea, c, cs) -> FamilyHandler.acceptFamily(slea, c));
        put(RecvPacketOpcode.SOLOMON, (slea, c, cs) -> PlayersHandler.solomon(slea, c));
        put(RecvPacketOpcode.GACH_EXP, (slea, c, cs) -> PlayersHandler.gachExp(slea, c));
    }
}
