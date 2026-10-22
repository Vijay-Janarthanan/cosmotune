package com.opencanvas.core.stream

import com.opencanvas.core.models.OpenCanvasResolution

/**
 * A resolved, directly playable video stream.
 *
 * @property url Direct playable MP4 stream URL.
 * @property durationMs Video length in milliseconds, or `0` when the resolver did not report it.
 * @property width Frame width in pixels, or `0` when unknown.
 * @property height Frame height in pixels, or `0` when unknown.
 * @property headers HTTP headers the media request must carry for [url] to be served at full speed
 *   (googlevideo compares the client that minted a URL with the one fetching it). Players apply
 *   them to every range request; empty when the URL needs none.
 */
data class StreamInfo(
    val url: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * An MP4 file whose audio track is read to measure how a song lines up with its music video. Only
 * its header is needed (a couple of hundred kilobytes of a progressive file such as YouTube's muxed
 * 360p stream), so the smallest stream available is the right one; audio-only streams in the
 * fragmented layout work too but are read whole.
 *
 * @property url Direct URL, served with HTTP range requests.
 * @property headers HTTP headers every request for [url] must carry.
 * @property durationMs Length in milliseconds, or `0` when unknown.
 * @property contentLength Size in bytes, or `0` when unknown (the fetcher then learns it from the
 *   first range response).
 * @property maxRangeBytes Largest single range the server serves at full speed for this URL.
 */
data class AudioStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val durationMs: Long = 0L,
    val contentLength: Long = 0L,
    val maxRangeBytes: Long = 1024L * 1024L,
)

/**
 * Where OpenCanvas gets playable media URLs from.
 *
 * The library ships [InnerTubeBackend], which works on its own with no server and no extra
 * dependency. An app that already resolves YouTube streams for its own playback (with PO tokens,
 * signature handling, account cookies, ...) should plug that resolver in instead, so the canvas
 * rides on exactly the machinery the app already keeps working:
 *
 * ```kotlin
 * OpenCanvas.configure(OpenCanvasConfig(streamBackend = object : StreamBackend {
 *     override suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution) = myResolver.video(videoId, resolution.maxHeight)
 * }))
 * ```
 *
 * Implementations are called from background threads and may block.
 */
interface StreamBackend {
    /**
     * Called once, in the background, when OpenCanvas is configured: open connections, start helper
     * processes, load whatever makes the first real lookup fast. Does nothing by default.
     */
    fun warmUp() = Unit

    /**
     * A playable video-only or muxed stream for [videoId] no taller than [resolution] allows
     * (the nearest lower quality when the exact one does not exist), or null when none can be
     * resolved.
     */
    suspend fun resolveVideo(videoId: String, resolution: OpenCanvasResolution): StreamInfo?

    /**
     * A progressive MP4 stream for [videoId] to read the audio frame sizes from, or null (the
     * default) to leave that to the built-in client: hosts normally only implement [resolveVideo].
     */
    suspend fun resolveAudio(videoId: String): AudioStream? = null
}
