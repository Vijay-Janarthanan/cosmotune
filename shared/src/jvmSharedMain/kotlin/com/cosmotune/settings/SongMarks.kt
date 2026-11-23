package com.cosmotune.settings

/**
 * A moment kept in a song (Bookmarks in the player menu): where it starts, where its part ends when it is one, and a name.
 * Kept per recording, music only, beside its own speed and key.
 */
data class SongMark(val startMs: Long, val endMs: Long? = null, val label: String = "")

/** A–B repeat: the song goes back to [startMs] each time it reaches [endMs]. While [endMs] is null, A is set and B is awaited. */
data class AbLoop(val songId: String, val startMs: Long, val endMs: Long?) {
    /** Where to seek to when the song [playingId] is at [positionMs], or null to let it play on. */
    fun seekTarget(playingId: String?, positionMs: Long): Long? =
        if (endMs != null && playingId == songId && positionMs >= endMs) startMs else null

    companion object {
        const val SHORTEST_MS = 1_000L
        /** The loop between two points in either order, or null when they are closer than a second. */
        fun of(songId: String, a: Long, b: Long): AbLoop? {
            val start = minOf(a, b).coerceAtLeast(0); val end = maxOf(a, b)
            return if (end - start < SHORTEST_MS) null else AbLoop(songId, start, end)
        }
    }
}

object SongMarks {
    const val PER_SONG = 30
    private const val SONGS = 200
    private const val MAX_CHARS = 60_000

    fun decode(raw: String): Map<String, List<SongMark>> {
        val out = linkedMapOf<String, MutableList<SongMark>>()
        raw.take(MAX_CHARS).lineSequence().forEach { line ->
            val f = line.split('\t')
            if (f.size < 3 || !valid(f[0])) return@forEach
            val start = f[1].toLongOrNull()?.takeIf { it >= 0 } ?: return@forEach
            val end = f[2].toLongOrNull()?.takeIf { it - start >= AbLoop.SHORTEST_MS }
            out.getOrPut(f[0]) { mutableListOf() } += SongMark(start, end, clean(f.getOrNull(3).orEmpty()))
        }
        return out.entries.toList().takeLast(SONGS).associate { (id, marks) -> id to marks.sortedBy { it.startMs }.takeLast(PER_SONG) }
    }

    fun encode(map: Map<String, List<SongMark>>): String = map.entries.joinToString("\n") { (id, marks) ->
        marks.joinToString("\n") { "$id\t${it.startMs}\t${it.endMs ?: ""}\t${clean(it.label)}" }
    }

    /** [map] with [mark] added to [id]'s marks; the song moves to the newest end, and the oldest marks and songs go past the limits. */
    fun add(map: Map<String, List<SongMark>>, id: String, mark: SongMark): Map<String, List<SongMark>> {
        if (!valid(id)) return map
        val mine = (map[id].orEmpty() + mark.copy(label = clean(mark.label))).takeLast(PER_SONG).sortedBy { it.startMs }
        return put(map, id, mine)
    }

    fun remove(map: Map<String, List<SongMark>>, id: String, marks: Collection<SongMark>): Map<String, List<SongMark>> =
        put(map, id, map[id].orEmpty() - marks.toSet())

    private fun put(map: Map<String, List<SongMark>>, id: String, marks: List<SongMark>): Map<String, List<SongMark>> {
        val out = LinkedHashMap(map); out.remove(id)
        if (marks.isNotEmpty()) out[id] = marks
        while (out.size > SONGS || (out.size > 1 && encode(out).length > MAX_CHARS)) out.remove(out.keys.first())
        return out
    }

    /** "1:05", or "1:02:03" past an hour. */
    fun time(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun clean(label: String) = label.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(60)
    private fun valid(id: String) = SongPlaybackMemory.isMusic(id) && id.length <= 512 && id.none { it == '\t' || it == '\n' || it == '\r' }
}
