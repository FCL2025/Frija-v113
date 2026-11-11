/* Pi
	Ludibrium Village (220000300)
	
	Refining NPC: 
 * Minerals
 * Jewels
 * Moon/Star Rocks
 * Crystals (minus Dark)
 * Processed Wood/Screws
 * Arrows/Bronze Arrows/Steel Arrows
 */

var status = 0;
var selectedType = -1;
var selectedItem = -1;
var item;
var mats;
var matQty;
var cost;
var qty;
var equip;

function action(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	cm.dispose();
	return;
    }
    if (status == 0) {
	var selStr = "嗯? 你是誰? 喔，你聽說過我的鍛造技術了? 那我很樂意幫你提煉一些礦石...不過要收點手續費喔。#b";
	var options = new Array("提煉礦物母礦","提煉寶石母礦","提煉稀有寶石","提煉水晶母礦","製作材料","製作箭矢");
	for (var i = 0; i < options.length; i++){
	    selStr += "\r\n#L" + i + "# " + options[i] + "#l";
	}	
	cm.sendSimple(selStr);
    } else if (status == 1) {
	selectedType = selection;
	if (selectedType == 0){ //mineral refine
	    var selStr = "你想要做哪一種呢?#b";
	    var minerals = new Array ("青銅","鋼鐵","鋰礦石","朱礦石","銀","紫礦石","黃金");
	    for (var i = 0; i < minerals.length; i++){
		selStr += "\r\n#L" + i + "# " + minerals[i] + "#l";
	    }
	    equip = false;
	    cm.sendSimple(selStr);
	} else if (selectedType == 1){ //jewel refine
	    var selStr = "你想要做哪一種呢?#b";
	    var jewels = new Array ("#t4021000#","#t4021001#","#t4021002#","#t4021003#","#t4021004#","#t4021005#","#t4021006#","#t4021007#","#t4021008#");
	    for (var i = 0; i < jewels.length; i++){
		selStr += "\r\n#L" + i + "# " + jewels[i] + "#l";
	    }
	    equip = false;
	    cm.sendSimple(selStr);
	} else if (selectedType == 2){ //rock refine
	    var selStr = "你想要做哪一種呢?#b";
	    var items = new Array ("#t4011007#","#t4021009#");
	    for (var i = 0; i < items.length; i++){
		selStr += "\r\n#L" + i + "# " + items[i] + "#l";
	    }
	    equip = false;
	    cm.sendSimple(selStr);
	} else if (selectedType == 3){ //crystal refine
	    var selStr = "你想要做哪一種呢?#b";
	    var crystals = new Array ("#t4005000#","#t4005001#","#t4005002#","#t4005003#");
	    for (var i = 0; i < crystals.length; i++){
		selStr += "\r\n#L" + i + "# " + crystals[i] + "#l";
	    }
	    equip = false;
	    cm.sendSimple(selStr);
	} else if (selectedType == 4){ //material refine
	    var selStr = "你想要做哪一種呢?#b";
	    var materials = new Array ("用#t4000003#製作#t4003001#","用#t4000018#製作#t4003001#","製作#t4003000# (一組 15 個)");
	    for (var i = 0; i < materials.length; i++){
		selStr += "\r\n#L" + i + "# " + materials[i] + "#l";
	    }
	    equip = false;
	    cm.sendSimple(selStr);
	} else if (selectedType == 5){ //arrow refine
	    var selStr = "你想要做哪一種呢?#b";
	    var arrows = new Array ("#t2060000#","#t2061000#","#t2060001#","#t2061001#","#t2060002#","#t2061002#");
	    for (var i = 0; i < arrows.length; i++){
		selStr += "\r\n#L" + i + "# " + arrows[i] + "#l";
	    }
	    equip = true;
	    cm.sendSimple(selStr);
	}
	if (equip)
	    status++;
    } else if (status == 2) {
	selectedItem = selection;
	if (selectedType == 0){ //mineral refine
	    var itemSet = new Array(4011000,4011001,4011002,4011003,4011004,4011005,4011006);
	    var matSet = new Array(4010000,4010001,4010002,4010003,4010004,4010005,4010006);
	    var matQtySet = new Array(10,10,10,10,10,10,10);
	    var costSet = new Array(270,270,270,450,450,450,720);
	    item = itemSet[selectedItem];
	    mats = matSet[selectedItem];
	    matQty = matQtySet[selectedItem];
	    cost = costSet[selectedItem];
	}
	else if (selectedType == 1){ //jewel refine
	    var itemSet = new Array(4021000,4021001,4021002,4021003,4021004,4021005,4021006,4021007,4021008);
	    var matSet = new Array(4020000,4020001,4020002,4020003,4020004,4020005,4020006,4020007,4020008);
	    var matQtySet = new Array(10,10,10,10,10,10,10,10,10);
	    var costSet = new Array (450,450,450,450,450,450,450,900,2700);
	    item = itemSet[selectedItem];
	    mats = matSet[selectedItem];
	    matQty = matQtySet[selectedItem];
	    cost = costSet[selectedItem];
	}
	else if (selectedType == 2){ //rock refine
	    var itemSet = new Array(4011007,4021009);
	    var matSet = new Array(new Array(4011000,4011001,4011002,4011003,4011004,4011005,4011006), new Array(4021000,4021001,4021002,4021003,4021004,4021005,4021006,4021007,4021008));
	    var matQtySet = new Array(new Array(1,1,1,1,1,1,1),new Array(1,1,1,1,1,1,1,1,1));
	    var costSet = new Array(9000,13500);
	    item = itemSet[selectedItem];
	    mats = matSet[selectedItem];
	    matQty = matQtySet[selectedItem];
	    cost = costSet[selectedItem];
	}
	else if (selectedType == 3){ //crystal refine
	    var itemSet = new Array (4005000,4005001,4005002,4005003);
	    var matSet = new Array(4004000,4004001,4004002,4004003);
	    var matQtySet = new Array (10,10,10,10);
	    var costSet = new Array (4500,4500,4500,4500);
	    item = itemSet[selectedItem];
	    mats = matSet[selectedItem];
	    matQty = matQtySet[selectedItem];
	    cost = costSet[selectedItem];
	}
	else if (selectedType == 4){ //material refine
	    var itemSet = new Array (4003001,4003001,4003000);
	    var matSet = new Array(4000003,4000018,new Array (4011000,4011001));
	    var matQtySet = new Array (10,5,new Array (1,1));
	    var costSet = new Array (0,0,0);
	    item = itemSet[selectedItem];
	    mats = matSet[selectedItem];
	    matQty = matQtySet[selectedItem];
	    cost = costSet[selectedItem];
	}
		
	var prompt = "你想要做 #t" + item + "# 嗎? 這對我來說很簡單!\r\n請問你要做幾個?";
		
	cm.sendGetNumber(prompt,1,1,100)
    } else if (status == 3) {
	if (equip) {
	    selectedItem = selection;
	    qty = 1;
	} else
	    qty = selection;

	if (selectedType == 5){ //arrow refine
	    var itemSet = new Array(2060000,2061000,2060001,2061001,2060002,2061002);
	    var matSet = new Array(new Array (4003001,4003004),new Array (4003001,4003004),new Array (4011000,4003001,4003004),new Array (4011000,4003001,4003004),
		new Array (4011001,4003001,4003005),new Array (4011001,4003001,4003005));
	    var matQtySet = new Array (new Array (1,1),new Array (1,1),new Array (1,3,10),new Array (1,3,10),new Array (1,5,15),new Array (1,5,15));
	    var costSet = new Array (0,0,0,0,0,0);
	    item = itemSet[selectedItem];
	    mats = matSet[selectedItem];
	    matQty = matQtySet[selectedItem];
	    cost = costSet[selectedItem];
	}
		
	var prompt = "你想做 ";
	if (qty == 1) {
	    prompt += "一個 #t" + item + "#?";
	} else {
	    prompt += qty + " #t" + item + "#?";
	}
	prompt += " 那麼我需要以下的材料，另外請先確認背包有足夠的空間。#b";
		
	if (mats instanceof Array){
	    for (var i = 0; i < mats.length; i++) {
		prompt += "\r\n#i"+mats[i]+"# " + matQty[i] * qty + " #t" + mats[i] + "#";
	    }
	} else {
	    prompt += "\r\n#i"+mats+"# " + matQty * qty + " #t" + mats + "#";
	}
	if (cost > 0) {
	    prompt += "\r\n#i4031138# " + cost * qty + " 楓幣";
	}
	cm.sendYesNo(prompt);
    } else if (status == 4) {
	var complete = true;
	if (qty < 1 || qty > 100) {
	    cm.dispose();
	    return;
	}
				
	if (cm.getMeso() < cost * qty) {
	    cm.sendNext("你的楓幣不夠支付手續費喔。");
		cm.dispose();
		return;
	} else {
	    if (mats instanceof Array) {
		for (var i = 0; complete && i < mats.length; i++) {
		    if (matQty[i] * qty == 1) {
			complete = cm.haveItem(mats[i]);
		    } else {
			complete = cm.haveItem(mats[i], matQty[i] * qty);
		    }
		}
	    } else {
		complete = cm.haveItem(mats, matQty * qty);
	    }
	}
			
	if (!complete)
	    cm.sendOk("很抱歉由於你的材料不足，所以我不想幫你做了。");
	else {
	    if (mats instanceof Array) {
		for (var i = 0; i < mats.length; i++){
		    cm.gainItem(mats[i], -matQty[i] * qty);
		}
	    }
	    else
		cm.gainItem(mats, -matQty * qty);
					
	    if (cost > 0)
		cm.gainMeso(-cost * qty);
				
	    if (item >= 2060000 && item <= 2060002) //bow arrows
		cm.gainItem(item, 1000 - (item - 2060000) * 100);
	    else if (item >= 2061000 && item <= 2061002) //xbow arrows
		cm.gainItem(item, 1000 - (item - 2061000) * 100);
	    else if (item == 4003000)//screws
		cm.gainItem(4003000, 15 * qty);
	    else
		cm.gainItem(item, qty);
	    cm.sendOk("製作完畢。");
	}
	cm.safeDispose();
    }
}