/*
 * 狂狼勇士三轉，任務 21302「製作紅珠玉」：拿到紅珠玉 (4032312) 交給瑪哈就能三轉。
 * 鏡子洞窟 (140030000) 的 enterMCave 傳送點把接了這個任務、身上還沒有紅珠玉的玩家
 * 送進「狂狼勇士的過去」：108010701 像刀刃的絕壁，從那裡的傳送點走到 108010702
 * 盜賊烏鴉領地，打倒過去的盜賊鸚鵡 (9001013) 就拿到紅珠玉。WZ 沒有這隻怪的掉寶，
 * 牠的 mobTime 也是 -1，所以由這裡生成並在牠倒下時發紅珠玉。
 *
 * 兩張地圖之間是 WZ 的普通傳送點，只會通往頻道上共用的那一份地圖，不能各自複製，
 * 所以每個頻道同時只能一個人（started 鎖，與 Mechanical 相同）；20 分鐘沒打完就
 * 送回鏡子洞窟。要在 tms.Events 裡列出才會載入。
 */

function init() {
    em.setProperty("started", "0");
}

function setup() {
    em.setProperty("started", "1");
    var eim = em.newInstance("aran3rd2");
    eim.setInstanceMap(108010701);
    var lair = eim.setInstanceMap(108010702);
    lair.killAllMonsters(false); // 上一次逾時留下的
    var crow = em.getMonster(9001013);
    eim.registerMonster(crow);
    lair.spawnMonsterOnGroundBelow(crow, new java.awt.Point(-142, 443));
    eim.startEventTimer(1200000); // 20 min
    return eim;
}

function playerEntry(eim, player) {
    var map = eim.getMapInstance(0);
    player.changeMap(map, map.getPortal(0));
}

function changedMap(eim, player, mapid) {
    if (mapid != 108010701 && mapid != 108010702) {
        eim.unregisterPlayer(player);
        if (eim.disposeIfPlayerBelow(0, 0)) {
            em.setProperty("started", "0");
        }
    }
}

function allMonstersDead(eim) {
    var players = eim.getPlayers();
    for (var i = 0; i < players.size(); i++) {
        var player = players.get(i);
        var c = player.getClient();
        if (player.getQuestStatus(21302) != 1 || player.haveItem(4032312)) {
            continue;
        }
        if (Packages.server.MapleInventoryManipulator.checkSpace(c, 4032312, 1, "")) {
            Packages.server.MapleInventoryManipulator.addById(c, 4032312, 1);
            c.send(Packages.tools.packet.InventoryPacket.getShowItemGain(4032312, 1, true));
        } else {
            player.dropMessage(5, "其他欄已滿，拿不到紅珠玉。請空出一格後再進入鏡子洞窟。");
        }
    }
}

function scheduledTimeout(eim) {
    eim.disposeIfPlayerBelow(100, 140030000);
    em.setProperty("started", "0");
}

function playerExit(eim, player) {
    eim.unregisterPlayer(player);
    if (eim.disposeIfPlayerBelow(0, 0)) {
        em.setProperty("started", "0");
    }
}

function playerDisconnected(eim, player) {
    // 一次只有一個人：他一斷線實例就因為沒人而銷毀（回傳 0），鎖要在這裡放開。
    em.setProperty("started", "0");
    return 0;
}

function playerRevive(eim, player) {
    return false;
}

function monsterValue(eim, mobId) {
    return 1;
}

function playerDead(eim, player) {}

function leftParty(eim, player) {}

function disbandParty(eim) {}

function clearPQ(eim) {}

function cancelSchedule() {}
