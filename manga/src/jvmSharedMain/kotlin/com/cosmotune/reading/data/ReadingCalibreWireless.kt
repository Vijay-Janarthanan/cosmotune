package com.cosmotune.reading.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.*
import java.net.*
import java.security.MessageDigest

/** A calibre found on the network, ready for a wireless connection. */
data class CalibreServer(val name: String, val host: String, val port: Int)

/**
 * calibre's wireless device connection ("Connect/share › Start wireless device connection" in calibre): Read connects as a device,
 * and the books calibre sends to it land in the Library. Written for Cosmotune from calibre's documented smart-device protocol:
 * length-prefixed JSON messages `[opcode, {…}]`, calibre asking and the device answering. Read reports an empty device, so calibre
 * only ever sends; nothing on this device is read or changed by calibre.
 */
class ReadingCalibreWireless(private val deviceName: String, private val inbox: File, private val onBook: suspend (name: String, file: File) -> Unit) {
    sealed interface Status {
        data object Idle : Status
        data class Connected(val library: String, val received: Int) : Status
        data class Receiving(val name: String, val index: Int, val total: Int) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status
    private var socket: Socket? = null

    companion object {
        /** The UDP ports calibre listens on for a device's hello. */
        val DISCOVERY_PORTS = listOf(54982, 48123, 39001, 44044, 59678)
        const val DEFAULT_PORT = 9090
        internal const val OK = 0; internal const val SET_CALIBRE_DEVICE_INFO = 1; internal const val SET_CALIBRE_DEVICE_NAME = 2
        internal const val GET_DEVICE_INFORMATION = 3; internal const val TOTAL_SPACE = 4; internal const val FREE_SPACE = 5
        internal const val GET_BOOK_COUNT = 6; internal const val SEND_BOOKLISTS = 7; internal const val SEND_BOOK = 8
        internal const val GET_INITIALIZATION_INFO = 9; internal const val BOOK_DONE = 11; internal const val NOOP = 12
        internal const val DELETE_BOOK = 13; internal const val GET_BOOK_FILE_SEGMENT = 14; internal const val GET_BOOK_METADATA = 15
        internal const val SEND_BOOK_METADATA = 16; internal const val DISPLAY_MESSAGE = 17; internal const val CALIBRE_BUSY = 18
        internal const val SET_LIBRARY_INFO = 19; internal const val ERROR = 20
        private const val MAX_MESSAGE = 16 * 1024 * 1024
        private const val MAX_BOOK = 1L * 1024 * 1024 * 1024
        /** Formats Read takes from calibre, best first (calibre sends the first one a book has). */
        val EXTENSIONS = listOf("epub", "azw3", "mobi", "fb2", "pdf", "docx", "rtf", "txt", "htmlz", "cbz", "chm", "md")

        /** calibre's answer to a hello: "calibre wireless device client (on DESKTOP-1);9090,8080". */
        internal fun parseReply(text: String, host: String): CalibreServer? {
            val match = Regex("""^calibre wireless device client \(on (.{0,120})\);(\d{1,5}),(\d{1,5})""").find(text.trim()) ?: return null
            val port = match.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
            return CalibreServer(match.groupValues[1].ifBlank { host }, host, port)
        }

        /** One message as calibre frames it: the JSON's length in digits, then the JSON. */
        internal fun frame(opcode: Int, body: JsonObject): ByteArray {
            val text = buildJsonArray { add(opcode); add(body) }.toString().toByteArray()
            return text.size.toString().toByteArray() + text
        }

        /** The next message from calibre: its opcode and its arguments; null when calibre closed the connection. */
        internal fun read(input: InputStream): Pair<Int, JsonObject>? {
            var length = 0
            var digits = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == '['.code) break
                require(b in '0'.code..'9'.code && digits < 9) { "calibre sent something Read does not understand." }
                length = length * 10 + (b - '0'.code); digits++
            }
            require(length in 2..MAX_MESSAGE) { "calibre sent a message that is too large." }
            val bytes = ByteArray(length)
            bytes[0] = '['.code.toByte()
            var read = 1
            while (read < length) { val n = input.read(bytes, read, length - read); if (n < 0) return null; read += n }
            val array = Json.parseToJsonElement(bytes.decodeToString()).jsonArray
            return array[0].jsonPrimitive.int to (array.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap()))
        }

        /** calibre's password check: SHA-1 of the password and its challenge, in hex; blank with no password. */
        internal fun passwordHash(password: String, challenge: String): String =
            if (password.isEmpty() || challenge.isEmpty()) "" else MessageDigest.getInstance("SHA-1").digest((password + challenge).toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Says hello on every calibre port and gives the calibres that answer within [waitMs]. */
    suspend fun discover(waitMs: Long = 2_500): List<CalibreServer> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, CalibreServer>()
        DatagramSocket().use { udp ->
            udp.broadcast = true
            udp.soTimeout = 300
            val hello = "hello".toByteArray()
            val targets = listOf(InetAddress.getByName("255.255.255.255")) + broadcastAddresses()
            targets.distinct().forEach { target -> DISCOVERY_PORTS.forEach { port -> runCatching { udp.send(DatagramPacket(hello, hello.size, target, port)) } } }
            val until = System.currentTimeMillis() + waitMs
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < until && isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try { udp.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val host = packet.address.hostAddress ?: continue
                parseReply(String(packet.data, 0, packet.length), host)?.let { found["$host:${it.port}"] = it }
            }
        }
        found.values.toList()
    }

    /**
     * Connects to calibre at [host]:[port] and serves it until it lets go or [disconnect]; books it sends are added with [onBook].
     * [password] is the one set in calibre's wireless dialog, if any.
     */
    suspend fun connect(host: String, port: Int, password: String = "") = withContext(Dispatchers.IO) {
        val connection = Socket()
        socket = connection
        try {
            connection.connect(InetSocketAddress(host.trim(), port), 8_000)
            connection.soTimeout = 0
            serve(connection.getInputStream().buffered(), connection.getOutputStream().buffered(), password)
            if (_status.value !is Status.Failed) _status.value = Status.Idle
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (socket != null) _status.value = Status.Failed(e.message?.takeIf { "calibre" in it } ?: "calibre could not be reached at $host:$port.") }
        finally { runCatching { connection.close() }; socket = null }
    }

    fun disconnect() { val open = socket; socket = null; runCatching { open?.close() }; _status.value = Status.Idle }

    /** Answers calibre's messages on [input] and [output] until it closes. */
    internal suspend fun serve(input: InputStream, output: OutputStream, password: String = "") {
        var library = "calibre"
        var received = 0
        fun send(opcode: Int, body: JsonObject = JsonObject(emptyMap())) { output.write(frame(opcode, body)); output.flush() }
        while (currentCoroutineContext().isActive) {
            val (opcode, args) = read(input) ?: return
            when (opcode) {
                GET_INITIALIZATION_INFO -> {
                    library = args["currentLibraryName"]?.jsonPrimitive?.contentOrNull ?: library
                    val challenge = args["passwordChallenge"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    send(OK, buildJsonObject {
                        put("versionOK", true); put("maxBookContentPacketLen", 4096); put("acceptedExtensions", buildJsonArray { EXTENSIONS.forEach { add(it) } })
                        put("extensionPathLengths", buildJsonObject { EXTENSIONS.forEach { put(it, 200) } })
                        put("passwordHash", passwordHash(password, challenge))
                        put("canStreamBooks", true); put("canStreamMetadata", true); put("canReceiveBookBinary", true); put("canDeleteMultipleBooks", true)
                        put("canUseCachedMetadata", false); put("canSendOkToSendbook", true); put("canAcceptLibraryInfo", true); put("cacheUsesLpaths", true)
                        put("useUuidFileNames", false); put("isReadOnly", false); put("coverHeight", 240); put("ccVersionNumber", 391)
                        put("deviceKind", "Cosmotune Read"); put("deviceName", deviceName); put("appName", "Cosmotune")
                    })
                    _status.value = Status.Connected(library, received)
                }
                GET_DEVICE_INFORMATION -> send(OK, buildJsonObject {
                    put("device_info", buildJsonObject { put("device_store_uuid", "cosmotune-read-" + deviceName.hashCode().toUInt().toString(16)); put("device_name", deviceName) })
                    put("version", "1"); put("device_version", "1")
                })
                FREE_SPACE -> send(OK, buildJsonObject { put("free_space_on_device", inbox.usableSpace.coerceAtLeast(0)) })
                TOTAL_SPACE -> send(OK, buildJsonObject { put("total_space_on_device", inbox.totalSpace.coerceAtLeast(0)) })
                // Read shows an empty device: calibre then sends, and never asks for books back.
                GET_BOOK_COUNT -> send(OK, buildJsonObject { put("count", 0); put("willStream", true); put("willScan", true) })
                SEND_BOOK -> {
                    val length = args["length"]?.jsonPrimitive?.longOrNull ?: error("calibre sent a book without its size.")
                    require(length in 0..MAX_BOOK) { "calibre sent a book that is too large." }
                    val lpath = args["lpath"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val name = ReadingNearby.safeName(lpath.ifBlank { "book.epub" })
                    val index = (args["thisBook"]?.jsonPrimitive?.intOrNull ?: 0) + 1
                    val total = args["totalBooks"]?.jsonPrimitive?.intOrNull ?: 1
                    _status.value = Status.Receiving(name, index, total)
                    if (args["wantsSendOkToSendbook"]?.jsonPrimitive?.booleanOrNull == true) send(OK, buildJsonObject { put("lpath", lpath) })
                    inbox.mkdirs()
                    val file = File(inbox, "calibre-${System.nanoTime()}.part")
                    try {
                        file.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var left = length
                            while (left > 0) {
                                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                if (n < 0) error("calibre stopped in the middle of a book.")
                                out.write(buffer, 0, n); left -= n
                            }
                        }
                        onBook(name, file)
                        received++
                    } finally { file.delete() }
                    _status.value = Status.Connected(library, received)
                }
                DELETE_BOOK -> {
                    send(OK)
                    (args["lpaths"] as? JsonArray).orEmpty().forEach { path -> send(OK, buildJsonObject { put("uuid", ""); put("lpath", path) }) }
                }
                NOOP -> when {
                    args["ejecting"]?.jsonPrimitive?.booleanOrNull == true -> { send(OK); return }
                    args.containsKey("count") || args.containsKey("priKey") -> Unit
                    else -> send(OK)
                }
                SEND_BOOKLISTS, SEND_BOOK_METADATA -> Unit
                DISPLAY_MESSAGE -> {
                    val message = args["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (args["messageKind"]?.jsonPrimitive?.intOrNull == 1) { _status.value = Status.Failed("calibre did not accept the password."); return }
                    if (message.isNotBlank()) _status.value = Status.Failed("calibre: " + message.take(200))
                }
                CALIBRE_BUSY -> { _status.value = Status.Failed("calibre is busy with another device."); return }
                else -> send(OK)
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }
    }.getOrDefault(emptyList())
}
