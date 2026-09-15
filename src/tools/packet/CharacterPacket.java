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
package tools.packet;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import client.inventory.MapleMount;
import client.inventory.IItem;
import constants.GameConstants;
import client.MapleBuffStat;
import client.MapleCharacter;
import client.inventory.MapleInventoryType;
import client.MapleKeyLayout;
import client.inventory.MaplePet;
import client.MapleQuestStatus;
import client.MapleStat;
import client.inventory.MapleRing;
import client.SkillMacro;
import handling.MaplePacket;
import handling.SendPacketOpcode;
import handling.world.guild.MapleGuild;
import handling.world.Alliance;
import handling.world.Guild;
import handling.world.guild.MapleGuildAlliance;
import server.MapleItemInformationProvider;
import server.Randomizer;
import server.movement.LifeMovementFragment;
import tools.data.output.MaplePacketLittleEndianWriter;
import tools.AttackPair;
import tools.Pair;

/**
 * 角色資訊、外觀、能力值、名聲、經驗與楓幣取得、攻擊與技能效果（E5 由 MaplePacketCreator 拆出）。
 */
public class CharacterPacket {

    public final static List<Pair<MapleStat, Integer>> EMPTY_STATUPDATE = Collections.emptyList();

    public static final MaplePacket getCharInfo(final MapleCharacter chr) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.WARP_TO_MAP.getValue());
        mplew.writeInt(chr.getClient().getChannel() - 1);
        mplew.write(1);
        mplew.write(1);
        mplew.writeShort(0);

        chr.CRand().connectData(mplew); // Random number generator

        PacketHelper.addCharacterInfo(mplew, chr);

        mplew.writeLong(PacketHelper.getTime(System.currentTimeMillis()));

        return mplew.getPacket();
    }

    public static final MaplePacket enableActions() {
        return updatePlayerStats(EMPTY_STATUPDATE, true, 0);
    }

    public static final MaplePacket updatePlayerStats(final List<Pair<MapleStat, Integer>> stats, final int evan) {
        return updatePlayerStats(stats, false, evan);
    }

    public static final MaplePacket updatePlayerStats(final List<Pair<MapleStat, Integer>> stats, final boolean itemReaction, final int evan) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.UPDATE_STATS.getValue());
        mplew.write(itemReaction ? 1 : 0);
        int updateMask = 0;
        for (final Pair<MapleStat, Integer> statupdate : stats) {
            updateMask |= statupdate.getLeft().getValue();
        }
        List<Pair<MapleStat, Integer>> mystats = stats;
        if (mystats.size() > 1) {
            Collections.sort(mystats, (o1, o2) -> {
                int val1 = o1.getLeft().getValue();
                int val2 = o2.getLeft().getValue();
                return (val1 < val2 ? -1 : (val1 == val2 ? 0 : 1));
            });
        }
        mplew.writeInt(updateMask);
        Integer value;

        for (final Pair<MapleStat, Integer> statupdate : mystats) {
            value = statupdate.getLeft().getValue();

            if (value >= 1) {
                if (value == 0x1) {
                    mplew.writeShort(statupdate.getRight().shortValue());
                } else if (value <= 0x4) {
                    mplew.writeInt(statupdate.getRight());
                } else if (value < 0x20) {
                    mplew.write(statupdate.getRight().byteValue());
                } else if (value == 0x8000) { //availablesp
                    if (GameConstants.isEvan(evan) || GameConstants.isResist(evan)) {
                        throw new UnsupportedOperationException("Evan/Resistance wrong updating");
                    } else {
                        mplew.writeShort(statupdate.getRight().shortValue());
                    }
                } else if (value < 0xFFFF) {
                    mplew.writeShort(statupdate.getRight().shortValue());
                } else {
                    mplew.writeInt(statupdate.getRight().intValue());
                }
            }
        }
        return mplew.getPacket();
    }

    public static final MaplePacket updateSp(MapleCharacter chr, final boolean itemReaction) { //this will do..
        return updateSp(chr, itemReaction, false);
    }

    public static final MaplePacket updateSp(MapleCharacter chr, final boolean itemReaction, final boolean overrideJob) { //this will do..
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.UPDATE_STATS.getValue());
        mplew.write(itemReaction ? 1 : 0);
        mplew.writeInt(0x8000);
        if (overrideJob || GameConstants.isEvan(chr.getJob()) || GameConstants.isResist(chr.getJob())) {
            mplew.write(chr.getRemainingSpSize());
            for (int i = 0; i < chr.getRemainingSps().length; i++) {
                if (chr.getRemainingSp(i) > 0) {
                    mplew.write(i + 1);
                    mplew.write(chr.getRemainingSp(i));
                }
            }
        } else {
            mplew.writeShort(chr.getRemainingSp());
        }
        return mplew.getPacket();

    }

    public static MaplePacket GameMaster_Func(int value) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.GM_EFFECT.getValue());
        mplew.write(value);
        mplew.writeZeroBytes(17);

        return mplew.getPacket();
    }

    public static MaplePacket testCombo(int value) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.ARAN_COMBO.getValue());
        mplew.writeInt(value);

        return mplew.getPacket();
    }

    public static final MaplePacket GainEXP_Monster(final int gain, final boolean white, final int partyinc, final int Class_Bonus_EXP, final int Equipment_Bonus_EXP, final int Premium_Bonus_EXP) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_STATUS_INFO.getValue());
        mplew.write(3); // 3 = exp, 4 = fame, 5 = mesos, 6 = guildpoints
        mplew.write(white ? 1 : 0);
        mplew.writeInt(gain);
        mplew.write(0); // Not in chat
        mplew.writeInt(0); // Event Bonus
        mplew.writeShort(0);
        mplew.writeInt(0); //wedding bonus
        mplew.writeInt(0); //party ring bonus
        mplew.write(0);
        mplew.writeInt(partyinc); // Party size
        mplew.writeInt(Equipment_Bonus_EXP); //Equipment Bonus EXP
        mplew.writeInt(Premium_Bonus_EXP); // Premium bonus EXP
        // 逆向出來的線路格式筆記：其他版本在此還有 Rainbow Week 加成欄位，v113 沒有，取消註解會多送 4 bytes。
