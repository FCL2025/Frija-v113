/*
 * 101大道 整形 (742000000): one 5152031 「101整形手術一般會員卡」 for a random
 * face, keeping the current eye colour. Sits beside 9330051 (hair) and
 * 9330054 (skin).
 */
var status = -1;
var mface = [20000, 20001, 20002, 20003, 20004, 20005, 20006, 20007, 20008, 20012, 20014];
var fface = [21000, 21001, 21002, 21003, 21004, 21005, 21006, 21007, 21008, 21012, 21014];
var facenew = [];

function start() {
    status = -1;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode != 1) {
        cm.dispose();
        return;
    }
    status++;
    if (status == 0) {
        cm.sendSimple("嗨，我是#p9330052# 如果您有 #b#t5152031##k，我將可以幫您隨機整形!\r\n#L2#使用 #b#t5152031##k#l");
    } else if (status == 1) {
        if (selection != 2) {
            cm.dispose();
            return;
        }
        var face = cm.getPlayer().getFace();
        var color = face % 1000 - face % 100;
        var base = cm.getPlayer().getGender() == 0 ? mface : fface;
        facenew = [];
        for (var i = 0; i < base.length; i++) {
            facenew.push(base[i] + color);
        }
        cm.sendYesNo("注意!這是隨機，請問是否要使用 #b#t5152031##k?");
    } else {
        if (facenew.length > 0 && cm.haveItem(5152031)) {
            cm.gainItem(5152031, -1);
            cm.setFace(facenew[Math.floor(Math.random() * facenew.length)]);
            cm.sendOk("享受!");
        } else {
            cm.sendOk("您貌似沒有#b#t5152031##k..");
        }
        cm.dispose();
    }
}
