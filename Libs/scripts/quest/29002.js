/*
 * 人氣王勳章 (1142003). Act.img is empty for this quest, so the script is the
 * whole quest. Checks absolute fame >= 1000, like the other title quests here
 * (29001, 29300) it does not enforce QuestInfo's 30-day window.
 */
function start(mode, type, selection) {
	qm.forceStartQuest();
	qm.dispose();
}

function end(mode, type, selection) {
	if (qm.getPlayer().getFame() < 1000) {
		qm.sendNext("名聲不足1000。");
	} else if (qm.haveItem(1142003, 1)) {
		qm.sendNext("您已經擁有#b#t1142003##k了。");
	} else if (!qm.canHold(1142003, 1)) {
		qm.sendNext("請先空出一格裝備欄。");
	} else {
		qm.gainItem(1142003, 1);
		qm.forceCompleteQuest();
		qm.sendNext("恭喜您完成人氣王任務。");
		qm.worldMessage("『稱號挑戰』：恭喜 " + qm.getChar().getName() + " 成功挑戰人氣王，看來不是個邊緣人！");
	}
	qm.dispose();
}
