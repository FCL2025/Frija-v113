function enter(pi) {
	if (pi.isQuestActive(1035)) {
		pi.showAvatarOrientedEffect("UI/tutorial.img/20");
		return true;
	}
	return false;
}