/*
 * 任務 2126「波達的小箱子」：天空之城的波達 (2012019) 脖子上的小箱子 (4031624)
 * 交給納希沙漠的艾里斯卡 (2101002)。
 *
 * Check.img 給完成那一側 endscript，所以接任務時 MapleQuest.start 先照 Act.img
 * 給小箱子，接著就呼叫這裡的 end()——那時任務還是 0（未開始），這裡要把它開始。
 * 以前沒有這個檔：任務永遠停在 0，每接一次就多一個小箱子，也永遠交不出去。
 * Act.img 完成那一側是空的，所以交出箱子只是收回箱子、完成任務。
 */
var status = -1;

function start(mode, type, selection) {
    qm.dispose();
}

function end(mode, type, selection) {
    if (qm.getQuestStatus(2126) == 0) {
        qm.forceStartQuest();
        qm.dispose();
        return;
    }
    if (mode != 1) {
        qm.dispose();
        return;
    }
    status++;
    if (status == 0) {
        if (!qm.haveItem(4031624, 1)) {
            qm.sendNext("給我的箱子？……你身上好像沒有#b#t4031624##k喔。");
            qm.dispose();
            return;
        }
        qm.sendNext("這是……掛在#p2012019#脖子上的#b#t4031624##k！上面寫著給我的。謝謝你特地從#m200000000#帶過來。");
    } else {
        qm.gainItem(4031624, -1);
        qm.forceCompleteQuest();
        qm.dispose();
    }
}
