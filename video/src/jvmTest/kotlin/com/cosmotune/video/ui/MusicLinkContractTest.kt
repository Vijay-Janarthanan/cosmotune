package com.cosmotune.video.ui

import com.cosmotune.ui.modes.ModeLinks
import com.cosmotune.ui.modes.musicVideoLink
import com.cosmotune.ui.modes.musicVideoMomentMs
import com.music.bitchord.data.canvas.CanvasArtwork
import com.opencanvas.core.sync.SyncMap
import com.opencanvas.core.sync.SyncSegment
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link between Music and Video (plan task Y-26, spec section 9): "Watch the music video" in Music's Now Playing, "Play as a song"
 * on the desktop, the music video's id carried from OpenCanvas to the player, and the seam (Music never names Video). The decision
 * and the moment are run; what needs a window is pinned by reading the source.
 */
class MusicLinkContractTest {
    private val controls = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingControls.kt"
    private val nowPlaying = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/CosmoNowPlaying.kt"
    private val wide = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingWide.kt"
    private val modeLinks = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/modes/ModeLinks.kt"
    private val phoneHost = "app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt"
    private val desktopHost = "desktopApp/src/main/kotlin/com/cosmotune/video/host/DesktopModeHost.kt"
    private val desktopApp = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt"
    private val canvasArtwork = "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/CanvasArtwork.kt"
    private val openCanvas = "shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/OpenCanvasProvider.kt"
    private val desktopCanvas = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvas.kt"
    private val desktopCanvasVideo = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvasVideo.kt"
    private val desktopCanvasSources = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvasSources.kt"

    private fun read(path: String) = VideoSourceContract.read(path)

    /** The file without comments, white space collapsed to single spaces. */
    private fun flat(path: String) = VideoSourceContract.code(read(path)).replace(Regex("\\s+"), " ")

    private fun body(path: String, name: String) = VideoSourceContract.functionBody(read(path), name).replace(Regex("\\s+"), " ")

    private fun count(text: String, what: String) = Regex(Regex.escape(what)).findAll(text).count()

    /** A recorder of what the hosts are asked. */
    private class Recorder : ModeLinks {
        val asked = ArrayList<List<Any?>>()
        override fun watchMusicVideo(videoId: String, title: String, artist: String, atVideoMs: Long?) {
            asked += listOf(videoId, title, artist, atVideoMs)
        }
    }

    private val synced = CanvasArtwork(
        url = "https://example.invalid/clip.mp4",
        syncMap = SyncMap.constant(2_000L),
        videoDurationMs = 200_000L,
        videoId = "dQw4w9WgXcQ",
    )

    // ---- The decision: the tool exists only behind the three conditions --------------------------------------------------------

    @Test fun theToolNeedsTheLinksTheMusicVideosIdAndItsMap() {
        val links = Recorder()
        assertNull(musicVideoLink(null, synced, "Song", "Artist") { 0L }, "no links: Video is not available (or the look is off)")
        assertNull(musicVideoLink(links, null, "Song", "Artist") { 0L }, "no canvas")
        assertNull(musicVideoLink(links, synced.copy(videoId = null), "Song", "Artist") { 0L }, "a clip with no music video id (every source but OpenCanvas)")
        assertNull(musicVideoLink(links, synced.copy(syncMap = null), "Song", "Artist") { 0L }, "a looping clip, not a video that follows the song")
        assertNotNull(musicVideoLink(links, synced, "Song", "Artist") { 0L }, "all three: the tool is drawn")
        assertEquals(emptyList(), links.asked, "deciding asks nothing of the host")
    }

    @Test fun theToolAsksForTheVideoAtTheSongsMomentReadWhenItIsPressed() {
        val links = Recorder()
        var songMs = 1_000L
        val action = assertNotNull(musicVideoLink(links, synced, "Song", "Artist") { songMs })
        songMs = 61_000L
        action()
        assertEquals(listOf(listOf<Any?>("dQw4w9WgXcQ", "Song", "Artist", 63_000L)), links.asked, "the position when pressed, plus the offset")
        songMs = 199_000L
        action()
        assertEquals(listOf<Any?>("dQw4w9WgXcQ", "Song", "Artist", null), links.asked.last(), "past the video's end: no moment, the video opens as it was left")
    }

