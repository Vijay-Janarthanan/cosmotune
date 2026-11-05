package com.cosmotune.music.wear

import java.io.DataInput
import java.io.DataOutput
import java.io.IOException
import java.io.InputStream

/**
 * The rules both ends of [WearProtocol] apply, kept free of Android, Media3 and
 * Play services types so they run in a plain JVM test.
 *
 * Compiled into `:app`, `:tv` and `:wearos` with [WearProtocol]. The phone's
 * command service and stream server, and the watch's link, speaker and stream
 * reader, go through these functions rather than each spelling out the
 * framing, so a test of this file is a test of what both sides put on the wire.
 */
object WearWire {

    /** Media3's Player.REPEAT_MODE_* values, which [WearProtocol.KEY_REPEAT] carries. */
    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2

    // ---- Commands, watch -> phone ----

    fun commandPath(command: String): String = "${WearProtocol.PATH_COMMAND}/$command"

    /** The command a [WearProtocol.PATH_COMMAND] message carries, or null for any other path. */
    fun commandOf(path: String?): String? {
        if (path == null || !path.startsWith(WearProtocol.PATH_COMMAND + "/")) return null
        return path.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    /** The command under [WearProtocol.PATH_SPEAKER], or null for any other path. */
    fun speakerCommandOf(path: String?): String? {
        if (path == null || !path.startsWith(WearProtocol.PATH_SPEAKER + "/")) return null
        return path.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    fun speakerPath(command: String): String = "${WearProtocol.PATH_SPEAKER}/$command"

    fun argOf(payload: ByteArray?): String = payload?.let { String(it, Charsets.UTF_8) }.orEmpty()

    /** [WearProtocol.CMD_VOLUME]'s signed step count; anything unreadable is no change. */
    fun volumeSteps(arg: String): Int = arg.trim().toIntOrNull()?.coerceIn(-MAX_VOLUME_STEPS, MAX_VOLUME_STEPS) ?: 0

    /** [WearProtocol.CMD_SEEK]'s target, never before the start; null when unreadable. */
    fun seekTarget(arg: String): Long? = arg.trim().toLongOrNull()?.coerceAtLeast(0L)

    /** [WearProtocol.CMD_SKIP_TO]'s queue index, only when it names an item of a [count]-long queue. */
    fun skipTarget(arg: String, count: Int): Int? = arg.trim().toIntOrNull()?.takeIf { it in 0 until count }

    /** The phone player's own repeat button cycle: off, all, one, off. */
    fun nextRepeatMode(mode: Int): Int = when (mode) {
        REPEAT_OFF -> REPEAT_ALL
        REPEAT_ALL -> REPEAT_ONE
        else -> REPEAT_OFF
    }

    /**
     * The rows [WearProtocol.PATH_QUEUE] carries: one played track above the
     * playing one, for context, and up to [ahead] after it.
     */
    fun queueWindow(current: Int, ahead: Int, count: Int): IntRange {
        if (count <= 0 || current !in 0 until count) return IntRange.EMPTY
        return (current - 1).coerceAtLeast(0)..(current + ahead.coerceAtLeast(0)).coerceAtMost(count - 1)
    }

    // ---- The watch as the speaker, phone -> watch ----

    /**
     * The phone wraps the queue itself, so the watch never loops the few
     * tracks it holds; only repeat-one, which stays inside the track, plays there.
     */
    fun watchRepeat(phoneMode: Int): Int = if (phoneMode == REPEAT_ONE) REPEAT_ONE else REPEAT_OFF

    /**
     * The stream level a [WearProtocol.SPEAKER_VOLUME] spec asks for: "+n"/"-n"
     * (or a bare "n") steps from [now], "=n" an absolute level, always within
     * 0..[max]. Null when the spec is unreadable, so nothing changes.
     */
    fun volumeTarget(spec: String, now: Int, max: Int): Int? {
        val text = spec.trim()
        val target = if (text.startsWith("=")) {
            text.drop(1).toIntOrNull() ?: return null
        } else {
            val steps = text.removePrefix("+").toIntOrNull() ?: return null
            now + steps
        }
        return target.coerceIn(0, max.coerceAtLeast(0))
    }

    /**
     * How much of what the watch holds after its playing track already matches
     * [wanted]: the length of the shared prefix, or null when the two are the
     * same and nothing needs touching. The watch then removes what follows the
     * prefix and appends the rest of [wanted] — never a blanket replace, which
     * would throw away the buffered next track.
     */
    fun keepAhead(held: List<String>, wanted: List<String>): Int? {
        var same = 0
        while (same < held.size && same < wanted.size && held[same] == wanted[same]) same++
        return if (same == held.size && same == wanted.size) null else same
    }

    // ---- The stream channel ([WearProtocol.PATH_STREAM]) ----

    /** What the watch asks for: the phone's media URI, a byte position and a length (-1 for open-ended). */
    data class StreamRequest(val uri: String, val position: Long, val length: Long) {
        fun writeTo(out: DataOutput) {
            out.writeUTF(uri)
            out.writeLong(position)
            out.writeLong(length)
        }

        companion object {
            fun readFrom(input: DataInput): StreamRequest {
                val uri = input.readUTF()
                val position = input.readLong()
                val length = input.readLong()
                if (position < 0L) throw IOException("negative stream position $position")
                if (length < -1L) throw IOException("bad stream length $length")
                return StreamRequest(uri, position, length)
            }
        }
    }

    /** The phone's answer before the bytes: whether it could open the track, and how much it can serve (-1 unknown). */
    data class StreamReply(val ok: Boolean, val length: Long) {
        fun writeTo(out: DataOutput) {
            out.writeByte(if (ok) WearProtocol.STREAM_OK else WearProtocol.STREAM_FAILED)
            out.writeLong(if (ok) length.coerceAtLeast(-1L) else -1L)
        }

        companion object {
            fun readFrom(input: DataInput): StreamReply {
                val status = input.readUnsignedByte()
                val length = input.readLong()
                return StreamReply(status == WearProtocol.STREAM_OK, if (status == WearProtocol.STREAM_OK) length else -1L)
            }
        }
    }

    /**
     * The watch's side of one read: the bytes after a [StreamReply], never
     * more than [remaining] when that is known (-1 for open-ended).
     * [read] answers -1 at the end, the way an InputStream does.
     */
    class StreamBody(private val input: InputStream, remaining: Long) {
        var remaining: Long = remaining
            private set

        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val want = if (remaining < 0L) length else minOf(length.toLong(), remaining).toInt()
            val read = input.read(buffer, offset, want)
            if (read == -1) return -1
            if (remaining > 0L) remaining -= read
            return read
        }
    }

    /** How many bytes the watch's player should expect: its own bound when it set one, else what the phone said. */
    fun expectedLength(requested: Long, reply: StreamReply): Long = if (requested >= 0L) requested else reply.length

    private const val MAX_VOLUME_STEPS = 100
}
