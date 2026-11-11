var status = -1;

function start() {
	if (cm.getPlayer().getMapId() == 551030200) {
		cm.sendYesNo("你要離開了嗎?");
		status = 1;
		return;
	}
		if (cm.getPlayer().getLevel() < 90) {
			cm.sendOk("你的等級尚未達到90，無法挑戰熊獅王。");
			cm.dispose();
			return;
		}
		if (cm.getPlayer().getClient().getChannel() != 1 && cm.getPlayer().getClient().getChannel() != 2) {
			cm.sendOk("熊獅王只能在頻道 1、2 挑戰。");
			cm.dispose();
			return;
		}
    var em = cm.getEventManager("ScarTarBattle");

    if (em == null) {
	cm.sendOk("本活動尚未開放，請聯絡管理員。");
	cm.dispose();
	return;
    }
    var eim_status = em.getProperty("state");
	    var marr = cm.getQuestRecord(160108);
	    var data = marr.getCustomData();
	    if (data == null) {
		marr.setCustomData("0");
	        data = "0";
	    }
	    var time = parseInt(data);
	if (eim_status == null || eim_status.equals("0")) {
    var squadAvailability = cm.getSquadAvailability("ScarTar");
    if (squadAvailability == -1) {
	status = 0;
	    if (time + (3 * 3600000) >= cm.getCurrentTime() && !cm.getPlayer().isGM()) {
		cm.sendOk("你在 3 小時內已經挑戰過熊獅王了。剩餘時間：" + cm.getReadableMillis(cm.getCurrentTime(), time + (3 * 3600000)));
		cm.dispose();
		return;
	    }
	cm.sendYesNo("你想成為遠征隊隊長嗎？");

    } else if (squadAvailability == 1) {
	    if (time + (3 * 3600000) >= cm.getCurrentTime() && !cm.getPlayer().isGM()) {
		cm.sendOk("你在 3 小時內已經挑戰過熊獅王了。剩餘時間：" + cm.getReadableMillis(cm.getCurrentTime(), time + (3 * 3600000)));
		cm.dispose();
		return;
	    }
	// -1 = Cancelled, 0 = not, 1 = true
	var type = cm.isSquadLeader("ScarTar");
	if (type == -1) {
	    cm.sendOk("已經結束了申請，請重新申請。");
	    cm.dispose();
	} else if (type == 0) {
	    var memberType = cm.isSquadMember("ScarTar");
	    if (memberType == 2) {
		cm.sendOk("你在遠征隊的制裁名單中。");
		cm.dispose();
	    } else if (memberType == 1) {
		status = 5;
		cm.sendSimple("你要做什麼? \r\n#b#L0#加入遠征隊#l \r\n#b#L1#退出遠征隊#l \r\n#b#L2#查看遠征隊名單#l");
	    } else if (memberType == -1) {
		cm.sendOk("已經結束了申請，請重新申請。");
		cm.dispose();
	    } else {
		status = 5;
		cm.sendSimple("你要做什麼? \r\n#b#L0#加入遠征隊#l \r\n#b#L1#退出遠征隊#l \r\n#b#L2#查看遠征隊名單#l");
	    }
	} else { // Is leader
	    status = 10;
	    cm.sendSimple("你現在想做什麼？\r\n#b#L0#查看遠征隊成員。#l \r\n#b#L1#管理遠征隊成員。#l \r\n#b#L2#編輯限制列表。#l \r\n#r#L3#進入地圖。#l");
	// TODO viewing!
	}
	    } else {
			var eim = cm.getDisconnected("ScarTarBattle");
			if (eim == null) {
				var squd = cm.getSquad("ScarTar");
				if (squd != null) {
	    if (time + (3 * 3600000) >= cm.getCurrentTime() && !cm.getPlayer().isGM()) {
		cm.sendOk("你在 3 小時內已經挑戰過熊獅王了。剩餘時間：" + cm.getReadableMillis(cm.getCurrentTime(), time + (3 * 3600000)));
		cm.dispose();
		return;
	    }
					cm.sendYesNo("已經遠征隊正在進行挑戰了.\r\n" + squd.getNextPlayer());
					status = 3;
				} else {
					cm.sendOk("遠征隊的挑戰已經開始.");
					cm.safeDispose();
				}
			} else {
				cm.sendYesNo("你要繼續進行遠征任務嗎？");
				status = 2;
			}
	    }
	} else {
			var eim = cm.getDisconnected("ScarTarBattle");
			if (eim == null) {
				var squd = cm.getSquad("ScarTar");
				if (squd != null) {
	    if (time + (3 * 3600000) >= cm.getCurrentTime() && !cm.getPlayer().isGM()) {
		cm.sendOk("你在 3 小時內已經挑戰過熊獅王了。剩餘時間：" + cm.getReadableMillis(cm.getCurrentTime(), time + (3 * 3600000)));
		cm.dispose();
		return;
	    }
					cm.sendYesNo("已經遠征隊正在進行挑戰了.\r\n" + squd.getNextPlayer());
					status = 3;
				} else {
					cm.sendOk("遠征隊的挑戰已經開始.");
					cm.safeDispose();
				}
			} else {
				cm.sendYesNo("你要繼續進行遠征任務嗎？");
				status = 2;
			}
	}
}

