var status = -1;

function action(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	if (status == 0) {
	    cm.sendOk("好吧，等你有需要再來找我。");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (cm.getMapId() == 680000300 && cm.getQuestRecord(160002).getCustomData() != null) {
	var dat = parseInt(cm.getQuestRecord(160002).getCustomData());
	if (dat > 30) {
	    if (status == 0) {
	    	cm.sendYesNo("我可以帶你們前往#b結婚派對#k的獎勵關卡，準備好出發了嗎？");
	    } else {
		cm.warpMap(680000401,0);
		cm.dispose();
	    }
	    return;
	}
    }
    if (status == 0) {
	cm.sendYesNo("你確定要離開婚禮會場嗎？我會帶你到出口，離開後就不能再回來了喔。");
    } else if (status == 1) {
	cm.warp(680000500, 0);
	cm.dispose();
    }
}