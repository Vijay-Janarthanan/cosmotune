package com.cosmotune.listening

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import kotlinx.serialization.json.Json
import kotlin.test.*
import org.junit.Test

class SmartPlaylistRulesTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400L
    private val songs = listOf(
        Song("a", "Alpha", "Ilaiyaraaja", null, "2:30", albumName = "Classics", localDateAddedSeconds = now / 1000 - 2 * day, downloadFormat = "FLAC"),
        Song("b", "Beta", "A. R. Rahman", null, "4:10", albumName = "Roja", isExplicit = true, localDateAddedSeconds = now / 1000 - 90 * day, downloadFormat = "opus"),
        Song("c", "Gamma", "Duet", null, "7:05", artists = listOf(ArtistRef("Shreya Ghoshal", null), ArtistRef("Ilaiyaraaja", null)), albumName = "Live classics", sourceHiRes = true),
        Song("d", "Delta", "Unknown", null, "not a time"),
    )
    private val library = ListeningLibrary(songs, likedIds = songs.map { it.videoId }.toSet(), recentIds = setOf("a"))
    private fun ids(rule: SmartPlaylist) = rule.songs(library, now).map { it.videoId }

    @Test fun textDurationDateExplicitAndLosslessRulesCombine() {
        val base = SmartPlaylist(name = "x")
        assertEquals(listOf("a", "c"), ids(base.copy(artistContains = "ilaiya")).sorted()) // any credited artist
        assertEquals(listOf("a", "c"), ids(base.copy(albumContains = "CLASSICS")).sorted())
        assertEquals(listOf("a"), ids(base.copy(duration = SmartDuration.SHORT)))
        assertEquals(listOf("b"), ids(base.copy(duration = SmartDuration.MEDIUM)))
        assertEquals(listOf("c"), ids(base.copy(duration = SmartDuration.LONG)))
        assertEquals(listOf("a"), ids(base.copy(addedWithinDays = 30)))
        assertFalse("b" in ids(base.copy(excludeExplicit = true)))
        assertEquals(listOf("a", "c"), ids(base.copy(losslessOnly = true)).sorted())
        assertEquals(listOf("a"), ids(base.copy(recentOnly = true)))
        assertEquals(listOf("c"), ids(base.copy(artistContains = "ilaiya", duration = SmartDuration.LONG)))
    }

    @Test fun ordersAndLimitsAreDeterministic() {
        val base = SmartPlaylist(id = "fixed", name = "x")
        assertEquals(listOf("c", "b", "a", "d"), ids(base.copy(order = SmartOrder.LONGEST)))
        assertEquals(listOf("a", "b", "c", "d"), ids(base.copy(order = SmartOrder.SHORTEST)))
        assertEquals(ids(base.copy(order = SmartOrder.SHUFFLE)), ids(base.copy(order = SmartOrder.SHUFFLE)))
        assertEquals(2, ids(base.copy(order = SmartOrder.TITLE, limit = 2)).size)
        assertEquals(listOf("a", "b"), ids(base.copy(order = SmartOrder.TITLE, limit = 2)))
        assertEquals(225L, SmartPlaylist.durationSeconds("3:45")); assertEquals(3723L, SmartPlaylist.durationSeconds("1:02:03"))
        assertNull(SmartPlaylist.durationSeconds("3:x")); assertNull(SmartPlaylist.durationSeconds("45"))
    }

    @Test fun rulesSavedByOlderVersionsKeepTheirMeaningAndInvalidRulesAreRejected() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(SmartPlaylist.serializer(),
            """{"id":"r","name":"Old","likedOnly":true,"downloadedOnly":false,"excludeRecent":true,"order":"TITLE","pinned":false}""")
        assertEquals(listOf("b", "c", "d"), ids(old).sorted())
        val store = ListeningStore(kotlin.io.path.createTempDirectory().resolve("l.json").toFile())
        assertFailsWith<IllegalArgumentException> { store.saveSmart(SmartPlaylist(name = "Bad", limit = 0)) }
        assertFailsWith<IllegalArgumentException> { store.saveSmart(SmartPlaylist(name = "Bad", addedWithinDays = -1)) }
        store.saveSmart(SmartPlaylist(name = "Good", artistContains = "Rahman", limit = 25))
        assertEquals("Rahman", store.state.value.smartPlaylists.single().artistContains)
    }
}
