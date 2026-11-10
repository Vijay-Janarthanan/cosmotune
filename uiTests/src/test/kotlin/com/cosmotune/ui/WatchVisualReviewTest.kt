package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchPlayback
import com.cosmotune.ui.watch.WatchRoute
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerSnapshot
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.SubtitleCue
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.WatchPlayer
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.model.AudioTrack
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.Episode
import com.cosmotune.watch.model.PlayTarget
import com.cosmotune.watch.model.Stream
import com.cosmotune.watch.model.StreamHint
import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import com.cosmotune.watch.model.WatchKey
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Watch mode's screens (Canvas repo, design/cosmotune/watch-orbit/board.html) on the in-memory repository, drawn at phone width
 * and at the board's page width in the dark and the light palettes, written under build/watch-review for a look.
 */
class WatchVisualReviewTest {
    @get:Rule val compose = createComposeRule()

    private val evening = 1_762_461_600_000L

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null); val previousUi = uiField.get(null)
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi); TimeZone.setDefault(zone); WatchNav.go(WatchTab.HOME) }
    }

    private class Size(val name: String, val width: Int, val height: Int)
    private val phone = Size("phone", 390, 844)
    private val wide = Size("wide", 1004, 780)

    private fun page(dark: Boolean, size: Size, repository: FakeWatchRepository, playback: WatchPlayback? = null) {
        val palette = if (dark) CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true) else CosmoPaletteState(com.cosmotune.theme.OnePalette.of(false, false), false)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(size.width.dp, size.height.dp).testTag("watch:review").background(Color(colors.palette.backgroundBottom))) {
                        ProvideWatch(repository = repository, playback = playback, clock = { evening }) {
                            WatchApp(showNavigation = size === phone)
                        }
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val file = File("../build/watch-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("watch:review").captureToImage().toAwtImage(), "png", file)
    }

    private val film = TitleSummary(WatchKey.tmdbFilm(2), TitleType.FILM, "Ember Road", 2021, rating = 7.4, genres = listOf("Drama"), runtimeMin = 112,
        overview = "Two sisters drive across a burning valley to reach their father before the roads close.")
    private val series = TitleSummary(WatchKey.tmdbSeries(101), TitleType.SERIES, "Harbour Town", 2019, rating = 8.1, runtimeMin = 3)

    private fun each(block: (dark: Boolean, size: Size, tag: String) -> Unit) {
        for (dark in listOf(true, false)) for (size in listOf(phone, wide)) block(dark, size, "${size.name}-${if (dark) "dark" else "light"}")
    }

    @Test fun watchPagesInDarkAndLight() = hosts {
        each { dark, size, tag ->
            val repository = FakeWatchRepository()
            WatchNav.go(WatchTab.HOME); page(dark, size, repository); capture("home-$tag")
            WatchNav.go(WatchTab.FILMS); WatchNav.openTitle(film); page(dark, size, repository); capture("film-$tag")
            WatchNav.go(WatchTab.SERIES); WatchNav.openTitle(series); page(dark, size, repository); capture("series-$tag")
            WatchNav.go(WatchTab.LIVE); page(dark, size, repository); capture("live-$tag")
            WatchNav.go(WatchTab.PLUGINS); page(dark, size, repository); capture("plugins-$tag")
            WatchNav.go(WatchTab.MY_LIST); page(dark, size, repository); capture("mylist-$tag")
            WatchNav.go(WatchTab.SEARCH); page(dark, size, repository)
            compose.onNode(hasSetTextAction()).performTextInput("the")
            capture("search-$tag")
        }
    }

    @Test fun watchPlayerInDarkAndLight() = hosts {
        each { dark, size, tag ->
            val repository = FakeWatchRepository()
            val player = StillPlayer(repository)
            val playback = WatchPlayback(player) { _, modifier ->
                Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF2A3A5C), Color(0xFF0E1424), Color(0xFFC9A24A)))))
            }
            val episode = runBlocking { repository.episodes(series.key, 2)[3] }
            WatchNav.go(WatchTab.SERIES)
            WatchNav.open(WatchRoute.Play(PlayTarget.EpisodeOf(episode.key, series.ids, series.name, 2, 4, episodeName = episode.name), series, episode = episode))
            page(dark, size, repository, playback)
            capture("player-$tag")
        }
    }

    @Test fun liveTvShowsFullscreenAndTransportControlsAtPhoneWidth() = hosts {
        val repository = FakeWatchRepository()
        val channel = Channel(WatchKey.channel("test", "live"), "test", "Live channel")
        WatchNav.go(WatchTab.LIVE)
        WatchNav.playChannel(channel)
        page(true, phone, repository, WatchPlayback(StillPlayer(repository)) { _, modifier ->
            Box(modifier.background(Color.Black))
        })
        compose.onNodeWithContentDescription("Mute").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jump to live").assertIsDisplayed()
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Exit full screen").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Full screen").assertIsDisplayed()
    }

    @Test fun fullscreenPictureDismissesControlsAndSkipButtonsClampToTheVideo() = hosts {
        val repository = FakeWatchRepository()
        val player = StillPlayer(repository)
        WatchNav.go(WatchTab.FILMS)
        WatchNav.open(WatchRoute.Play(PlayTarget.Film(film.key, film.ids, film.name), film))
        page(true, phone, repository, WatchPlayback(player) { _, modifier -> Box(modifier.background(Color.Black)) })
        compose.onNodeWithContentDescription("Full screen").performClick()
        val rewind = compose.onNodeWithContentDescription("Back 30 seconds")
        val forward = compose.onNodeWithContentDescription("Forward 30 seconds")
        rewind.performClick()
        assertEquals(1_470_000L, player.engine.positionMs())
        forward.performClick()
        assertEquals(1_500_000L, player.engine.positionMs())
        compose.runOnIdle { player.engine.seekTo(4_000L) }
        rewind.performClick()
        assertEquals(0L, player.engine.positionMs())
        compose.runOnIdle { player.engine.seekTo(49 * 60_000L - 4_000L) }
        forward.performClick()
        assertEquals(49 * 60_000L, player.engine.positionMs())
        val picture = compose.onNodeWithTag("watch:cinema:picture")
        picture.performTouchInput { click(center) }
        rewind.assertDoesNotExist()
        picture.performTouchInput { click(center) }
        rewind.assertIsDisplayed()
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("4K").performClick()
        compose.onNodeWithText("58.0 GB", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Plex · Free with ads").assertDoesNotExist()
    }

    @Test fun filmPlaybackShowsPlotRatingCastAndRelatedTitlesBelowTheControls() = hosts {
        val repository = FakeWatchRepository()
        val player = StillPlayer(repository)
        WatchNav.go(WatchTab.FILMS)
        WatchNav.open(WatchRoute.Play(PlayTarget.Film(film.key, film.ids, film.name), film))
        page(true, phone, repository, WatchPlayback(player) { _, modifier -> Box(modifier.background(Color.Black)) })
        compose.onNodeWithText("About this film", ignoreCase = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("7.4", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Two sisters drive across", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cast").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("More like this").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Asha Varma").assertIsDisplayed()
        assertEquals(1_500_000L, player.engine.positionMs())
    }
}

/** A player that is paused 25 minutes into S2 E4 with the next episode counting down: the screen's state without an engine. */
private class StillPlayer(private val repository: FakeWatchRepository) : WatchPlayer {
    override val engine: VideoPlayerEngine = StillEngine()
    private val flow = MutableStateFlow(WatchPlayerState())
    override val state: StateFlow<WatchPlayerState> = flow

    override fun open(target: PlayTarget, summary: TitleSummary?, stream: Stream?, startMs: Long?, hint: StreamHint?, fallback: Stream?) {
        val streams = runBlocking { repository.sourcesNow(target).streams }
        val next = runBlocking { repository.nextEpisode(target.key) }
        flow.value = WatchPlayerState(
            status = WatchPlayerStatus.PAUSED, target = target, summary = summary, subtitleLine = "S2 · E4 · Low Water", stream = streams.firstOrNull(),
            streams = streams, durationMs = 49 * 60_000L,
            audioTracks = listOf(AudioTrack("a1", "eng", "English 5.1", selected = true), AudioTrack("a2", "hin", "Hindi stereo")),
            subtitles = listOf(Subtitle("s1", language = "eng", label = "English")), selectedSubtitleId = "s1",
            nextEpisode = next as Episode?, nextEpisodeInMs = 8_000, isLive = target is PlayTarget.Live,
        )
    }
    override fun switchStream(stream: Stream) {}
    override fun selectAudio(trackId: String) {}
    override fun selectSubtitle(id: String?) {}
    override fun searchSubtitles(languages: List<String>) {}
    override fun setSubtitleDelay(ms: Long) {}
    override fun playNextEpisode() {}
    override fun cancelNextEpisode() {}
    override fun stop() {}
}

private class StillEngine : VideoPlayerEngine {
    override val capabilities = EngineCapabilities(emptyList(), opus = true, hdrDisplay = false, displayHeightPx = 1080, sideloadedDash = false, autoCeilingHeight = null)
    override val snapshot: StateFlow<PlayerSnapshot> = MutableStateFlow(PlayerSnapshot.Idle.copy(status = PlayerStatus.READY, durationMs = 49 * 60_000L, videoWidth = 1920, videoHeight = 1080))
    override val cues: StateFlow<List<SubtitleCue>> = MutableStateFlow(emptyList())
    private var position = 25 * 60_000L
    override fun positionMs(): Long = position
    override fun load(request: PlaybackRequest) {}
    override fun play() {}
    override fun pause() {}
    override fun seekTo(ms: Long) { position = ms }
    override fun setSpeed(speed: Float) {}
    override fun setVolume(volume: Float) {}
    override fun setMuted(muted: Boolean) {}
    override fun setAudioOnly(audioOnly: Boolean) {}
    override fun selectSubtitle(language: String?) {}
    override fun release() {}
}
