/* Dawnveil
    To Rien
	Puro
    Made by Daenerys
*/
function start() {
    cm.sendYesNo("你想要去瑞恩島必須支付#b 80 楓幣#k，要出發嗎?");
}

function action(mode, type, selection) {
    if (mode == 0) {
	cm.sendOk("等你考慮好再來找我吧!");
	cm.dispose();
    } else {
	if (cm.getPlayer().getMeso() >= 80) {
	    cm.gainMeso(-80);
	    cm.warp(140020300, 0);
	} else {
	    cm.sendOk("你的楓幣好像不夠喔。");
	}
	cm.dispose();
    }
}
