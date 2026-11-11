/**
	Pison - Florina Beach(110000000)
**/
var status = -1;
var returnmap = -1;

function action(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	cm.sendNext("你不回去 #m"+returnmap+"# 嗎？在這裡好好休息也不錯。\r\n看看我在這邊還不是過得好好的，想回去的時候再來找我吧!");
	cm.safeDispose();
	return;
    }
    if (status == 0) {
	returnmap = cm.getSavedLocation("FLORINA");
	if (returnmap < 0) {
		returnmap = 104000000;
	}
	cm.sendNext("所以你想離開 #b#m110000000##k? 如果你想，我可以幫助你回到 #b#m"+returnmap+"##k。");
    } else if (status == 1) {
	cm.sendYesNo("你確定你想回到 #b#m"+returnmap+"##k? 好吧，我們得走快點了。");
    } else if (status == 2) {
	cm.warp(returnmap);
	cm.clearSavedLocation("FLORINA");
	cm.dispose();
    }
}
