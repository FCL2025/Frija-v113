/*
 * 轉蛋屋 (749050400) 的皮奧 9330112，五台職業轉蛋機之一（9330112–9330116）。
 * WZ 和資料庫都沒有這幾台的獎品清單；在決定內容之前先顯示維修中，不收轉蛋券。
 * 以前沒有腳本，點了只會落到 npc/notcoded.js。
 */
function start() {
    cm.sendOk("這台轉蛋機正在維修中，暫時無法使用。\r\n請先使用旁邊的#b經驗#k、#b卷軸#k或#b潮流#k轉蛋機。");
    cm.dispose();
}

function action(mode, type, selection) {
    cm.dispose();
}
