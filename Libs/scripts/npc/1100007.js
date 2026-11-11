/* Dawnveil
    To Ereve
	Kiriru
    Made by Daenerys
*/
function start() {
    cm.sendYesNo("你想要去耶雷佛必須支付#b 500 楓幣#k，要出發嗎?");
}

function action(mode, type, selection) {
    if (mode == 0) {
        cm.sendOk("等你考慮好再來找我吧!");
        cm.dispose();
    } else {
        if (cm.getPlayer().getMeso() >= 500) {
            cm.gainMeso(-500);
            cm.warp(130000210);
        } else {
            cm.sendOk("你的楓幣好像不夠喔。");
        }
        cm.dispose();
    }
}
