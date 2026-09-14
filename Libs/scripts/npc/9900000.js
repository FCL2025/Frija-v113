/*	KIN
	Map(s):		Victoria Road : 工作場所 (180000000)
	Description:	以 2,000,000 楓幣兌換 1 點商城點數
*/
/*	A18：這支原本是裸露的最上層程式碼，沒有 start() 也沒有 action()。
	NPC 的進入點契約是先 eval 整支腳本、之後才 put("cm", cm)，所以那段
	程式碼跑在 cm 還不存在的時候，每次點這個 NPC 都是 ReferenceError，
	腳本載入失敗，最後退回 npc/notcoded.js。包進 start() 才符合契約。
	同時把全樹不存在的 cm.gainAcash() 換成真的有的 cm.gainNX()
	（等同 modifyCSPoints(1, n)，加的就是 acash），並補上楓幣不足時的
	回覆與兩條路徑的 dispose()，否則對話會卡住，只能用 @解卡 脫身。 */

var price = 2000000;

function start() {
	if (cm.getPlayer().getMeso() >= price) {
		cm.gainMeso(-price);
		cm.gainNX(1);
		cm.sendOk("獲得#r1點");
	} else {
		cm.sendOk("楓幣不足，兌換 1 點需要#r" + price + "#k楓幣。");
	}
	cm.dispose();
}