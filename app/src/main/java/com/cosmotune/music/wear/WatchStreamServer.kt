package com.cosmotune.music.wear

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's half of one [WearProtocol.PATH_STREAM] read, apart from the
 * Wearable channel and Media3 so a JVM test can drive it over a pipe.
 *
 * [WearBridge.serveStream] hands it the channel's two streams and an opener
 * that runs the phone player's own data-source chain.
 */
internal object WatchStreamServer {

    /** An opened track: how many bytes it can serve (-1 unknown), then reads until -1. */
    interface Source : Closeable {
        val length: Long
        fun read(buffer: ByteArray, offset: Int, length: Int): Int
    }

    /**
     * Reads the watch's request, opens it with [open] (null, or a throw, is a
     * failure the watch is told about), answers, then copies the bytes. Returns
     * the bytes served, or -1 when nothing could be opened. The source is always
     * closed; the streams are the caller's.
     */
    fun serve(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = 32 * 1024,
        open: (WearWire.StreamRequest) -> Source?,
    ): Long {
        val request = WearWire.StreamRequest.readFrom(DataInputStream(input))
        val out = DataOutputStream(BufferedOutputStream(output, bufferSize))
        val source = runCatching { open(request) }.getOrNull()
        if (source == null) {
            WearWire.StreamReply(ok = false, length = -1L).writeTo(out)
            out.flush()
            return -1L
        }
        source.use { s ->
            WearWire.StreamReply(ok = true, length = s.length).writeTo(out)
            out.flush()
            val buffer = ByteArray(bufferSize)
            var served = 0L
            while (true) {
                val read = s.read(buffer, 0, buffer.size)
                if (read < 0) break
                if (read > 0) {
                    out.write(buffer, 0, read)
                    served += read
                }
            }
            out.flush()
            return served
        }
    }
}
