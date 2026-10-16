package com.cosmotune.video.ui

import com.cosmotune.video.ui.VideoSourceContract.code
import com.cosmotune.video.ui.VideoSourceContract.read
import com.cosmotune.video.ui.VideoSourceContract.root
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Android engine (plan task Y-11, spec 6.2) cannot run here (no device, no Android runtime): what it must do is pinned by
 * reading its sources. The controller checks it on the emulator (Y-29) with the task's device checklist.
 */
class AndroidEngineStructureTest {

    private val dir = "video/src/androidMain/kotlin/com/cosmotune/video/player/android"
    private val engine by lazy { read("$dir/AndroidVideoEngine.kt") }
    private val service by lazy { read("$dir/VideoPlaybackService.kt") }
    private val holder by lazy { read("$dir/VideoPlayerHolder.kt") }
    private val sources by lazy { read("$dir/VideoMediaSourceFactory.kt") }
    private val googlevideo by lazy { read("$dir/GooglevideoDataSource.kt") }
    private val chunked by lazy { read("$dir/ChunkedRangeDataSource.kt") }
    private val capabilities by lazy { read("$dir/AndroidCapabilities.kt") }
    private val pip by lazy { read("video/src/androidMain/kotlin/com/cosmotune/video/player/Pip.android.kt") }

    /** [src] without comments, its white space collapsed, so a rule reads the code as one line. */
    private fun flat(src: String) = code(src).replace(Regex("\\s+"), " ")

    /** The [length] characters of [src] (flattened) from the first [anchor]; fails when the anchor is missing. */
    private fun after(src: String, anchor: String, length: Int = 600): String {
        val text = flat(src)
        val at = text.indexOf(anchor)
        assertTrue(at >= 0, "the source holds `$anchor`")
        return text.substring(at, minOf(text.length, at + length))
    }

    @Test fun theServiceBuildsItsPlayerWithAudioFocusHandled() {
        assertTrue("VideoPlayerHolder.build(this)" in flat(service), "the service's player comes from the holder")
        assertTrue("/* handleAudioFocus = */ true" in holder, "the audio focus flag is written as the plan says")
        val build = after(holder, "fun build(context: Context): ExoPlayer", 1200)
        assertTrue(Regex("""setAudioAttributes\( ?attributes, ?true ?\)""").containsMatchIn(build), "handleAudioFocus = true")
        assertTrue("setUsage(C.USAGE_MEDIA)" in build && "setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)" in build)
        assertTrue("setHandleAudioBecomingNoisy(true)" in build)
        assertTrue("setWakeMode(C.WAKE_MODE_NETWORK)" in build)
        assertTrue("setMediaSourceFactory(VideoMediaSourceFactory(context))" in build)
    }

    @Test fun theSessionNotificationAndIdsAreVideosOwn() {
        val text = flat(service)
        assertTrue("class VideoPlaybackService : MediaSessionService()" in text)
        assertTrue("override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession" in text)
        assertTrue("const val SESSION_ID = \"CosmotuneVideo\"" in text, "a session id of its own (Music's is BitChordPlayback)")
        assertTrue("const val CHANNEL_ID = \"video_playback\"" in text)
        assertTrue("setNotificationId(NOTIFICATION_ID)" in text, "not Media3's default 1001, which Music's notification uses")
        val id = Regex("""const val NOTIFICATION_ID = (0x[0-9a-fA-F]+|\d+)""").find(text)!!.groupValues[1]
        assertFalse(id.removePrefix("0x").toInt(if (id.startsWith("0x")) 16 else 10) in setOf(1001, 0x8175, 0x8176))
        assertTrue("setChannelName(R.string.video_notification_channel)" in text)
        assertTrue("setSmallIcon(R.drawable.ic_video_notification)" in text)
        assertTrue("getLaunchIntentForPackage(packageName)" in text && "Intent.FLAG_ACTIVITY_SINGLE_TOP" in text)
        assertTrue(
            "if (!isPlaybackOngoing || mediaSession?.player?.playWhenReady != true) pauseAllPlayersAndStopSelf()" in text,
            "swiping away stops it unless it plays",
        )
        assertTrue("setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)" in text, "no notification lingers when idle")
        assertTrue("if (player.mediaItemCount == 0 && player.playbackState == Player.STATE_IDLE) stopSelf()" in text, "an unload stops it")
        assertTrue("VideoPlayerHolder.release(session.player as ExoPlayer)" in text && "session.release()" in text)
        val strings = read("video/src/androidMain/res/values/strings.xml")
        assertTrue("<string name=\"video_notification_channel\">Video playback</string>" in strings)
        assertTrue(File(root, "video/src/androidMain/res/drawable/ic_video_notification.xml").isFile)
    }

