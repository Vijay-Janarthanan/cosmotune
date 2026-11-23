package com.cosmotune.podio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The song a playing station names in its own stream, as the player reads it: no second connection is needed to show it.
 * A player that reads the titles publishes them here; the radio screen asks the stream itself only when nothing comes.
 */
object PodioLiveTitle {
    data class Heard(val url: String, val title: String, val atMs: Long)

    private val _current = MutableStateFlow<Heard?>(null)
    val current: StateFlow<Heard?> = _current.asStateFlow()

    fun publish(url: String, title: String, atMs: Long = System.currentTimeMillis()) {
        val clean = title.trim().takeIf { it.isNotEmpty() } ?: return
        _current.value = Heard(url, clean, atMs)
    }

    /** The title last heard on [url] within [freshMs], or null. */
    fun recent(url: String, nowMs: Long = System.currentTimeMillis(), freshMs: Long = 90_000): String? =
        _current.value?.takeIf { same(it.url, url) && nowMs - it.atMs <= freshMs }?.title

    fun same(a: String, b: String) = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)
}