//        mplew.writeInt(0); //Rainbow Week Bonus EXP
        mplew.writeInt(Class_Bonus_EXP); // Class bonus EXP
        // 同上：其他版本在結尾多一個 byte，v113 沒有。
//        mplew.write(0);

        return mplew.getPacket();
    }

    public static final MaplePacket GainEXP_Others(final int gain, final boolean inChat, final boolean white) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_STATUS_INFO.getValue());
        mplew.write(3); // 3 = exp, 4 = fame, 5 = mesos, 6 = guildpoints
        mplew.write(white ? 1 : 0);
        mplew.writeInt(gain);
        mplew.write(inChat ? 1 : 0);
        mplew.writeInt(0); // monster book bonus
        mplew.write(0); // Party percentage
        mplew.writeShort(0); // Party bouns
        mplew.writeZeroBytes(8);

        if (inChat) {
            mplew.writeZeroBytes(4); // some ring bonus/ party exp ??
            mplew.writeZeroBytes(10);
        } else { // some ring bonus/ party exp
            mplew.writeInt(0); // Party size
            mplew.writeZeroBytes(4); // Item equip bonus EXP
        }
        mplew.writeZeroBytes(4); // Premium bonus EXP
        mplew.writeZeroBytes(4); // Class bonus EXP
        // 逆向出來的線路格式筆記：其他版本在此還有兩個尾欄位，v113 沒有。
