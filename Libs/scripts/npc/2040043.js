/*
    Blue Balloon - LudiPQ 8th stage NPC
*/

var status;
var partyLdr;
var chatState;
var party;
var preamble;

var stage8combos = [Array(0, 0, 0, 0, 1, 1, 1, 1, 1),
    Array(0, 0, 0, 1, 0, 1, 1, 1, 1),
    Array(0, 0, 0, 1, 1, 0, 1, 1, 1),
    Array(0, 0, 0, 1, 1, 1, 0, 1, 1),
    Array(0, 0, 0, 1, 1, 1, 1, 0, 1),
    Array(0, 0, 0, 1, 1, 1, 1, 1, 0),
    Array(0, 0, 1, 0, 0, 1, 1, 1, 1),
    Array(0, 0, 1, 0, 1, 0, 1, 1, 1),
    Array(0, 0, 1, 0, 1, 1, 0, 1, 1),
    Array(0, 0, 1, 0, 1, 1, 1, 0, 1),
    Array(0, 0, 1, 0, 1, 1, 1, 1, 0),
    Array(0, 0, 1, 1, 0, 0, 1, 1, 1),
    Array(0, 0, 1, 1, 0, 1, 0, 1, 1),
    Array(0, 0, 1, 1, 0, 1, 1, 0, 1),
    Array(0, 0, 1, 1, 0, 1, 1, 1, 0),
    Array(0, 0, 1, 1, 1, 0, 0, 1, 1),
    Array(0, 0, 1, 1, 1, 0, 1, 0, 1),
    Array(0, 0, 1, 1, 1, 0, 1, 1, 0),
    Array(0, 0, 1, 1, 1, 1, 0, 0, 1),
    Array(0, 0, 1, 1, 1, 1, 0, 1, 0),
    Array(0, 0, 1, 1, 1, 1, 1, 0, 0),
    Array(0, 1, 0, 0, 0, 1, 1, 1, 1),
    Array(0, 1, 0, 0, 1, 0, 1, 1, 1),
    Array(0, 1, 0, 0, 1, 1, 0, 1, 1),
    Array(0, 1, 0, 0, 1, 1, 1, 0, 1),
    Array(0, 1, 0, 0, 1, 1, 1, 1, 0),
    Array(0, 1, 0, 1, 0, 0, 1, 1, 1),
    Array(0, 1, 0, 1, 0, 1, 0, 1, 1),
    Array(0, 1, 0, 1, 0, 1, 1, 0, 1),
    Array(0, 1, 0, 1, 0, 1, 1, 1, 0),
    Array(0, 1, 0, 1, 1, 0, 0, 1, 1),
    Array(0, 1, 0, 1, 1, 0, 1, 0, 1),
    Array(0, 1, 0, 1, 1, 0, 1, 1, 0),
    Array(0, 1, 0, 1, 1, 1, 0, 0, 1),
    Array(0, 1, 0, 1, 1, 1, 0, 1, 0),
    Array(0, 1, 0, 1, 1, 1, 1, 0, 0),
    Array(0, 1, 1, 0, 0, 0, 1, 1, 1),
    Array(0, 1, 1, 0, 0, 1, 0, 1, 1),
    Array(0, 1, 1, 0, 0, 1, 1, 0, 1),
    Array(0, 1, 1, 0, 0, 1, 1, 1, 0),
    Array(0, 1, 1, 0, 1, 0, 0, 1, 1),
    Array(0, 1, 1, 0, 1, 0, 1, 0, 1),
    Array(0, 1, 1, 0, 1, 0, 1, 1, 0),
    Array(0, 1, 1, 0, 1, 1, 0, 0, 1),
    Array(0, 1, 1, 0, 1, 1, 0, 1, 0),
    Array(0, 1, 1, 0, 1, 1, 1, 0, 0),
    Array(0, 1, 1, 1, 0, 0, 0, 1, 1),
    Array(0, 1, 1, 1, 0, 0, 1, 0, 1),
    Array(0, 1, 1, 1, 0, 0, 1, 1, 0),
    Array(0, 1, 1, 1, 0, 1, 0, 0, 1),
    Array(0, 1, 1, 1, 0, 1, 0, 1, 0),
    Array(0, 1, 1, 1, 0, 1, 1, 0, 0),
    Array(0, 1, 1, 1, 1, 0, 0, 0, 1),
    Array(0, 1, 1, 1, 1, 0, 0, 1, 0),
    Array(0, 1, 1, 1, 1, 0, 1, 0, 0),
    Array(0, 1, 1, 1, 1, 1, 0, 0, 0),
    Array(1, 0, 0, 0, 0, 1, 1, 1, 1),
    Array(1, 0, 0, 0, 1, 0, 1, 1, 1),
    Array(1, 0, 0, 0, 1, 1, 0, 1, 1),
    Array(1, 0, 0, 0, 1, 1, 1, 0, 1),
    Array(1, 0, 0, 0, 1, 1, 1, 1, 0),
    Array(1, 0, 0, 1, 0, 0, 1, 1, 1),
    Array(1, 0, 0, 1, 0, 1, 0, 1, 1),
    Array(1, 0, 0, 1, 0, 1, 1, 0, 1),
    Array(1, 0, 0, 1, 0, 1, 1, 1, 0),
    Array(1, 0, 0, 1, 1, 0, 0, 1, 1),
    Array(1, 0, 0, 1, 1, 0, 1, 0, 1),
    Array(1, 0, 0, 1, 1, 0, 1, 1, 0),
    Array(1, 0, 0, 1, 1, 1, 0, 0, 1),
    Array(1, 0, 0, 1, 1, 1, 0, 1, 0),
    Array(1, 0, 0, 1, 1, 1, 1, 0, 0),
    Array(1, 0, 1, 0, 0, 0, 1, 1, 1),
    Array(1, 0, 1, 0, 0, 1, 0, 1, 1),
    Array(1, 0, 1, 0, 0, 1, 1, 0, 1),
    Array(1, 0, 1, 0, 0, 1, 1, 1, 0),
    Array(1, 0, 1, 0, 1, 0, 0, 1, 1),
    Array(1, 0, 1, 0, 1, 0, 1, 0, 1),
    Array(1, 0, 1, 0, 1, 0, 1, 1, 0),
    Array(1, 0, 1, 0, 1, 1, 0, 0, 1),
    Array(1, 0, 1, 0, 1, 1, 0, 1, 0),
    Array(1, 0, 1, 0, 1, 1, 1, 0, 0),
    Array(1, 0, 1, 1, 0, 0, 0, 1, 1),
    Array(1, 0, 1, 1, 0, 0, 1, 0, 1),
    Array(1, 0, 1, 1, 0, 0, 1, 1, 0),
    Array(1, 0, 1, 1, 0, 1, 0, 0, 1),
    Array(1, 0, 1, 1, 0, 1, 0, 1, 0),
    Array(1, 0, 1, 1, 0, 1, 1, 0, 0),
    Array(1, 0, 1, 1, 1, 0, 0, 0, 1),
    Array(1, 0, 1, 1, 1, 0, 0, 1, 0),
    Array(1, 0, 1, 1, 1, 0, 1, 0, 0),
    Array(1, 0, 1, 1, 1, 1, 0, 0, 0),
    Array(1, 1, 0, 0, 0, 0, 1, 1, 1),
    Array(1, 1, 0, 0, 0, 1, 0, 1, 1),
    Array(1, 1, 0, 0, 0, 1, 1, 0, 1),
    Array(1, 1, 0, 0, 0, 1, 1, 1, 0),
    Array(1, 1, 0, 0, 1, 0, 0, 1, 1),
    Array(1, 1, 0, 0, 1, 0, 1, 0, 1),
    Array(1, 1, 0, 0, 1, 0, 1, 1, 0),
    Array(1, 1, 0, 0, 1, 1, 0, 0, 1),
    Array(1, 1, 0, 0, 1, 1, 0, 1, 0),
    Array(1, 1, 0, 0, 1, 1, 1, 0, 0),
    Array(1, 1, 0, 1, 0, 0, 0, 1, 1),
    Array(1, 1, 0, 1, 0, 0, 1, 0, 1),
    Array(1, 1, 0, 1, 0, 0, 1, 1, 0),
    Array(1, 1, 0, 1, 0, 1, 0, 0, 1),
    Array(1, 1, 0, 1, 0, 1, 0, 1, 0),
    Array(1, 1, 0, 1, 0, 1, 1, 0, 0),
    Array(1, 1, 0, 1, 1, 0, 0, 0, 1),
    Array(1, 1, 0, 1, 1, 0, 0, 1, 0),
    Array(1, 1, 0, 1, 1, 0, 1, 0, 0),
    Array(1, 1, 0, 1, 1, 1, 0, 0, 0),
    Array(1, 1, 1, 0, 0, 0, 0, 1, 1),
    Array(1, 1, 1, 0, 0, 0, 1, 0, 1),
    Array(1, 1, 1, 0, 0, 0, 1, 1, 0),
    Array(1, 1, 1, 0, 0, 1, 0, 0, 1),
    Array(1, 1, 1, 0, 0, 1, 0, 1, 0),
    Array(1, 1, 1, 0, 0, 1, 1, 0, 0),
    Array(1, 1, 1, 0, 1, 0, 0, 0, 1),
    Array(1, 1, 1, 0, 1, 0, 0, 1, 0),
    Array(1, 1, 1, 0, 1, 0, 1, 0, 0),
    Array(1, 1, 1, 0, 1, 1, 0, 0, 0),
    Array(1, 1, 1, 1, 0, 0, 0, 0, 1),
    Array(1, 1, 1, 1, 0, 0, 0, 1, 0),
    Array(1, 1, 1, 1, 0, 0, 1, 0, 0),
    Array(1, 1, 1, 1, 0, 1, 0, 0, 0),
    Array(1, 1, 1, 1, 1, 0, 0, 0, 0)];

