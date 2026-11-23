package com.cosmotune.remote

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The live channel of the paired remote (the idea from th-ch's YouTube Music API server, see NOTICE.md): one WebSocket at
 * `/api/live` that pushes the player's status as it changes and takes the same typed commands as `/api/action`, for a
 * Stream Deck, a home-automation script or an overlay. A browser cannot send the bearer header on a WebSocket, so the
 * pairing key travels as one of its subprotocols (`cosmotune`, then the key); the server answers with `cosmotune` only.
 * Text frames only, client frames masked, at most [MAX_FRAME] bytes each.
 */
object LanRemoteLive {
    const val PATH = "/api/live"
    const val PROTOCOL = "cosmotune"
    const val MAX_FRAME = 8 * 1024
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** The `Sec-WebSocket-Accept` answer to a client's `Sec-WebSocket-Key`. */
    fun accept(clientKey: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((clientKey.trim() + GUID).toByteArray(Charsets.US_ASCII)))

    /** The pairing key offered among the subprotocols, when the list starts with [PROTOCOL]. */
    fun offeredKey(protocols: String?): String? {
        val items = protocols?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        return items.takeIf { it.size == 2 && it[0] == PROTOCOL }?.get(1)
    }

    /** Whether [headers] (lower-case names) ask for a WebSocket. */
    fun isUpgrade(headers: Map<String, String>): Boolean =
        headers["upgrade"]?.equals("websocket", ignoreCase = true) == true &&
            headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true &&
            headers["sec-websocket-version"] == "13" && !headers["sec-websocket-key"].isNullOrBlank()

    sealed interface Frame {
        data class Text(val text: String) : Frame
        data class Ping(val payload: ByteArray) : Frame
        data object Pong : Frame
        data object Close : Frame
    }

    /** Reads one client frame; a frame that breaks the rules (unmasked, fragmented, binary, too long) is an error. */
    fun read(input: InputStream): Frame {
        val first = input.read().also { if (it < 0) throw java.io.EOFException() }
        val second = input.read().also { if (it < 0) throw java.io.EOFException() }
        val fin = first and 0x80 != 0
        val opcode = first and 0x0F
        require(second and 0x80 != 0) { "client frames must be masked" }
        var length = (second and 0x7F).toLong()
        if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
        else if (length == 127L) { length = 0; repeat(8) { length = (length shl 8) or input.read().toLong() } }
        require(fin && length in 0..MAX_FRAME) { "frame too long or fragmented" }
        val mask = ByteArray(4).also { readFully(input, it) }
        val payload = ByteArray(length.toInt()).also { readFully(input, it) }
        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return when (opcode) {
            0x1 -> Frame.Text(payload.toString(Charsets.UTF_8))
            0x8 -> Frame.Close
            0x9 -> Frame.Ping(payload)
            0xA -> Frame.Pong
            else -> throw IllegalArgumentException("unsupported frame")
        }
    }

    /** A server frame (never masked): text, pong or close. */
    fun write(output: OutputStream, opcode: Int, payload: ByteArray) {
        val out = ByteArrayOutputStream(payload.size + 10)
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> { out.write(126); out.write(payload.size shr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt()) }
        }
        out.write(payload)
        output.write(out.toByteArray()); output.flush()
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var offset = 0
        while (offset < into.size) {
            val n = input.read(into, offset, into.size - offset)
            if (n < 0) throw java.io.EOFException()
            offset += n
        }
    }
}