//        mplew.writeInt(0);
//        mplew.write(0);

        return mplew.getPacket();
    }

    public static final MaplePacket getShowFameGain(final int gain) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_STATUS_INFO.getValue());
        mplew.write(4);
        mplew.writeInt(gain);

        return mplew.getPacket();
    }

    public static final MaplePacket showMesoGain(final int gain, final boolean inChat) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_STATUS_INFO.getValue());
        if (!inChat) {
            mplew.write(0);
            mplew.write(1);
            mplew.write(0);
            mplew.writeInt(gain);
            mplew.writeShort(0); // inet cafe meso gain ?.o
        } else {
            mplew.write(5);
            mplew.writeInt(gain);
        }

        return mplew.getPacket();
    }

    public static MaplePacket spawnPlayerMapobject(MapleCharacter chr) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SPAWN_PLAYER.getValue());
        mplew.writeInt(chr.getId());
        mplew.write(chr.getLevel());
        mplew.writeMapleAsciiString(chr.getName());
        String Prefix = "";
        if (chr.getPrefix() == 1) {
            Prefix = "[技術團隊成員]";
        }
        if (chr.getPrefix() == 2) {
            Prefix = "[遊戲管理成員]";
        }
        if (chr.getPrefix() == 3) {
            Prefix = "[活動舉辦成員]";
        }

        if (chr.getGuildId() <= 0) {
            final MapleGuild gs = Guild.getGuild(chr.getGuildId());
            if(Prefix != null){
                mplew.writeMapleAsciiString(Prefix);
              } else {
              mplew.writeMapleAsciiString("");
              }

            mplew.writeShort(0);
            mplew.write(0);
            mplew.writeShort(0);
            mplew.write(0);
        } else {
            final MapleGuild gs = Guild.getGuild(chr.getGuildId());
            if (gs != null) {
                mplew.writeMapleAsciiString(Prefix + gs.getName());
                mplew.writeShort(gs.getLogoBG());
                mplew.write(gs.getLogoBGColor());
                mplew.writeShort(gs.getLogo());
                mplew.write(gs.getLogoColor());
            } else {
                mplew.writeInt(0);
                mplew.writeInt(0);
            }
        }
        //mplew.writeInt(3); after aftershock
        List<Pair<Integer, Boolean>> buffvalue = new ArrayList<>();
        long fbuffmask = 0xFFFC0000000000L; //becomes F8000000 after bb?
        // 以下四段被註解掉的 buff 位元是逆向出來的線路格式筆記：這些 buff 在其他版本
        // 會進 fbuffmask（並在 buffvalue 補一個值），v113 的 CHAR_INFO 不送它們。
        //if (chr.getBuffedValue(MapleBuffStat.FINAL_CUT) != null) {
        //    fbuffmask |= MapleBuffStat.FINAL_CUT.getValue();
        //    buffvalue.add(new Pair<Integer, Boolean>(Integer.valueOf(chr.getBuffedValue(MapleBuffStat.FINAL_CUT).intValue()), false));
        //}
        //if (chr.getBuffedValue(MapleBuffStat.OWL_SPIRIT) != null) {
        //    fbuffmask |= MapleBuffStat.OWL_SPIRIT.getValue();
        //    buffvalue.add(new Pair<Integer, Boolean>(Integer.valueOf(chr.getBuffedValue(MapleBuffStat.OWL_SPIRIT).intValue()), false));
        //}
        if (chr.getBuffedValue(MapleBuffStat.SOARING) != null) {
            fbuffmask |= MapleBuffStat.SOARING.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.MIRROR_IMAGE) != null) {
            fbuffmask |= MapleBuffStat.MIRROR_IMAGE.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.DARK_AURA) != null) {
            fbuffmask |= MapleBuffStat.DARK_AURA.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.BLUE_AURA) != null) {
            fbuffmask |= MapleBuffStat.BLUE_AURA.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.YELLOW_AURA) != null) {
            fbuffmask |= MapleBuffStat.YELLOW_AURA.getValue();
        }
        //if (chr.getBuffedValue(MapleBuffStat.PYRAMID_PQ) != null) {
        //    fbuffmask |= MapleBuffStat.PYRAMID_PQ.getValue();
        //    buffvalue.add(new Pair<Integer, Boolean>(Integer.valueOf(chr.getBuffedValue(MapleBuffStat.PYRAMID_PQ).intValue()), false)); //idk
        //}
        //if (chr.getBuffedValue(MapleBuffStat.MAGIC_SHIELD) != null) {
        //    fbuffmask |= MapleBuffStat.MAGIC_SHIELD.getValue();
        //    buffvalue.add(new Pair<Integer, Boolean>(Integer.valueOf(chr.getBuffedValue(MapleBuffStat.MAGIC_SHIELD).intValue()), false)); //idk
        //}
        mplew.writeLong(fbuffmask);
        /*	mplew.writeInt(0);
         mplew.write(0);
         mplew.write(-4); // SEA V82 	mplew.write(-2);
         mplew.write(-1); // SEA V82 	mplew.write(0);
         mplew.write(0); // SEA V82 	mplew.write(0);*/
        long buffmask = 0;

        if (chr.getBuffedValue(MapleBuffStat.DARKSIGHT) != null && !chr.isHidden()) {
            buffmask |= MapleBuffStat.DARKSIGHT.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.COMBO) != null) {
            buffmask |= MapleBuffStat.COMBO.getValue();
            buffvalue.add(new Pair<>(Integer.valueOf(chr.getBuffedValue(MapleBuffStat.COMBO).intValue()), false));
        }
        if (chr.getBuffedValue(MapleBuffStat.SHADOWPARTNER) != null) {
            buffmask |= MapleBuffStat.SHADOWPARTNER.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.SOULARROW) != null) {
            buffmask |= MapleBuffStat.SOULARROW.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.DIVINE_BODY) != null) {
            buffmask |= MapleBuffStat.DIVINE_BODY.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.BERSERK_FURY) != null) {
            buffmask |= MapleBuffStat.BERSERK_FURY.getValue();
        }
        if (chr.getBuffedValue(MapleBuffStat.MORPH) != null) {
            buffmask |= MapleBuffStat.MORPH.getValue();
            buffvalue.add(new Pair<>(Integer.valueOf(chr.getBuffedValue(MapleBuffStat.MORPH).intValue()), true));
        }

        mplew.writeLong(buffmask);
        for (Pair<Integer, Boolean> i : buffvalue) {
            if (i.right) {
                mplew.writeShort(i.left.shortValue());
            } else {
                mplew.write(i.left.byteValue());
            }
        }
        final int CHAR_MAGIC_SPAWN = Randomizer.nextInt();
        //CHAR_MAGIC_SPAWN is really just tickCount
        //this is here as it explains the 7 "dummy" buffstats which are placed into every character
        //these 7 buffstats are placed because they have irregular packet structure.
        //they ALL have writeShort(0); first, then a long as their variables, then server tick count
        //0x80000, 0x100000, 0x200000, 0x400000, 0x800000, 0x1000000, 0x2000000

        mplew.writeShort(0); //start of energy charge
        mplew.writeLong(0);
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeShort(0); //start of dash_speed
        mplew.writeLong(0);
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeShort(0); //start of dash_jump
        mplew.writeLong(0);
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeShort(0); //start of Monster Riding
        int buffSrc = chr.getBuffSource(MapleBuffStat.MONSTER_RIDING);
        if (buffSrc > 0) {
            final IItem c_mount = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -118);
            final IItem mount = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -18);
            if (GameConstants.getMountItem(buffSrc) == 0 && c_mount != null) {
                mplew.writeInt(c_mount.getItemId());
            } else if (GameConstants.getMountItem(buffSrc) == 0 && mount != null) {
                mplew.writeInt(mount.getItemId());
            } else {
                mplew.writeInt(GameConstants.getMountItem(buffSrc));
            }
            mplew.writeInt(buffSrc);
        } else {
            mplew.writeLong(0);
        }
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeLong(0); //speed infusion behaves differently here
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeInt(1);
        mplew.writeLong(0); //homing beacon
        mplew.write(0);
        mplew.writeShort(0);
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeInt(0); //and finally, something ive no idea
        mplew.writeLong(0);
        mplew.write(1);
        mplew.writeInt(CHAR_MAGIC_SPAWN);
        mplew.writeShort(0);
        mplew.writeLong(0); // 台版自己加的
        mplew.write(1); // 台版自己加的
        mplew.writeInt(CHAR_MAGIC_SPAWN); // 台版自己加的
        mplew.writeShort(chr.getJob());
        PacketHelper.addCharLook(mplew, chr, false);
