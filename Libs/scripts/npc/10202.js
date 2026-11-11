/*
	NPC Name: 		Dances with Balrog
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
	    cm.sendNext("如果你想體驗成為劍士的感覺，隨時再來找我吧。");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	cm.sendNext("劍士擁有強大的力量與耐力，在近身戰鬥中最為耀眼。普通攻擊本身就很強，再加上多樣的技能，非常適合爆發性的攻擊。");
    } else if (status == 1) {
	cm.sendYesNo("你想體驗看看成為劍士的感覺嗎？");
    } else if (status == 2) {
	cm.movieClipIntroUI(true);
	cm.warp(1020100, 0); // Effect/Direction3.img/swordman/Scene00
	cm.dispose();
    }
}