    @Test fun theMomentIsThePolicysTargetAndNullOutsideTheVideo() {
        assertEquals(12_000L, musicVideoMomentMs(SyncMap.constant(2_000L), 200_000L, 10_000L))
        assertNull(musicVideoMomentMs(SyncMap.constant(-5_000L), 200_000L, 1_000L), "before the video's start")
        assertEquals(0L, musicVideoMomentMs(SyncMap.constant(-5_000L), 200_000L, 5_000L), "its first frame")
        assertNull(musicVideoMomentMs(SyncMap.constant(2_000L), 200_000L, 198_000L), "at its end")
        assertEquals(199_999L, musicVideoMomentMs(SyncMap.constant(2_000L), 200_000L, 197_999L), "its last moment")
        assertEquals(1_002_000L, musicVideoMomentMs(SyncMap.constant(2_000L), 0L, 1_000_000L), "an unknown length bounds only the start")
        assertNull(musicVideoMomentMs(SyncMap(emptyList()), 200_000L, 10_000L), "a map with no segment has no moment")
        // An edit of the song: the offset of the segment in force, and of the nearest one where the song has no picture.
        val edit = SyncMap(listOf(SyncSegment(0L, 30_000L, 4_000L), SyncSegment(40_000L, 180_000L, 9_000L)))
        assertEquals(24_000L, musicVideoMomentMs(edit, 200_000L, 20_000L))
        assertEquals(59_000L, musicVideoMomentMs(edit, 200_000L, 50_000L))
        assertEquals(36_000L, musicVideoMomentMs(edit, 200_000L, 32_000L), "in the gap, nearer the first segment")
        assertEquals(47_000L, musicVideoMomentMs(edit, 200_000L, 38_000L), "in the gap, nearer the second")
    }

    @Test fun theMomentIsComputedWithTheCanvasSyncPolicy() {
        val src = flat(modeLinks)
        assertTrue("CanvasSyncPolicy(syncMap, videoDurationMs).targetVideoMs(" in src, "the policy's own mapping (spec 9)")
        assertTrue("links.watchMusicVideo(videoId, title, artist, musicVideoMomentMs(syncMap, durationMs, songPositionMs()))" in src)
        assertTrue("interface ModeLinks { fun watchMusicVideo(videoId: String, title: String, artist: String, atVideoMs: Long?) }" in src)
        assertTrue("@Stable interface ModeLinks" in src)
        assertTrue("val LocalModeLinks: ProvidableCompositionLocal<ModeLinks?> = staticCompositionLocalOf { null }" in src)
    }

    // ---- The tool in Now Playing ------------------------------------------------------------------------------------------------

    @Test fun nowPlayingDecidesOnceFromTheLinksAndTheCanvasAndReadsThePositionOnlyWhenPressed() {
        val remember = body(controls, "rememberWatchMusicVideo")
        assertEquals(
            listOf("song: Song", "canvas: CanvasArtwork?", "position: PlaybackPosition"),
            VideoSourceContract.parameters(read(controls), "rememberWatchMusicVideo"),
        )
        assertTrue("val links = LocalModeLinks.current" in remember, "Video available: its hosts provide the links")
        assertTrue("musicVideoLink(links, canvas, song.title, song.artist) { position.positionMs }" in remember, "the playhead is read in the action, not here")
        assertTrue("remember(links, canvas, song.title, song.artist, position)" in remember)
        val screen = flat(nowPlaying)
        assertEquals(1, count(screen, "val watchMusicVideo = rememberWatchMusicVideo(song, canvas, position)"))
        assertTrue(screen.indexOf("val canvas = rememberCanvasArtwork(song)") < screen.indexOf("rememberWatchMusicVideo("), "after the canvas")
        assertEquals(2, count(screen, "onWatchMusicVideo = watchMusicVideo"), "the stack's tools and the wide header")
    }

