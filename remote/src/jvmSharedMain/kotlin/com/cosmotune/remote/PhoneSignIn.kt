package com.cosmotune.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Signing the computer in from the phone. The computer shows a QR code of a page it serves on the home network; the phone's
 * camera opens that page, which hands Cosmotune on the phone a `cosmotune://signin` link. After the listener agrees there, the
 * phone sends its YouTube Music session to the computer sealed with AES-GCM under a key that only the QR code carries: the key
 * sits in the page address's fragment, so it never crosses the network, and the computer takes one session and stops listening.
 */
object PhoneSignIn {

    /** Where the computer listens ([origin], plain http on the home network), its one-time [code] and the sealing [key]. */
    data class Link(val origin: String, val code: String, val key: String) {
        /** The computer's address as the phone shows it, without the port. */
        val computer: String get() = URI(origin).host

        fun appLink(): String = "cosmotune://signin?to=${enc(origin)}&c=${enc(code)}&k=${enc(key)}"

        override fun toString() = "PhoneSignIn.Link($origin, key hidden)"

        companion object {
            /** The link from the page, or null when it is not one or names anywhere but a computer on this network. */
            fun parse(text: String): Link? = runCatching {
                val uri = URI(text)
                if (uri.scheme != "cosmotune" || uri.host != "signin") return null
                val query = uri.rawQuery?.split('&').orEmpty().mapNotNull { part ->
                    val at = part.indexOf('=')
                    if (at < 1) null else part.substring(0, at) to URLDecoder.decode(part.substring(at + 1), "UTF-8")
                }.toMap()
                val origin = query["to"] ?: return null
                val code = query["c"]?.takeIf { it.matches(TOKEN) } ?: return null
                val key = query["k"]?.takeIf { it.matches(TOKEN) } ?: return null
                val target = URI(origin)
                if (target.scheme != "http" || target.port !in 1..65535 || target.rawPath.orEmpty().isNotEmpty()) return null
                if (!target.host.orEmpty().matches(IPV4)) return null
                val address = InetAddress.getByName(target.host)
                if (address !is Inet4Address || !(address.isSiteLocalAddress || address.isLoopbackAddress)) return null
                Link(origin, code, key)
            }.getOrNull()

            private val TOKEN = Regex("[A-Za-z0-9_-]{4,64}")
            private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        }
    }

    fun newKey(): SecretKey = SecretKeySpec(ByteArray(32).also { random.nextBytes(it) }, "AES")
    fun textOf(key: SecretKey): String = b64(key.encoded)
    fun keyOf(text: String): SecretKey = SecretKeySpec(Base64.getUrlDecoder().decode(text), "AES")

    /** [text] sealed under [key]: a fresh nonce and the AES-GCM text, as URL-safe Base64. */
    fun seal(key: SecretKey, text: String): String {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        return b64(nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** What [seal] sealed, or null when the key is wrong or the text was changed. */
    fun open(key: SecretKey, sealed: String): String? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(sealed.trim())
        if (bytes.size < 12 + 16) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
        }
        cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
    }.getOrNull()