function start() {
    status = -1;
    preamble = null;
    action(1, 0, 0);
}

function action(mode, type, selection) {
    if (mode == -1) {
        cm.dispose();
    } else {
        if (mode == 0 && status == 0) {
            cm.dispose();
            return;
        }
        if (mode == 1) {
            status++;
        } else {
            status--;
        }
        boxStage(cm);
    }
}

function clear(stage, eim, cm) {
    eim.setProperty("8stageclear", "true");

    cm.showEffect(true, "quest/party/clear");
    cm.playSound(true, "Party1/Clear");
    cm.environmentChange(true, "gate");
    cm.givePartyExp(5040, eim.getPlayers());
    // stage 9 does not have a door, might be cause of DC error
}

function failstage(eim, cm) {
    cm.showEffect(true, "quest/party/wrong_kor");
    cm.playSound(true, "Party1/Failed");
}

function boxStage(cm) {
    var debug = false;
    var eim = cm.getEventInstance();
    var nthtext = "eighth";
    var nthobj = "boxes";
    var nthverb = "stand";
    var nthpos = "stand too close to the edges";
    var curcombo = stage8combos;
    var currect = cm.getMap().getAreas();
    var objset = [0, 0, 0, 0, 0, 0, 0, 0, 0];

    if( eim == null )
        return;

    if (cm.isLeader()) { // leader
        if (status == 0) {
            party = eim.getPlayers();
            preamble = eim.getProperty("leader" + nthtext + "preamble");
            if (preamble == null) {
                cm.sendNext("嗨，歡迎來到第八階段，這一階段完成後就可以打BOSS。我旁邊有九個箱子，請讓 5 位隊員站到箱子上，再由隊長來點我確認組合是否正確。祝你們好運！");
                eim.setProperty("leader" + nthtext + "preamble", "done");
                eim.setProperty("stage" + nthtext + "combo", Math.floor(Math.random() * curcombo.length).toString());
                cm.dispose();
            } else { // otherwise, check for stage completed
                var complete = eim.getProperty("8stageclear");
                if (complete != null) {
                    var mapClear = "8stageclear";
                    eim.setProperty(mapClear, "true"); // Just to be sure
                    cm.sendNext("請趕快到下一個階段，門已經打開了！");
                } else {
                    var totplayers = 0;
                    for (i = 0; i < objset.length; i++) {
                        for (j = 0; j < party.size(); j++) {
                            var present = currect.get(i).contains(party.get(j).getPosition());
                            if (present) {
                                objset[i] = objset[i] + 1;
                                totplayers = totplayers + 1;
                            }
                        }
                    }
                    if (totplayers == 5 || debug) {
                        var combo = curcombo[parseInt(eim.getProperty("stage" + nthtext + "combo"))];
                        var testcombo = true;
                        for (i = 0; i < objset.length; i++) {
                            if (combo[i] != objset[i]) {
                                testcombo = false;
                            }
                        }
                        if (testcombo || debug) {
                            clear(8, eim, cm);
                            if (cm.getEventInstance().getProperty("s8start") != null) {
                                var starts4Time = cm.getEventInstance().getProperty("s8start");
                                var nowTime = new Date().getTime();
                                if ((nowTime - starts4Time) < 90000)
                                    cm.getEventInstance().setProperty("s8achievement", "true"); // give via portal script.
                            }
                            cm.dispose();
                        } else {
                            failstage(eim, cm);
                            cm.dispose();
                        }
                    } else {
                        if (debug) {
                            var outstring = "Objects contain:"
                            for (i = 0; i < objset.length; i++) {
                                outstring += "\r\n" + (i + 1).toString() + ". " + objset[i].toString();
                            }
                            cm.sendNext(outstring);
                            var combo = curcombo[parseInt(eim.getProperty("stage" + nthtext + "combo"))];
                        } else {
                            cm.sendNext("看來你們還沒找到正確的 5 個箱子，請換個組合再試試。只能有 5 個人站在箱子上，太靠近箱子邊緣可能不會被算進去，請注意。加油！");
                            cm.dispose();
                        }
                    }
                }
            }
        } else {
            cm.dispose();
        }
    } else { // not leader
        if (status == 0) {
            var complete = eim.getProperty("8stageclear");
            if (complete != null) {
                cm.sendNext("請趕快到下一個階段，門已經打開了！");
                cm.dispose();
            } else {
                cm.sendNext("請找隊長來找我。");
                cm.dispose();
            }
        } else {
            var complete = eim.getProperty("8stageclear");
            if (complete != null) {
                cm.sendNext("請趕快到下一個階段，門已經打開了！");
                cm.dispose();
            }
            cm.dispose();
        }
    }
}