//        mplew.writeInt(0);//this is CHARID to follow
//        mplew.writeInt(0); //probably charid following
//        mplew.writeLong(Math.min(250, chr.getInventory(MapleInventoryType.CASH).countById(5110000))); //max is like 100. but w/e
        mplew.writeInt(Math.min(250, chr.getInventory(MapleInventoryType.CASH).countById(5110000))); //max is like 100. but w/e
        mplew.writeInt(chr.getItemEffect());
        mplew.writeInt(0); // 台版自己加的
        mplew.writeInt(0); // 只有台陸版有
        mplew.writeInt(-1); // 只有台陸版有
        mplew.writeInt(GameConstants.getInventoryType(chr.getChair()) == MapleInventoryType.SETUP ? chr.getChair() : 0);
        mplew.writePos(chr.getPosition());
        mplew.write(chr.getStance());
        mplew.writeShort(0); // FH
        mplew.write(0);
        mplew.writeInt(chr.getMount().getLevel()); // mount lvl
        mplew.writeInt(chr.getMount().getExp()); // exp
        mplew.writeInt(chr.getMount().getFatigue()); // tiredness
        PacketHelper.addAnnounceBox(mplew, chr);
        mplew.write(chr.getChalkboard() != null && chr.getChalkboard().length() > 0 ? 1 : 0);
        if (chr.getChalkboard() != null && chr.getChalkboard().length() > 0) {
            mplew.writeMapleAsciiString(chr.getChalkboard());
        }
        Pair<List<MapleRing>, List<MapleRing>> rings = chr.getRings(false);
        addRingInfo(mplew, rings.getLeft());
        addRingInfo(mplew, rings.getRight());
        mplew.writeShort(0);
        mplew.write(0); // 台版自己加的
        if (chr.getCarnivalParty() != null) {
            mplew.write(chr.getCarnivalParty().getTeam());
        } else if (chr.getMapId() == 109080000 || chr.getMapId() == 109080010) {
            mplew.write(1/*chr.getCoconutTeam()*/); //is it 0/1 or is it 1/2?
        }
        return mplew.getPacket();
    }

    public static MaplePacket removePlayerFromMap(int cid) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.REMOVE_PLAYER_FROM_MAP.getValue());
        mplew.writeInt(cid);

        return mplew.getPacket();
    }

    public static MaplePacket facialExpression(MapleCharacter from, int expression) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.FACIAL_EXPRESSION.getValue());
        mplew.writeInt(from.getId());
        mplew.writeInt(expression);
        // 逆向出來的線路格式筆記：其他版本在此還送表情道具 id 與一個 byte，v113 沒有。
        /*        mplew.writeInt(-1); //itemid of expression use
         mplew.write(0);*/

        return mplew.getPacket();
    }

    public static MaplePacket movePlayer(int cid, List<LifeMovementFragment> moves, Point startPos) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.MOVE_PLAYER.getValue());
        mplew.writeInt(cid);
        // 逆向出來的線路格式筆記：其他版本在此送起點座標，v113 送一個 int 0（長度相同，內容不同）。
