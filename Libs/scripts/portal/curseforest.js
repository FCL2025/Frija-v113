function enter(pi) {
	// Portal scripts are compiled once and the PortalScript object is cached,
	// so the hour has to be read on every entry, not at load time.
	var hour = new Date().getHours();
	var map;
	if (hour < 6) { // 00:00 ~ 05:59
		map = 910100000;
	} else if (hour < 12) { // 06:00 ~ 11:59
		map = 910100001;
	} else if (hour < 18) { // 12:00 ~ 17:59
		map = 910100000;
	} else { // 18:00 ~ 23:59
		map = 910100001;
	}
	pi.warp(map, 0);
}
