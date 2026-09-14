/* 	Sixx
	Singa REG/VIP Eye Color Changer
*/
/*	A19：三個邏輯修正（M3 修過語法，邏輯還是壞的）。
	1. selection == 1 / == 2 兩臂原本沒有用 status 把關。sendStyle 回來的
	   selection 是造型索引 0..7，所以在 status == 2 選第 2、3 個顏色時，
	   會先撞進這兩臂、把對話倒回去重問一次，status 再加到 3 就沒有任何
	   分支接得住，對話直接卡死，只能 @解卡。加上 status == 1 && 之後，
	   這兩臂只在剛從選單選完時成立。
	2. status == 2 那臂原本第一行就 cm.dispose()，等於先把對話收掉再去
	   setAvatar 和 sendOk。改成照同族腳本（npc/9201062、npc/9270024）的
	   慣用法，把 cm.safeDispose() 放在 sendOk 之後。
	3. 補一個 else 收尾，走不到任何分支時直接 dispose()，不留卡住的對話。 */
var status = -1;
var beauty = 0;
var colors = Array();

function start() {
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
	cm.sendSimple("Hi, there! I'm Sixx, in charge of Da Yan Jing Lens Shop here at CBD! With #b#t5152039##k or #b#t5152040##k, you can let us take care of the rest and have the kind of beautiful look you've always craved! Remember, the first thing everyone notices about you are the eyes, and we can help you find the cosmetic lens that most fits you! Now, what would you like to use?\r\n#L1#Cosmetic Lenses: #i5152039##t5152039##l\r\n#L2#Cosmetic Lenses: #i5152040##t5152040##l");
    } else if (status == 1 && selection == 1) {
	beauty = 1;
	if (cm.getPlayerStat("GENDER") == 0) {
	    var current = cm.getPlayerStat("FACE") % 100 + 20000;
	} else {
	    var current = cm.getPlayerStat("FACE") % 100 + 21000;
	}
	colors = Array();
	colors = Array(current , current + 100, current + 200, current + 300, current +400, current + 500, current + 600, current + 700);
	cm.sendYesNo("If you use the regular coupon, you'll be awarded a random pair of cosmetic lenses. Are you going to use a #b#t5152039##k and really make the change to your eyes?");
    } else if (status == 1 && selection == 2) {
	beauty = 2;
	if (cm.getPlayerStat("GENDER") == 0) {
	    var current = cm.getPlayerStat("FACE") % 100 + 20000;
	} else {
	    var current = cm.getPlayerStat("FACE") % 100 + 21000;
	}
	colors = Array();
	colors = Array(current , current + 100, current + 200, current + 300, current +400, current + 500, current + 600, current + 700);
	cm.sendStyle("With our specialized machine, you can see yourself after the treatment in advance. What kind of lens would you like to wear? Choose the style of your liking.", colors);
    }
    else if (status == 2){
	if (beauty == 1){
	    if (cm.setAvatar(5152039, colors[Math.floor(Math.random() * colors.length)]) == 1){
		cm.sendOk("Enjoy your new and improved cosmetic lenses!");
	    } else {
		cm.sendOk("I'm sorry, but I don't think you have our cosmetic lens coupon with you right now. Without the coupon, I'm afraid I can't do it for you..");
	    }
	}
	if (beauty == 2){
	    if (cm.setAvatar(5152040, colors[selection]) == 1){
		cm.sendOk("Enjoy your new and improved cosmetic lenses!");
	    } else {
		cm.sendOk("I'm sorry, but I don't think you have our cosmetic lens coupon with you right now. Without the coupon, I'm afraid I can't do it for you..");
	    }
	}
	cm.safeDispose();
    } else {
	cm.dispose();
    }
}