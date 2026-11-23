package com.cosmotune.music.data.sources

import com.cosmotune.music.data.MonotonicClock
import com.cosmotune.music.data.model.Song

/**
 * Uploads found gone (YouTube's "This video is not available" on every client), and the copy of the same song that
 * played instead, for [ttlMs].
 *
 * The resolver's own verdict lasts ten minutes, which is right for the refusals that soften (an age gate, a bot check);
 * a removed upload does not come back, and asking it first again costs the listener another nine silent seconds
 * before the stand-in search even starts. So a song found gone goes straight to the copy that played for it last
 * time, or to the search for one, and the dead upload is asked only after both.
 */
class UnavailableCopies(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = { MonotonicClock.nowMs() },
    private val capacity: Int = 200,
) {
    private class Entry(val at: Long, val standIn: Song?)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized fun markGone(id: String) {
        val kept = live(id)?.standIn
        entries.remove(id)
        entries[id] = Entry(now(), kept)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun isGone(id: String): Boolean = live(id) != null

    @Synchronized fun standInFor(id: String): Song? = live(id)?.standIn

    @Synchronized fun rememberStandIn(id: String, standIn: Song) {
        val at = live(id)?.at ?: now()
        entries[id] = Entry(at, standIn)
    }

    @Synchronized fun forgetStandIn(id: String) {
        live(id)?.let { entries[id] = Entry(it.at, null) }
    }

    private fun live(id: String): Entry? {
        val entry = entries[id] ?: return null
        if (now() - entry.at < ttlMs) return entry
        entries.remove(id)
        return null
    }

    companion object {
        /** Long enough to cover a listening session; short enough that an upload put back is heard again the next day. */
        const val DEFAULT_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
