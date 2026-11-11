/*
    Return from
    Ariant Coliseum : Lobby (980010000)

    The original imported org.rise.server.maps / org.rise.net.channel (packages that do not
    exist here) and read SavedLocationType.ARIANT_PQ, which this server does not have, so the
    portal never worked. Nothing saves a location on the way in, so send the player back to
    Ariant (260000000).
*/

function enter(pi) {
    pi.warp(260000000, 0);
    return true;
}
