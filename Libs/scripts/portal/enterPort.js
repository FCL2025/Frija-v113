/*
 * 瑞恩島 雪平原３ (140020200) → 企鵝港口 (140020300)。
 * 接了狂狼勇士三轉任務 21301「趕走盜賊鸚鵡」的玩家改送進 aran3rd 的單人副本
 * (108010700 企鵝港口異常)，在那裡打倒盜賊鸚鵡。
 */
function enter(pi) {
    if (pi.isQuestActive(21301)) {
        var em = pi.getEventManager("aran3rd");
        if (em != null) {
            em.startInstance(pi.getPlayer());
            return true;
        }
        pi.playerMessage(5, "找不到 aran3rd 事件，請聯絡管理員。");
    }
    pi.warp(140020300, 1);
    return true;
}