    @Test fun theToolIsTheVideoIconWithItsDescriptionInTheToolsRowAndTheWideHeaderOnlyWhenThereIsAnAction() {
        val tools = body(controls, "Tools")
        val button = "CosmoIconButton(CosmoIcons.Video, stringResource(Res.string.cosmo_watch_music_video), onWatchMusicVideo, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, tint = resting)"
        assertEquals(1, count(tools, "if (onWatchMusicVideo != null) { $button }"), "behind the decision, the other tools' look")
        assertTrue(tools.indexOf("CosmoIcons.Together") < tools.indexOf("CosmoIcons.Video") && tools.indexOf("CosmoIcons.Video") < tools.indexOf("CosmoIcons.Queue"), "between the middle tools: the two panel toggles keep the ends")
        assertEquals("onWatchMusicVideo: (() -> Unit)? = null", VideoSourceContract.parameters(read(controls), "NowPlayingStack").last(), "one more optional parameter, at the end")
        assertTrue("Tools(lyricsOpen, onToggleLyrics, onOpenOutput, onTogether, queueOpen, onToggleQueue, onWatchMusicVideo)" in body(controls, "NowPlayingStack"))
        val header = body(wide, "NowPlayingWideHeader")
        assertEquals("onWatchMusicVideo: (() -> Unit)? = null", VideoSourceContract.parameters(read(wide), "NowPlayingWideHeader").last(), "one more optional parameter, at the end")
        val wideButton = "if (onWatchMusicVideo != null) { CosmoIconButton(CosmoIcons.Video, stringResource(Res.string.cosmo_watch_music_video), onWatchMusicVideo, style = CosmoIconButtonStyle.TONAL, size = HEADER_BUTTON) }"
        assertEquals(1, count(header, wideButton), "the wide page has no tools row: the header's buttons")
        assertTrue(header.indexOf(wideButton) < header.indexOf("if (hasVideo) {"), "beside the video toggle")
    }

    // ---- The seam ---------------------------------------------------------------------------------------------------------------

    @Test fun nothingUnderSharedUiNamesVideo() {
        val offenders = File(VideoSourceContract.root, "sharedUi/src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/build/" !in it.invariantSeparatorsPath && "/jvmTest/" !in it.invariantSeparatorsPath }
            .filter { "com.cosmotune.video" in VideoSourceContract.code(it.readText()) }
            .map { it.relativeTo(VideoSourceContract.root).invariantSeparatorsPath }
            .toList()
        assertEquals(emptyList(), offenders, "Music knows only ModeLinks; the hosts implement it")
    }

    // ---- The hosts --------------------------------------------------------------------------------------------------------------

    @Test fun thePhoneHostProvidesTheLinksAroundMusicOnlyWhileVideoIsAvailableAndTheLookIsOn() {
        val src = flat(phoneHost)
        assertTrue("val links = remember(shell, asked) { shell?.let { phoneModeLinks(it.settings, asked) } }" in src, "no shell (the look off): no links")
        val provider = "CompositionLocalProvider(LocalModeLinks provides links?.takeIf { AppModes.available(AppMode.VIDEO) }) {"
        assertEquals(1, count(src, provider))
        assertTrue(src.indexOf(provider) < src.indexOf("holder.SaveableStateProvider(\"music\") { music() }"), "around Music's slot")
        val watch = src.substringAfter("internal fun phoneModeLinks(").substringBefore("internal fun videoOf(")
        assertTrue("asked.value = PendingVideo(videoId, atVideoMs) settings.setMode(AppMode.VIDEO)" in watch, "the video first, then the mode: Video opens it on arrival")
        assertFalse("pause" in watch.lowercase(), "it pauses nothing: the video takes audio focus when it starts")
    }

    @Test fun theDesktopHostHasTheLinksTheStableSongActionAndPausesMusicWhenItsVideoOpens() {
        val src = flat(desktopHost)
        assertTrue("fun rememberDesktopModeLinks(): ModeLinks? {" in src)
        assertTrue("val links = remember(shell) { shell?.let { desktopModeLinks(it.settings) } }" in src, "no shell (the look off): no links")
        assertTrue("return links?.takeIf { AppModes.available(AppMode.VIDEO) }" in src)
        val watch = src.substringAfter("internal fun desktopModeLinks(").substringBefore("fun rememberStableSongAction(")
        assertTrue("DesktopModeHost.askedVideo = PendingVideo(videoId, atVideoMs) settings.setMode(AppMode.VIDEO)" in watch, "the video first, then the mode")
        assertEquals(listOf("action: (Song) -> Unit"), VideoSourceContract.parameters(read(desktopHost), "rememberStableSongAction"))
        assertTrue("fun rememberStableSongAction(action: (Song) -> Unit): (Song) -> Unit {" in src)
        val stable = body(desktopHost, "rememberStableSongAction")
        assertTrue("val latest = rememberUpdatedState(action)" in stable && "return remember { { song: Song -> latest.value(song) } }" in stable, "one identity, the latest action")
        assertTrue(
            "onPendingHandled = { DesktopModeHost.askedVideo = null if (playing.value) playPause.value?.invoke() }" in src,
            "Music pauses through LocalCosmoPlayPause as soon as its video's page opens",
        )
        val play = src.substringAfter("playAsSong = {").substringBefore("pauseMusic")
        assertTrue("Song(videoId = id, title = title, artist = channel, thumbnailUrl = thumbnailUrl, isVideo = true)" in play, "Play as a song plays the video as a song")
    }

