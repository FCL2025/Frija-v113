function enter(pi) {
	var map = pi.getPlayer().getMap();
	var reactor = map.getReactorByName("gate00");
	var state = reactor.getState();
	if (state >= 4) {
		pi.warp(670010600, 2);
		return true;
	} else {
		pi.getClient().send(Packages.tools.packet.ChatPacket.serverNotice(5, "The gate is closed."));
		return false;
	}
}