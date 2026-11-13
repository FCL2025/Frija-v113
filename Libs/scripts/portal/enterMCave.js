/*
 * 鏡子洞窟 (140030000) 裡的鏡子。
 * - 狂狼勇士二轉，任務 21201 進行中：開始 21202（在這裡收集 30 個 4032311）。
 *   21201 留給它自己的 end() 完成——那是唯一會 changeJob(2110) 的地方。
 * - 狂狼勇士三轉，任務 21302 進行中、身上還沒有紅珠玉 (4032312)：進入 aran3rd2，
 *   回到過去打倒盜賊鸚鵡拿紅珠玉。
 */
function enter(pi) {
    if (pi.isQuestActive(21302) && !pi.haveItem(4032312)) {
        var em = pi.getEventManager("aran3rd2");
        if (em == null) {
            pi.playerMessage(5, "找不到 aran3rd2 事件，請聯絡管理員。");
        } else if (em.getProperty("started") == "1") {
            pi.playerMessage(5, "鏡子裡正映著別人的過去，請稍後再試。");
        } else {
            em.startInstance(pi.getPlayer());
        }
        return true;
    }
    if (pi.isQuestActive(21201) && pi.getQuestStatus(21202) == 0) {
        pi.forceStartQuest(21202);
    }
    pi.warp(108000700, 0);
    return true;
}