    @Test fun theDesktopProviderListHoldsTheTwoLinesOnceAfterThePlayPauseLine() {
        val lines = read(desktopApp).lines().map { it.trim() }
        val anchor = "com.cosmotune.desktop.LocalCosmoPlayPause provides com.cosmotune.desktop.rememberStableAction(::togglePlayPauseFromUser),"
        val song = "com.cosmotune.video.host.LocalDesktopPlaySong provides com.cosmotune.video.host.rememberStableSongAction { playSong(it) },"
        val links = "com.cosmotune.ui.modes.LocalModeLinks provides com.cosmotune.video.host.rememberDesktopModeLinks(),"
        assertEquals(1, lines.count { it == song })
        assertEquals(1, lines.count { it == links })
        val at = lines.indexOf(anchor)
        assertEquals(listOf(song, links), lines.subList(at + 1, at + 3), "right after the play/pause line, in this order")
        assertEquals(1, count(read(desktopApp), "com.cosmotune.ui.modes"), "a full name, no import")
    }

    // ---- The music video's id ---------------------------------------------------------------------------------------------------

    @Test fun bothArtworksCarryTheVideoIdWithANullDefaultAfterTheHeaders() {
        assertNull(CanvasArtwork(url = "https://example.invalid/clip.mp4").videoId, "null unless OpenCanvas found one")
        for (path in listOf(canvasArtwork, desktopCanvas)) {
            val lines = read(path).lines().map { it.trim() }
            val at = lines.indexOf("val videoId: String? = null,")
            assertTrue(at > 0, "$path: the field")
            assertEquals(1, lines.count { it == "val videoId: String? = null," }, path)
            assertTrue(lines[at - 1].startsWith("/**") && lines[at - 2] == "val headers: Map<String, String> = emptyMap(),", "$path: after the headers, with its KDoc")
        }
    }

    @Test fun theIdTravelsFromOpenCanvasToBothPlayers() {
        assertTrue("videoId = track.videoId," in flat(openCanvas), "OpenCanvas fills it")
        val toShared = read(desktopCanvasVideo).substringAfter("internal fun DesktopCanvasArtwork.toShared(): CanvasArtwork = CanvasArtwork(\n").substringBefore("\n)\n")
        assertTrue("    videoId = videoId," in toShared.lines(), "toShared() passes it")
        val desktopProvider = read(desktopCanvasSources).substringAfter("internal object DesktopOpenCanvasProvider {").substringBefore("\n}\n")
        assertTrue("videoId = clip.videoId," in desktopProvider, "the desktop's adapter passes it")
    }

    // ---- Strings and FORK.md ----------------------------------------------------------------------------------------------------

    @Test fun everySharedUiStringsFileHasTheOneNewKey() {
        val files = File(VideoSourceContract.root, "sharedUi/src/commonMain/composeResources").listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!
            .map { File(it, "strings.xml") }.filter { it.isFile }
        assertEquals(18, files.size, "the 18 strings files of sharedUi")
        for (file in files) {
            assertEquals(1, count(file.readText(), "<string name=\"cosmo_watch_music_video\">Watch the music video</string>"), file.path)
        }
    }

    @Test fun forkMdHasNewRowsForTheUpstreamFilesWithFourColumns() {
        val rows = read("FORK.md").lines().filter { it.startsWith("|") }
        fun columns(row: String) = row.trim().removePrefix("|").removeSuffix("|").split("|").size
        val ours = listOf(
            rows.filter { it.startsWith("| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopApp.kt`") && "LocalModeLinks" in it && "LocalDesktopPlaySong" in it },
            rows.filter { it.startsWith("| `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/CanvasArtwork.kt`") && "videoId" in it },
            rows.filter { it.startsWith("| `sharedUi/src/commonMain/composeResources/values*/strings.xml`") && "cosmo_watch_music_video" in it },
            rows.filter { it.startsWith("| `desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopCanvas.kt`") && "DesktopCanvasVideo.kt" in it && "DesktopCanvasSources.kt" in it && "videoId" in it },
        )
        for (found in ours) assertEquals(1, found.size, "one new row each: $found")
        for (row in ours.flatten()) assertEquals(4, columns(row), "four columns: ${row.take(80)}")
        val broken = rows.filter { columns(it) != 4 && !it.startsWith("| :--") }
        assertEquals(emptyList(), broken.map { it.take(80) }, "every row of the table has four columns")
    }
}