//        mplew.writePos(startPos);
        mplew.writeInt(0);
        PacketHelper.serializeMovementList(mplew, moves);

        return mplew.getPacket();
    }

    public static MaplePacket closeRangeAttack(int cid, int tbyte, int skill, int level, byte display, byte animation, byte speed, List<AttackPair> damage, final boolean energy, int lvl, byte mastery, byte unk, int charge) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(energy ? SendPacketOpcode.ENERGY_ATTACK.getValue() : SendPacketOpcode.CLOSE_RANGE_ATTACK.getValue());
        mplew.writeInt(cid);
        mplew.write(tbyte);
        mplew.write(lvl); //?
        if (skill > 0) {
            mplew.write(level);
            mplew.writeInt(skill);
        } else {
            mplew.write(0);
        }
        mplew.write(unk); // Added on v.82
        mplew.write(display);
        mplew.write(animation);
        mplew.write(speed);
        mplew.write(mastery); // Mastery
        mplew.writeInt(0);  // E9 03 BE FC

        if (skill == 4211006) {
            for (AttackPair oned : damage) {
                if (oned.attack != null) {
                    mplew.writeInt(oned.objectid);
                    mplew.write(0x07);
                    mplew.write(oned.attack.size());
                    for (Pair<Integer, Boolean> eachd : oned.attack) {
                        // highest bit set = crit
                        mplew.writeInt(eachd.left); //m.e. is never crit
                    }
                }
            }
        } else {
            for (AttackPair oned : damage) {
                if (oned.attack != null) {
                    mplew.writeInt(oned.objectid);
                    mplew.write(0x07);
                    for (Pair<Integer, Boolean> eachd : oned.attack) {
                        // highest bit set = crit
                        if (eachd.right) {
                            mplew.writeInt(eachd.left.intValue() + 0x80000000);
                        } else {
                            mplew.writeInt(eachd.left.intValue());
                        }
                    }
                }
            }
        }
        //if (charge > 0) {
        //	mplew.writeInt(charge); //is it supposed to be here
        //}
        return mplew.getPacket();
    }

    public static MaplePacket rangedAttack(int cid, byte tbyte, int skill, int level, byte display, byte animation, byte speed, int itemid, List<AttackPair> damage, final Point pos, int lvl, byte mastery, byte unk) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.RANGED_ATTACK.getValue());
        mplew.writeInt(cid);
        mplew.write(tbyte);
        mplew.write(lvl); //?
        if (skill > 0) {
            mplew.write(level);
            mplew.writeInt(skill);
        } else {
            mplew.write(0);
        }
        mplew.write(unk); // Added on v.82
        mplew.write(display);
        mplew.write(animation);
        mplew.write(speed);
        mplew.write(mastery); // Mastery level, who cares
        mplew.writeInt(itemid);

        for (AttackPair oned : damage) {
            if (oned.attack != null) {
                mplew.writeInt(oned.objectid);
                mplew.write(0x07);
                for (Pair<Integer, Boolean> eachd : oned.attack) {
                    // highest bit set = crit
                    if (eachd.right) {
                        mplew.writeInt(eachd.left.intValue() + 0x80000000);
                    } else {
                        mplew.writeInt(eachd.left.intValue());
                    }
                }
            }
        }
        mplew.writePos(pos); // Position

        return mplew.getPacket();
    }

    public static MaplePacket magicAttack(int cid, int tbyte, int skill, int level, byte display, byte animation, byte speed, List<AttackPair> damage, int charge, int lvl, byte unk) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.MAGIC_ATTACK.getValue());
        mplew.writeInt(cid);
        mplew.write(tbyte);
        mplew.write(lvl); //?
        mplew.write(level);
        mplew.writeInt(skill);

        mplew.write(unk); // Added on v.82
        mplew.write(display);
        mplew.write(animation);
        mplew.write(speed);
        mplew.write(0); // Mastery byte is always 0 because spells don't have a swoosh
        mplew.writeInt(0);

        for (AttackPair oned : damage) {
            if (oned.attack != null) {
                mplew.writeInt(oned.objectid);
                mplew.write(-1/*0x07*/);
                for (Pair<Integer, Boolean> eachd : oned.attack) {
                    // highest bit set = crit
                    if (eachd.right) {
                        mplew.writeInt(eachd.left.intValue() + 0x80000000);
                    } else {
                        mplew.writeInt(eachd.left.intValue());
                    }
                }
            }
        }
        if (charge > 0) {
            mplew.writeInt(charge);
        }
        return mplew.getPacket();
    }

    public static MaplePacket updateCharLook(MapleCharacter chr) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.UPDATE_CHAR_LOOK.getValue());
        mplew.writeInt(chr.getId());
        mplew.write(1);
        PacketHelper.addCharLook(mplew, chr, false);
        Pair<List<MapleRing>, List<MapleRing>> rings = chr.getRings(false);
        addRingInfo(mplew, rings.getLeft());
        addRingInfo(mplew, rings.getRight());
        mplew.writeZeroBytes(5); //probably marriage ring (1) -> charid to follow (4)
        return mplew.getPacket();
    }

    public static void addRingInfo(MaplePacketLittleEndianWriter mplew, List<MapleRing> rings) {
        mplew.write(rings.size());
        for (MapleRing ring : rings) {
            mplew.writeInt(1);
            mplew.writeLong(ring.getRingId());
            mplew.writeLong(ring.getPartnerRingId());
            mplew.writeInt(ring.getItemId());
        }
    }

    public static MaplePacket damagePlayer(int skill, int monsteridfrom, int cid, int damage, int fake, byte direction, int reflect, boolean is_pg, int oid, int pos_x, int pos_y) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.DAMAGE_PLAYER.getValue());
        mplew.writeInt(cid);
        mplew.write(skill);
        mplew.writeInt(damage);
        mplew.writeInt(monsteridfrom);
        mplew.write(direction);

        if (reflect > 0) {
            mplew.write(reflect);
            mplew.write(is_pg ? 1 : 0);
            mplew.writeInt(oid);
            mplew.write(6);
            mplew.writeShort(pos_x);
            mplew.writeShort(pos_y);
            mplew.write(0);
        } else {
            mplew.writeShort(0);
        }
        mplew.writeInt(damage);
        if (fake > 0) {
            mplew.writeInt(fake);
        }
        return mplew.getPacket();
    }

    public static final MaplePacket charInfo(final MapleCharacter chr, final boolean isSelf) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.CHAR_INFO.getValue());
        mplew.writeInt(chr.getId());
        mplew.write(chr.getLevel());
        mplew.writeShort(chr.getJob());
        mplew.writeShort(chr.getFame());
        mplew.write(chr.getMarriageId() > 0 ? 1 : 0); // heart red or gray
        String Prefix = "";

        if (chr.getPrefix() == 1) {
            Prefix = "[技術團隊成員]";
        }
        if (chr.getPrefix() == 2) {
            Prefix = "[遊戲管理成員]";
        }
        if (chr.getPrefix() == 3) {
            Prefix = "[活動舉辦成員]";
        }
        if (chr.getGuildId() <= 0) {
            if(chr.getPrefix() == 0) {
            mplew.writeMapleAsciiString("尚未加入公會");
            mplew.writeMapleAsciiString("尚未加入聯盟");
            } else {
            mplew.writeMapleAsciiString(Prefix);
            mplew.writeMapleAsciiString("尚未加入聯盟");
            }
        } else {
            final MapleGuild gs = Guild.getGuild(chr.getGuildId());
            if (gs != null) {
                mplew.writeMapleAsciiString(gs.getName());
                if (gs.getAllianceId() > 0) {
                    final MapleGuildAlliance allianceName = Alliance.getAlliance(gs.getAllianceId());
                    if (allianceName != null) {
                        mplew.writeMapleAsciiString(allianceName.getName());
                    } else {
                        if(chr.getPrefix() == 0) {
                                mplew.writeMapleAsciiString("尚未加入聯盟");
                        } else {
                            mplew.writeMapleAsciiString(Prefix);
                        }
                    }
                } else {
                    if(chr.getPrefix() == 0) {
                        mplew.writeMapleAsciiString("尚未加入聯盟");
                    } else {
                        mplew.writeMapleAsciiString(Prefix);
                    }
                }
            } else {
                 if(chr.getPrefix() == 0) {
                     mplew.writeMapleAsciiString("尚未加入公會");
                     mplew.writeMapleAsciiString("尚未加入聯盟");
                 } else {
                     mplew.writeMapleAsciiString(Prefix);
                     mplew.writeMapleAsciiString("尚未加入聯盟");
                 }
            }
        }
        // 逆向出來的線路格式筆記：其他版本在此送一個「是否為自己」的 byte，v113 沒有。
