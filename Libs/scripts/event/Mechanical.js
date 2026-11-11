/*
 * 任務 3239「機械零件的下落」的單人副本（NPC 2040003 曾助教開啟）。
 * 玩家進入隱藏地圖 922000000 玩具工廠<第4區>，20 分鐘內打破木桶（反應器 2202001）
 * 收集 10 個機械零件（4031092），交給地圖內的曾助教領獎。每個頻道同時只能一人。
 * 取自 scripts/ 腳本包，2026-11 加入；要在 tms.Events 裡列出才會載入。
 */

function init() {
    em.setProperty("started", "0");
}

function setup(character) {
    em.setProperty("started", "1");
    var eim = em.newInstance("Mechanical");

    var map = eim.createInstanceMap(922000000);
    eim.startEventTimer(1200000); // 20 mins
    return eim;
}

function playerEntry(eim, player) {
    var map = eim.getMapInstance(0);
    player.changeMap(map, map.getPortal(0));
}

function playerRevive(eim, player) {
    return false;
}

function scheduledTimeout(eim) {
    end(eim);
}

function changedMap(eim, player, mapid) {
    if (mapid != 922000000) {
        eim.unregisterPlayer(player);

        if (eim.disposeIfPlayerBelow(0, 0)) {
            em.setProperty("started", "0");
        }
    }
}

function playerDisconnected(eim, player) {
    // 這個副本一次只有一個人：他一斷線，實例就會因為沒人而銷毀（回傳 0），
    // 所以這裡要把 started 放回 0，否則這個頻道之後再也沒人進得去。
    em.setProperty("started", "0");
    return 0;
}

function monsterValue(eim, mobId) {
    return 1;
}

function playerExit(eim, player) {
    eim.unregisterPlayer(player);

    if (eim.disposeIfPlayerBelow(0, 0)) {
        em.setProperty("started", "0");
    }
}

function end(eim) {
    eim.disposeIfPlayerBelow(100, 220020000);
    em.setProperty("started", "0");
}

function clearPQ(eim) {}

function allMonstersDead(eim) {}

function leftParty(eim, player) {}

function disbandParty(eim) {}

function playerDead(eim, player) {}

function cancelSchedule() {}