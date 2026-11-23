package com.cosmotune.podio

/** Where the phone's Podio sends a song a station played to be scrobbled: the player's Last.fm session, once one is configured. */
object PodioRadioScrobbles {
    @Volatile var send: ((PodioRadioListen) -> Unit)? = null
}
