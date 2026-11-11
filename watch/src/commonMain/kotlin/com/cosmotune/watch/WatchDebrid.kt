package com.cosmotune.watch

enum class WatchDebrid(val key: String, val label: String, val accountUrl: String) {
    REAL_DEBRID("realdebrid", "Real-Debrid", "https://real-debrid.com/apitoken"),
    ALL_DEBRID("alldebrid", "AllDebrid", "https://alldebrid.com/apikeys/"),
    TORBOX("torbox", "TorBox", "https://torbox.app/settings"),
}
