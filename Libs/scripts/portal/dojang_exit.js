function enter(pi) {
    var returnMap = pi.getSavedLocation("MULUNG_TC");
    if (returnMap < 0) {
        returnMap = 100000000;
    }
    pi.playPortalSE();
    pi.warp(returnMap, 0);
    pi.clearSavedLocation("MULUNG_TC");
    return true;
}
