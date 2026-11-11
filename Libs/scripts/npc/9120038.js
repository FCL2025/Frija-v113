var minlv = 140;

function start() {
	var level = cm.getPlayerStat("LVL");
	var trainMap = cm.getMap(802000311);
    if (cm.canHold(4032202)) {
		if (trainMap.getCharactersSize() == 0) {
		trainMap.resetFully();
		}
        if (!(cm.haveItem(4032202)) && level >= minlv) {
			cm.gainItem(4032202,1);
			cm.warp(802000311,0);
		} else if (cm.haveItem(4032202)) {
			cm.warp(802000311,0);
		} else {
			cm.sendOk("等級需要達到 " + minlv + " 才能進入。")
		}
		cm.sendOk("走囉!");
	} else {
		cm.sendOk("請確認你的其他欄是否還有空位。");
	}
		cm.dispose();
}