    @Test fun theSurfaceIsContentFrameOnASurfaceViewWithTheViewFallbackReady() {
        val surface = after(engine, "override fun Surface(modifier: Modifier, scale: VideoScale)", 700)
        assertTrue("ContentFrame(" in surface && "surfaceType = SURFACE_TYPE_SURFACE_VIEW" in surface)
        assertTrue("contentScale = if (scale == VideoScale.FIT) ContentScale.Fit else ContentScale.Crop" in surface)
        assertTrue("var surfaceKind: SurfaceKind = SurfaceKind.CONTENT_FRAME" in flat(engine), "ContentFrame is the default")
        val fallback = after(engine, "private fun ViewSurface(", 1500)
        assertTrue("AndroidView(" in fallback && "SurfaceView(it)" in fallback)
        assertTrue("setVideoSurfaceView(surface)" in fallback && "clearVideoSurfaceView(surface)" in fallback)
        assertFalse("positionMs()" in surface || "currentPosition" in surface, "the picture never reads the position")
    }

    @Test fun dashItemsAreDataAddressesThroughTheDefaultFactory() {
        val text = flat(engine)
        assertTrue("const val DASH_DATA_PREFIX = \"data:application/dash+xml;base64,\"" in text)
        val dash = after(engine, "is MediaInput.Dash ->", 300)
        assertTrue("DASH_DATA_PREFIX + Base64.encodeToString(" in dash && "setMimeType(MimeTypes.APPLICATION_MPD)" in dash)
        assertTrue("setMimeType(MimeTypes.APPLICATION_M3U8)" in after(engine, "is MediaInput.Hls ->", 200))
        assertTrue("setSubtitleConfigurations(request.subtitles.map(::subtitleOf) +" in text)
        val factory = flat(sources)
        assertTrue("DefaultMediaSourceFactory(dataSourceFactory).setLiveTargetOffsetMs(LIVE_OFFSET_MS)" in factory)
        assertTrue("const val LIVE_OFFSET_MS = 10_000L" in factory, "live 10 s behind the edge")
        assertTrue("return default.createMediaSource(mediaItem)" in factory, "everything but the pair scheme goes through the default factory")
        assertTrue(
            "DefaultDataSource.Factory(context, ChunkedRangeDataSource.Factory(GooglevideoDataSource.Factory()))" in factory,
            "the default data source reads data: addresses over the googlevideo chain (W3)",
        )
    }

    @Test fun thePairSchemeMergesProgressiveSourcesWithTheSubtitlesAsTheirOwnSources() {
        val factory = flat(sources)
        assertTrue("const val PAIR_SCHEME = \"cosmotune-video\"" in factory)
        assertTrue("if (uri != null && uri.scheme == PAIR_SCHEME) return pair(mediaItem, uri)" in factory)
        assertTrue("/* adjustPeriodTimeOffsets = */ true, /* clipDurations = */ true" in sources)
        assertTrue(Regex("""MergingMediaSource\( ?true, ?true, \*children.toTypedArray\(\)\)""").containsMatchIn(factory))
        assertTrue("ProgressiveMediaSource.Factory(dataSourceFactory, extractors)" in factory && "SubtitleExtractor(" in factory)
        val engineText = flat(engine)
        assertTrue("is MediaInput.Progressive -> builder.setUri(VideoMediaSourceFactory.pairUri(" in engineText)
        assertTrue("is MediaInput.LocalFiles -> builder.setUri(" in engineText)
    }

