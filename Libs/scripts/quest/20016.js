/*
	NPC Name: 		Nineheart
	Description: 		Quest - Do you know the black Magician?
*/

var status = -1;

function start(mode, type, selection) {
    if (mode == 1) {
	status++;
    } else {
	if (status == 6) {
	    qm.sendNext("喔，還有不明白的地方嗎？再來找我，我會從頭再解釋一次。");
	    qm.safeDispose();
	    return;
	}
	status--;
    }
    if (status == 0) {
	qm.sendNext("嗨, #h0#. 歡迎來到 #p1101000# 騎士團。我是 #p1101002#，目前擔任年輕女皇的軍師。往後我們會常常見面，先好好認識一下吧，哈哈！");
    } else if (status == 1) {
	qm.sendNextPrev("我敢肯定，你有很多的問題，因為一切都發生得太快。我會解釋這一切，一個接一個，從那裡你是你在這裡做什麼。");
    } else if (status == 2) {
	qm.sendNextPrev("這個島叫做耶雷弗。託女皇的魔力，這座島平常像船一樣漂浮在空中，巡視楓之谷世界。不過現在，我們因為某個原因停留在這裡。");
    } else if (status == 3) {
	qm.sendNextPrev("這位年輕的女皇是楓之谷世界的統治者。什麼？這是你聽說過她的第一次？啊，是的。嗯，她是楓之谷世界的統治者，但她不喜歡來控制它。她從遠處觀看，以確保一切都很好。好吧，至少這是她一貫的作用。");
    } else if (status == 4) {
	qm.sendNextPrev("但是，這不是這種情況現在。我們一直在尋找的標牌立在楓的世界，預示黑法師的復興。我們不能讓黑法師回來恐嚇楓之谷，因為他在過去!");
    } else if (status == 5) {
	qm.sendNextPrev("但是，這是很久以前的今天，人們不要為實現黑法師是有多嚇人的。我們都成了被寵壞和平楓之谷世界我們今天所享有和遺忘是如何混亂和可怕的楓之谷世界曾經是。如果我們不做些什麼，黑法師將再次統治楓之谷世界！");
    } else if (status == 6) {
	qm.askAcceptDecline("以上是我的解釋，有問題嗎？ \r\n\r\n#fUI/UIWindow.img/QuestIcon/4/0# \r\n#fUI/UIWindow.img/QuestIcon/8/0# 380 經驗值");
    } else if (status == 7) {
	if (qm.getQuestStatus(20016) == 0) {
	    qm.gainExp(380);
	    qm.forceCompleteQuest();
	}
	qm.sendNext("我很高興你清楚我們目前的情況，但你知道在你目前的能力，你甚至沒有強大到足以面對黑法師的爪牙，更別說黑魔導士本人。事實上，你連他爪牙的手下都打不過。你將如何保護楓之谷世界在你目前的等級？");
    } else if (status == 8) {
	qm.sendNextPrev("雖然你已經加入了騎士團，但還不能被認可為騎士。你連修煉騎士都還不是，當然也不是正式騎士。如果一直停留在現在的等級，你只能當 #p1101000# 騎士團的雜工而已。");
    } else if (status == 9) {
	qm.sendNextPrev("不過，沒有人第一天就是強大的騎士。女皇要的不是強者，而是有勇氣、能透過嚴格修煉成長為強大騎士的人。所以你應該先成為修煉騎士，等到那時我們再談你的任務。");
    } else if (status == 10) {
	qm.sendPrev("從左邊的傳送點前往修煉之森，你會在那裡找到修煉教官 #p1102000#，他會教你如何變強。在達到 10 級之前，可別讓我看到你到處閒晃，聽到了嗎？");
	qm.safeDispose();
    }
}

function end(mode, type, selection) {
}