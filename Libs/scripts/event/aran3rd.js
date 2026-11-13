/*
 * 狂狼勇士三轉，任務 21301「趕走盜賊鸚鵡」。
 * 瑞恩島 雪平原３ (140020200) 的 enterPort 傳送點把接了這個任務的玩家送進自己的
 * 一份 108010700 企鵝港口異常，打倒一次盜賊鸚鵡 (9001013)。WZ 裡這張地圖的怪
 * mobTime 是 -1，伺服器永遠不會自己生成，所以由這裡生成在 WZ 的位置。
 * 每個玩家一份地圖，可同時多人；10 分鐘沒打完就送回雪平原３。
 * 要在 tms.Events 裡列出才會載入。
 */

var instanceId = 0;

function init() {
}

function setup() {
    instanceId++;
    var eim = em.newInstance("aran3rd" + instanceId);
    var map = eim.createInstanceMap(108010700);
    map.spawnMonsterOnGroundBelow(em.getMonster(9001013), new java.awt.Point(2617, -2));
    eim.startEventTimer(600000); // 10 min
    return eim;
}

function playerEntry(eim, player) {
    var map = eim.getMapInstance(0);
    player.changeMap(map, map.getPortal(0));
}

function changedMap(eim, player, mapid) {
    if (mapid != 108010700) {
        eim.unregisterPlayer(player);
        eim.disposeIfPlayerBelow(0, 0);
    }
}

function scheduledTimeout(eim) {
    eim.disposeIfPlayerBelow(100, 140020200);
}

function playerExit(eim, player) {
    eim.unregisterPlayer(player);
    eim.disposeIfPlayerBelow(0, 0);
}

function playerDisconnected(eim, player) {
    return 0;
}

function playerRevive(eim, player) {
    return false;
}

function monsterValue(eim, mobId) {
    return 1;
}

function allMonstersDead(eim) {}

function playerDead(eim, player) {}

function leftParty(eim, player) {}

function disbandParty(eim) {}

function clearPQ(eim) {}

function cancelSchedule() {}