    @Test fun audioOnlyDisablesTheVideoTrackAndKeepsTheSource() {
        val audioOnly = after(engine, "override fun setAudioOnly(audioOnly: Boolean)", 400)
        assertTrue("setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)" in audioOnly)
        assertFalse("setMediaItem" in audioOnly || "prepare()" in audioOnly, "the source is kept")
        val subtitle = after(engine, "private fun withText(", 400)
        assertTrue("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)" in subtitle && "setPreferredTextLanguage(language)" in subtitle)
        val muted = after(engine, "private fun effectiveVolume()", 80)
        assertTrue("if (muted) 0f else volume" in muted, "muting is the volume at 0 and back")
    }

    @Test fun theEngineHasThePlansSignatureAndTalksToItsOwnServiceOnly() {
        val text = flat(engine)
        assertTrue("class AndroidVideoEngine(context: Context) : VideoPlayerEngine, VideoSurfaceProvider" in text)
        assertTrue("SessionToken(appContext, ComponentName(appContext, VideoPlaybackService::class.java))" in text)
        assertTrue(".buildAsync()" in text)
        assertTrue("c.setMediaItem(item, start)" in text && "c.prepare()" in text)
        assertTrue("it.setPlaybackSpeed(wanted)" in text)
        assertTrue("get() = AndroidCapabilities.of(appContext)" in text)
        val position = after(engine, "override fun positionMs(): Long", 300)
        assertTrue("c.currentPosition" in position, "the position is the controller's on the main thread")
        assertTrue("private const val TICK_MS = 1_000L" in text, "the snapshot is refreshed once a second, never per frame")
        assertTrue("VideoRequestHeaders.current = VideoRequestHeaders.StreamSetRequests(request.headers" in text)
    }

    @Test fun everyCallIsSafeFromAnyThreadAndWhatItChangesShowsAtOnce() {
        val text = flat(engine)
        assertTrue("if (Looper.myLooper() !== mainLooper) { main.post { command(block) } return }" in text, "Media3 work is posted to the main thread")
        assertFalse(Regex("""\.get\(\s*\d""").containsMatchIn(text) || "runBlocking" in text || "await()" in text, "nothing waits on another thread")
        val load = after(engine, "override fun load(request: PlaybackRequest)", 3600)
        assertTrue("requestedLoads.incrementAndGet()" in load, "a load is counted at once")
        assertTrue("status = PlayerStatus.LOADING, playing = false" in load && "error = null" in load, "LOADING, not playing, no error at once")
        assertTrue(Regex("""c\.pause\(\) c\.setMediaItem\(item, start\) c\.prepare\(\)""").containsMatchIn(load), "load never starts playback")
        assertTrue("if (released || loadPending()) return" in text, "the previous video's state is not published while a load is pending")
        for (setter in listOf("override fun setMuted(muted: Boolean)", "override fun setAudioOnly(audioOnly: Boolean)", "override fun setSpeed(speed: Float)")) {
            assertTrue("snapshotValues.update {" in after(engine, setter, 200), "$setter shows in the snapshot at once")
        }
        assertTrue("anchor = Anchor(target, SystemClock.elapsedRealtime()" in after(engine, "override fun seekTo(ms: Long)", 200))
        assertTrue("droppedFrames = droppedSinceLoad(holderPlayer)" in text && "counters.ensureUpdated()" in text, "live dropped-frame counters")
    }

