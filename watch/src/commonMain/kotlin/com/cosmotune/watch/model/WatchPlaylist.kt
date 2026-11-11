package com.cosmotune.watch.model

import kotlinx.serialization.Serializable

@Serializable
data class WatchPlaylist(val id: String, val name: String, val items: List<TitleSummary> = emptyList(), val updatedAtMs: Long = 0)
