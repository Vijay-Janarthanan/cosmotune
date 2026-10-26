package com.cosmotune.providers

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Metadata host APIs shared by Android and desktop. Media/file APIs have a separate scoped host. */
class ProviderMetadataHost(private val install: ProviderInstall, private val store: ProviderStore,
    val network: ProviderNetwork = ProviderNetwork(install.manifest.permissions, install.allowLocalNetwork,
        cookieJar = ProviderCookieJar.forProvider(install, store))) {
    private val work = ProviderHostWork(network::cancel)
    private val mutableProgress = kotlinx.coroutines.flow.MutableStateFlow<ProviderWorkProgress?>(null)
    val progress: kotlinx.coroutines.flow.StateFlow<ProviderWorkProgress?> get() = mutableProgress
    private val progressLock = Any()
    private var lastProgress = 0L
    @Volatile private var status = "Working with provider"
    private fun report(value: ProviderWorkProgress, force: Boolean = false) = synchronized(progressLock) {
        val now = System.nanoTime()
        if (force || now - lastProgress >= 100_000_000) { lastProgress = now; mutableProgress.value = value }
    }
    private fun fileProgress(bytes: Long, total: Long?, completed: Int? = null, count: Int? = null) {
        work.report(bytes, total, completed, count)
        report(ProviderWorkProgress(status, bytes, total, segmentsCompleted = completed, segmentsTotal = count), total == bytes)
    }
    internal val files by lazy { ProviderFiles(java.io.File(store.root, "workspaces/${install.manifest.name}"), network,
        progress = { bytes, total -> fileProgress(bytes, total) },
        segmentProgress = { bytes, total, completed, count -> fileProgress(bytes, total, completed, count) }) }
    fun cancel() { network.cancel(); work.close(); mutableProgress.value = null }
    private val auth by lazy { ProviderAuth(install, store, network) }
    private val session by lazy { ProviderSignedSession(install, store, network) }
    @Volatile private var started = System.nanoTime()
    fun beginOperation() { check(!network.isCancelled); started = System.nanoTime(); synchronized(progressLock) { lastProgress = 0; mutableProgress.value = null }; status = "Working with provider" }

    fun bridge(method: String, encoded: String): String {
        return try {
            require(encoded.toByteArray().size <= ProviderScripts.MAX_RESULT_BYTES)
            check(!network.isCancelled) { "Provider request cancelled" }
            val args = Json.parseToJsonElement(encoded).jsonArray
            val response = when {
                method == "work.poll" -> work.poll(args[0].jsonPrimitive.content)
                method == "work.cancel" -> { work.cancel(args[0].jsonPrimitive.content); buildJsonObject { put("value", JsonNull) } }
                method in setOf("file.download", "file.downloadSegments", "file.transformPatternedBlocks") -> {
                    require(install.manifest.permissions.file) { "Provider file permission denied" }
                    val options = args.getOrNull(2) as? JsonObject
                    val explicit = (args.getOrNull(3) as? JsonObject)?.takeIf { it["__callback"] != null }
                    val callback = ((explicit ?: options?.get("onProgress")) as? JsonObject)?.get("__callback")
                        ?.jsonPrimitive?.longOrNull
                    status = if (method == "file.transformPatternedBlocks") "Processing provider audio" else "Downloading provider audio"
                    report(ProviderWorkProgress(status), force = true)
                    work.start(callback) { call(method, args) }
                }
                else -> {
                    if (method.startsWith("file.") && method !in setOf("file.exists", "file.read", "file.readBytes", "file.getSize"))
                        check(!work.isRunning) { "File mutation cannot overlap a running provider transfer" }
                    buildJsonObject { put("value", call(method, args)) }
                }
            }
            response.toString().also {
                require(it.toByteArray().size <= ProviderScripts.MAX_RESULT_BYTES) { "Provider host result exceeds 2 MB" }
            }
        } catch (error: Exception) {
            // Parser/network exceptions can contain a URL, header or token. They never enter logs or guest errors.
            buildJsonObject { put("failure", safeError(error)) }.toString()
        }
    }

    private fun call(method: String, args: JsonArray): JsonElement {
        fun text(index: Int) = (args.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
        val group = method.substringBefore('.')
        val action = method.substringAfter('.', "")
        return when (group) {
            "http" -> when (action) {
                "clearCookies" -> { network.clearCookies(); JsonPrimitive(true) }
                "request" -> try { network.request(text(0), args.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap())) }
                    catch (error: Exception) { buildJsonObject { put("ok", false); put("status", 0); put("statusCode", 0); put("error", safeError(error)) } }
                else -> error("Unsupported provider HTTP API")
            }
            "storage", "credentials" -> storage(group, action, args)
            "auth" -> auth.call(action, args)
            "session" -> session.call(action, args)
            "file" -> {
                require(install.manifest.permissions.file) { "Provider file permission denied" }
                try { files.call(action, args) }
                catch (error: Exception) {
                    if (action == "exists") JsonPrimitive(false)
                    else buildJsonObject { put("success", false); put("error", safeError(error)); put("error_message", safeError(error)) }
                }
            }
            "url" -> urls(action, args)
            "utils" -> utils(action, args)
            "matching" -> when (action) {
                "normalizeString" -> JsonPrimitive(normalize(text(0)))
                "compareStrings" -> JsonPrimitive(similarity(text(0), text(1)))
                else -> error("Unsupported provider matching API")
            }
            "gobackend" -> when (action) {
                "sanitizeFilename" -> JsonPrimitive(filename(text(0)))
                "buildFilename" -> JsonPrimitive(filename(Regex("\\{([^}]+)}").replace(text(0)) { match ->
                    ((args.getOrNull(1) as? JsonObject)?.get(match.groupValues[1]) as? JsonPrimitive)?.contentOrNull.orEmpty()
                }))
                "getLocalTime" -> ZonedDateTime.now().let { now -> buildJsonObject {
                    put("hour", now.hour); put("minute", now.minute); put("second", now.second)
                    put("timezone", now.zone.id); put("offset", now.offset.totalSeconds)
                } }
                else -> error("Unsupported provider library API")
            }
            "base64Bytes" -> JsonPrimitive(Base64.getEncoder().encodeToString(bytes(args.getOrNull(0))))
            "decodeBase64Bytes" -> packed(Base64.getDecoder().decode(text(0)), buffer = true)
            "encode" -> packed(text(0).toByteArray(Charsets.UTF_8))
            "decode" -> JsonPrimitive(bytes(args.getOrNull(0)).toString(Charsets.UTF_8))
            "progress" -> {
                val percent = requireNotNull((args.getOrNull(0) as? JsonPrimitive)?.floatOrNull)
                require(percent.isFinite())
                report(ProviderWorkProgress(status, percent = percent.coerceIn(0f, 100f)), percent >= 100f)
                JsonNull
            }
            "log" -> JsonNull // Extension logs may include account/stream secrets; native health reports are separate.
            else -> error("Unsupported provider host API")
        }
    }

    private fun storage(group: String, action: String, args: JsonArray): JsonElement {
        require(install.manifest.permissions.storage) { "Provider storage permission denied" }
        val name = install.manifest.name
        val key = (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(key.length <= 500)
        fun current(state: ProviderState) = (if (group == "storage") state.storage else state.credentials)[name] ?: JsonObject(emptyMap())
        if (action == "get") {
            val values = current(store.state.value)
            return buildJsonObject { put("found", key in values); put("value", values[key] ?: JsonNull) }
        }
        require(action in setOf("set", "remove", "clear"))
        runBlocking { store.update { old ->
            val values = current(old).toMutableMap()
            when (action) { "clear" -> values.clear(); "remove" -> values.remove(key); "set" -> values[key] = args.getOrNull(1) ?: JsonNull }
            require(values.size <= 2_000 && JsonObject(values).toString().toByteArray().size <= 512 * 1024) { "Provider storage exceeds its limit" }
            if (group == "storage") old.copy(storage = old.storage + (name to JsonObject(values)))
            else old.copy(credentials = old.credentials + (name to JsonObject(values)))
        } }
        return buildJsonObject { put("success", true) }
    }

    private fun urls(action: String, args: JsonArray): JsonElement {
        fun text(index: Int) = (args.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun decode(value: String) = URLDecoder.decode(value, "UTF-8")
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        return when (action) {
            "parse" -> {
                val raw = text(0)
                val uri = (if (text(1).isBlank()) URI(raw) else URI(text(1)).resolve(raw)).normalize()
                val port = uri.port.takeIf { it >= 0 && !(it == 443 && uri.scheme == "https") && !(it == 80 && uri.scheme == "http") }
                val host = uri.host.orEmpty() + (port?.let { ":$it" } ?: "")
                buildJsonObject {
                    put("href", uri.toASCIIString()); put("origin", if (host.isEmpty()) "null" else "${uri.scheme}://$host")
                    put("protocol", "${uri.scheme}:"); put("host", host); put("hostname", uri.host.orEmpty()); put("port", port?.toString().orEmpty())
                    put("pathname", uri.rawPath.orEmpty()); put("search", uri.rawQuery?.let { "?$it" }.orEmpty())
                    put("hash", uri.rawFragment?.let { "#$it" }.orEmpty()); put("username", uri.userInfo?.substringBefore(':').orEmpty())
                    put("password", uri.userInfo?.substringAfter(':', "").orEmpty())
                }
            }
            "query" -> {
                val input = args.getOrNull(0)
                val pairs = when (input) {
                    is JsonArray -> input.map { pair -> pair.jsonArray.take(2).map { it.jsonPrimitive.content } }
                    is JsonObject -> input.map { (key, value) -> listOf(key, (value as? JsonPrimitive)?.content ?: value.toString()) }
                    else -> text(0).removePrefix("?").split('&').filter { it.isNotEmpty() }.map { part ->
                        listOf(decode(part.substringBefore('=')), decode(part.substringAfter('=', "")))
                    }
                }
                buildJsonArray { pairs.forEach { pair -> require(pair.size == 2); add(buildJsonArray { pair.forEach { add(it) } }) } }
            }
            "encode" -> JsonPrimitive(args.getOrNull(0)?.jsonArray.orEmpty().joinToString("&") { pair ->
                require(pair.jsonArray.size == 2)
                encode(pair.jsonArray[0].jsonPrimitive.content) + "=" + encode(pair.jsonArray[1].jsonPrimitive.content)
            })
            else -> error("Unsupported provider URL API")
        }
    }

    private fun utils(action: String, args: JsonArray): JsonElement {
        fun text(index: Int) = (args.getOrNull(index) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (action) {
            "encrypt", "decrypt", "encryptBlockCipher", "decryptBlockCipher", "decryptCTRSegments" ->
                ProviderCrypto.call(action, args) { check(!network.isCancelled) { "Provider request cancelled" } }
            "setDownloadStatus" -> {
                val value = text(0).filterNot(Char::isISOControl).take(200)
                status = if (value.isBlank() || Regex("(?i)://|authorization|cookie|secret|token").containsMatchIn(value))
                    "Working with provider" else value
                report(ProviderWorkProgress(status))
                JsonNull
            }
            "isRequestCancelled", "isDownloadCancelled" -> JsonPrimitive(network.isCancelled)
            "appVersion" -> JsonPrimitive("1.0.6")
            "appUserAgent" -> JsonPrimitive("Cosmotune/1.0.6")
            "randomUserAgent" -> JsonPrimitive("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
            "getResolutionRemainingMs" -> JsonPrimitive((30_000 - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(0))
            "sleep" -> {
                val duration = text(0).toDoubleOrNull()?.toLong() ?: 0L
                require(duration in 0..30_000)
                val deadline = System.nanoTime() + duration * 1_000_000
                while (System.nanoTime() < deadline) {
                    check(!network.isCancelled) { "Provider request cancelled" }
                    Thread.sleep(minOf(50, ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
                }
                JsonPrimitive(true)
            }
            "base64Encode" -> JsonPrimitive(Base64.getEncoder().encodeToString(text(0).toByteArray(Charsets.UTF_8)))
            "base64Decode" -> JsonPrimitive(Base64.getDecoder().decode(text(0)).toString(Charsets.UTF_8))
            "md5", "sha256" -> JsonPrimitive(MessageDigest.getInstance(if (action == "md5") "MD5" else "SHA-256")
                .digest(text(0).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
            "hmacSHA256", "hmacSHA256Base64", "hmacSHA1" -> {
                val sha1 = action == "hmacSHA1"
                val key = if (sha1) bytes(args.getOrNull(0)) else text(1).toByteArray(Charsets.UTF_8)
                val data = if (sha1) bytes(args.getOrNull(1)) else text(0).toByteArray(Charsets.UTF_8)
                val algorithm = if (sha1) "HmacSHA1" else "HmacSHA256"
                val output = Mac.getInstance(algorithm).run { init(SecretKeySpec(key, algorithm)); doFinal(data) }
                if (sha1) buildJsonArray { output.forEach { add(it.toInt() and 255) } }
                else JsonPrimitive(if (action == "hmacSHA256Base64") Base64.getEncoder().encodeToString(output) else output.joinToString("") { "%02x".format(it) })
            }
            "generateKey" -> {
                val length = text(0).toIntOrNull() ?: 32
                require(length in 1..4096)
                val data = ByteArray(length).also { java.security.SecureRandom().nextBytes(it) }
                buildJsonObject { put("success", true); put("key", Base64.getEncoder().encodeToString(data)); put("hex", data.joinToString("") { "%02x".format(it) }) }
            }
            else -> error("Unsupported provider utility API")
        }
    }

    private fun bytes(value: JsonElement?): ByteArray {
        if (value is JsonPrimitive && value.isString) return value.content.toByteArray(Charsets.UTF_8)
        val values = (if (value is JsonObject) value["__bytes"] else value)?.jsonArray ?: JsonArray(emptyList())
        require(values.size <= ProviderScripts.MAX_RESULT_BYTES)
        return ByteArray(values.size) { values[it].jsonPrimitive.int.also { require(it in 0..255) }.toByte() }
    }
    private fun packed(bytes: ByteArray, buffer: Boolean = false): JsonObject = buildJsonObject {
        require(bytes.size <= 256 * 1024) { "Provider binary host result is too large" }
        putJsonArray("__bytes") { bytes.forEach { add(it.toInt() and 255) } }; put("__buffer", buffer)
    }
    private fun normalize(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\p{M} ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun similarity(left: String, right: String): Double {
        val a = normalize(left); val b = normalize(right)
        require(a.length <= 512 && b.length <= 512)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var row = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length + 1); next[0] = i + 1
            for (j in b.indices) next[j + 1] = minOf(next[j] + 1, row[j + 1] + 1, row[j] + if (a[i] == b[j]) 0 else 1)
            row = next
        }
        return 1.0 - row.last().toDouble() / maxOf(a.length, b.length)
    }
    private fun filename(value: String): String {
        val cleaned = value.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim().trimEnd('.', ' ').take(180).ifBlank { "untitled" }
        return if (cleaned.substringBefore('.').uppercase(Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) "_$cleaned" else cleaned
    }
    private fun safeError(error: Exception): String = when {
        network.isCancelled -> "Provider request cancelled"
        error is java.net.UnknownHostException -> "Provider address is unavailable or requires local-network permission"
        error is java.io.IOException -> "Provider network or storage request failed"
        error is IllegalArgumentException -> "Provider input or permission check failed"
        else -> "Provider host API is unavailable (${error.javaClass.simpleName})"
    }
}
