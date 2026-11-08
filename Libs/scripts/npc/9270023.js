/* 	Noel
	Singapore Random Face Changer
	Credits to aaron and cody
	Side note by aaron [If there is something wrong PM me on fMS]
*/
var status = 0;
var beauty = 0;
var mface = Array(20000, 20001, 20002, 20003, 20004, 20005, 20006, 20007, 20008, 20012);
var fface = Array(21001, 21002, 21003, 21004, 21005, 21006, 21008, 21012, 21014, 21016);
var facenew = Array();

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == 0 && status == 0) {
	cm.dispose();
	return;
    }
    if (mode == 1)
	status++;
    else
	status--;
    if (status == 0) {
	cm.sendSimple("If you use this regular coupon, your face may transform into a random new look...do you still want to do it using #b#t5152037##k, I will do it anyways for you. But don't forget, it will be random!\r\n\#L2#OK!#l");
    } else if (status == 1 && selection == 2) {
	// A30：以前 status == 1 這一臂直接 dispose()，選完 OK 對話就結束，下面兩臂從來走不到；
	// 而 selection == 2 這一臂沒有用 status 把關。
	facenew = Array();
	if (cm.getChar().getGender() == 0) {
	    for(var i = 0; i < mface.length; i++) {
		facenew.push(mface[i] + cm.getChar().getFace() % 1000 - (cm.getChar().getFace() % 100));
	    }
	}
	if (cm.getChar().getGender() == 1) {
	    for(var i = 0; i < fface.length; i++) {
		facenew.push(fface[i] + cm.getChar().getFace() % 1000 - (cm.getChar().getFace() % 100));
	    }
	}
	cm.sendYesNo("If you use the regular coupon, your face may transform into a random new look...do you still want to do it using #b#t5152037##k?");
    } else if (status == 2) {
	// A30：右括號以前放錯位置，傳進去的是 (臉 == 1) 也就是 false，被轉成 0：扣掉券、把膚色改成 0、臉不變；
	// 而且 setAvatar 回的 1 與 -1 在 JS 裡都是真值，沒有券也會顯示成功。寫法比照 npc/9270026。
	if (cm.setAvatar(5152037, facenew[Math.floor(Math.random() * facenew.length)]) == 1) {
	    cm.sendOk("Enjoy your new and improved face!");
	} else {
	    cm.sendOk("Hmm ... it looks like you don't have the coupon specifically for this place. Sorry to say this, but without the coupon, there's no plastic surgery for you...");
	}
	cm.safeDispose();
    } else {
	cm.dispose();
    }
}