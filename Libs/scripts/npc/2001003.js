/*
 *  Strawhat Snowman - Happy Ville NPC
 */

function start() {
    cm.sendSimple(" и琌 #p2001003# 璶┬丁? PS挡︸︽ \n\r #b#L0#る此狶11#l \n\r #L1#る此狶12#l \n\r #L2#る此狶13#l \n\r #L3#る此狶14#l \n\r #L4#る此狶15#l");
}

function action(mode, type, selection) {
    if (mode == 1) {
	cm.warp(209000011 + selection, 0);
    }
    cm.dispose();
}
