var status = -1;

function action(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	if (status <= 2) {
	    cm.sendNext("你在這裡應該還有事情要辦吧。旅行和狩獵一定很累了，先好好休息，改變主意的話再來找我吧！");
	    cm.dispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	cm.sendSimple("你有沒有聽過離玩具城有點遠、能看到壯觀海景的 #b黃金海灘#k 呢？我現在就可以帶你過去，只需要 #b1500 楓幣#k，或者如果你有一張 #b#t4031134##k 的話，就可以免費前往。\r\n\r\n#L0##b 我願意付 1500 楓幣。#l\r\n#L1# 我有一張#t4031134#。#l\r\n#L2# 什麼是#t4031134#？#k#l");
    } else if (status == 1) {
	if (selection == 0) {
	    cm.sendYesNo("你想付 #b1500 楓幣#k 前往黃金海灘嗎？那邊也會有怪物出沒，可別掉以輕心喔。現在就要出發嗎？");
	} else if (selection == 1) {
	    status = 2;
	    cm.sendYesNo("你有 #b#t4031134##k 嗎？有那張票隨時都可以前往黃金海灘。那邊也會有怪物出沒，可別掉以輕心喔。現在就要出發嗎？");
	} else if (selection == 2) {
	    status = 4;
	    cm.sendNext("你一定很好奇什麼是 #b#t4031134##k 吧。只要身上帶著這張票，就可以免費前往黃金海灘。這是連我們都得花錢買的稀有道具，可惜我在幾週前的長假裡把我的那張弄丟了。");
	}
    } else if (status == 2) {
	if (cm.getMeso() < 1500) {
	    cm.sendNext("你的楓幣好像不夠喔。賺錢的方法很多，像是賣掉裝備、打倒怪物、完成任務……你懂我的意思吧。");
	    cm.dispose();
	} else {
	    cm.gainMeso(-1500);
	    cm.saveLocation("FLORINA");
	    cm.warp(110000000, 0);
	    cm.dispose();
	}
    } else if (status == 3) {
	if (cm.haveItem(4031134)) {
	    cm.saveLocation("FLORINA");
	    cm.warp(110000000, 0);
	    cm.dispose();
	} else {
	    cm.sendNext("嗯……你確定身上有 #b#t4031134##k 嗎？我找不到耶，請再確認一下。");
	    cm.dispose();
	}
    } else if (status == 4) {
	cm.sendNext("你一定很好奇什麼是 #b#t4031134##k 吧。只要身上帶著這張票，就可以免費前往黃金海灘。這是連我們都得花錢買的稀有道具，可惜我在幾週前的長假裡把我的那張弄丟了。");
    } else if (status == 5) {
	cm.sendNextPrev("沒有它回來之後，總覺得很不是滋味。希望有人把它撿起來好好收著。總之這就是我的故事，說不定你能撿到它，好好利用呢。還有什麼問題的話，隨時來問我吧。");
    } else if (status == 6) {
	cm.dispose();
    }
}
