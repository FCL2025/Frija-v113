/* 蛋糕禮物盒 */

var status = -1;
var banMap = Array(109080000, 109080010, 109040000, 109030001, 109060000, 109010000);

function start() {
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode === 1) {
        status++;
    } else {
        cm.dispose();
        return;
    }

    if (status === 0) {
        for (var i = 0; i < banMap.length; i++) {
            if (cm.getPlayer().getMapId() == banMap[i]) {
                cm.sendOk("活動進行中，不能在這裡找我喔！");
                cm.dispose();
                return;
            }
        }
        cm.sendSimple("#b歡迎!!\r\n" +
            "#L2#我要打開藍色小箱子#l\r\n" +
            "#L12#我要打恰吉#l\r\n" +
            "#L17#我要買狼的生命水，1瓶1億，解狂狼坐騎需要的#l");
    } else if (status === 1) {
        if (selection === 2) {
            if (!cm.haveItem(4031307, 1)) {
                cm.sendOk("#b檢查一下背包有沒有藍色禮物盒哦");
            } else if (!cm.canHold(2020020, 100)) {
                cm.sendOk("#d請確認背包欄位是否足夠！");
            } else {
                cm.gainItem(4031307, -1);
                cm.gainItem(2020020, 100);
                cm.sendOk("#b蛋糕不要吃太多~旅遊愉快~");
            }
        } else if (selection === 12) {
            if (cm.getPlayer().getMapId() == 749050400) {
                cm.playerMessage("轉蛋屋不能去打恰吉T.T");
            } else {
                cm.warp(229010000);
            }
        } else if (selection === 17) {
            if (cm.getPlayer().getMeso() >= 100000000 && cm.canHold(4032334, 1)) {
                cm.gainMeso(-100000000);
                cm.gainItem(4032334, 1);
                cm.sendOk("感謝購買!");
            } else {
                cm.sendOk("#d請確認楓幣是否足夠，或者背包欄位滿了！");
            }
        }
        cm.dispose();
    } else {
        cm.dispose();
    }
}
