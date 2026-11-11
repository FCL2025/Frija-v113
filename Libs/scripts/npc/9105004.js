var status = -1;

function action(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	if (status == 0) {
	    cm.dispose();
		return;
	}
	status--;
    }
    switch(cm.getPlayer().getMapId()) {
	case 889100100: //event map
    	if (status == 0) {
	        cm.sendSimple("你好~我是雪之妖精。請選擇要前往的保護雪人難度：#b\r\n\r\n#L0#保護雪人 - 簡單 (等級 10 以上)#l\r\n#L1#保護雪人 - 中等 (等級 30 以上)#l\r\n#L2#保護雪人 - 困難 (等級 70 以上)#l");
    	} else if (status == 1) {
	        if (selection == 0) {
		    cm.warp(889100000,0); //exit map lobby
		    cm.dispose();
			} else if (selection == 1) {
			cm.warp(889100010,0); //exit map lobby
		    cm.dispose();
			} else if (selection == 2) {
			cm.warp(889100020,0); //exit map lobby
		    cm.dispose();
			}
		}
	    break;
	case 889100000:
	case 889100010:
	case 889100020:
    	    if (status == 0) {
	        cm.sendSimple("你好~我是雪之妖精!#b\r\n\r\n#L0#前往保護雪人#l");
    	    } else if (status == 1) {
			var s = ((cm.getMapId() % 100) / 10) | 0;
   		    var em = cm.getEventManager("Christmas");
    		    if (em == null) {
			cm.sendOk("目前無法進入，請稍後再試。");
			cm.dispose();
			return;
    		    }
		    if (cm.getPlayer().getParty() == null || !cm.isLeader()) {
			cm.sendOk("請隊長來找我對話。");
		    } else {
			var party = cm.getPlayer().getParty().getMembers();
			var mapId = cm.getPlayer().getMapId();
			var next = true;
			var size = 0;
			var it = party.iterator();
			while (it.hasNext()) {
				var cPlayer = it.next();
				var ccPlayer = cm.getPlayer().getMap().getCharacterById(cPlayer.getId());
				if (ccPlayer == null || ccPlayer.getLevel() < (s == 0 ? 10 : (s == 1 ? 30 : 70))) {
					next = false;
					break;
				}
				size++;
			}	
			if (next && size >= 2) {
		    		if (em.getInstance("Christmas" + s) == null) {
					em.startInstance_Party("" + s, cm.getPlayer());
		    		} else {
					cm.sendOk("這個頻道已經有其他隊伍在進行了。");
		    		}
			} else {
				cm.sendOk("隊伍至少需要兩人，且所有隊員都必須在這裡並達到等級要求。");
			}
		    }
	        cm.dispose();
            }
	    break;
	case 889100001:
	case 889100011:
	case 889100021:
		if (cm.getPlayer().getEventInstance() == null || !cm.isLeader()) {
			cm.sendOk("拜託了，請你們的隊長來找我。");
		} else {
			if (!cm.getPlayer().getEventInstance().getProperty("stage").equals("1")) {
				cm.sendOk("請保護雪人直到時間結束!");
			} else if (cm.getPlayer().getMap().countMonsterById(9400319) > 0 || cm.getPlayer().getMap().countMonsterById(9400320) > 0 || cm.getPlayer().getMap().countMonsterById(9400321) > 0) {
				cm.sendOk("請先打倒鬼精靈!");
			} else {
				var s = ((cm.getMapId() % 100) / 10) | 0;
				cm.givePartyExp((s == 0 ? 2500 : (s == 1 ? 7500 : 20000)));
//				cm.givePartyNX((s == 0 ? 2 : (s == 1 ? 5 : 10)));
				cm.warpParty(cm.getMapId() + 1);
			}
		}
		cm.dispose();
		break;
	default:
		cm.dispose();
		break;
    }
}