//        mplew.write(isSelf ? 1 : 0);
        mplew.writeMapleAsciiString(chr.getcharmessage()); // 角色訊息
        mplew.write(chr.getexpression());// 表情
        mplew.write(chr.getconstellation());// 星座
        mplew.write(chr.getblood());// 血型
        mplew.write(chr.getmonth());// 月
        mplew.write(chr.getday());// 日

        int i = 0 ;
        for (final MaplePet pet : chr.getPets()) {
            if (pet.getSummoned()) {
                final IItem inv = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) (-114 - (i*8)));
                final int peteqid = inv != null ? inv.getItemId() : 0;
                mplew.write(pet.getUniqueId()); //o-o byte ?
                mplew.writeInt(pet.getPetItemId()); // petid
                mplew.writeMapleAsciiString(pet.getName());
                mplew.write(pet.getLevel()); // pet level
                mplew.writeShort(pet.getCloseness()); // pet closeness
                mplew.write(pet.getFullness()); // pet fullness
                mplew.writeShort(pet.getFlags());
                mplew.writeInt(peteqid);
                ++i;
            }
        }
        mplew.write(0); // End of pet

        if (chr.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -18) != null) {
            final int itemid = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -18).getItemId();
            final MapleMount mount = chr.getMount();
            final boolean canwear = MapleItemInformationProvider.getInstance().getReqLevel(itemid) <= chr.getLevel();
            mplew.write(canwear ? 1 : 0);
            if (canwear) {
                mplew.writeInt(mount.getLevel());
                mplew.writeInt(mount.getExp());
                mplew.writeInt(mount.getFatigue());
            }
        } else {
            mplew.write(0);
        }

        final int wishlistSize = chr.getWishlistSize();
        mplew.write(wishlistSize);
        if (wishlistSize > 0) {
            final int[] wishlist = chr.getWishlist();
            for (int x = 0; x < wishlistSize; x++) {
                mplew.writeInt(wishlist[x]);
            }
        }
        // 逆向出來的線路格式筆記：其他版本在願望清單後多一個 byte，v113 沒有。
