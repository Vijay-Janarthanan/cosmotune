package com.cosmotune.video.player.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.cosmotune.video.player.net.GooglevideoRequests

/**
 * Reads a long googlevideo stream as a run of ranges of at most [chunkBytes] (2 MiB, [GooglevideoRequests.CHUNK_BYTES]) instead
 * of one long read (spec 6.2, 6.3).
 *
 * Why: googlevideo paces one continuous response down to about the playback rate, while each bounded range is served at line
 * rate. Music measured it on the same stream over the same connection, seconds apart: an unbounded GET came at 15.5 kB/s, a
 * `bytes=0-2097151` range at 5.7 MB/s (the KDoc of Music's `ChunkedDataSource` in `app`, which this module does not depend on;
 * this is our own implementation). With ranges the player can build a buffer; without them it can only just keep up.
 *
 * Only `videoplayback` reads of a stream set with the range rules ([VideoRequestHeaders.StreamSetRequests.rangeRules]) are split,
 * and only when the end is known (the read's length, or the stream's `clen`) and further away than one chunk; anything else goes
 * to [upstream] as it is. Each range is a bounded [DataSpec], which [GooglevideoDataSource] below turns into `&range=`. To the
 * player above it is one continuous read of the length it asked for.
 */
@OptIn(UnstableApi::class)
class ChunkedRangeDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = GooglevideoRequests.CHUNK_BYTES,
) : DataSource {

    /** Wraps every data source [upstream] makes. */
    class Factory(
        private val upstream: DataSource.Factory,
        private val chunkBytes: Long = GooglevideoRequests.CHUNK_BYTES,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedRangeDataSource(upstream.createDataSource(), chunkBytes)
    }

    private var base: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false

    /** True when the read was not split and every call goes straight to [upstream]. */
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        base = dataSpec
        position = dataSpec.position
        val end = splitEnd(dataSpec)
        if (end == null) {
            passthrough = true
            chunkOpen = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        bytesRemaining = (end - position).coerceAtLeast(0L)
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    /** The end (exclusive) of a read worth splitting, or null for a read that goes to [upstream] as it is. */
    private fun splitEnd(dataSpec: DataSpec): Long? {
        if (!VideoRequestHeaders.current.rangeRules) return null
        if (!GooglevideoRequests.isVideoplayback(dataSpec.uri.toString())) return null
        val unset = C.LENGTH_UNSET.toLong()
        if (dataSpec.length != unset) return if (dataSpec.length > chunkBytes) dataSpec.position + dataSpec.length else null
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: return null
        return if (total - dataSpec.position > chunkBytes) total else null
    }

    private fun openChunk() {
        val length = minOf(chunkBytes, bytesRemaining)
        // marked open first: a failed open is still closed by close(), as the DataSource contract asks
        chunkOpen = true
        upstream.open(requireNotNull(base).buildUpon().setPosition(position).setLength(length).build())
        chunkRemaining = length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        // A range that ends early is opened again for the part that did not come, which is also how the step to the next
        // range happens; the limit keeps a server that sends nothing from holding the loader here.
        repeat(MAX_EMPTY_RANGES) {
            if (chunkRemaining == 0L) {
                closeChunk()
                openChunk()
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                chunkRemaining -= read
                bytesRemaining -= read
                return read
            }
            chunkRemaining = 0L
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun closeChunk() {
        if (chunkOpen) {
            upstream.close()
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: base?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        base = null
        bytesRemaining = 0L
        chunkRemaining = 0L
    }

    private companion object {
        /** Enough to ride out a range that was cut short, not enough to hang on a dead one. */
        const val MAX_EMPTY_RANGES = 3
    }
}
