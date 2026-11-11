/*
	NPC Name: 		Grendel the Really Old
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
	    cm.sendNext("如果你想體驗成為法師的感覺，隨時再來找我吧。");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	cm.sendNext("法師擁有華麗的屬性魔法，以及能輔助整個隊伍的魔法。二轉之後，屬性魔法對相剋屬性的敵人能造成大量傷害。");
    } else if (status == 1) {
	cm.sendYesNo("你想體驗看看成為法師的感覺嗎？");
    } else if (status == 2) {
	cm.movieClipIntroUI(true);
	cm.warp(1020200, 0); // Effect/Direction3.img/magician/Scene00
	cm.dispose();
    }
}