//        mplew.write(0);
        chr.getMonsterBook().addCharInfoPacket(chr.getMonsterBookCover(), mplew);

        IItem medal = chr.getInventory(MapleInventoryType.EQUIPPED).getItem((byte) -21);
        mplew.writeInt(medal == null ? 0 : medal.getItemId());
        List<Integer> medalQuests = new ArrayList<>();
        List<MapleQuestStatus> completed = chr.getCompletedQuests();
        for (MapleQuestStatus q : completed) {
            if (q.getQuest().getMedalItem() > 0 && GameConstants.getInventoryType(q.getQuest().getMedalItem()) == MapleInventoryType.EQUIP) { //chair kind medal viewmedal is weird
                medalQuests.add(q.getQuest().getId());
            }
        }
        mplew.writeShort(medalQuests.size());
        for (int x : medalQuests) {
            mplew.writeShort(x);
        }
        return mplew.getPacket();
    }

    public static MaplePacket showForeignEffect(int cid, int effect) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_FOREIGN_EFFECT.getValue());
        mplew.writeInt(cid);
        mplew.write(effect); // 0 = Level up, 8 = job change

        return mplew.getPacket();
    }

    public static MaplePacket showBuffeffect(int cid, int skillid, int effectid) {
        return showBuffeffect(cid, skillid, effectid, (byte) 3);
    }

    public static MaplePacket showBuffeffect(int cid, int skillid, int effectid, byte direction) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_FOREIGN_EFFECT.getValue());
        mplew.writeInt(cid);
        mplew.write(effectid); //ehh?
        mplew.writeInt(skillid);
        mplew.write(1); //skill level = 1 for the lulz
        mplew.write(1); //actually skill level ? 0 = dosnt show
        if (direction != (byte) 3) {
            mplew.write(direction);
        }
        return mplew.getPacket();
    }

    public static MaplePacket showOwnBuffEffect(int skillid, int effectid) {
        return showOwnBuffEffect(skillid, effectid, (byte) 3);
    }

    public static MaplePacket showOwnBuffEffect(int skillid, int effectid, byte direction) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());
        mplew.write(effectid);
        mplew.writeInt(skillid);
        mplew.write(1); //skill level = 1 for the lulz
        mplew.write(1); //0 = doesnt show? or is this even here
        if (direction != (byte) 3) {
            mplew.write(direction);
        }

        return mplew.getPacket();
    }

    public static MaplePacket showItemLevelupEffect() {
        return showSpecialEffect(16);
    }

    public static MaplePacket showForeignItemLevelupEffect(int cid) {
        return showSpecialEffect(cid, 16);
    }

    public static MaplePacket showSpecialEffect(int effect) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());
        mplew.write(effect);

        return mplew.getPacket();
    }

    public static MaplePacket showSpecialEffect(int cid, int effect) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_FOREIGN_EFFECT.getValue());
        mplew.writeInt(cid);
        mplew.write(effect);

        return mplew.getPacket();
    }

    public static MaplePacket updateSkill(int skillid, int level, int masterlevel, long expiration) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.UPDATE_SKILLS.getValue());
        mplew.write(1);
        mplew.writeShort(1);
        mplew.writeInt(skillid);
        mplew.writeInt(level);
        mplew.writeInt(masterlevel);
        PacketHelper.addExpirationTime(mplew, expiration);
        mplew.write(4);

        return mplew.getPacket();
    }

    public static MaplePacket getKeymap(MapleKeyLayout layout) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.KEYMAP.getValue());
        mplew.write(0);

        layout.writeData(mplew);

        return mplew.getPacket();
    }

    public static MaplePacket giveFameResponse(int mode, String charname, int newfame) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.FAME_RESPONSE.getValue());
        mplew.write(0);
        mplew.writeMapleAsciiString(charname);
        mplew.write(mode);
        mplew.writeShort(newfame);
        mplew.writeShort(0);

        return mplew.getPacket();
    }

    public static MaplePacket giveFameErrorResponse(int status) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        /*	* 0: ok, use giveFameResponse<br>
         * 1: the username is incorrectly entered<br>
         * 2: users under level 15 are unable to toggle with fame.<br>
         * 3: can't raise or drop fame anymore today.<br>
         * 4: can't raise or drop fame for this character for this month anymore.<br>
         * 5: received fame, use receiveFame()<br>
         * 6: level of fame neither has been raised nor dropped due to an unexpected error*/
        mplew.writeShort(SendPacketOpcode.FAME_RESPONSE.getValue());
        mplew.write(status);

        return mplew.getPacket();
    }

    public static MaplePacket receiveFame(int mode, String charnameFrom) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.FAME_RESPONSE.getValue());
        mplew.write(5);
        mplew.writeMapleAsciiString(charnameFrom);
        mplew.write(mode);

        return mplew.getPacket();
    }

    public static MaplePacket itemEffect(int characterid, int itemid) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_ITEM_EFFECT.getValue());
        mplew.writeInt(characterid);
        mplew.writeInt(itemid);

        return mplew.getPacket();
    }

    public static MaplePacket showChair(int characterid, int itemid) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_CHAIR.getValue());
        mplew.writeInt(characterid);
        mplew.writeInt(itemid);

        return mplew.getPacket();
    }

    public static MaplePacket cancelChair(int id) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.CANCEL_CHAIR.getValue());
        if (id == -1) {
            mplew.write(0);
        } else {
            mplew.write(1);
            mplew.writeShort(id);
        }
        return mplew.getPacket();
    }

    public static MaplePacket skillEffect(MapleCharacter from, int skillId, byte level, byte flags, byte speed, byte unk) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SKILL_EFFECT.getValue());
        mplew.writeInt(from.getId());
        mplew.writeInt(skillId);
        mplew.write(level);
        mplew.write(flags);
        mplew.write(speed);
        mplew.write(unk); // Direction ??

        return mplew.getPacket();
    }

    public static MaplePacket skillCancel(MapleCharacter from, int skillId) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.CANCEL_SKILL_EFFECT.getValue());
        mplew.writeInt(from.getId());
        mplew.writeInt(skillId);

        return mplew.getPacket();
    }

    public static MaplePacket showEquipEffect() {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_EQUIP_EFFECT.getValue());

        return mplew.getPacket();
    }

    public static MaplePacket showEquipEffect(int team) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SHOW_EQUIP_EFFECT.getValue());
        mplew.writeShort(team);
        return mplew.getPacket();
    }

    public static MaplePacket skillCooldown(int sid, int time) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.COOLDOWN.getValue());
        mplew.writeInt(sid);
        mplew.writeShort(time);

        return mplew.getPacket();
    }

    public static MaplePacket useSkillBook(MapleCharacter chr, int skillid, int maxlevel, boolean canuse, boolean success) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.USE_SKILL_BOOK.getValue());
