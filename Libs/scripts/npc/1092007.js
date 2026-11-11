function start() {
	if (cm.isQuestActive(2175)) {
	cm.sendOk("你準備好對付黑魔法師的手下了嗎?? 我將把你傳送過去...");
    } else {
    cm.sendOk("黑魔法師和他的手下，與卡伊琳和鯨魚號的船員們，在其中一方消失之前，這場追逐是不會結束的。");
    cm.dispose();
}
}

function action(mode, type, selection) {
    cm.warp(912000000,0);
    cm.dispose();
}
