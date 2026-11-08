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
package client;

import client.inventory.MapleInventoryType;
import constants.GameConstants;
import java.util.concurrent.ScheduledFuture;
import server.MapleInventoryManipulator;
import server.MapleStatEffect;
import server.RandomRewards;
import server.Randomizer;
import server.Timer;
import server.maps.MapleMap;
import tools.packet.CharacterPacket;
import tools.packet.MapPacket;
import tools.packet.UIPacket;

/**
 * 一個角色身上的計時器（E4 第三刀，從 {@link MapleCharacter} 拆出）。
 *
 * <p>七個 {@link ScheduledFuture}：暗之守護者的治療與 buff、狂暴、龍之魂、小精靈墜飾、
 * 地圖時限、釣魚，以及它們的啟動與取消。方法內容逐字搬移，只把讀角色欄位的地方換成
 * {@code chr} 的 getter（{@code stats} → {@code chr.getStat()}、{@code client} →
 * {@code chr.getClient()}、{@code map} → {@code chr.getMap()}），lambda 仍然在觸發當下才去讀，
 * 時機與搬家前相同。每個 buff 自己的到期排程（{@link MapleBuffStatValueHolder#schedule}）不在這裡，
 * 它屬於 {@link PlayerBuffs} 的那張表。
 *
 * <p>{@link #cancelAll()} 就是以前的 {@code cancelAllTimers()}。搬家之外唯一的行為變更是
 * {@code MapleCharacter.changeRemoval} 現在也會呼叫它，理由寫在那裡。
 */
public final class PlayerSchedules {

    private final MapleCharacter chr;
    private ScheduledFuture<?> beholderHealingSchedule, beholderBuffSchedule, BerserkSchedule,
            dragonBloodSchedule, fairySchedule, mapTimeLimitTask, fishing;

    PlayerSchedules(final MapleCharacter chr) {
        this.chr = chr;
    }

    /** 暗之守護者（1321007）被收回時，由 MapleCharacter.buffStatDeregistered 呼叫。內容與搬家前逐字相同。 */
    void cancelBeholderSchedules() {
        if (beholderHealingSchedule != null) {
            beholderHealingSchedule.cancel(false);
            beholderHealingSchedule = null;
        }
        if (beholderBuffSchedule != null) {
            beholderBuffSchedule.cancel(false);
            beholderBuffSchedule = null;
        }
    }

