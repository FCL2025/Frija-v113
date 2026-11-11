/*
 *  Metal Bucket Snowman - Happy Ville NPC
 */

function start() {
    cm.sendSimple(" и琌 #p2001002# 璶┬丁? PS挡︸︽ \n\r #b#L0#る此狶6#l \n\r #L1#る此狶7#l \n\r #L2#る此狶8#l \n\r #L3#る此狶9#l \n\r #L4#る此狶10#l");
}

function action(mode, type, selection) {
    if (mode == 1) {
	cm.warp(209000006 + selection, 0);
    }
    cm.dispose();
}
