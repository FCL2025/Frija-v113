/*
	Machine Apparatus - Origin of Clocktower(220080001)
*/

var status = -1;

function start() {
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == 1) {
        status++;
    } else {
        status--;
    }
    if (status == 0) {
        cm.sendYesNo("嗶...嗶...你可以透過我逃到比較安全的地方。嗶...嗶...你想要離開這裡嗎？");
    } else if (status == 1) {
        cm.warp(220080000);
        if (cm.getPlayerCount(220080001) == 0) {
            cm.getMap(220080000).resetReactors();
        }
        cm.dispose();
    } else {
        cm.dispose();
    }
}
