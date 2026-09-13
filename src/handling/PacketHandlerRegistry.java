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
            CharLoginHandler.Welcome(c);
            // Does nothing for now, HackShield's heartbeat
        });
        put(RecvPacketOpcode.HELLO_CHANNEL, (slea, c, cs) -> CharLoginHandler.Welcome(c));
        put(RecvPacketOpcode.LOGIN_PASSWORD, (slea, c, cs) -> CharLoginHandler.login(slea, c));
        put(RecvPacketOpcode.SERVERLIST_REQUEST, (slea, c, cs) -> CharLoginHandler.ServerListRequest(c));
        put(RecvPacketOpcode.CHARLIST_REQUEST, (slea, c, cs) -> CharLoginHandler.CharlistRequest(slea, c));
        put(RecvPacketOpcode.SERVERSTATUS_REQUEST, (slea, c, cs) -> CharLoginHandler.ServerStatusRequest(c));
        put(RecvPacketOpcode.CHECK_CHAR_NAME, (slea, c, cs) -> CharLoginHandler.CheckCharName(slea.readMapleAsciiString(), c));
        put(RecvPacketOpcode.CREATE_CHAR, (slea, c, cs) -> CharLoginHandler.CreateChar(slea, c));
        put(RecvPacketOpcode.DELETE_CHAR, (slea, c, cs) -> CharLoginHandler.DeleteChar(slea, c));
        put(RecvPacketOpcode.CHAR_SELECT, (slea, c, cs) -> CharLoginHandler.Character_WithoutSecondPassword(slea, c));
        put(RecvPacketOpcode.AUTH_SECOND_PASSWORD, (slea, c, cs) -> CharLoginHandler.Character_WithSecondPassword(slea, c));
        put(RecvPacketOpcode.SET_GENDER, (slea, c, cs) -> CharLoginHandler.SetGenderRequest(slea, c));
        put(RecvPacketOpcode.RSA_KEY, (slea, c, cs) -> {
            // Fix this somehow
            c.send(LoginPacket.StrangeDATA());
        });
        // END OF LOGIN SERVER
        put(RecvPacketOpcode.CHANGE_CHANNEL, (slea, c, cs) -> InterServerHandler.ChangeChannel(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PLAYER_LOGGEDIN, (slea, c, cs) -> {
            final int playerid = slea.readInt();
            if (cs) {
                CashShopOperation.EnterCS(playerid, c);
            } else {
                InterServerHandler.Loggedin(playerid, c);
            }
        });
        put(RecvPacketOpcode.ENTER_CASH_SHOP, (slea, c, cs) -> InterServerHandler.EnterCS(c, c.getPlayer(), false));
        put(RecvPacketOpcode.ENTER_MTS, (slea, c, cs) -> {
            //                InterServerHandler.EnterCS(c, c.getPlayer(), true);
            c.getPlayer().dropMessage(1, "目前暫停使用.");
        });
        put(RecvPacketOpcode.MOVE_PLAYER, (slea, c, cs) -> PlayerHandler.MovePlayer(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.CHAR_INFO_REQUEST, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            PlayerHandler.CharInfoRequest(slea.readInt(), c, c.getPlayer());
            //System.err.println("CHAR_INFO_REQUEST");
        });
        put(RecvPacketOpcode.CLOSE_RANGE_ATTACK, (slea, c, cs) -> PlayerHandler.closeRangeAttack(slea, c, c.getPlayer(), false));
        put(RecvPacketOpcode.RANGED_ATTACK, (slea, c, cs) -> PlayerHandler.rangedAttack(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MAGIC_ATTACK, (slea, c, cs) -> PlayerHandler.MagicDamage(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.SPECIAL_MOVE, (slea, c, cs) -> PlayerHandler.SpecialMove(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PASSIVE_ENERGY, (slea, c, cs) -> PlayerHandler.closeRangeAttack(slea, c, c.getPlayer(), true));
        put(RecvPacketOpcode.FACE_EXPRESSION, (slea, c, cs) -> PlayerHandler.ChangeEmotion(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.TAKE_DAMAGE, (slea, c, cs) -> PlayerHandler.TakeDamage(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.HEAL_OVER_TIME, (slea, c, cs) -> PlayerHandler.Heal(slea, c.getPlayer()));
        put(RecvPacketOpcode.CANCEL_BUFF, (slea, c, cs) -> PlayerHandler.CancelBuffHandler(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.CANCEL_ITEM_EFFECT, (slea, c, cs) -> PlayerHandler.CancelItemEffect(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.USE_CHAIR, (slea, c, cs) -> PlayerHandler.UseChair(slea.readInt(), c, c.getPlayer()));
        put(RecvPacketOpcode.CANCEL_CHAIR, (slea, c, cs) -> PlayerHandler.CancelChair(slea.readShort(), c, c.getPlayer()));
        // USE_ITEMEFFECT fell through into WHEEL_OF_FORTUNE - one body, two
        // opcodes - so the two share one handler instance here.
        final PacketHandler useItemEffect = (slea, c, cs) -> PlayerHandler.UseItemEffect(slea.readInt(), c, c.getPlayer());
        put(RecvPacketOpcode.USE_ITEMEFFECT, useItemEffect);
        put(RecvPacketOpcode.WHEEL_OF_FORTUNE, useItemEffect);
        put(RecvPacketOpcode.SKILL_EFFECT, (slea, c, cs) -> PlayerHandler.SkillEffect(slea, c.getPlayer()));
        put(RecvPacketOpcode.MESO_DROP, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            PlayerHandler.DropMeso(slea.readInt(), c.getPlayer());
        });
        put(RecvPacketOpcode.MONSTER_BOOK_COVER, (slea, c, cs) -> PlayerHandler.ChangeMonsterBookCover(slea.readInt(), c, c.getPlayer()));
        put(RecvPacketOpcode.CHANGE_KEYMAP, (slea, c, cs) -> PlayerHandler.ChangeKeymap(slea, c.getPlayer()));
        put(RecvPacketOpcode.CHANGE_MAP, (slea, c, cs) -> {
            if (cs) {
                CashShopOperation.LeaveCS(slea, c, c.getPlayer());
            } else {
                PlayerHandler.ChangeMap(slea, c, c.getPlayer());
            }
        });
        put(RecvPacketOpcode.CHANGE_MAP_SPECIAL, (slea, c, cs) -> {
            slea.skip(1);
            PlayerHandler.ChangeMapSpecial(slea.readMapleAsciiString(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_INNER_PORTAL, (slea, c, cs) -> {
            slea.skip(1);
            PlayerHandler.InnerPortal(slea, c, c.getPlayer());
        });
        put(RecvPacketOpcode.TROCK_ADD_MAP, (slea, c, cs) -> PlayerHandler.TrockAddMap(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.ARAN_COMBO, (slea, c, cs) -> PlayerHandler.AranCombo(c, c.getPlayer()));
        put(RecvPacketOpcode.SKILL_MACRO, (slea, c, cs) -> PlayerHandler.ChangeSkillMacro(slea, c.getPlayer()));
        put(RecvPacketOpcode.GIVE_FAME, (slea, c, cs) -> PlayersHandler.GiveFame(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.TRANSFORM_PLAYER, (slea, c, cs) -> PlayersHandler.TransformPlayer(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.NOTE_ACTION, (slea, c, cs) -> PlayersHandler.Note(slea, c.getPlayer()));
        put(RecvPacketOpcode.USE_DOOR, (slea, c, cs) -> PlayersHandler.UseDoor(slea, c.getPlayer()));
        put(RecvPacketOpcode.DAMAGE_REACTOR, (slea, c, cs) -> PlayersHandler.HitReactor(slea, c));
        put(RecvPacketOpcode.TOUCH_REACTOR, (slea, c, cs) -> PlayersHandler.TouchReactor(slea, c));
        put(RecvPacketOpcode.CLOSE_CHALKBOARD, (slea, c, cs) -> c.getPlayer().setChalkboard(null));
        put(RecvPacketOpcode.ITEM_MAKER, (slea, c, cs) -> ItemMakerHandler.ItemMaker(slea, c));
        put(RecvPacketOpcode.ITEM_SORT, (slea, c, cs) -> InventoryHandler.ItemSort(slea, c));
        put(RecvPacketOpcode.ITEM_GATHER, (slea, c, cs) -> InventoryHandler.ItemGather(slea, c));
        put(RecvPacketOpcode.ITEM_MOVE, (slea, c, cs) -> InventoryHandler.ItemMove(slea, c));
        put(RecvPacketOpcode.ITEM_PICKUP, (slea, c, cs) -> InventoryHandler.Pickup_Player(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_CASH_ITEM, (slea, c, cs) -> InventoryHandler.UseCashItem(slea, c));
        put(RecvPacketOpcode.USE_ITEM, (slea, c, cs) -> InventoryHandler.UseItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_MAGNIFY_GLASS, (slea, c, cs) -> InventoryHandler.UseMagnify(slea, c));
        put(RecvPacketOpcode.USE_SCRIPTED_NPC_ITEM, (slea, c, cs) -> InventoryHandler.UseScriptedNPCItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_RETURN_SCROLL, (slea, c, cs) -> InventoryHandler.UseReturnScroll(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_UPGRADE_SCROLL, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.UseUpgradeScroll((byte) slea.readShort(), (byte) slea.readShort(), (byte) slea.readShort(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_POTENTIAL_SCROLL, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.UseUpgradeScroll((byte) slea.readShort(), (byte) slea.readShort(), (byte) 0, c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_EQUIP_SCROLL, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.UseUpgradeScroll((byte) slea.readShort(), (byte) slea.readShort(), (byte) 0, c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_SUMMON_BAG, (slea, c, cs) -> InventoryHandler.UseSummonBag(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_TREASUER_CHEST, (slea, c, cs) -> InventoryHandler.UseTreasureChest(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_SKILL_BOOK, (slea, c, cs) -> {
            //                c.getPlayer().updateTick(slea.readInt());
            InventoryHandler.UseSkillBook(slea, c, c.getPlayer());
        });
        put(RecvPacketOpcode.USE_CATCH_ITEM, (slea, c, cs) -> InventoryHandler.UseCatchItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.USE_MOUNT_FOOD, (slea, c, cs) -> InventoryHandler.UseMountFood(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.REWARD_ITEM, (slea, c, cs) -> InventoryHandler.UseRewardItem((byte) slea.readShort(), slea.readInt(), c, c.getPlayer()));
        put(RecvPacketOpcode.HYPNOTIZE_DMG, (slea, c, cs) -> MobHandler.HypnotizeDmg(slea, c.getPlayer()));
        put(RecvPacketOpcode.MOB_NODE, (slea, c, cs) -> MobHandler.MobNode(slea, c.getPlayer()));
        put(RecvPacketOpcode.DISPLAY_NODE, (slea, c, cs) -> MobHandler.DisplayNode(slea, c.getPlayer()));
        put(RecvPacketOpcode.MOVE_LIFE, (slea, c, cs) -> MobHandler.MoveMonster(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.AUTO_AGGRO, (slea, c, cs) -> MobHandler.AutoAggro(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.FRIENDLY_DAMAGE, (slea, c, cs) -> MobHandler.FriendlyDamage(slea, c.getPlayer()));
        put(RecvPacketOpcode.MONSTER_BOMB, (slea, c, cs) -> MobHandler.MonsterBomb(slea.readInt(), c.getPlayer()));
        put(RecvPacketOpcode.NPC_SHOP, (slea, c, cs) -> NPCHandler.NPCShop(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.NPC_TALK, (slea, c, cs) -> NPCHandler.NPCTalk(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.NPC_TALK_MORE, (slea, c, cs) -> NPCHandler.NPCMoreTalk(slea, c));
        put(RecvPacketOpcode.NPC_ACTION, (slea, c, cs) -> NPCHandler.NPCAnimation(slea, c));
        put(RecvPacketOpcode.QUEST_ACTION, (slea, c, cs) -> NPCHandler.QuestAction(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.STORAGE, (slea, c, cs) -> NPCHandler.Storage(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.GENERAL_CHAT, (slea, c, cs) -> {
            //                c.getPlayer().updateTick(slea.readInt());
            ChatHandler.GeneralChat(slea.readMapleAsciiString(), slea.readByte(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.PARTYCHAT, (slea, c, cs) -> ChatHandler.Others(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.WHISPER, (slea, c, cs) -> ChatHandler.Whisper_Find(slea, c));
        put(RecvPacketOpcode.MESSENGER, (slea, c, cs) -> ChatHandler.Messenger(slea, c));
        put(RecvPacketOpcode.AUTO_ASSIGN_AP, (slea, c, cs) -> StatsHandling.AutoAssignAP(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.DISTRIBUTE_AP, (slea, c, cs) -> StatsHandling.DistributeAP(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.DISTRIBUTE_SP, (slea, c, cs) -> {
            c.getPlayer().updateTick(slea.readInt());
            StatsHandling.DistributeSP(slea.readInt(), c, c.getPlayer());
        });
        put(RecvPacketOpcode.PLAYER_INTERACTION, (slea, c, cs) -> PlayerInteractionHandler.PlayerInteraction(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.GUILD_OPERATION, (slea, c, cs) -> GuildHandler.Guild(slea, c));
        put(RecvPacketOpcode.UPDATE_CHAR_INFO, (slea, c, cs) -> {
            //System.err.println("UPDATE_CHAR_INFO");
            //PlayersHandler.UpdateCharInfo(slea.readAsciiString(), c);
            PlayersHandler.UpdateCharInfo(slea, c, c.getPlayer());
        });
        put(RecvPacketOpcode.DENY_GUILD_REQUEST, (slea, c, cs) -> {
            slea.skip(1);
            GuildHandler.DenyGuildRequest(slea.readMapleAsciiString(), c);
        });
        put(RecvPacketOpcode.ALLIANCE_OPERATION, (slea, c, cs) -> AllianceHandler.HandleAlliance(slea, c, false));
        put(RecvPacketOpcode.DENY_ALLIANCE_REQUEST, (slea, c, cs) -> AllianceHandler.HandleAlliance(slea, c, true));
        put(RecvPacketOpcode.BBS_OPERATION, (slea, c, cs) -> BBSHandler.BBSOperatopn(slea, c));
        put(RecvPacketOpcode.PARTY_OPERATION, (slea, c, cs) -> PartyHandler.PartyOperatopn(slea, c));
        put(RecvPacketOpcode.DENY_PARTY_REQUEST, (slea, c, cs) -> PartyHandler.DenyPartyRequest(slea, c));
        put(RecvPacketOpcode.BUDDYLIST_MODIFY, (slea, c, cs) -> BuddyListHandler.BuddyOperation(slea, c));
        put(RecvPacketOpcode.CYGNUS_SUMMON, (slea, c, cs) -> UserInterfaceHandler.CygnusSummon_NPCRequest(c));
        put(RecvPacketOpcode.SHIP_OBJECT, (slea, c, cs) -> UserInterfaceHandler.ShipObjectRequest(slea.readInt(), c));
        put(RecvPacketOpcode.BUY_CS_ITEM, (slea, c, cs) -> CashShopOperation.BuyCashItem(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.COUPON_CODE, (slea, c, cs) -> {
            FileoutputUtil.log(FileoutputUtil.PacketEx_Log, "Coupon : \n" + slea.toString(true));
            LOG.log(System.Logger.Level.INFO, slea.toString());
            slea.skip(2);
            CashShopOperation.CouponCode(slea.readMapleAsciiString(), c);
        });
        put(RecvPacketOpcode.CS_UPDATE, (slea, c, cs) -> CashShopOperation.CSUpdate(c));
        put(RecvPacketOpcode.TOUCHING_MTS, (slea, c, cs) -> MTSOperation.MTSUpdate(MTSStorage.getInstance().getCart(c.getPlayer().getId()), c));
        put(RecvPacketOpcode.MTS_TAB, (slea, c, cs) -> MTSOperation.MTSOperation(slea, c));
        put(RecvPacketOpcode.DAMAGE_SUMMON, (slea, c, cs) -> {
            slea.skip(4);
            SummonHandler.DamageSummon(slea, c.getPlayer());
        });
        put(RecvPacketOpcode.MOVE_SUMMON, (slea, c, cs) -> SummonHandler.MoveSummon(slea, c.getPlayer()));
        put(RecvPacketOpcode.SUMMON_ATTACK, (slea, c, cs) -> SummonHandler.SummonAttack(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MOVE_DRAGON, (slea, c, cs) -> SummonHandler.MoveDragon(slea, c.getPlayer()));
        put(RecvPacketOpcode.SPAWN_PET, (slea, c, cs) -> PetHandler.SpawnPet(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MOVE_PET, (slea, c, cs) -> PetHandler.MovePet(slea, c.getPlayer()));
        put(RecvPacketOpcode.PET_CHAT, (slea, c, cs) -> {
            if (slea.available() < 12) {
                return;
            }
            PetHandler.PetChat((int) slea.readLong(), slea.readShort(), slea.readMapleAsciiString(), c.getPlayer());
        });
        put(RecvPacketOpcode.PET_COMMAND, (slea, c, cs) -> PetHandler.PetCommand(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PET_FOOD, (slea, c, cs) -> PetHandler.PetFood(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PET_LOOT, (slea, c, cs) -> InventoryHandler.Pickup_Pet(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.PET_AUTO_POT, (slea, c, cs) -> PetHandler.Pet_AutoPotion(slea, c, c.getPlayer()));
        put(RecvPacketOpcode.MONSTER_CARNIVAL, (slea, c, cs) -> MonsterCarnivalHandler.MonsterCarnival(slea, c));
        put(RecvPacketOpcode.DUEY_ACTION, (slea, c, cs) -> DueyHandler.DueyOperation(slea, c));
        put(RecvPacketOpcode.USE_HIRED_MERCHANT, (slea, c, cs) -> HiredMerchantHandler.UseHiredMerchant(slea, c));
        put(RecvPacketOpcode.MERCH_ITEM_STORE, (slea, c, cs) -> HiredMerchantHandler.MerchantItemStore(slea, c));
        put(RecvPacketOpcode.CANCEL_DEBUFF, (slea, c, cs) -> {
            // Ignore for now
        });
        put(RecvPacketOpcode.LEFT_KNOCK_BACK, (slea, c, cs) -> PlayerHandler.leftKnockBack(slea, c));
        put(RecvPacketOpcode.SNOWBALL, (slea, c, cs) -> PlayerHandler.snowBall(slea, c));
        put(RecvPacketOpcode.COCONUT, (slea, c, cs) -> PlayersHandler.hitCoconut(slea, c));
        put(RecvPacketOpcode.REPAIR, (slea, c, cs) -> NPCHandler.repair(slea, c));
        put(RecvPacketOpcode.REPAIR_ALL, (slea, c, cs) -> NPCHandler.repairAll(c));
        put(RecvPacketOpcode.GAME_POLL, (slea, c, cs) -> UserInterfaceHandler.InGame_Poll(slea, c));
        put(RecvPacketOpcode.OWL, (slea, c, cs) -> InventoryHandler.Owl(slea, c));
        put(RecvPacketOpcode.OWL_WARP, (slea, c, cs) -> InventoryHandler.OwlWarp(slea, c));
        put(RecvPacketOpcode.USE_OWL_MINERVA, (slea, c, cs) -> InventoryHandler.OwlMinerva(slea, c));
        put(RecvPacketOpcode.RPS_GAME, (slea, c, cs) -> NPCHandler.RPSGame(slea, c));
        put(RecvPacketOpcode.UPDATE_QUEST, (slea, c, cs) -> NPCHandler.UpdateQuest(slea, c));
        put(RecvPacketOpcode.USE_ITEM_QUEST, (slea, c, cs) -> NPCHandler.UseItemQuest(slea, c));
        put(RecvPacketOpcode.FOLLOW_REQUEST, (slea, c, cs) -> PlayersHandler.FollowRequest(slea, c));
        put(RecvPacketOpcode.FOLLOW_REPLY, (slea, c, cs) -> PlayersHandler.FollowReply(slea, c));
        put(RecvPacketOpcode.RING_ACTION, (slea, c, cs) -> PlayersHandler.RingAction(slea, c));
        put(RecvPacketOpcode.REQUEST_FAMILY, (slea, c, cs) -> FamilyHandler.RequestFamily(slea, c));
        put(RecvPacketOpcode.OPEN_FAMILY, (slea, c, cs) -> FamilyHandler.OpenFamily(slea, c));
        put(RecvPacketOpcode.FAMILY_OPERATION, (slea, c, cs) -> FamilyHandler.FamilyOperation(slea, c));
        put(RecvPacketOpcode.DELETE_JUNIOR, (slea, c, cs) -> FamilyHandler.DeleteJunior(slea, c));
        put(RecvPacketOpcode.DELETE_SENIOR, (slea, c, cs) -> FamilyHandler.DeleteSenior(slea, c));
        put(RecvPacketOpcode.USE_FAMILY, (slea, c, cs) -> FamilyHandler.UseFamily(slea, c));
        put(RecvPacketOpcode.FAMILY_PRECEPT, (slea, c, cs) -> FamilyHandler.FamilyPrecept(slea, c));
        put(RecvPacketOpcode.FAMILY_SUMMON, (slea, c, cs) -> FamilyHandler.FamilySummon(slea, c));
        put(RecvPacketOpcode.ACCEPT_FAMILY, (slea, c, cs) -> FamilyHandler.AcceptFamily(slea, c));
        put(RecvPacketOpcode.SOLOMON, (slea, c, cs) -> PlayersHandler.Solomon(slea, c));
        put(RecvPacketOpcode.GACH_EXP, (slea, c, cs) -> PlayersHandler.GachExp(slea, c));
    }
}
