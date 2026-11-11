function action(mode, type, selection) {
    if (cm.getQuestStatus(6410) == 1) {
        cm.forceStartQuest(6411, "p2");
        cm.sendNext("謝謝你！");
    } else if (cm.getMapId() == 925010400) {
        cm.warp(120000104, 0);
    } else {
        cm.sendNext("拜託你，請把怪物們解放出來吧！");
    }
    cm.dispose();
}
