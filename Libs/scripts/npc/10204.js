/*
	NPC Name: 		Kyrin
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
	    cm.sendNext("如果你想體驗成為海盜的感覺，隨時再來找我吧。");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	cm.sendNext("海盜擁有出色的敏捷與力量，用槍進行遠距離攻擊，也能以力量進行近身戰鬥。槍手使用屬性子彈增加傷害，打手則能變身發揮最大威力。");
    } else if (status == 1) {
	cm.sendYesNo("你想體驗看看成為海盜的感覺嗎？");
    } else if (status == 2) {
	cm.movieClipIntroUI(true);
	cm.warp(1020500, 0); // Effect/Direction3.img/pirate/Scene00
	cm.dispose();
    }
}