package com.cosmotune.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongMarksTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test fun marksSurviveARoundTripAndBadLinesAreDropped() {
        val marks = mapOf("song:a" to listOf(SongMark(90_000, null, "Drop"), SongMark(10_000, 25_000, "Intro\tpart")))
        val again = SongMarks.decode(SongMarks.encode(marks))
        // Sorted by start, a tab in a name becomes a space.
        assertEquals(listOf(SongMark(10_000, 25_000, "Intro part"), SongMark(90_000, null, "Drop")), again["song:a"])
        assertEquals(emptyMap(), SongMarks.decode("song:b\tx\t\t\npodio:episode:1\t5\t\tA"))
        // An end before its start is not a part.
        assertNull(SongMarks.decode("song:c\t9000\t2000\tA")["song:c"]!!.single().endMs)
    }

    @Test fun aSongKeepsOnlyItsNewestThirtyMarks() {
        var map = emptyMap<String, List<SongMark>>()
        repeat(40) { map = SongMarks.add(map, "song:a", SongMark(it * 1_000L)) }
        assertEquals(30, map["song:a"]!!.size)
        assertEquals(10_000, map["song:a"]!!.first().startMs)
        assertEquals(emptyMap(), SongMarks.remove(map, "song:a", map["song:a"]!!).let { left -> left.filterValues { it.isNotEmpty() } })
    }

    @Test fun theLoopGoesBackToItsStartOnlyForItsOwnSong() {
        val loop = AbLoop("song:a", 30_000, 45_000)
        assertNull(loop.seekTarget("song:a", 44_900))
        assertEquals(30_000, loop.seekTarget("song:a", 45_000))
        assertNull(loop.seekTarget("song:b", 50_000))
        // Waiting for its end, it never jumps.
        assertNull(AbLoop("song:a", 30_000, null).seekTarget("song:a", 90_000))
        // Ends are put in order and a part shorter than a second is refused.
        assertEquals(AbLoop("song:a", 30_000, 45_000), AbLoop.of("song:a", 45_000, 30_000))
        assertNull(AbLoop.of("song:a", 30_000, 30_400))
    }

    @Test fun anotherSongEndsTheLoop() {
        val settings = ExtrasSettings(MapStore())
        settings.playingSongId = "song:a"
        settings.setAbLoop(AbLoop("song:a", 1_000, 5_000))
        settings.playingSongId = "song:a"
        assertEquals(5_000, settings.abLoop.value?.endMs)
        settings.playingSongId = "song:b"
        assertNull(settings.abLoop.value)
    }

    @Test fun marksAreKeptInTheStore() {
        val store = MapStore()
        ExtrasSettings(store).addSongMark("song:a", SongMark(61_000, 75_500, "Chorus"))
        assertEquals(listOf(SongMark(61_000, 75_500, "Chorus")), ExtrasSettings(store).songMarks.value["song:a"])
        assertEquals("1:01", SongMarks.time(61_000))
        assertEquals("1:02:03", SongMarks.time(3_723_000))
        assertTrue(SongMarks.time(-5) == "0:00")
    }
}