    @Test fun unloadStopsClearsAndLetsTheServiceGo() {
        assertTrue("class AndroidVideoEngine(context: Context) : VideoPlayerEngine, VideoSurfaceProvider {" in flat(engine))
        assertFalse("UnloadableVideoEngine" in engine, "the engine overrides VideoPlayerEngine.unload(), no stand-in interface")
        val unload = after(engine, "override fun unload()", 700)
        assertTrue("disconnect()" in unload && "PlayerSnapshot.Idle.copy(" in unload)
        val disconnect = after(engine, "private fun disconnect()", 500)
        assertTrue("c.stop()" in disconnect && "c.clearMediaItems()" in disconnect && "c.release()" in disconnect)
    }

    @Test fun downloadedSubtitlesAreSideLoadedAsFileAddresses() {
        val text = flat(engine)
        assertTrue("internal fun localSubtitleOf(subtitle: LocalSubtitle): MediaItem.SubtitleConfiguration" in text)
        assertTrue(
            "builder.setSubtitleConfigurations(request.subtitles.map(::subtitleOf) + request.localSubtitles.map(::localSubtitleOf))" in text,
            "a download's subtitle files are side-loaded with the online tracks",
        )
        assertTrue("subtitleConfiguration(subtitle.path, mimeType = \"\", subtitle.language, subtitle.label, subtitle.autoGenerated)" in text)
        assertTrue(".setLanguage(language)" in text && ".setRoleFlags(if (autoGenerated) 0 else C.ROLE_FLAG_SUBTITLE)" in text, "found by language, people's first")
        assertTrue(".setPreferredTextLanguage(language)" in after(engine, "private fun withText(", 400), "selectSubtitle selects by language")
        assertTrue("Uri.fromFile(File(path))" in after(engine, "private fun localAddress(path: String)", 300))
        assertTrue("val uri = localAddress(address)" in text, "a subtitle given as a path is read from the file")
        assertTrue("MimeTypes.APPLICATION_SUBRIP" in text && "MimeTypes.TEXT_VTT" in text)
    }

    @Test fun a403AnywhereInTheCauseChainIsSourceExpired() {
        val kind = after(engine, "internal fun kindOf(error: PlaybackException): PlayerErrorKind", 1400)
        assertTrue("cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403) return PlayerErrorKind.SOURCE_EXPIRED" in kind)
        assertTrue("2 -> PlayerErrorKind.NETWORK" in kind && "4 -> PlayerErrorKind.DECODER" in kind)
        assertTrue(
            "if (holderPlayer != null) holderPlayer.playerError else c?.playerError" in flat(engine),
            "the service's own player's error when it is there: it has the cause chain",
        )
        assertTrue("playerError?.takeUnless { it === staleError }" in flat(engine), "the previous load's error is not reported again")
    }

    @Test fun theDataSourceAppliesGooglevideosRequestRules() {
        val text = flat(googlevideo)
        assertTrue("GooglevideoRequests.isVideoplayback(url)" in text)
        assertTrue("GooglevideoRequests.withRequestNumber(url, requestNumber++)" in text, "rn counts the requests of the instance")
        assertTrue("GooglevideoRequests.withRange(numbered, dataSpec.position, endInclusive)" in text)
        assertTrue(".setPosition(0)" in text && ".setLength(C.LENGTH_UNSET.toLong())" in text, "the ranged response is read from 0")
        assertTrue("setHttpMethod(DataSpec.HTTP_METHOD_POST)" in text && "setHttpBody(GooglevideoRequests.POST_BODY)" in text)
        assertTrue("(e.responseCode == 400 || e.responseCode == 405) && !set.postConfirmed" in text && "set.useGet = true" in text)
        assertTrue("setHttpMethod(DataSpec.HTTP_METHOD_GET)" in text, "the fallback is a GET with a Range header")
        assertTrue("set.values + dataSpec.httpRequestHeaders" in text, "the stream set's headers on every request")
        assertTrue("OkHttpClient.Builder()" in text && "cookieJar(" !in text, "Video's own client, without cookies")
        val chunks = flat(chunked)
        assertTrue("chunkBytes: Long = GooglevideoRequests.CHUNK_BYTES" in chunks, "2 MiB ranges")
        assertTrue("getQueryParameter(\"clen\")" in chunks)
    }

