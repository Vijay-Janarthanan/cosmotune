package com.cosmotune.reading.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** How a device nearby describes itself (the LocalSend protocol's device info). */
@Serializable data class NearbyInfo(val alias: String, val version: String = "2.1", val deviceModel: String? = null, val deviceType: String? = null,
    val fingerprint: String, val port: Int = ReadingNearby.PORT, val protocol: String = "http", val download: Boolean = false, val announce: Boolean? = null)

/** A device found on the same network: a Cosmotune, or any app that speaks LocalSend. */
data class NearbyDevice(val info: NearbyInfo, val host: String) {
    val id: String get() = info.fingerprint
    val name: String get() = info.alias
    val kind: String get() = listOfNotNull(info.deviceModel, when (info.deviceType) { "mobile" -> "phone"; "desktop" -> "computer"; else -> null }).joinToString(" · ")
}

/** A file another device offers to send. */
@Serializable data class NearbyFile(val id: String, val fileName: String, val size: Long, val fileType: String = "application/octet-stream")

/** Books another device wants to send here, waiting for the listener's yes or no. */
class NearbyOffer internal constructor(val from: NearbyInfo, val files: List<NearbyFile>, internal val answer: CompletableDeferred<Boolean>) {
    /** The files Read can open; the rest are turned down. */
    val books: List<NearbyFile> get() = files.filter { ReadingNearby.isBook(it.fileName) }
}

/**
 * Sending books to devices on the same network and taking them from there, by the LocalSend protocol (v2), so a phone and a
 * computer running Cosmotune find each other, and so do the LocalSend apps. Nothing leaves the local network: devices are found
 * by a multicast hello (and, when that is blocked, by asking the addresses next to this one), and files go straight between them
 * over HTTP. Receiving runs only while the listener has Nearby open, and each sending device's books wait for a yes.
 */
class ReadingNearby(alias: String, deviceModel: String, deviceType: String, client: OkHttpClient, private val inbox: File,
    /** Where to listen (every network by default; the tests keep to this machine) and whether to say hello by multicast. */
    private val bindAddress: InetAddress? = null, private val discover: Boolean = true,
    private val onReceived: suspend (name: String, file: File) -> Unit) {
    private val me = NearbyInfo(alias.take(60), deviceModel = deviceModel.take(60), deviceType = deviceType,
        fingerprint = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })
    private val http = client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(90, TimeUnit.SECONDS)
        .followRedirects(false).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private val _devices = MutableStateFlow<List<NearbyDevice>>(emptyList())
    private val _offer = MutableStateFlow<NearbyOffer?>(null)
    private val _receiving = MutableStateFlow<Pair<Int, Int>?>(null)
    /** Devices found nearby, newest last. */
    val devices: StateFlow<List<NearbyDevice>> = _devices
    /** Books another device offers now. */
    val offer: StateFlow<NearbyOffer?> = _offer
    /** While books come in: how many have arrived, of how many. */
    val receiving: StateFlow<Pair<Int, Int>?> = _receiving
    val name: String get() = me.alias
    private var work: CoroutineScope? = null
    private var server: ServerSocket? = null
    private var multicast: MulticastSocket? = null
    private var session: Session? = null
    private var port = PORT

    private class Session(val id: String, val from: String, val files: Map<String, NearbyFile>, val tokens: Map<String, String>, val done: MutableSet<String> = mutableSetOf())

    /** Starts being seen and receiving; stays on until [stop]. */
    fun start() {
        if (work != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { work = it }
        val bound = CompletableDeferred<Unit>()
        scope.launch {
            // LocalSend's own port when it is free; any other one otherwise (the hello says which).
            val socket = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bindAddress, PORT)) } }.getOrNull()
                ?: ServerSocket().apply { bind(InetSocketAddress(bindAddress, 0)) }
            server = socket
            port = socket.localPort
            bound.complete(Unit)
            serve(socket, scope)
        }
        if (discover) {
            scope.launch { listenForHellos() }
            scope.launch { bound.await(); while (isActive) { announce(); delay(if (_devices.value.isEmpty()) 5_000 else 30_000) } }
        }
        ready = bound
    }

    private var ready: CompletableDeferred<Unit>? = null

    /** This device as others see it, once it is receiving. */
    internal suspend fun info(): NearbyInfo { ready?.await(); return me.copy(port = port, announce = null) }

    fun stop() {
        work?.cancel(); work = null
        runCatching { server?.close() }; server = null
        runCatching { multicast?.close() }; multicast = null
        _offer.value?.answer?.complete(false); _offer.value = null
        session = null; _receiving.value = null
    }

    /** The listener's answer to [offer]. */
    fun answer(offer: NearbyOffer, accept: Boolean) { offer.answer.complete(accept); if (_offer.value === offer) _offer.value = null }

    /** Looks for devices again: a fresh hello, then asking each address on this network directly (for networks that block hellos). */
    suspend fun search() = coroutineScope {
        announce()
        val own = localAddresses()
        own.flatMap { address -> (1..254).map { "${address.substringBeforeLast('.')}.$it" } }.filterNot { it in own }.distinct()
            .chunked(64).forEach { batch -> batch.map { host -> async(Dispatchers.IO) {
                // Cosmotune answers over HTTP; LocalSend apps mostly over HTTPS, whose certificate is then the one sends are pinned to.
                if (runCatching { register(host, PORT, "http") }.isFailure) runCatching { register(host, PORT, "https") }
            } }.awaitAll() }
    }

    /**
     * Sends [files] (a name and the file) to [device]: it is asked first, then each file goes in turn; [onProgress] hears how many
     * are done. Fails with a plain reason when the other device says no or is busy.
     */
    suspend fun send(device: NearbyDevice, files: List<Pair<String, File>>, onProgress: (Int) -> Unit = {}) {
        require(files.isNotEmpty()) { "Choose books to send." }
        val offered = files.mapIndexed { index, (name, file) -> NearbyFile("f$index", safeName(name), file.length(), mimeOf(name)) }
        val body = buildJsonObject {
            put("info", json.encodeToJsonElement(NearbyInfo.serializer(), me.copy(port = port, announce = null)))
            put("files", buildJsonObject { offered.forEach { put(it.id, json.encodeToJsonElement(NearbyFile.serializer(), it)) } })
        }.toString().toRequestBody(JSON)
        val client = clientFor(device.info)
        val base = "${device.info.protocol}://${hostLiteral(device.host)}:${device.info.port}/api/localsend/v2"
        val answer = withContext(Dispatchers.IO) {
            client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build().newCall(Request.Builder().url("$base/prepare-upload").post(body).build()).execute().use { response ->
                when (response.code) {
                    200 -> json.parseToJsonElement(response.body.string()).jsonObject
                    204 -> null
                    403 -> error("${device.name} said no.")
                    409 -> error("${device.name} is taking files from another device. Try again in a moment.")
                    401 -> error("${device.name} asks for a PIN; turn the PIN off there to send from Cosmotune.")
                    else -> error("${device.name} could not take the books (HTTP ${response.code}).")
                }
            }
        } ?: return
        val sessionId = answer.text("sessionId")
        val tokens = answer["files"]?.jsonObject.orEmpty()
        var sent = 0
        offered.forEachIndexed { index, file ->
            val token = (tokens[file.id] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            val source = files[index].second
            val url = okhttp3.HttpUrl.Builder().scheme(device.info.protocol).host(device.host).port(device.info.port).addPathSegments("api/localsend/v2/upload")
                .addQueryParameter("sessionId", sessionId).addQueryParameter("fileId", file.id).addQueryParameter("token", token).build()
            val upload = object : RequestBody() {
                override fun contentType() = file.fileType.toMediaType()
                override fun contentLength() = source.length()
                override fun writeTo(sink: BufferedSink) { source.inputStream().use { input -> input.copyTo(sink.outputStream()) } }
            }
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url(url).post(upload).build()).execute().use { response ->
                    check(response.isSuccessful) { "${file.fileName} could not be sent (HTTP ${response.code})." }
                }
            }
            sent++
            onProgress(sent)
        }
    }

    // ---- Being found --------------------------------------------------------------------------------------------------------

    private fun hello(announce: Boolean) = json.encodeToString(NearbyInfo.serializer(), me.copy(port = port, announce = announce)).toByteArray()

    private fun announce() {
        runCatching { DatagramSocket().use { socket -> val data = hello(true); socket.send(DatagramPacket(data, data.size, GROUP, PORT)) } }
    }

    private suspend fun listenForHellos() {
        val socket = runCatching { MulticastSocket(PORT).apply { reuseAddress = true; @Suppress("DEPRECATION") joinGroup(GROUP) } }.getOrNull() ?: return
        multicast = socket
        val buffer = ByteArray(8 * 1024)
        while (currentCoroutineContext().isActive && !socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            runCatching { socket.receive(packet) }.onFailure { return }
            val info = runCatching { json.decodeFromString(NearbyInfo.serializer(), String(packet.data, 0, packet.length)) }.getOrNull() ?: continue
            if (info.fingerprint == me.fingerprint) continue
            val host = packet.address.hostAddress ?: continue
            found(NearbyDevice(info, host))
            // A device saying hello hears back from us, over HTTP as the protocol asks (by a hello reply when that fails).
            if (info.announce != false) work?.launch {
                if (runCatching { register(host, info.port, info.protocol, info) }.isFailure)
                    runCatching { DatagramSocket().use { reply -> val data = hello(false); reply.send(DatagramPacket(data, data.size, GROUP, PORT)) } }
            }
        }
    }

    private fun register(host: String, port: Int, protocol: String, known: NearbyInfo? = null) {
        val body = json.encodeToString(NearbyInfo.serializer(), me.copy(port = this.port, announce = null)).toRequestBody(JSON)
        val client = (known?.let(::clientFor) ?: if (protocol == "https") probing() else http).newBuilder().connectTimeout(700, TimeUnit.MILLISECONDS)
            .readTimeout(3, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url("$protocol://${hostLiteral(host)}:$port/api/localsend/v2/register").post(body).build()).execute().use { response ->
            check(response.isSuccessful)
            val info = json.decodeFromString(NearbyInfo.serializer(), response.body.string().take(16 * 1024))
            val fingerprint = info.fingerprint.ifBlank { known?.fingerprint ?: return }
            if (fingerprint != me.fingerprint) found(NearbyDevice(info.copy(fingerprint = fingerprint, port = known?.port ?: port, protocol = known?.protocol ?: protocol), host))
        }
    }

    private fun found(device: NearbyDevice) {
        _devices.value = _devices.value.filterNot { it.id == device.id || (it.host == device.host && it.info.port == device.info.port) } + device
    }

    // ---- Receiving ------------------------------------------------------------------------------------------------------------

    private suspend fun serve(socket: ServerSocket, scope: CoroutineScope) {
        val workers = kotlinx.coroutines.sync.Semaphore(4)
        while (scope.isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (!workers.tryAcquire()) { runCatching { client.close() }; continue }
            scope.launch { try { client.use { handle(it) } } catch (_: Exception) { } finally { workers.release() } }
        }
    }

    private suspend fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        val head = readHead(input) ?: return
        val requestLine = head.first().split(' ')
        if (requestLine.size < 2) return reply(output, 400)
        val method = requestLine[0]
        val target = URI(requestLine[1])
        val headers = head.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }.toMap()
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        val query = target.rawQuery.orEmpty().split('&').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { URLDecoder.decode(it[0], "UTF-8") to URLDecoder.decode(it[1], "UTF-8") } }.toMap()
        val from = socket.inetAddress.hostAddress.orEmpty()
        fun body(): String { require(length in 0..MAX_JSON); return String(input.readNBytesCompat(length.toInt())) }
        when {
            method == "POST" && target.path == "/api/localsend/v2/register" -> {
                runCatching { json.decodeFromString(NearbyInfo.serializer(), body()) }.getOrNull()?.takeIf { it.fingerprint != me.fingerprint }?.let { found(NearbyDevice(it, from)) }
                reply(output, 200, json.encodeToString(NearbyInfo.serializer(), me.copy(port = port, announce = null)))
            }
            method == "GET" && target.path == "/api/localsend/v2/info" -> reply(output, 200, json.encodeToString(NearbyInfo.serializer(), me.copy(port = port, announce = null)))
            method == "POST" && target.path == "/api/localsend/v2/prepare-upload" -> prepare(output, body(), from)
            method == "POST" && target.path == "/api/localsend/v2/upload" ->
                upload(if (headers["transfer-encoding"]?.contains("chunked", true) == true) ChunkedInput(input) else input, output, query,
                    if (headers["transfer-encoding"]?.contains("chunked", true) == true) -1 else length, from)
            method == "POST" && target.path == "/api/localsend/v2/cancel" -> {
                if (session?.id == query["sessionId"] && session?.from == from) { session = null; _receiving.value = null }
                reply(output, 200)
            }
            else -> reply(output, 404)
        }
    }

    private suspend fun prepare(output: OutputStream, text: String, from: String) {
        val request = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return reply(output, 400)
        val info = runCatching { json.decodeFromJsonElement(NearbyInfo.serializer(), request.getValue("info")) }.getOrNull() ?: return reply(output, 400)
        val files = request["files"]?.jsonObject?.values?.mapNotNull { runCatching { json.decodeFromJsonElement(NearbyFile.serializer(), it) }.getOrNull() }.orEmpty()
        if (session != null || _offer.value != null) return reply(output, 409)
        if (files.size > 200) return reply(output, 413)
        found(NearbyDevice(info, from))
        val offer = NearbyOffer(info, files, CompletableDeferred())
        if (offer.books.isEmpty()) return reply(output, 403)
        _offer.value = offer
        val yes = withTimeoutOrNull(120_000) { offer.answer.await() } ?: false
        if (_offer.value === offer) _offer.value = null
        if (!yes) return reply(output, 403)
        val tokens = offer.books.filter { it.size in 1..MAX_FILE }.associate { it.id to randomToken() }
        if (tokens.isEmpty()) return reply(output, 403)
        val started = Session(randomToken(), from, offer.books.associateBy { it.id }, tokens)
        session = started
        _receiving.value = 0 to tokens.size
        reply(output, 200, buildJsonObject { put("sessionId", started.id); put("files", buildJsonObject { tokens.forEach { (id, token) -> put(id, token) } }) }.toString())
    }

    private suspend fun upload(input: InputStream, output: OutputStream, query: Map<String, String>, length: Long, from: String) {
        val current = session
        val fileId = query["fileId"].orEmpty()
        if (current == null || current.id != query["sessionId"] || current.from != from || current.tokens[fileId] != query["token"] || fileId in current.done)
            return reply(output, 403)
        val file = current.files.getValue(fileId)
        // A length of -1 is a chunked body: read to its end, never past the size the sender offered.
        if ((length >= 0 && length != file.size) || file.size > MAX_FILE) return reply(output, 400)
        inbox.mkdirs()
        val target = File(inbox, "${current.id}-$fileId.part")
        try {
            var copied = 0L
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (copied < file.size) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), file.size - copied).toInt())
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    copied += count
                }
            }
            if (copied != file.size) return reply(output, 400)
            current.done += fileId
            _receiving.value = current.done.size to current.tokens.size
            // Added to the Library before the sender hears it arrived, so "sent" means it is there.
            runCatching { onReceived(safeName(file.fileName), target) }
            reply(output, 200)
        } finally {
            target.delete()
            if (session === current && current.done.size >= current.tokens.size) { session = null; _receiving.value = null }
        }
    }

    private fun reply(output: OutputStream, code: Int, body: String = "") {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; else -> "Error" }
        output.write(("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        output.write(bytes)
        output.flush()
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------------------

    /** A client for [info]'s device: plain HTTP, or HTTPS that takes only the certificate whose fingerprint it announced. */
    private fun clientFor(info: NearbyInfo): OkHttpClient {
        if (info.protocol != "https") return http
        val pinned = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No certificate")
                val seen = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
                if (!seen.equals(info.fingerprint, ignoreCase = true)) throw java.security.cert.CertificateException("Not the device that said hello")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinned), SecureRandom()) }
        return http.newBuilder().sslSocketFactory(context.socketFactory, pinned).hostnameVerifier { _, _ -> true }.build()
    }

    /**
     * For asking an HTTPS device who it is, before its fingerprint is known: any certificate is taken for that one question, and
     * what is sent afterwards goes only to the certificate whose fingerprint it then gave ([clientFor]).
     */
    private fun probing(): OkHttpClient {
        val any = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(any), SecureRandom()) }
        return http.newBuilder().sslSocketFactory(context.socketFactory, any).hostnameVerifier { _, _ -> true }.build()
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun hostLiteral(host: String) = if (':' in host) "[$host]" else host
    private fun randomToken() = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    companion object {
        const val PORT = 53317
        private val GROUP: InetAddress = InetAddress.getByName("224.0.0.167")
        private val JSON = "application/json".toMediaType()
        private const val MAX_JSON = 1L * 1024 * 1024
        private const val MAX_FILE = 1L * 1024 * 1024 * 1024

        /** Files Read can open from another device. */
        private val BOOKS = setOf("epub", "pdf", "mobi", "azw", "azw3", "prc", "fb2", "fbz", "txt", "html", "htm", "md", "markdown", "docx", "rtf", "mht", "mhtml", "cbz", "chm", "zip")
        fun isBook(name: String) = name.substringAfterLast('.', "").lowercase() in BOOKS

        /** A file name without folders or odd characters, at most 120 letters. */
        internal fun safeName(name: String): String {
            val bare = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("""[\u0000-\u001f<>:"|?*]"""), "_").trim().trimStart('.')
            return bare.ifBlank { "book" }.take(120)
        }

        private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "epub" -> "application/epub+zip"; "pdf" -> "application/pdf"; "txt" -> "text/plain"; "html", "htm" -> "text/html"; "cbz", "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

        /** The request's head lines (request line and headers), at most 8 KiB, or null when the client closed. */
        internal fun readHead(input: InputStream): List<String>? {
            val bytes = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                bytes.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) break
                if (bytes.size() > 8 * 1024) return null
            }
            return bytes.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        }

        /** A chunked HTTP body as one stream. */
        internal class ChunkedInput(private val source: InputStream) : InputStream() {
            private var left = 0L
            private var done = false
            private fun line(): String {
                val text = StringBuilder()
                while (true) {
                    val c = source.read()
                    if (c < 0 || c == '\n'.code) break
                    if (c != '\r'.code) text.append(c.toChar())
                    check(text.length <= 1024) { "A chunk line is too long." }
                }
                return text.toString()
            }
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (done) return -1
                if (left == 0L) {
                    left = line().substringBefore(';').trim().toLongOrNull(16) ?: error("A chunk size is not readable.")
                    if (left == 0L) { done = true; while (line().isNotEmpty()) Unit; return -1 }
                }
                val n = source.read(b, off, minOf(len.toLong(), left).toInt())
                check(n >= 0) { "The body was cut short." }
                left -= n
                if (left == 0L) line()
                return n
            }
        }

        private fun InputStream.readNBytesCompat(count: Int): ByteArray {
            val out = ByteArray(count)
            var read = 0
            while (read < count) { val n = read(out, read, count - read); if (n < 0) break; read += n }
            return if (read == count) out else out.copyOf(read)
        }
    }
}
