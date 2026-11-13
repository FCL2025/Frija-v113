/*
 * Cabin portal out00 of the ship to Orbis (map 200090011). Boats.js hooks it up; WZ gives the
 * portal no script. Leads back to the deck and, while the Crimson Balrog is
 * attacking, replays the invasion music and ship effect for the player who
 * came up - the rest of the deck already has them.
 */
function enter(pi) {
    pi.playPortalSE();
    pi.warp(200090010, 4);
    var em = pi.getEventManager("Boats");
    if (em != null && em.getProperty("haveBalrog") == "true") {
        pi.getClient().send(Packages.tools.packet.MapPacket.musicChange("Bgm04/ArabPirate"));
        pi.getClient().send(Packages.tools.packet.MapPacket.boatEffect(1034));
    }
    return true;
}
