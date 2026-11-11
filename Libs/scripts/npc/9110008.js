var status = 0;
var cost = 3000;
function start() {
    cm.sendYesNo("要回到西門町嗎？單程收費 3000 楓幣。");
}

function action(mode, type, selection) {
    if (mode != 1) {
        if (mode == 0)
        cm.sendOk("好的，需要時再來找我吧。");
        cm.dispose();
        return;
    }
    status++;
    if (status == 1) {
		if(cm.getMeso() < cost) {
		cm.sendOk("你的楓幣不足 3000，無法回到西門町。");
		cm.dispose();
		} else {
		cm.gainMeso(-cost);
		cm.warp(740000000, 0);
        cm.dispose();
    }
}
}