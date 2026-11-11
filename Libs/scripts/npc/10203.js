/*
	NPC Name: 		Dark Lord
	Map(s): 		Maple Road : Spilt road of choice
	Description: 		Job tutorial, movie clip
*/

var status = -1;

function start() {
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	if (status == 1) {
	    cm.sendNext("如果你想體驗成為盜賊的感覺，隨時再來找我吧。");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	cm.sendNext("盜賊完美結合了幸運、敏捷與力量，擅長對毫無防備的敵人發動奇襲。高迴避率與速度讓盜賊能從各種角度攻擊敵人。");
    } else if (status == 1) {
	cm.sendYesNo("你想體驗看看成為盜賊的感覺嗎？");
    } else if (status == 2) {
	cm.movieClipIntroUI(true);
	cm.warp(1020400, 0); // Effect/Direction3.img/rouge/Scene00
	cm.dispose();
    }
}