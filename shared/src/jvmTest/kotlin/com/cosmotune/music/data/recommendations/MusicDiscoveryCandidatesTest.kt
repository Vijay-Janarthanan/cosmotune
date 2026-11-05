package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class MusicDiscoveryCandidatesTest {
    private val today = LocalDate.of(2026, 11, 2)
    private fun song(id: String, title: String = "A song", artist: String = "Singer") =
        Song(id, title, artist, null, durationText = "3:30", albumName = "Original album")
    private fun row(song: Song, origin: MusicCandidateOrigin = MusicCandidateOrigin.PROVIDER, rank: Int = 0) = MusicCandidate(song, origin, rank)

    @Test fun catalogueDuplicatesMergeButDifferentRecordingsStaySeparate() {
        val original = song("youtube")
        val copy = original.copy(videoId = "src:sflx-fixture::one", title = "A song (Official Audio)", artist = "Singer, Guest")
        val remix = original.copy(videoId = "remix", title = "A song (Remix)")
        val live = original.copy(videoId = "live", albumName = "Original album (Live)")
        val remaster = original.copy(videoId = "remaster", title = "A song (2025 Remastered)")
        val another = original.copy(videoId = "long", durationText = "4:00")
        val explicit = original.copy(videoId = "explicit", isExplicit = true)
        val clean = original.copy(videoId = "clean", isExplicit = false)
        assertTrue(MusicRecordingIdentity.same(row(original), row(copy)))
        for (different in listOf(remix, live, remaster, another)) assertFalse(MusicRecordingIdentity.same(row(original), row(different)), different.videoId)
        assertFalse(MusicRecordingIdentity.same(row(explicit), row(clean)))
        assertNotEquals(MusicRecordingIdentity.key(explicit), MusicRecordingIdentity.key(clean))
        assertEquals(5, MusicDiscoveryRanker.deduplicate(listOf(original, copy, remix, live, remaster, another).map { row(it) }).size)
    }

    @Test fun statedIsrcLinksTranslatedCataloguesWithoutCollapsingEditionsOrConflictingIdentifiers() {
        val left = row(song("one", "Original title")).copy(isrc = "INABC2600001")
        val right = row(song("two", "Completely different translation")).copy(isrc = "inabc2600001")
        assertTrue(MusicRecordingIdentity.same(left, right))
        assertFalse(MusicRecordingIdentity.same(left, right.copy(isrc = "INABC2600002")))
        assertFalse(MusicRecordingIdentity.same(left, right.copy(song = right.song.copy(title = "Translation (Live)"))))
        assertEquals(1, MusicDiscoveryRanker.deduplicate(listOf(left, right)).size)
        assertFalse(MusicRecordingIdentity.same(left.copy(isrc = null), right.copy(isrc = null)))
    }

    @Test fun playableLocalCopyWinsAndConflictingLanguageDoesNotBecomeACertainLabel() {
        val online = row(song("online")).copy(language = "ta", releasedOn = today)
        val local = row(song("file:local").copy(localPath = "/music/song.flac"), MusicCandidateOrigin.LIBRARY)
            .copy(language = "te", releasedOn = today.minusYears(2))
        val choice = MusicDiscoveryRanker.deduplicate(listOf(online, local)).single()
        assertEquals("file:local", choice.song.videoId)
        assertEquals(setOf("online", "file:local"), choice.alternatives)
        assertNull(choice.language)
        assertEquals(today.minusYears(2), choice.releasedOn)
    }

    @Test fun mixesRespectRecentRecordingAlternativesFeedbackFutureDatesAndArtistDiversity() {
        val library = (0 until 24).map { song("library-$it", "Song $it", "Singer ${it / 6}") }
        val recentAlias = library.first().copy(videoId = "recent-alternative")
        val context = MusicDiscoveryContext(library, listOf(recentAlias), setOf(library[6].videoId))
        val rows = library.mapIndexed { rank, song -> row(song, MusicCandidateOrigin.LIBRARY, rank) } + row(recentAlias, MusicCandidateOrigin.RECENT)
        val rediscover = MusicDiscoveryRanker.select(rows, context, MusicPlaylistKind.REDISCOVER, limit = 50, today = today,
            hidden = { it.videoId == library[1].videoId })
        assertFalse(rediscover.any { library.first().videoId in it.alternatives || it.song.videoId == library[1].videoId })
        assertTrue(rediscover.groupBy { it.song.artist }.values.all { it.size <= 3 })
        val future = row(song("future", "Tomorrow")).copy(releasedOn = today.plusDays(1))
        assertFalse(MusicDiscoveryRanker.select(rows + future, context, MusicPlaylistKind.DAILY_MIX, today = today).any { it.song.videoId == "future" })
        assertEquals(MusicDiscoveryRanker.select(rows, context, MusicPlaylistKind.DAILY_MIX, today = today),
            MusicDiscoveryRanker.select(rows, context, MusicPlaylistKind.DAILY_MIX, today = today))
        assertTrue(MusicDiscoveryRanker.select(rows, context, MusicPlaylistKind.FOLLOWED_RELEASES, today = today).isEmpty())
        assertTrue(MusicDiscoveryRanker.select(rows, context, MusicPlaylistKind.REGION, today = today).isEmpty())
    }

    @Test fun candidatePoolsRejectStaleLocalesDisabledProvidersAndStatedWrongLanguages() {
        val previous = MusicCandidateService.context()
        val region = DiscoveryRegion("IN", "ta")
        try {
            MusicCandidateService.updateContext(MusicDiscoveryContext())
            MusicCandidateService.begin(DiscoveryRegion("US", "en"), emptySet())
            MusicCandidateService.begin(region, setOf("fixture"))
            MusicCandidateService.ingest("provider:fixture", region, listOf(
                row(song("ta")).copy(provider = "fixture", language = "ta"),
                row(song("unknown")).copy(provider = "fixture"),
                row(song("te")).copy(provider = "fixture", language = "te"),
                row(song("disabled")).copy(provider = "other")))
            MusicCandidateService.ingest("stale", DiscoveryRegion("US", "en"), listOf(row(song("stale"))))
            assertEquals(setOf("ta", "unknown"), MusicCandidateService.snapshot(region).map { it.song.videoId }.toSet())
            MusicCandidateService.begin(region, emptySet())
            assertTrue(MusicCandidateService.snapshot(region).isEmpty())
        } finally {
            MusicCandidateService.begin(DiscoveryRegion("US", "en"), emptySet())
            MusicCandidateService.updateContext(previous)
        }
    }


    @Test fun offlinePreviewExcludesCachedOnlinePoolsButStillUsesPlayableLibraryRecordings() = runBlocking<Unit> {
        val previous = MusicCandidateService.context()
        val region = DiscoveryRegion("GB", "en")
        val local = song("local:one", "Local song")
        try {
            MusicCandidateService.begin(region, emptySet())
            MusicCandidateService.updateContext(MusicDiscoveryContext(library = listOf(local)))
            MusicCandidateService.ingest("cached-radio", region, listOf(row(song("online", "Cached song"), MusicCandidateOrigin.RADIO)))
            val preview = MusicCandidateService.preview(MusicPlaylistKind.DAILY_MIX, null, 10, false, region)
            assertEquals(listOf(local.videoId), preview.songs.map { it.videoId })
        } finally {
            MusicCandidateService.begin(DiscoveryRegion("US", "en"), emptySet())
            MusicCandidateService.updateContext(previous)
        }
    }

    @Test fun candidateAndSelectionBudgetsStayBoundedForLargeCatalogues() {
        val rows = (0 until 3_000).map { row(song("song-$it", "Song $it", "Artist $it")) }
        assertEquals(2_000, MusicDiscoveryRanker.deduplicate(rows).size)
        assertEquals(50, MusicDiscoveryRanker.select(rows, MusicDiscoveryContext(), MusicPlaylistKind.DAILY_MIX, limit = 5_000, today = today).size)
    }
}
