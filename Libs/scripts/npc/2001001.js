/*
 *  Branch Bucket Snowman - Happy Ville NPC
 */

function start() {
    cm.sendSimple(" и琌 #p2001001# 璶┬丁? PS挡︸︽ \n\r #b#L0#る此狶1#l \n\r #L1#る此狶2#l \n\r #L2#る此狶3#l \n\r #L3#る此狶4#l \n\r #L4#る此狶5#l");
}

function action(mode, type, selection) {
    if (mode == 1) {
	cm.warp(209000001 + selection, 0);
    }
    cm.dispose();
}