//        mplew.write(0); //?
        mplew.writeInt(chr.getId());
        mplew.write(1);
        mplew.writeInt(skillid);
        mplew.writeInt(maxlevel);
        mplew.write(canuse ? 1 : 0);
        mplew.write(success ? 1 : 0);

        return mplew.getPacket();
    }

    public static MaplePacket getMacros(SkillMacro[] macros) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.SKILL_MACRO.getValue());
        int count = 0;
        for (int i = 0; i < 5; i++) {
            if (macros[i] != null) {
                count++;
            }
        }
        mplew.write(count); // number of macros
        for (int i = 0; i < 5; i++) {
            SkillMacro macro = macros[i];
            if (macro != null) {
                mplew.writeMapleAsciiString(macro.getName());
                mplew.write(macro.getShout());
                mplew.writeInt(macro.getSkill1());
                mplew.writeInt(macro.getSkill2());
                mplew.writeInt(macro.getSkill3());
            }
        }
        return mplew.getPacket();
    }

    public static MaplePacket leftKnockBack() {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.LEFT_KNOCK_BACK.getValue());
        return mplew.getPacket();
    }

    public static final MaplePacket temporaryStats_Aran() {
        final List<Pair<MapleStat.Temp, Integer>> stats = new ArrayList<>();
        stats.add(new Pair<>(MapleStat.Temp.STR, 999));
        stats.add(new Pair<>(MapleStat.Temp.DEX, 999));
        stats.add(new Pair<>(MapleStat.Temp.INT, 999));
        stats.add(new Pair<>(MapleStat.Temp.LUK, 999));
        stats.add(new Pair<>(MapleStat.Temp.WATK, 255));
        stats.add(new Pair<>(MapleStat.Temp.ACC, 999));
        stats.add(new Pair<>(MapleStat.Temp.AVOID, 999));
        stats.add(new Pair<>(MapleStat.Temp.SPEED, 140));
        stats.add(new Pair<>(MapleStat.Temp.JUMP, 120));
        return temporaryStats(stats);
    }

    public static final MaplePacket temporaryStats_Balrog(final MapleCharacter chr) {
        final List<Pair<MapleStat.Temp, Integer>> stats = new ArrayList<>();
        int offset = 1 + (chr.getLevel() - 90) / 20;
        //every 20 levels above 90, +1
        stats.add(new Pair<>(MapleStat.Temp.STR, chr.getStat().getTotalStr() / offset));
        stats.add(new Pair<>(MapleStat.Temp.DEX, chr.getStat().getTotalDex() / offset));
        stats.add(new Pair<>(MapleStat.Temp.INT, chr.getStat().getTotalInt() / offset));
        stats.add(new Pair<>(MapleStat.Temp.LUK, chr.getStat().getTotalLuk() / offset));
        stats.add(new Pair<>(MapleStat.Temp.WATK, chr.getStat().getTotalWatk() / offset));
        stats.add(new Pair<>(MapleStat.Temp.MATK, chr.getStat().getTotalMagic() / offset));
        return temporaryStats(stats);
    }

    public static final MaplePacket temporaryStats(final List<Pair<MapleStat.Temp, Integer>> stats) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.TEMP_STATS.getValue());
        //str 0x1, dex 0x2, int 0x4, luk 0x8
        //level 0x10 = 255
        //0x100 = 999
        //0x200 = 999
        //0x400 = 120
        //0x800 = 140
        int updateMask = 0;
        for (final Pair<MapleStat.Temp, Integer> statupdate : stats) {
            updateMask |= statupdate.getLeft().getValue();
        }
        List<Pair<MapleStat.Temp, Integer>> mystats = stats;
        if (mystats.size() > 1) {
            Collections.sort(mystats, (o1, o2) -> {
                int val1 = o1.getLeft().getValue();
                int val2 = o2.getLeft().getValue();
                return (val1 < val2 ? -1 : (val1 == val2 ? 0 : 1));
            });
        }
        mplew.writeInt(updateMask);
        Integer value;

        for (final Pair<MapleStat.Temp, Integer> statupdate : mystats) {
            value = statupdate.getLeft().getValue();

            if (value >= 1) {
                if (value <= 0x200) { //level 0x10 - is this really short or some other? (FF 00)
                    mplew.writeShort(statupdate.getRight().shortValue());
                } else {
                    mplew.write(statupdate.getRight().byteValue());
                }
            }
        }
        return mplew.getPacket();
    }

    public static final MaplePacket temporaryStats_Reset() {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.TEMP_STATS_RESET.getValue());
        return mplew.getPacket();
    }

    //its likely that durability items use this
    public static final MaplePacket showHpHealed(final int cid, final int amount) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.SHOW_FOREIGN_EFFECT.getValue());
        mplew.writeInt(cid);
        mplew.write(0x06); //Type
        mplew.writeInt(amount);
        return mplew.getPacket();
    }

    public static final MaplePacket showOwnHpHealed(final int amount) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.SHOW_ITEM_GAIN_INCHAT.getValue());
        mplew.write(0x06); //Type
        mplew.writeInt(amount);
        return mplew.getPacket();
    }

    public static MaplePacket followRequest(int chrid) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.FOLLOW_REQUEST.getValue());
        mplew.writeInt(chrid);
        return mplew.getPacket();
    }

    public static MaplePacket followEffect(int initiator, int replier, Point toMap) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.FOLLOW_EFFECT.getValue());
        mplew.writeInt(initiator);
        mplew.writeInt(replier);
        if (replier == 0) { //cancel
            mplew.write(toMap == null ? 0 : 1); //1 -> x (int) y (int) to change map
            if (toMap != null) {
                mplew.writeInt(toMap.x);
                mplew.writeInt(toMap.y);
            }
        }
        return mplew.getPacket();
    }

    public static MaplePacket getFollowMsg(int opcode) {
        final MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();
        mplew.writeShort(SendPacketOpcode.FOLLOW_MSG.getValue());
        mplew.writeLong(opcode); //5 = canceled request.
        return mplew.getPacket();
    }

    public static MaplePacket moveFollow(Point otherStart, Point myStart, Point otherEnd, List<LifeMovementFragment> moves) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.FOLLOW_MOVE.getValue());
        mplew.writePos(otherStart);
        mplew.writePos(myStart);
        PacketHelper.serializeMovementList(mplew, moves);
        mplew.write(0x11); //what? could relate to movePlayer
        for (int i = 0; i < 8; i++) {
            mplew.write(0x88); //?? sometimes 44
        }
        mplew.write(8); //?
        mplew.writePos(otherEnd);
        mplew.writePos(otherStart);

        return mplew.getPacket();
    }

    public static final MaplePacket getFollowMessage(final String msg) {
        MaplePacketLittleEndianWriter mplew = new MaplePacketLittleEndianWriter();

        mplew.writeShort(SendPacketOpcode.FOLLOW_MESSAGE.getValue());
        mplew.writeShort(0x0B); //?
        mplew.writeMapleAsciiString(msg); //white in gms, but msea just makes it pink.. waste
        return mplew.getPacket();
    }
}
