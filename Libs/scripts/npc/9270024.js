/* 	Kelvin
	SingaPore VIP Face changer
*/
var status = -1;
var beauty = 0;
var mface = Array(20109, 20110, 20106, 20108, 20112, 20013);
var fface = Array(21021, 21009, 21010, 21006, 21008, 21012);
var facenew = Array();

function action(mode, type, selection) {
    if (mode == 0 && status == 0) {
	cm.dispose();
	return;
    }
    if (mode == 1) {
	status++;
    } else {
	status--;
    }

    if (status == 0) {
	cm.sendSimple("Let's see...I can totally transform your face into something new. Don't you want to try it? For #b#t5152038##k, you can get the face of your liking. Take your time in choosing the face of your preference...\r\n\#L2#Let me get my dream face!#l");
    } else if (status == 1 && selection == 2) {
	// A30：以前這一臂沒有用 status 把關。sendStyle 回來的 selection 是造型索引 0..5，
	// 選第三張臉（索引 2）會撞回這裡重畫選單，status 再加一就沒有任何一臂接得住，對話卡死。
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
	cm.sendStyle("Let's see... I can totally transform your face into something new. Don't you want to try it? For #b#t5152038##k, you can get the face of your liking. Take your time in choosing the face of your preference...", facenew);

    } else if (status == 2){
	// A30：右括號以前放錯位置，傳進去的是 (臉 == 1) 也就是 false，被轉成 0：扣掉 VIP 券、把膚色改成 0、
	// 臉不變；而且沒有券也會顯示成功（1 與 -1 在 JS 裡都是真值）。寫法比照 npc/9270026。
	if (cm.setAvatar(5152038, facenew[selection]) == 1) {
	    cm.sendOk("Enjoy your new and improved face!");
	} else {
	    cm.sendOk("Hmm ... it looks like you don't have the coupon specifically for this place. Sorry to say this, but without the coupon, there's no plastic surgery for you...");
	}
	cm.safeDispose();
    } else {
	cm.dispose();
    }
}