    @Test fun capabilitiesAskTheThreeCodecsAtTheRungsOfTheSpec() {
        val text = flat(capabilities)
        assertTrue("MimeTypes.VIDEO_H264" in text && "MimeTypes.VIDEO_VP9" in text && "MimeTypes.VIDEO_AV1" in text)
        assertTrue("MediaCodecUtil.getDecoderInfos(mime, /* secure = */ false, /* tunneling = */ false)" in capabilities.replace(Regex("\\s+"), " "))
        assertTrue(
            "Rung(3840, 2160, 60), Rung(3840, 2160, 30), Rung(2560, 1440, 60), Rung(1920, 1080, 60), Rung(1920, 1080, 30), " +
                "Rung(1280, 720, 60), Rung(1280, 720, 30)" in text,
            "the rungs of spec 2.5, highest first",
        )
        assertTrue("info.isVideoSizeAndRateSupported(rung.width, rung.height, rung.fps.toDouble())" in text)
        assertTrue("hardware = info.hardwareAccelerated" in text)
        assertTrue("opus = true" in text && "sideloadedDash = true" in text && "autoCeilingHeight = null" in text)
        assertTrue("hdrDisplay = display?.isHdr == true" in text && "minOf(size.x, size.y)" in text)
    }

    @Test fun pictureInPictureFollowsSpec62() {
        val text = flat(pip)
        assertTrue("actual fun rememberPictureInPicture(): PictureInPictureState" in text)
        assertTrue("PackageManager.FEATURE_PICTURE_IN_PICTURE" in text && "componentActivity()" in text)
        assertTrue("addOnPictureInPictureModeChangedListener(modeListener)" in text)
        assertTrue("removeOnPictureInPictureModeChangedListener(modeListener)" in text, "the listener goes when the callers leave")
        assertTrue("addOnUserLeaveHintListener(leaveHintListener)" in text && "activity.enterPictureInPictureMode(wanted)" in text)
        assertTrue("Build.VERSION.SDK_INT < Build.VERSION_CODES.S" in text, "the leave hint enters only on Android 8 to 11")
        assertTrue("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(enabled)" in text)
        assertTrue("setSourceRectHint(" in text && "setAspectRatio(aspect)" in text)
        assertTrue("Rational(239, 100)" in text && "Rational(100, 239)" in text, "clamped to between 1:2.39 and 2.39:1")
        assertTrue("onDispose { handle.shared.leave(handle) }" in text)
        assertTrue("apply(handle, enabled = false" in after(pip, "fun leave(handle: PictureInPictureHandle)", 300), "leaving turns auto-enter off")
    }

    @Test fun nothingInAndroidMainReferencesMusicsPlayback() {
        val offenders = File(root, "video/src/androidMain").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { "com.music.bitchord.playback" in it.readText() }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals(emptyList(), offenders)
    }

    @Test fun theAppManifestCarriesThePictureInPictureAttributeOnceOnMainActivity() {
        val manifest = read("app/src/main/AndroidManifest.xml")
        assertEquals(1, Regex(Regex.escape("android:supportsPictureInPicture=\"true\"")).findAll(manifest).count())
        val activity = manifest.substringAfter("<activity\n").substringBefore(">")
        assertTrue("android:name=\".MainActivity\"" in activity, "the attribute is on MainActivity")
        val lines = activity.lines().map { it.trim() }
        val launch = lines.indexOf("android:launchMode=\"singleTask\"")
        assertTrue(launch >= 0 && lines[launch + 1] == "android:supportsPictureInPicture=\"true\"", "right after launchMode")
        assertTrue("screenSize|screenLayout|smallestScreenSize" in activity && "orientation" in activity, "configChanges cover it")
    }
}
