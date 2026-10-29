package com.cosmotune.providers

import com.cosmotune.music.data.Http
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A package's own network boundary: declared domains, DNS checks, bounded metadata and isolated cookies. */
class ProviderNetwork(private val permissions: ProviderPermissions, private val allowLocalNetwork: Boolean = false,
    baseClient: OkHttpClient = Http.client, private val allowPublicArtwork: Boolean = false,
    private val localArtworkOrigin: HttpUrl? = null, private val cookieJar: ProviderCookieJar = ProviderCookieJar()) {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .dns { host -> baseClient.dns.lookup(host).also { validateAddresses(it, allowLocalNetwork && (!allowPublicArtwork || declared(host))) } }.build()

    private fun declared(host: String): Boolean = permissions.network.any { domain ->
        val allowed = domain.lowercase(java.util.Locale.ROOT)
        if (allowed.startsWith("*.")) host.endsWith(allowed.drop(1)) && host != allowed.drop(2) else host == allowed
    }

    internal fun address(raw: String): HttpUrl {
        val url = raw.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) { "Provider addresses cannot contain user credentials" }
        val configuredArtworkOrigin = allowPublicArtwork && localArtworkOrigin != null && url.host == localArtworkOrigin.host &&
            url.scheme == localArtworkOrigin.scheme && url.port == localArtworkOrigin.port
        require(url.isHttps || configuredArtworkOrigin || !allowPublicArtwork && permissions.allowHttp && url.scheme == "http") { "Provider requires HTTPS" }
        require(declared(url.host) || allowPublicArtwork) { "Provider network domain is not permitted" }
        if (allowPublicArtwork && localArtworkOrigin != null && declared(url.host)) {
            require(url.scheme == localArtworkOrigin.scheme && url.port == localArtworkOrigin.port) { "Artwork is outside the configured server origin" }
        }
        if (':' in url.host || url.host.matches(Regex("[0-9.]+"))) {
            validateAddresses(listOf(InetAddress.getByName(url.host)), allowLocalNetwork && (!allowPublicArtwork || declared(url.host)))
        }
        return url
    }

    fun cancel() { cancelled = true; calls.forEach { it.cancel() } }
    fun clearCookies() { cookieJar.clear() }
    internal fun fork(): ProviderNetwork = ProviderNetwork(permissions, allowLocalNetwork, client, cookieJar = cookieJar)

    internal fun <T> withResponse(raw: String, options: JsonObject, media: Boolean = false, followRedirects: Boolean = true,
        responseTimeoutSeconds: Long? = null,
        consume: (okhttp3.Response) -> T): T {
        check(!cancelled) { "Provider request cancelled" }
        var url = address(raw)
        var method = options["method"]?.jsonPrimitive?.contentOrNull?.uppercase(java.util.Locale.ROOT) ?: "GET"
        require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")) { "Unsupported provider HTTP method" }
        var body = options["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(body.toByteArray().size <= MAX_METADATA_BYTES) { "Provider request body exceeds 2 MB" }
        var headers = (options["headers"] as? JsonObject).orEmpty().mapValues { (_, value) -> value.jsonPrimitive.content }
        require(headers.size <= 100 && headers.entries.all { it.key.length <= 100 && it.value.length <= 8_192 })
        headers = headers.filterKeys { it.lowercase(java.util.Locale.ROOT) !in BLOCKED_HEADERS }
        val visited = HashSet<String>()
        repeat(6) {
            check(!cancelled) { "Provider request cancelled" }
            require(visited.add(url.toString())) { "Provider redirect loop" }
            val builder = Request.Builder().url(url).header("User-Agent", "Cosmotune/1.0.12")
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (media) builder.header("Accept-Encoding", "identity")
            val held = cookieJar.request(url)
            if (held.header != null && headers.keys.none { it.equals("cookie", true) }) builder.header("Cookie", held.header)
            builder.method(method, if (method in setOf("GET", "HEAD")) null else body.toRequestBody(
                headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.toMediaTypeOrNull()))
            require(responseTimeoutSeconds == null || responseTimeoutSeconds in 1..60)
            val transport = if (responseTimeoutSeconds != null) client.newBuilder()
                .readTimeout(responseTimeoutSeconds, TimeUnit.SECONDS).callTimeout(responseTimeoutSeconds, TimeUnit.SECONDS).build() else client
            val call = transport.newCall(builder.build())
            if (media) call.timeout().timeout(5, TimeUnit.MINUTES)
            calls.add(call)
            try {
                check(!cancelled) { "Provider request cancelled" }
                call.execute().use { reply ->
                    check(!cancelled) { "Provider request cancelled" }
                    val received = okhttp3.Cookie.parseAll(url, reply.headers)
                    cookieJar.receive(received, held.generation)
                    if (followRedirects && reply.code in setOf(301, 302, 303, 307, 308)) {
                        val next = address((url.resolve(reply.header("Location").orEmpty()) ?: error("Invalid provider redirect")).toString())
                        require(!url.isHttps || next.isHttps) { "Provider redirect cannot downgrade HTTPS" }
                        if (url.scheme != next.scheme || url.host != next.host || url.port != next.port) headers = emptyMap()
                        if (reply.code == 303 || reply.code in setOf(301, 302) && method == "POST") { method = "GET"; body = "" }
                        url = next
                    } else {
                        return consume(reply)
                    }
                }
            } finally { calls.remove(call) }
        }
        error("Provider redirected too many times")
    }

    fun request(raw: String, options: JsonObject, followRedirects: Boolean = true, responseTimeoutSeconds: Long? = null): JsonObject =
        withResponse(raw, options, followRedirects = followRedirects, responseTimeoutSeconds = responseTimeoutSeconds) { reply ->
        val payload = reply.body
        require(payload.contentLength() <= MAX_METADATA_BYTES) { "Provider metadata exceeds 2 MB" }
        val bytes = payload.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                check(!cancelled) { "Provider request cancelled" }
                val count = input.read(buffer)
                if (count < 0) break
                require(out.size() + count <= MAX_METADATA_BYTES) { "Provider metadata exceeds 2 MB" }
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        buildJsonObject {
            put("status", reply.code); put("statusCode", reply.code); put("ok", reply.isSuccessful)
            put("url", reply.request.url.toString()); put("body", bytes.toString(Charsets.UTF_8))
            putJsonObject("headers") { reply.headers.names().forEach { name -> put(name.lowercase(java.util.Locale.ROOT), reply.headers.values(name).joinToString(", ")) } }
            if (options["asFetch"]?.jsonPrimitive?.booleanOrNull == true) put("binary", Base64.getEncoder().encodeToString(bytes))
        }
    }

    internal companion object {
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private val BLOCKED_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "proxy-authorization", "accept-encoding")
        fun validateAddresses(addresses: List<InetAddress>, allowLocal: Boolean) {
            if (addresses.isEmpty() || !allowLocal && addresses.any(::privateAddress)) throw UnknownHostException("Provider DNS resolved to a private or unavailable address")
        }
        private fun privateAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
            val bytes = address.address
            val first = bytes[0].toInt() and 255
            return if (bytes.size == 4) first == 0 || first >= 224 || first == 100 && (bytes[1].toInt() and 255) in 64..127
                else first and 254 == 252
        }
    }
}
