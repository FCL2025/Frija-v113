/*
	NPC Name: 		Athena Pierce
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
	    cm.sendNext("如果你想體驗成為弓箭手的感覺，隨時再來找我吧。");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	cm.sendNext("弓箭手擁有靈敏與力量，負責遠距離攻擊，支援在戰鬥前線作戰的夥伴，也很擅長利用地形作戰。");
    } else if (status == 1) {
	cm.sendYesNo("你想體驗看看成為弓箭手的感覺嗎？");
    } else if (status == 2) {
	cm.movieClipIntroUI(true);
	cm.warp(1020300, 0); // Effect/Direction3.img/archer/Scene00
	cm.dispose();
    }
}