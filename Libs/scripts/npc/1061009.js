/* Door of Dimension
	Enter 3rd job event
	合併：沿用 L 的 3rdjob 事件（個人副本），入口條件改為等級 70 以上的二轉職業，
	不再要求自訂任務 100101 進行中（道具式三轉流程不會開始該任務）。
*/

function start() {
    var job = cm.getJob();
    var secondJob = job >= 100 && job < 600 && job % 100 != 0 && job % 10 == 0;
    if (cm.getPlayer().getLevel() < 70 || !secondJob) {
	cm.sendOk("等級好像不正確。");
    } else if (cm.haveItem(4031059)) {
	cm.sendOk("你貌似已經有了#t4031059#。");
    } else if (!cm.canHold(4031059)) {
	cm.sendOk("請確認是否有足夠的空間。");
    } else {
	var em = cm.getEventManager("3rdjob");
	if (em == null) {
	    cm.sendOk("三轉任務好像有些問題");
	} else {
	    em.newInstance(cm.getName()).registerPlayer(cm.getChar());
	}
    }
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
