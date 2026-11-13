/*
 * Check.img gives this quest a startscript but no endscript: the script only
 * has to start the quest. Turning it in is the normal Act.img completion
 * (materials for the cape), and Check.img's interval and "does not own the
 * cape yet" requirement keep it from being farmed.
 */
function start(mode, type, selection) {
	qm.forceStartQuest();
	qm.dispose();
}

function end(mode, type, selection) {
	qm.dispose();
}
