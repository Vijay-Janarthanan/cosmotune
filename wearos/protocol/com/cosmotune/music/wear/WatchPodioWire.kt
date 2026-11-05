package com.cosmotune.music.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Small, versioned catalogue messages. The watch never supplies a media/feed URL to the phone. */
object WatchPodioWire {
    const val PAGE_SIZE = 20
    const val MAX_ROWS = 300
    const val MAX_PAGE_BYTES = 48 * 1024
    enum class Kind { SAVED_RADIO, POPULAR_RADIO, PODCASTS, EPISODES }
    enum class RowKind { STATION, SHOW, EPISODE }
    data class Request(val id: Long, val kind: Kind, val parent: String = "", val offset: Int = 0, val refresh: Boolean = false)
    data class Row(val id: String, val kind: RowKind, val title: String, val subtitle: String = "", val durationMs: Long = 0, val resumeMs: Long = 0)
    data class Page(val request: Request, val rows: List<Row>, val more: Boolean = false, val cached: Boolean = false, val error: String = "")
    data class Selection(val id: Long, val row: String, val enqueue: Boolean = false)
    data class Action(val id: Long, val success: Boolean, val message: String)

    private fun request(out: DataOutputStream, request: Request) {
        require(request.parent.length <= 512 && request.offset in 0..MAX_ROWS)
        out.writeLong(request.id); out.writeInt(request.kind.ordinal); out.writeUTF(request.parent); out.writeInt(request.offset); out.writeBoolean(request.refresh)
    }
    private fun request(input: DataInputStream): Request = Request(input.readLong(), Kind.entries.getOrNull(input.readInt()) ?: error("Unknown Podio view"),
        input.readUTF().also { require(it.length <= 512) }, input.readInt().also { require(it in 0..MAX_ROWS) }, input.readBoolean())
    fun encodeRequest(value: Request) = encode(2048) { request(it, value) }
    fun decodeRequest(bytes: ByteArray) = decode(bytes, 2048, ::request)
    fun encodePage(value: Page) = encode(MAX_PAGE_BYTES) { out ->
        request(out, value.request)
        require(value.rows.size <= PAGE_SIZE)
        out.writeBoolean(value.more); out.writeBoolean(value.cached); out.writeUTF(value.error.take(300)); out.writeInt(value.rows.size)
        value.rows.forEach { row ->
            require(row.id.length in 1..512 && row.durationMs >= 0 && row.resumeMs >= 0)
            out.writeUTF(row.id); out.writeInt(row.kind.ordinal); out.writeUTF(row.title.take(180)); out.writeUTF(row.subtitle.take(180))
            out.writeLong(row.durationMs); out.writeLong(row.resumeMs)
        }
    }
    fun decodePage(bytes: ByteArray): Page = decode(bytes, MAX_PAGE_BYTES) { input ->
        val request = request(input)
        val more = input.readBoolean(); val cached = input.readBoolean(); val error = input.readUTF().also { require(it.length <= 300) }
        val count = input.readInt().also { require(it in 0..PAGE_SIZE) }
        val rows = List(count) { Row(input.readUTF().also { require(it.length in 1..512) },
            RowKind.entries.getOrNull(input.readInt()) ?: error("Unknown Podio row"),
            input.readUTF().also { require(it.length <= 180) }, input.readUTF().also { require(it.length <= 180) },
            input.readLong().also { require(it >= 0) }, input.readLong().also { require(it >= 0) }) }
        Page(request, rows, more, cached, error)
    }
    fun encodeSelection(value: Selection) = encode(2048) { out ->
        require(value.row.length in 1..512 && value.row.none(Char::isISOControl))
        out.writeLong(value.id); out.writeUTF(value.row); out.writeBoolean(value.enqueue)
    }
    fun decodeSelection(bytes: ByteArray): Selection = decode(bytes, 2048) { input ->
        Selection(input.readLong(), input.readUTF().also { require(it.length in 1..512 && it.none(Char::isISOControl)) }, input.readBoolean())
    }
    fun encodeAction(value: Action) = encode(2048) { out -> out.writeLong(value.id); out.writeBoolean(value.success); out.writeUTF(value.message.take(300)) }
    fun decodeAction(bytes: ByteArray): Action = decode(bytes, 2048) { input -> Action(input.readLong(), input.readBoolean(), input.readUTF().also { require(it.length <= 300) }) }
    private fun encode(limit: Int, body: (DataOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out -> out.writeInt(1); body(out) }
        bytes.toByteArray().also { require(it.size <= limit) }
    }
    private fun <T> decode(bytes: ByteArray, limit: Int, body: (DataInputStream) -> T): T {
        require(bytes.size in 4..limit)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == 1)
            body(input).also { require(input.available() == 0) }
        }
    }
}