    /** 龍之魂這個 stat 被移除時，由 MapleCharacter.buffStatDeregistered 呼叫。內容與搬家前逐字相同。 */
    void cancelDragonBloodSchedule() {
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
            dragonBloodSchedule = null;
        }
    }

    void prepareDragonBlood(final MapleStatEffect bloodEffect) {
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
        }
        dragonBloodSchedule = Timer.BUFF.register(() -> {
            if (chr.getStat().getHp() - bloodEffect.getX() > 1) {
                chr.cancelBuffStats(MapleBuffStat.DRAGONBLOOD);
            } else {
                chr.addHP(-bloodEffect.getX());
                chr.getClient().send(CharacterPacket.showOwnBuffEffect(bloodEffect.getSourceId(), 5));
                chr.getMap().broadcastMessage(chr, CharacterPacket.showBuffeffect(chr.getId(), bloodEffect.getSourceId(), 5), false);
            }
        }, 4000, 4000);
    }

    void startMapTimeLimitTask(int time, final MapleMap to) {
        chr.getClient().send(MapPacket.getClock(time));

        time *= 1000;
        mapTimeLimitTask = Timer.MAP.register(() -> {
            chr.changeMap(to, to.getPortal(0));
        }, time, time);
    }

    void startFishingTask(final boolean VIP) {
        final int time = GameConstants.getFishingTime(VIP, chr.isGM());
        cancelFishingTask();

        fishing = Timer.ETC.register(() -> {
            final boolean expMulti = chr.haveItem(2300001, 1, false, true);
            if (!expMulti && !chr.haveItem(2300000, 1, false, true)) {
                cancelFishingTask();
                return;
            }
            MapleInventoryManipulator.removeById(chr.getClient(), MapleInventoryType.USE, expMulti ? 2300001 : 2300000, 1, false, false);

            final int randval = RandomRewards.getInstance().getFishingReward();

            switch (randval) {
                case 0: // Meso
                    final int money = Randomizer.rand(expMulti ? 15 : 10, expMulti ? 7500 : 5000);
                    chr.gainMeso(money, true);
                    break;
                case 1: // EXP
                    final int experi = Randomizer.nextInt(Math.abs(GameConstants.getExpNeededForLevel(chr.getLevel()) / 400) + 1);
                    chr.gainExp(expMulti ? (experi * 3 / 2) : experi, true, false, true);
                    break;
                default:
                    MapleInventoryManipulator.addById(chr.getClient(), randval, (short) 1);
                    break;
            }
            chr.getMap().broadcastMessage(UIPacket.fishingCaught(chr.getId()));
        }, time, time);
    }

    void cancelMapTimeLimitTask() {
        if (mapTimeLimitTask != null) {
            mapTimeLimitTask.cancel(false);
        }
    }

    void cancelFishingTask() {
        if (fishing != null) {
            fishing.cancel(false);
        }
    }

    void cancelAll() {
        cancelFishingTask();
        cancelMapTimeLimitTask();

        if (fairySchedule != null) {
            fairySchedule.cancel(false);
        }
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
            dragonBloodSchedule = null;
        }
        if (BerserkSchedule != null) {
            BerserkSchedule.cancel(false);
            BerserkSchedule = null;
        }
        if (beholderHealingSchedule != null) {
            beholderHealingSchedule.cancel(false);
            beholderHealingSchedule = null;
        }
        if (beholderBuffSchedule != null) {
            beholderBuffSchedule.cancel(false);
            beholderBuffSchedule = null;
        }
        if (dragonBloodSchedule != null) {
            dragonBloodSchedule.cancel(false);
            dragonBloodSchedule = null;
        }
    }

    void checkBerserk() {
        if (BerserkSchedule != null) {
            BerserkSchedule.cancel(false);
            BerserkSchedule = null;
        }

        final ISkill BerserkX = SkillFactory.getSkill(1320006);
        final int skilllevel = chr.getSkillLevel(BerserkX);
        if (skilllevel >= 1) {
            final MapleStatEffect ampStat = BerserkX.getEffect(skilllevel);
            chr.getStat().Berserk = chr.getStat().getHp() * 100 / chr.getStat().getMaxHp() <= ampStat.getX();
            chr.getClient().send(CharacterPacket.showOwnBuffEffect(1320006, 1, (byte) (chr.getStat().Berserk ? 1 : 0)));
            chr.getMap().broadcastMessage(chr, CharacterPacket.showBuffeffect(chr.getId(), 1320006, 1, (byte) (chr.getStat().Berserk ? 1 : 0)), false);

            BerserkSchedule = Timer.BUFF.schedule(() -> {
                checkBerserk();
            }, 10000);
        }
    }

    void prepareBeholderEffect() {
        if (beholderHealingSchedule != null) {
            beholderHealingSchedule.cancel(false);
        }
        if (beholderBuffSchedule != null) {
            beholderBuffSchedule.cancel(false);
        }
        ISkill bHealing = SkillFactory.getSkill(1320008);
        final int bHealingLvl = chr.getSkillLevel(bHealing);
        final int berserkLvl = chr.getSkillLevel(SkillFactory.getSkill(1320006));

        if (bHealingLvl > 0) {
            final MapleStatEffect healEffect = bHealing.getEffect(bHealingLvl);
            int healInterval = healEffect.getX() * 15000;
            beholderHealingSchedule = Timer.BUFF.register(() -> {
                int remhppercentage = (int) Math.ceil((chr.getStat().getHp() * 100.0) / chr.getStat().getMaxHp());
                if (berserkLvl == 0 || remhppercentage >= berserkLvl + 10) {
                    chr.addHP(healEffect.getHp());
                }
                chr.getClient().send(CharacterPacket.showOwnBuffEffect(1321007, 2));
                chr.getMap().broadcastMessage(MapPacket.summonSkill(chr.getId(), 1321007, 5));
                chr.getMap().broadcastMessage(chr, CharacterPacket.showBuffeffect(chr.getId(), 1321007, 2), false);
            }, healInterval, healInterval);
        }
        ISkill bBuff = SkillFactory.getSkill(1320009);
        final int bBuffLvl = chr.getSkillLevel(bBuff);
        if (bBuffLvl > 0) {
            final MapleStatEffect buffEffect = bBuff.getEffect(bBuffLvl);
            int buffInterval = buffEffect.getX() * 10000;
            beholderBuffSchedule = Timer.BUFF.register(() -> {
                buffEffect.applyTo(chr);
                chr.getClient().send(CharacterPacket.showOwnBuffEffect(1321007, 2));
                chr.getMap().broadcastMessage(MapPacket.summonSkill(chr.getId(), 1321007, Randomizer.nextInt(3) + 6));
                chr.getMap().broadcastMessage(chr, CharacterPacket.showBuffeffect(chr.getId(), 1321007, 2), false);
            }, buffInterval, buffInterval);
        }
    }

    void startFairySchedule(boolean exp) {
        startFairySchedule(exp, false);
    }

    void startFairySchedule(boolean exp, boolean equipped) {
        cancelFairySchedule(exp);
        if (chr.getFairyExp() < 30 && chr.getStat().equippedFairy) {
            if (equipped) {
                chr.dropMessage(5, "The Fairy Pendant's experience points will increase to " + (chr.getFairyExp() + 10) + "% after one hour.");
            }
            fairySchedule = Timer.ETC.schedule(() -> {
                if (chr.getFairyExp() < 30 && chr.getStat().equippedFairy) {
                    chr.setFairyExp((byte) (chr.getFairyExp() + 10));
                    chr.dropMessage(5, "The Fairy Pendant's EXP was boosted to " + chr.getFairyExp() + "%.");
                    startFairySchedule(false, true);
                } else {
                    cancelFairySchedule(!chr.getStat().equippedFairy);
                }
            }, 60 * 60 * 1000);
        } else {
            cancelFairySchedule(!chr.getStat().equippedFairy);
        }
    }

    void cancelFairySchedule(boolean exp) {
        if (fairySchedule != null) {
            fairySchedule.cancel(false);
            fairySchedule = null;
        }
        if (exp) {
            chr.setFairyExp((byte) 10);
        }
    }
}
