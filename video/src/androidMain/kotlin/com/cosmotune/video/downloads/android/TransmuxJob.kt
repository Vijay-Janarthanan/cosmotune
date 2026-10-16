package com.cosmotune.video.downloads.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Joins a downloaded video-only file and audio-only file into one MP4 without re-encoding (plan task Y-24, step 5; spec 2.5):
 * Media3 Transformer with a two-sequence `Composition` (one video-only and one audio-only `EditedMediaItemSequence`) and both
 * transmux flags on. Its MP4 muxer takes H.264, VP9 and AV1 video and AAC and Opus audio; no official sample shows exactly
 * this, so it is to be proven on the device: when it fails the answer is false and the caller keeps both files (they play
 * inside the app). The Transformer runs on its own looper thread; cancelling the coroutine cancels the export.
 */
@OptIn(UnstableApi::class)
class TransmuxJob(private val context: Context) {
    /** Joins [video] and [audio] into [output] (MP4); true when the file was written. */
    suspend fun run(video: File, audio: File, output: File): Boolean {
        output.delete()
        val thread = HandlerThread("cosmotune-transmux").apply { start() }
        val handler = Handler(thread.looper)
        try {
            return suspendCancellableCoroutine { continuation ->
                var transformer: Transformer? = null
                continuation.invokeOnCancellation { handler.post { transformer?.cancel() } }
                handler.post {
                    try {
                        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video))).setRemoveAudio(true).build()
                        val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(audio))).setRemoveVideo(true).build()
                        val composition = Composition.Builder(
                            EditedMediaItemSequence.withVideoFrom(listOf(videoItem)),
                            EditedMediaItemSequence.withAudioFrom(listOf(audioItem)),
                        )
                            .setTransmuxAudio(true)
                            .setTransmuxVideo(true)
                            .build()
                        val listener = object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                        transformer = Transformer.Builder(context).setLooper(thread.looper).addListener(listener).build().also {
                            it.start(composition, output.absolutePath)
                        }
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}
