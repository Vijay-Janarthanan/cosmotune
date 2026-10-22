package com.cosmotune.canvas

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.canvas.CanvasCache
import com.cosmotune.music.data.canvas.CanvasRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.playback.ChunkedDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit device/network test: instrumentation argument videoLive=true. Restores the user's settings. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class CanvasQualityLiveTest {
    @Test fun changingQualityLoadsAndRendersTheRequestedRendition() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("videoLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val oldQuality = AppSettings.openCanvasResolution.value
        val oldEnabled = AppSettings.openCanvasEnabled.value
        try {
            AppSettings.setOpenCanvasEnabled(true)
            for (quality in listOf(360, 1080)) {
                AppSettings.setOpenCanvasResolution("${quality}p")
                val clip = CanvasRepository.canvasFor(Song("dQw4w9WgXcQ", "Never Gonna Give You Up", "Rick Astley", null))
                assertNotNull("a synced music video resolves at ${quality}p", clip)
                val rendered = CountDownLatch(1)
                val error = AtomicReference<PlaybackException?>()
                val size = AtomicReference(VideoSize.UNKNOWN)
                lateinit var player: ExoPlayer
                lateinit var reader: ImageReader
                instrumentation.runOnMainSync {
                    reader = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 3)
                    reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
                    val upstream = OkHttpDataSource.Factory(Http.client).setDefaultRequestProperties(clip!!.headers)
                    player = ExoPlayer.Builder(instrumentation.targetContext)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(
                            CanvasCache.dataSourceFactory(ChunkedDataSource.Factory(upstream, 1024 * 1024))))
                        .build()
                    player.volume = 0f
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                    player.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: VideoSize) { size.set(videoSize) }
                        override fun onRenderedFirstFrame() { rendered.countDown() }
                        override fun onPlayerError(failure: PlaybackException) { error.set(failure); rendered.countDown() }
                    })
                    player.setVideoSurface(reader.surface)
                    player.setMediaItem(MediaItem.fromUri(clip.url), 30_000)
                    player.prepare()
                    player.play()
                }
                try {
                    assertTrue("a frame renders at ${quality}p", rendered.await(45, TimeUnit.SECONDS))
                    assertNull(error.get()?.stackTraceToString(), error.get())
                    assertEquals("decoded height follows Settings", quality, size.get().height)
                    android.util.Log.i("CanvasQualityLive", "Rendered ${size.get().width}x${size.get().height} from Settings")
                } finally {
                    instrumentation.runOnMainSync { player.release(); reader.close() }
                }
            }
        } finally {
            AppSettings.setOpenCanvasResolution(oldQuality)
            AppSettings.setOpenCanvasEnabled(oldEnabled)
        }
    }
}
