package com.cosmotune.watch.model

import kotlinx.serialization.Serializable

@Serializable
data class WatchProfile(
    val id: String,
    val name: String,
    val progress: Map<String, WatchProgress> = emptyMap(),
    val myList: List<MyListEntry> = emptyList(),
    val locked: Boolean = false,
    val playlists: List<WatchPlaylist> = emptyList(),
    val preferences: com.cosmotune.watch.WatchPreferences? = null,
)
