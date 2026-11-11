package com.opencanvas.core.stream

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Who may search right now. The song that is playing comes first: while its search runs, the searches for queued
 * songs (a prefetch) wait, one that is already on the wire is called off and asked again afterwards, and only one
 * prefetch page is ever in flight. A search page is about a megabyte and youtube.com answers a burst of them slowly,
 * then not at all, so the queue's lookups must never cost the playing song its video.
 *
 * Priority is a [StateFlow] because it can change while a lookup runs: a queued song that starts playing is promoted
 * and stops waiting at once.
 */
internal class SearchTraffic {
    private val foregroundFinds = MutableStateFlow(0)
    private val backgroundSlot = Mutex()

    /** How many searches for a playing song are running. */
    val foregroundActive: Int get() = foregroundFinds.value

    /** One search (all of its pages) under [foreground]'s priority; [close] it when the search ends. */
    fun lease(foreground: StateFlow<Boolean>): Lease = Lease(foreground)

    /** Waits until no playing song is searching, unless [foreground] is (or becomes) the playing song itself. */
    suspend fun awaitQuiet(foreground: StateFlow<Boolean>) {
        combine(foregroundFinds, foreground) { searching, playing -> playing || searching == 0 }.first { it }
    }

    inner class Lease internal constructor(private val foreground: StateFlow<Boolean>) {
        private var counted = false

        private fun countIfForeground(): Boolean {
            if (foreground.value && !counted) {
                counted = true
                foregroundFinds.update { it + 1 }
            }
            return counted
        }

        /** Runs one page request in this search's turn: at once for the playing song, otherwise when the way is clear. */
        suspend fun <T> page(fetch: suspend () -> T): T {
            while (true) {
                if (countIfForeground()) return fetch()
                awaitQuiet(foreground)
                if (countIfForeground()) return fetch()
                val done = backgroundSlot.withLock {
                    if (foregroundFinds.value > 0 && !foreground.value) null else preemptible(fetch)
                }
                if (done != null) {
                    @Suppress("UNCHECKED_CAST")
                    return done.value as T
                }
            }
        }

        /** The request, called off (null) the moment a playing song starts to search while this one is a prefetch. */
        private suspend fun <T> preemptible(fetch: suspend () -> T): Done? = coroutineScope {
            val fetching = async { Done(fetch()) }
            val preempted = async {
                combine(foregroundFinds, foreground) { searching, playing -> searching > 0 && !playing }.first { it }
            }
            select<Done?> {
                fetching.onAwait { answer -> preempted.cancel(); answer }
                preempted.onAwait { fetching.cancel(); null }
            }
        }

        fun close() {
            if (counted) foregroundFinds.update { it - 1 }
            counted = false
        }
    }

    private class Done(val value: Any?)

    companion object {
        /** The priority of a search made for the playing song. */
        val PLAYING: StateFlow<Boolean> = MutableStateFlow(true)
    }
}
