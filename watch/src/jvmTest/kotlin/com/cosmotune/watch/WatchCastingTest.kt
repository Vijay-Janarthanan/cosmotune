package com.cosmotune.watch

import com.cosmotune.music.data.cast.dlna.DlnaDevice
import com.cosmotune.music.data.cast.dlna.DlnaRenderer
import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.watch.model.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*
import okhttp3.mockwebserver.MockResponse

/**
 * Cast sessions against a loopback DLNA renderer: the real [DlnaRenderer] SOAP calls, answered by
 * a fake TV. No physical receiver is involved, so this proves the session lifecycle, not that any
 * particular TV plays a given copy.
 */
class WatchCastingTest {
    private val film = PlayTarget.Film(WatchKey.tmdbFilm(7), ExternalIds(tmdb = 7), "Night of the Living Dead", 1968)
    private val summary = TitleSummary(film.key, TitleType.FILM, film.name)
    private val source = StreamSource("ia", "Internet Archive", "IA")
    private val copy = Stream(source, "720p", url = "https://example.invalid/film.mp4", protocol = StreamProtocol.PROGRESSIVE, container = Container.MP4)
    private val otherCopy = copy.copy(label = "1080p", url = "https://example.invalid/film-1080.mp4")

    private class Player(initial: WatchPlayerState) : WatchPlayer {
        override val engine = FakeVideoPlayerEngine()
        val mutable = MutableStateFlow(initial)
        override val state: StateFlow<WatchPlayerState> = mutable
        val remote = CopyOnWriteArrayList<Boolean>()
        override fun setRemotePlayback(active: Boolean) { remote += active }
        override fun open(target: PlayTarget, summary: TitleSummary?, stream: Stream?, startMs: Long?, hint: StreamHint?, fallback: Stream?) {}
        override fun switchStream(stream: Stream) {}
        override fun selectAudio(trackId: String) {}
        override fun selectSubtitle(id: String?) {}
        override fun searchSubtitles(languages: List<String>) {}
        override fun setSubtitleDelay(ms: Long) {}
        override fun playNextEpisode() {}
        override fun cancelNextEpisode() {}
        override fun stop() {}
    }

    private class Repository(private val fake: WatchRepository = FakeWatchRepository()) : WatchRepository by fake {
        val saved = CopyOnWriteArrayList<WatchProgress>()
        @Volatile var leasesClosed = 0
        override suspend fun saveProgress(progress: WatchProgress) { saved += progress }
        override suspend fun openPlayback(stream: Stream) = WatchPlaybackLease(stream.url) { leasesClosed++ }
    }

    /** A TV that answers AVTransport over SOAP and records the actions it was sent. */
    private class Tv : AutoCloseable {
        val server = TestServer()
        val actions = CopyOnWriteArrayList<String>()
        @Volatile var alive = true
        init {
            server.on("/av") { request ->
                val action = request.getHeader("SOAPACTION").orEmpty().trim('"').substringAfter('#')
                actions += action
                if (!alive) return@on MockResponse().setResponseCode(500)
                MockResponse().setBody(when (action) {
                    "GetTransportInfo" -> "<s:Envelope><CurrentTransportState>PLAYING</CurrentTransportState></s:Envelope>"
                    "GetPositionInfo" -> "<s:Envelope><RelTime>0:00:42</RelTime><TrackDuration>1:30:00</TrackDuration></s:Envelope>"
                    else -> "<s:Envelope/>"
                })
            }
        }
        fun device(id: String = "tv") = DlnaDevice(id, "Living room TV", "Fake", server.url("/av"), null)
        fun commands() = actions.filter { it != "GetTransportInfo" && it != "GetPositionInfo" }
        override fun close() = server.close()
    }

    private fun playing(stream: Stream = copy) =
        WatchPlayerState(WatchPlayerStatus.PLAYING, film, summary, stream = stream, streams = listOf(copy, otherCopy), durationMs = 5_400_000)

    private fun casting(player: Player, repository: Repository) = WatchCasting(player, repository, receiverOf = { DlnaRenderer(it, pollMs = 40) })

