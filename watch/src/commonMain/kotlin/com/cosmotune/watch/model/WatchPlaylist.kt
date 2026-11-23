package com.cosmotune.watch.model

import kotlinx.serialization.Serializable

/** A collection of films and series, in the order the listener set, with the cover they chose ([posterUrl]) or its first title's poster. */
@Serializable
data class WatchPlaylist(val id: String, val name: String, val items: List<TitleSummary> = emptyList(), val updatedAtMs: Long = 0, val posterUrl: String? = null) {
    val cover: String? get() = posterUrl ?: items.firstOrNull()?.posterUrl

    companion object {
        /** [list] with the entry at [from] moved to [to] (both held to the list). */
        fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
            if (from !in list.indices) return list
            val target = to.coerceIn(0, list.lastIndex)
            if (target == from) return list
            return list.toMutableList().apply { add(target, removeAt(from)) }
        }

        /** A cover address a listener typed: a web picture address, or null for anything else. */
        fun posterOf(url: String): String? = url.trim().takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 2_000 && it.none { c -> c.isWhitespace() } }
    }
}
