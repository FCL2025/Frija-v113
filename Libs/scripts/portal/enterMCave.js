function enter(pi) {
    // Aran 2nd job: entering the mirror cave while 21201 is active starts 21202
    // (collect 30 x 4032311 here). Completing 21201 is left to its own end()
    // script, the only place that calls changeJob(2110).
    if (pi.isQuestActive(21201) && pi.getQuestStatus(21202) == 0) {
        pi.forceStartQuest(21202);
    }
    pi.warp(108000700, 0);
    return true;
}
