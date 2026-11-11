/*
 * 小遊戲製作
 * by Kodan
 */

var select;
// 五子棋：成品, 五目石 A, 五目石 B（各 99 個，另需 1 個圍棋板 4030009）
var omok = [
	[4080000, 4030000, 4030001],
	[4080001, 4030000, 4030010],
	[4080002, 4030000, 4030011],
	[4080003, 4030010, 4030001],
	[4080004, 4030011, 4030010],
	[4080005, 4030011, 4030001]
];

function start() {
	status = -1;
	action(1, 0, 0);
}

function action(mode, type, selection) {
	if (mode < 1) {
		cm.dispose();
		return;
	} else if (mode == 1) {
		status++;
	} else {
		status--;
	}

	if (status == 0) {
		cm.sendSimple("嘿！你好像打獵打累了？享受快樂生活是我的信念。怎麼樣？只要有幾種道具，我就給你更換能玩小遊戲的道具。嗯。。。幫你甚麼好呢？\r\n#b#L0#製作小遊戲道具#l\r\n#b#L1#聽聽小遊戲的說明#l");
	} else if (status == 1) {
		if (selection == 0) {
			cm.sendSimple("請問想要做哪種小遊戲呢？？\r\n\r\n#b#L2#五子棋#l#k\r\n#b#L3#找碴#l#k");
		} else if (selection == 1) {
			cm.sendSimple("你要聽哪種小遊戲呢？？\r\n\r\n#b#L4#五子棋#l#k\r\n#b#L5#找碴#l#k");
		} else {
			cm.dispose();
		}
	} else if (status == 2) {
		if (selection != null)
			select = selection;

		if (select == 2) {
			cm.sendNext("製作五子棋需要兩種五目石各 #b99#k 個，以及 #b1#k 個#t4030009#。請準備好材料。");
		} else if (select == 3) {
			if (!cm.haveItem(4030012, 15)) {
				cm.sendNext("請準備好材料。 找碴需要 #b15#k 張#t4030012# ！");
			} else if (!cm.canHold(4080100, 1)) {
				cm.sendNext("你的其他欄空間不足，請先整理一下背包。");
			} else {
				cm.gainItem(4030012, -15);
				cm.gainItem(4080100, 1);
				cm.sendOk("恭喜製作完成#t4080100#。");
			}
			cm.dispose();
		} else if (select == 4) {
			cm.sendOk("#e五子棋#n是兩人輪流在棋盤上放下棋子的遊戲，先讓自己的五顆棋子在橫線、直線或斜線上連成一線的人就獲勝。\r\n在背包中使用五子棋道具就能開設遊戲房間，邀請其他玩家一起對戰。");
			cm.dispose();
		} else if (select == 5) {
			cm.sendOk("#e找碴#n是翻牌配對的記憶遊戲，兩人輪流翻開兩張牌，翻到相同圖案就能得分並繼續翻牌，最後配對最多的人獲勝。\r\n在背包中使用找碴道具就能開設遊戲房間，邀請其他玩家一起對戰。");
			cm.dispose();
		} else {
			cm.dispose();
		}
	} else if (status == 3) {
		if (select == 2) {
			cm.sendSimple("那麼，你想要做哪種形狀的五子棋呢？？\r\n#b#L6##t4080000##l#k\r\n#b#L7##t4080001##l#k\r\n#b#L8##t4080002##l#k\r\n#b#L9##t4080003##l#k\r\n#b#L10##t4080004##l#k\r\n#b#L11##t4080005##l#k");
		} else {
			cm.dispose();
		}
	} else if (status == 4) {
		var idx = selection - 6;
		if (idx >= 0 && idx < omok.length) {
			var set = omok[idx];
			if (!cm.haveItem(set[1], 99) || !cm.haveItem(set[2], 99) || !cm.haveItem(4030009, 1)) {
				cm.sendNext("請準備好材料。 #t" + set[0] + "#需要 #b99#k 個#t" + set[1] + "#和#t" + set[2] + "# 和 #b1#k 個 #t4030009# ！");
			} else if (!cm.canHold(set[0], 1)) {
				cm.sendNext("你的其他欄空間不足，請先整理一下背包。");
			} else {
				cm.gainItem(set[1], -99);
				cm.gainItem(set[2], -99);
				cm.gainItem(4030009, -1);
				cm.gainItem(set[0], 1);
				cm.sendOk("恭喜製作完成#t" + set[0] + "#。");
			}
		}
		cm.dispose();
	}
}
