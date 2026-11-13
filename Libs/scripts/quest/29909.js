/*
 * 騎士團長勳章 (1142069)。Check.img 只給這個任務 startscript（等級 120、職業
 * x112/x212/x312/x412/x512，normalAutoStart），完成那一側沒有 endscript、Act.img
 * 也是空的，所以 end() 永遠不會被呼叫——勳章原本寫在 end() 裡，等於沒人拿得到。
 * 改在 start() 一次發勳章並完成任務，條件與 WZ 相同。
 */
function isKnightCommander() {
    var job = qm.getPlayer().getJob();
    return job > 1000 && job < 2000 && job % 10 == 2;
}

function start(mode, type, selection) {
    if (!isKnightCommander() || qm.getPlayer().getLevel() < 120) {
        qm.dispose();
        return;
    }
    if (qm.haveItem(1142069, 1)) {
        qm.forceStartQuest();
        qm.forceCompleteQuest();
    } else if (!qm.canHold(1142069, 1)) {
        qm.sendNext("請先空出一格裝備欄，再來領取#b#t1142069##k。");
    } else {
        qm.gainItem(1142069, 1);
        qm.forceStartQuest();
        qm.forceCompleteQuest();
        qm.sendNext("恭喜你成為騎士團長，這是#b#t1142069##k。");
    }
    qm.dispose();
}

function end(mode, type, selection) {
    start(mode, type, selection);
}