    @Test fun stopCastingSavesWhereTheReceiverGotAndResumesThere() = runBlocking {
        Tv().use { tv ->
            val player = Player(playing())
            val repository = Repository()
            val casting = casting(player, repository)
            casting.connect(tv.device())
            assertEquals(listOf(true), player.remote.toList())
            assertTrue(player.engine.calls.any { it is FakeVideoPlayerEngine.Call.Pause })
            withTimeout(5_000) { casting.renderer.value!!.state.first { it.positionMs == 42_000L } }
            casting.disconnect()
            assertNull(casting.renderer.value)
            assertEquals(false, player.remote.last())
            // The progress is saved, and the lease closed, off the caller's thread: wait for them as the next test does.
            withTimeout(5_000) { while (repository.saved.isEmpty() || repository.leasesClosed == 0) delay(10) }
            assertEquals(42_000L, repository.saved.last().positionMs)
            assertTrue(player.engine.calls.contains(FakeVideoPlayerEngine.Call.SeekTo(42_000)))
            assertEquals("Stop", tv.commands().last())
            assertEquals(1, repository.leasesClosed)
            casting.close()
        }
    }

    @Test fun anotherCopyOnThePlayerEndsTheCastInsteadOfLeavingTheOldOnePlaying() = runBlocking {
        Tv().use { tv ->
            val player = Player(playing())
            val repository = Repository()
            val casting = casting(player, repository)
            casting.connect(tv.device())
            withTimeout(5_000) { casting.renderer.value!!.state.first { it.positionMs == 42_000L } }
            // Retry, next copy or a channel change: the player now shows something else.
            player.mutable.update { it.copy(stream = otherCopy) }
            withTimeout(5_000) { casting.renderer.first { it == null } }
            withTimeout(5_000) { while (repository.leasesClosed == 0) delay(10) }
            assertEquals(false, player.remote.last())
            val commands = tv.commands()
            assertTrue("Stop" in commands.drop(commands.lastIndexOf("SetAVTransportURI") + 1), "commands $commands")
            // The receiver's position belongs to the old copy, so the new one is not moved to it.
            assertFalse(player.engine.calls.any { it is FakeVideoPlayerEngine.Call.SeekTo })
            casting.close()
        }
    }

    @Test fun aReceiverThatLeavesTheNetworkEndsTheSession() = runBlocking {
        Tv().use { tv ->
            val player = Player(playing())
            val casting = casting(player, Repository())
            casting.connect(tv.device())
            tv.alive = false
            withTimeout(5_000) { casting.renderer.first { it == null } }
            withTimeout(5_000) { while (player.remote.lastOrNull() != false) delay(10) }
            casting.close()
        }
    }

    @Test fun aLateCloseFromALeftPageNeverEndsTheCastThatReplacedIt() = runBlocking {
        Tv().use { tv ->
            val player = Player(playing())
            val old = casting(player, Repository())
            val current = casting(player, Repository())
            old.connect(tv.device())
            current.connect(tv.device())
            val stopsBefore = tv.actions.count { it == "Stop" }
            old.close()
            withTimeout(5_000) { old.renderer.first { it == null } }
            delay(200)
            assertEquals(true, player.remote.last(), "the newer cast still owns the player")
            assertNotNull(current.renderer.value)
            assertEquals(stopsBefore, tv.actions.count { it == "Stop" }, "the shared TV was not stopped under the newer cast")
            current.disconnect()
            assertEquals(false, player.remote.last())
            current.close()
        }
    }

    @Test fun aCopyThatNeedsHeadersIsRefusedBeforeAnyNetwork() = runBlocking {
        Tv().use { tv ->
            val player = Player(playing(copy.copy(headers = mapOf("Referer" to "https://example.invalid"))))
            val casting = casting(player, Repository())
            assertFailsWith<IllegalArgumentException> { casting.connect(tv.device()) }
            assertTrue(tv.actions.isEmpty())
            assertTrue(player.remote.isEmpty())
            casting.close()
        }
    }
}
