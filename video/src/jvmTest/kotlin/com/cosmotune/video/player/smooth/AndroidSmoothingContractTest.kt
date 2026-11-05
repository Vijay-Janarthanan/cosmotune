package com.cosmotune.video.player.smooth

import com.cosmotune.video.ui.VideoSourceContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What no JVM test can run (the Android GL path needs a phone) but can read from the source: the safe-fallback wiring. Smoothing
 * Off leaves the plain picture path as it was, every entry into GL is guarded, an error reaches the hub and takes the picture back,
 * and nothing outside the `smooth` package touches GL.
 */
class AndroidSmoothingContractTest {
    private val android = "video/src/androidMain/kotlin/com/cosmotune/video/player/android"
    private fun read(path: String) = VideoSourceContract.read(path)
    private fun code(path: String) = VideoSourceContract.code(read(path))

    private val engine get() = code("$android/AndroidVideoEngine.kt")
    private val pipeline get() = code("$android/smooth/SmoothGlPipeline.kt")
    private val hub get() = code("$android/smooth/SmoothingHub.kt")
    private val host get() = code("$android/smooth/SmoothPicture.kt")

    @Test
    fun theMainFunctionsOfTheEnginesPictureKeepThePlainBranchUntouched() {
        // the plain ContentFrame branches are still there, reached when the route is off
        assertEquals(2, Regex("""SurfaceKind\.CONTENT_FRAME -> ContentFrame\(""").findAll(engine).count())
        assertEquals(2, Regex("""if \(smooth && player != null\) \{""").findAll(engine).count())
        assertTrue("SmoothPicture(player, smoothing" in engine)
        assertTrue("surfaceType = SURFACE_TYPE_SURFACE_VIEW" in engine)
    }

    @Test
    fun theRouteIsOffWhenSmoothingIsNotAskedFor() {
        assertTrue("val unwanted = s.mode == SmoothMode.OFF" in hub)
        val routed = hub.substringAfter("val routed =").substringBefore("\n        if (!routed)")
        assertTrue("!unwanted" in routed && "reason == null" in routed && "!hdr" in routed && "!audioOnly" in routed, routed)
        assertTrue("formatSeen" in routed, "no route until the decoder's format says what the video is")
        assertTrue("governor.ceiling != SmoothMode.OFF" in routed, "a governor stepped down to Off hands the picture back")
    }

    @Test
    fun everyEntryIntoGlIsGuarded() {
        // the four things the GL thread is asked to do, and the frame callbacks, all go through guarded(), which fails the pipeline
        for (label in listOf("start", "output", "aspect", "clear output", "stop", "input", "frame")) {
            assertTrue("guarded(\"$label\")" in pipeline, "guarded($label)")
        }
        assertTrue("private inline fun guarded(" in pipeline)
        val guarded = pipeline.substringAfter("private inline fun guarded(").substringBefore("private fun fail(")
        assertTrue("catch (t: Throwable)" in guarded && "fail(label, t)" in guarded)
    }

    @Test
    fun aFailureReachesTheHubAndTheHostTakesThePictureBack() {
        val fail = pipeline.substringAfter("private fun fail(").substringBefore("/** No more drawing")
        assertTrue("listener.onFailed(" in fail && "hub.holdAudio(null)" in fail, "the sound is released from its delay on failure")
        assertTrue("override fun onFailed(reason: String)" in host && "hub.fail(reason)" in host)
        assertTrue("failure = " in hub && "governor.stop(" in hub, "a failure keeps smoothing off until the setting changes")
    }

    @Test
    fun theDecodersSurfaceIsTakenAwayBeforeAnythingIsReleased() {
        val stop = host.substringAfter("private fun stopPipeline()").substringBefore("/** Asks the output surface")
        assertTrue(stop.indexOf("player.clearVideoSurface(it)") in 0 until stop.indexOf("stopped.release()"), stop)
        assertTrue("DRAIN_MS" in pipeline && "postDelayed({ teardown() }, DRAIN_MS)" in pipeline, "the surface outlives the player's last frame")
    }

    @Test
    fun theWindowSystemsContractOnADestroyedSurfaceIsKept() {
        val destroyed = host.substringAfter("override fun surfaceDestroyed").substringBefore("}")
        assertTrue("stopPipeline()" in destroyed)
        assertTrue("fun clearOutput()" in pipeline && "done.await(" in pipeline, "the output is let go of before surfaceDestroyed returns")
    }

    @Test
    fun glIsUsedOnlyInsideTheSmoothPackage() {
        val outside = listOf("AndroidVideoEngine", "AndroidCapabilities", "VideoPlayerHolder", "VideoPlaybackService")
        for (name in outside) {
            val source = code("$android/$name.kt")
            assertFalse("android.opengl" in source, "$name does not use GL")
            assertFalse("EGL14" in source, "$name does not use EGL")
        }
    }

    @Test
    fun theShadersAreCompiledWithTheirLogRead() {
        val core = code("$android/smooth/GlCore.kt")
        assertTrue("GL_COMPILE_STATUS" in core && "glGetShaderInfoLog" in core)
        assertTrue("GL_LINK_STATUS" in core && "glGetProgramInfoLog" in core)
        assertTrue("throw GlException(" in core)
        assertTrue("GL_FRAMEBUFFER_COMPLETE" in core, "an incomplete framebuffer is an error")
    }

    @Test
    fun theAudioProcessorSitsInTheServicePlayerAndPassesPcmOfAnyEncoding() {
        val holder = code("$android/VideoPlayerHolder.kt")
        assertTrue("VideoRenderersFactory(context)" in holder)
        val delay = code("$android/smooth/SmoothAudioDelay.kt")
        assertTrue("Util.isEncodingLinearPcm(" in delay)
        assertTrue("setAudioProcessors(arrayOf(SmoothAudioDelayProcessor()))" in delay)
    }

    @Test
    fun theSettingsPagesOfThePhoneAreNoLongerGreyedOut() {
        val capabilities = code("$android/AndroidCapabilities.kt")
        assertTrue("frameSmoothing = SmoothingHub.supported(context)" in capabilities)
        assertTrue("frameSmoothingExperimental = true" in capabilities)
    }
}