    /** The phone's half: sends [cookie] to the computer of [link], sealed. Throws when the computer did not take it. */
    suspend fun send(link: Link, cookie: String) = withContext(Dispatchers.IO) {
        val body = seal(keyOf(link.key), cookie).toByteArray(Charsets.US_ASCII)
        val connection = URI("${link.origin}/signin?c=${enc(link.code)}").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "text/plain")
            connection.outputStream.use { it.write(body) }
            when (connection.responseCode) {
                200 -> Unit
                410 -> error("The computer already took a sign-in. Show the code again there.")
                else -> error("The computer did not accept the sign-in. Show the code again there.")
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The computer's half: listens on one address until one sealed session arrives ([cookie]) or it is closed. [pageUrl] is what
     * the QR code shows.
     */
    class Receiver private constructor(address: InetAddress) : AutoCloseable {
        private val listener = ServerSocket(0, 8, address)
        private val secret = newKey()
        private val closed = AtomicBoolean(false)
        private val taken = AtomicBoolean(false)
        private val received = CompletableDeferred<String>()

        val link = Link("http://${address.hostAddress}:${listener.localPort}", b64(ByteArray(12).also { random.nextBytes(it) }), textOf(secret))
        val pageUrl: String get() = "${link.origin}/signin?c=${link.code}#${link.key}"

        /** The session the phone sent. */
        val cookie: Deferred<String> get() = received

        private val acceptor = thread(name = "cosmotune-phone-sign-in", isDaemon = true) {
            while (!closed.get()) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                runCatching { socket.use(::serve) }
            }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 4_000
            val input = socket.getInputStream()
            fun line(): String {
                val out = ByteArrayOutputStream()
                while (out.size() < 4_096) {
                    val b = input.read()
                    if (b < 0 || b == '\n'.code) break
                    if (b != '\r'.code) out.write(b)
                }
                return out.toString("US-ASCII")
            }
            val request = line().split(' ')
            var length = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: -1
            }
            if (request.size != 3 || length !in 0..16_384) return respond(socket, 400, "")
            val path = request[1]
            if (path.substringBefore('?') != "/signin" || path.substringAfter("c=", "") != link.code) return respond(socket, 404, "")
            when (request[0]) {
                "GET" -> respond(socket, 200, page(), "text/html; charset=utf-8")
                "POST" -> {
                    val body = ByteArray(length)
                    var at = 0
                    while (at < length) {
                        val read = input.read(body, at, length - at)
                        if (read <= 0) return respond(socket, 400, "")
                        at += read
                    }
                    if (taken.get()) return respond(socket, 410, "")
                    val cookie = open(secret, body.toString(Charsets.US_ASCII))?.takeIf { it.isNotBlank() }
                        ?: return respond(socket, 400, "")
                    if (!taken.compareAndSet(false, true)) return respond(socket, 410, "")
                    respond(socket, 200, "")
                    received.complete(cookie)
                }
                else -> respond(socket, 405, "")
            }
        }

        private fun page(): String {
            val origin = enc(link.origin)
            val code = enc(link.code)
            return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sign in your computer</title><style>
body{margin:0;font-family:system-ui,sans-serif;background:#0b0b0c;color:#f4efe9;display:flex;min-height:100vh;align-items:center;justify-content:center}
main{max-width:420px;padding:24px}h1{font-size:24px;margin:0 0 12px}p{color:#b9b2aa;line-height:1.45;margin:0 0 16px}
a{display:inline-block;background:#ffb98f;color:#1c1210;border-radius:999px;padding:14px 22px;font-weight:700;text-decoration:none}
</style></head><body><main><h1>Sign in your computer</h1>
<p>Open Cosmotune on this phone to send its YouTube Music sign-in to Cosmotune on your computer. You will be asked first.</p>
<a id="open" href="#">Open Cosmotune</a>
<p style="margin-top:16px">Not signed in on this phone? Sign in there first, then scan the code again.</p></main>
<script>document.getElementById('open').href='cosmotune://signin?to=$origin&c=$code&k='+encodeURIComponent(location.hash.slice(1));</script>
</body></html>"""
        }

        private fun respond(socket: Socket, status: Int, text: String, type: String = "text/plain; charset=utf-8") {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val reason = when (status) { 200 -> "OK"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 410 -> "Gone"; else -> "Bad Request" }
            val head = "HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n\r\n"
            socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { listener.close() }
            received.cancel()
            if (Thread.currentThread() !== acceptor) acceptor.join(1_000)
        }

        companion object {
            /** Listens on [address], a home-network (or loopback) IPv4 address of this computer. */
            fun start(address: InetAddress): Receiver {
                require(address is Inet4Address && (address.isSiteLocalAddress || address.isLoopbackAddress)) { "Choose a home network address" }
                return Receiver(address)
            }

            /** This computer's home-network address the phone is most likely to reach, or null when there is none. */
            fun homeAddress(): InetAddress? = LanRemoteServer.localAddresses().firstOrNull { !it.isLoopbackAddress }
        }
    }

    private val random = SecureRandom()
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
}