function action(mode, type, selection) {
    switch (status) {
	case 0:
	    if (mode == 1) {
			if (cm.registerSquad("ScarTar", 5, " 已經成為了遠征隊隊長。如果你想加入遠征隊，請重新打開對話申請加入遠征隊。")) {
				cm.sendOk("你已經成為了遠征隊隊長。接下來的5分鐘，請等待隊員們的申請。");
			} else {
				cm.sendOk("未知錯誤.");
			}
	    }
	    cm.dispose();
	    break;
	case 1:
	    if (mode == 1) {
		cm.warp(551030100, 0);
	    }
	    cm.dispose();
	    break;
	case 2:
		if (!cm.reAdd("ScarTarBattle", "ScarTar")) {
			cm.sendOk("由於未知的錯誤，操作失敗。");
		}
		cm.safeDispose();
		break;
	case 3:
		if (mode == 1) {
			var squd = cm.getSquad("ScarTar");
			if (squd != null) {
				// setNextPlayer 是開關而不是「加入候補」：人已經在本頻道的候補佇列裡，
				// 這一呼叫就是把他移出去。原本擋在前面的 getAllNextPlayer().contains(...)
				// 拿字串去比 Pair<String, Long>，恆為 false，等於沒擋——移除那一半照常
				// 會跑，只是訊息永遠印「已預約」。現在先問 isQueuedNextPlayer 再決定訊息。
				var pName = cm.getPlayer().getName();
				var wasQueued = squd.isQueuedNextPlayer(pName);
				squd.setNextPlayer(pName);
				cm.sendOk(wasQueued ? "你已經取消下一組的登記。" : "你已經成功登記為下一組..");
			}
		}
		cm.dispose();
		break;
	case 5:
	    if (selection == 0) { // join
		var ba = cm.addMember("ScarTar", true);
		if (ba == 2) {
		    cm.sendOk("遠征隊員已經達到30名，請稍後再試。");
		} else if (ba == 1) {
		    cm.sendOk("申請加入遠征隊成功，請等候隊長指示。");
		} else {
		    cm.sendOk("你已經參加了遠征隊，請等候隊長指示。");
		}
	    } else if (selection == 1) {// withdraw
		var baa = cm.addMember("ScarTar", false);
		if (baa == 1) {
		    cm.sendOk("成功退出遠征隊。");
		} else {
		    cm.sendOk("你沒有參加遠征隊。");
		}
	    } else if (selection == 2) {
		if (!cm.getSquadList("ScarTar", 0)) {
		    cm.sendOk("由於未知的錯誤，操作失敗。");
		}
	    }
	    cm.dispose();
	    break;
	case 10:
	    if (mode == 1) {
		if (selection == 0) {
		    if (!cm.getSquadList("ScarTar", 0)) {
			cm.sendOk("由於未知的錯誤，操作失敗。");
		    }
		    cm.dispose();
		} else if (selection == 1) {
		    status = 11;
		    if (!cm.getSquadList("ScarTar", 1)) {
			cm.sendOk("由於未知的錯誤，操作失敗。");
			cm.dispose();
		    }
		} else if (selection == 2) {
		    status = 12;
		    if (!cm.getSquadList("ScarTar", 2)) {
			cm.sendOk("由於未知的錯誤，操作失敗。");
			cm.dispose();
		    }
		} else if (selection == 3) { // get insode
		    if (cm.getSquad("ScarTar") != null) {
			var dd = cm.getEventManager("ScarTarBattle");
			dd.startInstance(cm.getSquad("ScarTar"), cm.getMap(), 160108);
		    } else {
			cm.sendOk("由於未知的錯誤，操作失敗。");
		    }
		    cm.dispose();
		}
	    } else {
		cm.dispose();
	    }
	    break;
	case 11:
	    cm.banMember("ScarTar", selection);
	    cm.dispose();
	    break;
	case 12:
	    if (selection != -1) {
		cm.acceptMember("ScarTar", selection);
	    }
	    cm.dispose();
	    break;
    }
}