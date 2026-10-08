package com.opencanvas.core.sync

import com.opencanvas.core.stream.AudioFetcher
import com.opencanvas.core.stream.AudioStream
import com.opencanvas.core.stream.StreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * What reading the two headers told: the song-to-video map, and how rich the video's picture is.
 *
 * @property videoKbps Average bitrate of the video file's picture in kbit/s (`0.0` when unknown).
 */
internal class Measurement(val align: AlignResult, val videoKbps: Double, val videoMotion: Double = 1.0)

/**
 * Measures how a song lines up with its music video, from the headers of the two files alone.
 *
 * Both files are asked for the frame sizes of their audio ([Mp4FrameSizes]): for the progressive MP4
 * YouTube serves, a couple of hundred kilobytes each, fetched at the same time. The two size curves
 * are turned into envelopes ([FrameEnvelope]) and aligned ([SyncAligner]), which takes a few
 * milliseconds. Nothing is decoded, so the work is the same on every platform and light enough to
 * run the moment a song starts.
 */
internal class SyncMeasurer(private val fetcher: AudioFetcher) {

    /**
     * The song-to-video map, or null when either file has no readable audio frame table. The result
     * itself says whether a trustworthy match was found ([AlignResult.error]).
     */
    suspend fun measure(song: AudioStream, video: AudioStream): Measurement? = coroutineScope {
        val songFrames = async { Mp4FrameSizes.read(StreamReader(fetcher, song)) }
        val videoFrames = async { Mp4FrameSizes.read(StreamReader(fetcher, video)) }
        val track = songFrames.await() ?: return@coroutineScope null
        val picture = videoFrames.await() ?: return@coroutineScope null
        val align = withContext(Dispatchers.Default) {
            SyncAligner.align(FrameEnvelope.build(track), FrameEnvelope.build(picture), FrameEnvelope.RATE_HZ)
        }
        Measurement(align, picture.videoKbps, picture.videoMotion)
    }
}
