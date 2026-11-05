package com.cosmotune.music.playback

import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.PlaybackSourceType
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-05 from the page tap to the restored queue. Upstream calls the service's bounded path from `MainActivity.playFrom`; this
 * fork's activity did not, so the bounded contexts were dead code on Android. The first test walks what a tap now hands over,
 * through the same functions the activity and the service call (no session, so the Binder hop is the token alone); the second
 * pins the activity's and the service's calls, and fails on the activity as it was (no `playBoundedContext` anywhere).
 */
class BoundedContextEntryPointTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun code(path: String) = File(root, path).readText().replace("\r\n", "\n")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @After fun clear() = BoundedContextRequests.clear()

    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, setVideoId = "set-$id")

    @Test fun aTapOnAnAlbumRowReachesTheRegistryAndARestoredQueue() {
        val songs = List(5) { song("t$it") }
        val page = DetailPage("MPREb_album", "Album", "", null, UiState.Success(songs), BrowseType.ALBUM, continuation = "page-2")
        val source = QueueSource(page.title, PlaybackSourceType.BROWSE, page.browseId)

        // The activity: the plan, then the route playFrom takes with no party and no Cast receiver.
        val plan = requireNotNull(ReleasePagePlayback.plan(page, songs, 2, casting = false, latest = { page }))
        assertTrue(plan.bounded)
        assertEquals(ReleasePagePlayback.Route.BOUNDED, ReleasePagePlayback.route(plan.bounded, inParty = false, casting = false))

        // playBoundedContext: the request stays in the process, its token crosses the session.
        val token = putBoundedContextRequest(plan.songs, plan.index, source, plan.continuationToken, shuffle = false)

        // The service (startBoundedContext): takes the request once and registers the context.
        val request = requireNotNull(BoundedContextRequests.take(token))
        assertNull("a request is taken once", BoundedContextRequests.take(token))
        val contextSongs = request.songs.map { it.copy(queueTier = QueueTier.CONTEXT, playbackSource = source.title, playbackSourceType = source.type, playbackSourceId = source.id) }
        val registered = ContextRegistry().register(
            contextToken = "BROWSE:${source.id}:test",
            sourceKey = "BROWSE:${source.id}",
            browseId = source.id,
            songs = contextSongs,
            continuationToken = request.continuationToken,
        )
        assertEquals(songs.map { it.contextEntryKey() }, registered.songs.map { it.contextEntryKey() })
        assertEquals("page-2", registered.continuationToken)
        assertEquals(ContinuationState.AVAILABLE, registered.continuationState)

        // And the queue the service saves comes back after a restart starting on the tapped row.
        val window = QueueWindow(registered.songs.size).startAt(request.startIndex, shuffle = false)
        val entries = (request.startIndex until registered.songs.size).map { BoundedContextPersistence.Entry(registered.songs[it].copy(queueEntryId = "entry-$it"), it, window.cycleId) }
        val state = BoundedContextPersistence.State(context = registered, source = source, window = window.snapshot(), entries = entries, currentIndex = 0, positionMs = 1_000L)
        val restored = requireNotNull(BoundedContextPersistence.decode(requireNotNull(BoundedContextPersistence.encode(state)) { "the saved queue encodes" }))
        assertEquals("t2", restored.entries[restored.currentIndex].song.videoId)
        assertEquals("page-2", restored.context.continuationToken)
        assertEquals(source, restored.source)
    }

    @Test fun theActivityAndTheServiceAreWiredToThatPath() {
        val activity = code("app/src/main/java/com/cosmotune/music/MainActivity.kt")
        // The release page's taps and Shuffle go through the planner, with the page's newest snapshot.
        assertTrue("onSongClick = ::playDetailSongs" in activity)
        assertTrue("playDetailSongs(songs, songs.indices.random())" in activity)
        assertTrue("latest = viewModel::takePlaybackContext" in activity)
        assertTrue("boundedContext = plan.bounded" in activity && "continuationToken = plan.continuationToken" in activity)
        // playFrom routes with the Cast guard and calls the service's bounded path.
        assertTrue("casting = com.cosmotune.music.playback.cast.CastController.state.value.casting" in activity)
        assertTrue("c.playBoundedContext(songs, index, source, continuationToken)" in activity)
        val connection = code("app/src/main/java/com/cosmotune/music/playback/PlayerConnection.kt")
        assertTrue("val token = putBoundedContextRequest(songs, startIndex, source, continuationToken, shuffle)" in connection)
        assertTrue("SessionCommand(ACTION_START_BOUNDED_CONTEXT, Bundle.EMPTY)" in connection)
        val service = code("app/src/main/java/com/cosmotune/music/playback/PlaybackService.kt")
        assertTrue("ACTION_START_BOUNDED_CONTEXT -> startBoundedContext(args)" in service)
        val start = service.substringAfter("private fun startBoundedContext(args: Bundle) {").substringBefore("\n    private fun ")
        assertTrue("BoundedContextRequests.take(requestToken)" in start && "boundedContextRegistry.register(" in start)
        assertTrue("the service repeats the Cast guard", "CastController.state.value.casting" in start)
    }
}
