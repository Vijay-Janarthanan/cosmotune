package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Nothing the old full screens had may be lost in the shared full-screen player: the long press at 2x (the speed before back on
 * release, with its hint), the vertical drags of brightness (left half) and volume (right half) with the level meter, and
 * Picture in picture in the ⋯ menu wherever the platform has it, wired for Video, Watch and Music's Cinema. Read from the source.
 */
class FullScreenGesturesContractTest {
    private fun flat(path: String) = SourceContract.code(SourceContract.read(path)).replace(Regex("""\s+"""), " ")

    private val player get() = flat("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/fullscreen/FullScreenPlayer.kt")
    private val device get() = flat("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/fullscreen/FullScreenDevice.kt")
    private val android get() = flat("sharedUi/src/androidMain/kotlin/com/cosmotune/ui/fullscreen/FullScreenDevice.android.kt")
    private val video get() = flat("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/watch/WatchCinema.kt")
    private val videoPage get() = flat("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/watch/WatchPage.kt")
    private val watch get() = flat("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/watch/WatchPlayerPage.kt")
    private val cinema get() = flat("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingCinema.kt")
    private val nowPlaying get() = flat("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/CosmoNowPlaying.kt")

    @Test fun aLongPressPlaysAt2xUntilReleasedWithItsHint() {
        val src = player
        assertTrue("onLongPress = { currentSpeed?.let { s -> held.value = s.get(); s.set(FULL_SCREEN_HOLD_SPEED) } }" in src, "a long press holds 2x")
        assertTrue("tryAwaitRelease() held.value?.let { before -> currentSpeed?.set(before) }" in src, "release gives the speed before back")
        assertTrue("if (held.value != null) Text( \"2x speed\"" in src, "with the 2x hint")
        assertTrue("const val FULL_SCREEN_HOLD_SPEED = 2f" in device)
    }

    @Test fun verticalDragsSetTheBrightnessOnTheLeftAndTheVolumeOnTheRightWithTheMeter() {
        val src = player
        assertTrue(".levelDrags(device, meter, meterLevel)" in src, "the drags are on the player")
        assertTrue("val device = rememberFullScreenDevice()" in src, "the device's brightness and volume")
        assertTrue("if (down.position.x < size.width / 2f) LevelKind.BRIGHTNESS else LevelKind.VOLUME" in src, "left half brightness, right half volume")
        assertTrue("if (event.changes.count { it.pressed } >= 2 || target == null) break" in src, "a second finger is still the pinch")
        assertTrue("LevelMeter(kind, meterLevel," in src, "the level meter at that edge")
        assertTrue("attributes.screenBrightness = value.coerceIn(MIN_BRIGHTNESS, 1f)" in android && "AudioManager.STREAM_MUSIC" in android, "Android: the window's brightness, the music volume")
        assertTrue("if (parent is DialogWindowProvider) return parent.window" in android, "the brightness of the full window itself")
        assertTrue("com.cosmotune.ui.fullscreen.LevelMeter(" in flat("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/player/PlayerGestures.kt"),
            "Video's own player box shows the same meter")
    }

    @Test fun pictureInPictureIsInTheMenuWhereThePlatformHasIt() {
        assertTrue("actions.forEach { item -> add(OrbitMenuItem(" in player, "the ⋯ menu lists the actions")
        assertTrue("actions = listOfNotNull(onEnterPip?.let { enter -> OrbitMenuItem(\"Picture in picture\"" in video, "Video")
        assertTrue("onEnterPip = onEnterPip," in videoPage, "Video's full screen is handed the device's picture in picture")
        assertTrue("actions = listOfNotNull(controls.pictureInPicture?.let { enter -> OrbitMenuItem(\"Picture in picture\"" in watch, "Watch")
        assertTrue("actions = listOfNotNull(pictureInPicture?.let { enter -> com.cosmotune.ui.orbit.OrbitMenuItem(\"Picture in picture\"" in cinema, "Music's Cinema")
        assertTrue("pictureInPicture = enterPip?.let { enter -> { leaveCinema(); enter() } }," in nowPlaying, "where Now Playing has it")
    }

    @Test fun videoAndWatchHandTheirSpeedToTheLongPress() {
        assertTrue("speed = if (state.live) null else FullScreenSpeed({ playback.state.value.speed }, playback::setSpeed)," in video, "Video, not live")
        assertTrue("speed = controls.speed.takeIf { !state.isLive }," in watch, "Watch, not a live channel")
        assertTrue("speed = if (castRenderer == null) com.cosmotune.ui.fullscreen.FullScreenSpeed({ player.engine.snapshot.value.speed }, player.engine::setSpeed) else null," in watch,
            "the engine's own speed, none while casting")
    }

    @Test fun theTapDoubleTapAndPinchRulesStay() {
        val src = player
        assertTrue("onTap = { shown = !shown; stamp++ }" in src)
        assertTrue("TapThird.LEFT -> if (canSkip) { skip(-FULL_SCREEN_SKIP_MS)" in src && "TapThird.RIGHT -> if (canSkip) { skip(FULL_SCREEN_SKIP_MS)" in src)
        assertTrue(".pinchToFill { wanted -> setFill(wanted)" in src)
    }
}
