var status = -1;

function start(mode, type, selection) {
    qm.sendNext("ÁÂÁÂ§A¡C");
    qm.forceStartQuest();
    qm.forceCompleteQuest();
    qm.dispose();
}

function end(mode, type, selection) {
    qm.forceStartQuest();
    qm.forceCompleteQuest();
    qm.dispose();
}