package com.cosmotune.providers

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Operator-issued grants and HMAC sessions; no music-account credentials enter this protocol. */
internal class ProviderSignedSession(private val install: ProviderInstall, private val store: ProviderStore,
    private val network: ProviderNetwork, private val now: () -> Long = System::currentTimeMillis) {
    private val config = requireNotNull(install.manifest.signedSession)
    private val name = install.manifest.name
    private val base = network.address(config.text("baseUrl")).also {
        require(it.isHttps && it.query == null && it.fragment == null) { "Invalid session origin" }
    }
    private val scope = digest(config.toString())
    private val callback = config.text("callbackUrl").ifBlank { store.defaultSessionCallback }.also { raw ->
        val uri = URI(raw)
        require(raw.length in 1..8_192 && uri.scheme != null && !uri.isOpaque && uri.host != null &&
            uri.userInfo == null && uri.fragment == null && uri.scheme.lowercase(Locale.ROOT) !in
            setOf("file", "content", "data", "javascript", "intent")) { "Invalid session callback" }
        if (uri.scheme.equals("http", true)) require(uri.host.lowercase(Locale.ROOT) in setOf("127.0.0.1", "localhost", "[::1]"))
        require(parameters(uri.rawQuery.orEmpty()).keys.none { it in setOf("state", "code", "grant", "error") })
    }
    private val appVersion = config.text("appVersion").ifBlank { "ext-1.0" }
    private val platform = config.text("platform").ifBlank { "extension" }
    private val label = config.text("schemeLabel").ifBlank { "SPOTIFLAC-HMAC-V1" }
    private val prefix = config.text("headerPrefix").ifBlank { "X-Sig-" }
    private val window = (config["timeWindowSeconds"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: 300L
    private val gate = store.signedSessionLocks.computeIfAbsent(name) { Any() }

    init {
        validateConfig(install.manifest)
        require(install.manifest.permissions.storage)
        require(config.text("namespace").length in 1..200 && config.toString().toByteArray().size <= 32 * 1024)
        require(window <= 86_400 && prefix.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,60}")))
        require(listOf(appVersion, platform, label).all { it.length in 1..200 && it.all { c -> c in ' '..'~' } })
        listOf("bootstrap", "challenge", "exchange").forEach { endpoint(endpointName(it)) }
        config["endpoints"]?.jsonObject?.text("refresh")?.takeIf(String::isNotBlank)?.let(::endpoint)
    }

    private fun check() {
        check(!network.isCancelled) { "Provider request cancelled" }
        check(store.state.value.installed.any { it.manifest.name == name && it.enabled && it.sha256 == install.sha256 &&
            it.manifest.permissions.storage && it.manifest.signedSession == config }) { "Provider authorization changed" }
    }
    private fun auth() = store.state.value.auth[name] ?: JsonObject(emptyMap())
    private fun signed(auth: JsonObject = auth()) = (auth["signedSession"] as? JsonObject)?.takeIf { it.text("scope") == scope }
        ?: JsonObject(emptyMap())
    private fun epoch() = auth().text("_epoch")
    private fun initialize() {
        check()
        if (signed().text("install_id").isNotBlank()) return
        runBlocking { store.update { state ->
            check()
            val old = state.auth[name] ?: JsonObject(emptyMap())
            val current = signed(old)
            if (current.text("install_id").isNotBlank()) state else state.copy(auth = state.auth + (name to buildJsonObject {
                put("_epoch", UUID.randomUUID().toString())
                putJsonObject("signedSession") { put("scope", scope); put("install_id", randomHex(16)) }
            }))
        } }
    }
    private fun update(expectedEpoch: String, edit: (JsonObject) -> JsonObject) {
        runBlocking { store.update { state ->
            check()
            val old = state.auth[name] ?: JsonObject(emptyMap())
            check(old.text("_epoch") == expectedEpoch && signed(old).text("scope") == scope) { "Provider authorization changed" }
            val next = edit(old)
            require(next.toString().toByteArray().size <= 256 * 1024)
            state.copy(auth = state.auth + (name to next))
        } }
    }
    private fun saveSession(expectedEpoch: String, response: JsonObject, expectedSession: String? = null) {
        val session = response.text("session_id"); val secret = response.text("session_secret"); val expiryText = response.text("expires_at")
        require(session.length in 1..4_096 && session.all { it in '!'..'~' } && secret.length in 1..8_192 &&
            expiryText.length <= 100 && expiry(expiryText) > now()) { "Invalid operator session" }
        update(expectedEpoch) { old ->
            if (expectedSession != null) check(signed(old).text("session_id") == expectedSession) { "Session was replaced" }
            JsonObject(old - setOf("pending", "grant") + ("signedSession" to JsonObject(signed(old) + mapOf(
                "session_id" to JsonPrimitive(session), "session_secret" to JsonPrimitive(secret),
                "expires_at" to JsonPrimitive(expiryText), "blocked" to JsonPrimitive(false)))))
        }
    }
    private fun usable(record: JsonObject) = record.text("session_id").isNotBlank() && record.text("session_secret").isNotBlank() &&
        expiry(record.text("expires_at")) > now() && record["blocked"] != JsonPrimitive(true)
    private fun pending() = (auth()["pending"] as? JsonObject)?.takeIf {
        it.text("kind") == "signed-grant" && it.text("scope") == scope &&
            now() - ((it["createdAt"] as? JsonPrimitive)?.longOrNull ?: Long.MIN_VALUE) in 0 until 180_000
    }
    fun call(action: String, args: JsonArray): JsonElement = synchronized(gate) {
        initialize()
        when (action) {
            "status" -> buildJsonObject {
                val record = signed()
                put("authenticated", usable(record)); put("verification_required", pending() != null || record["blocked"] == JsonPrimitive(true))
                listOf("expires_at", "session_id", "install_id").forEach { put(it, record.text(it)) }
                put("app_version", appVersion); put("platform", platform); put("auth_url", pending()?.text("url").orEmpty())
            }
            "clear" -> {
                update(epoch()) { old -> buildJsonObject {
                    put("_epoch", UUID.randomUUID().toString())
                    putJsonObject("signedSession") { put("scope", scope); put("install_id", signed(old).text("install_id")) }
                } }
                JsonPrimitive(true).let { buildJsonObject { put("success", it) } }
            }
            "completeGrant" -> { completeGrant((args.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty()); buildJsonObject { put("success", true) } }
            "beginVerification" -> {
                if (pending()?.get("completed") == JsonPrimitive(true)) completeGrant("")
                buildJsonObject { put("authenticated", bootstrap()); put("verification_required", pending() != null) }
            }
            "signedFetch" -> signedFetch(args)
            else -> error("Unsupported provider signed-session API")
        }
    }
    private fun endpointName(name: String) = (config["endpoints"] as? JsonObject)?.text(name)?.takeIf(String::isNotBlank)
        ?: when (name) { "exchange" -> "/session/exchange"; else -> "/$name" }
    internal fun endpoint(path: String): HttpUrl {
        return network.address(checkedEndpoint(base, path).toString())
    }
    private fun request(url: HttpUrl, method: String = "GET", body: String = "", headers: Map<String, String> = emptyMap(), responseTimeoutSeconds: Long? = null): JsonObject {
        check()
        return network.request(url.toString(), buildJsonObject {
            put("method", method); put("body", body)
            putJsonObject("headers") { headers.forEach { (key, value) -> put(key, value) } }
        }, followRedirects = false, responseTimeoutSeconds = responseTimeoutSeconds).also { check() }
    }
    private fun decoded(response: JsonObject): JsonObject {
        if (response["ok"] != JsonPrimitive(true)) {
            val status = (response["status"] as? JsonPrimitive)?.intOrNull ?: 0
            val payload = runCatching { Json.parseToJsonElement(response.text("body")).jsonObject }.getOrNull()
            val code = listOf("code", "error").firstNotNullOfOrNull { key ->
                (payload?.get(key) as? JsonPrimitive)?.contentOrNull?.uppercase(Locale.ROOT)
                    ?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_-]{1,79}")) }
            } ?: run {
                val detail = listOf("error", "message").mapNotNull { (payload?.get(it) as? JsonPrimitive)?.contentOrNull }.joinToString(" ").lowercase(Locale.ROOT)
                when {
                    "grant" in detail && "expir" in detail && "invalid" in detail -> "INVALID_OR_EXPIRED_GRANT"
                    "grant" in detail && "expir" in detail -> "GRANT_EXPIRED"
                    "grant" in detail && "already" in detail && "used" in detail -> "GRANT_ALREADY_USED"
                    "grant" in detail && ("not found" in detail || "unknown" in detail) -> "UNKNOWN_GRANT"
                    "install" in detail && "mismatch" in detail -> "INSTALL_MISMATCH"
                    "grant" in detail && "invalid" in detail -> "INVALID_GRANT"
                    else -> null
                }
            }
            throw ProviderVerificationException(status, code)
        }
        return Json.parseToJsonElement(response.text("body")).jsonObject
    }
    private fun bootstrap(): Boolean {
        if (usable(signed())) return true
        if (pending() != null) return false
        val expectedEpoch = epoch(); val record = signed()
        val url = endpoint(endpointName("bootstrap")).newBuilder().setQueryParameter("app_version", appVersion)
            .setQueryParameter("install_id", record.text("install_id")).build()
        val response = decoded(request(url))
        if (response.text("session_id").isNotBlank()) { saveSession(expectedEpoch, response); return true }
        var state = random(24)
        var address = response.text("auth_url").ifBlank { response.text("challenge_url") }
        if (address.isBlank()) {
            require(response.text("challenge_id").length in 1..4_096) { "Operator returned no session or challenge" }
            val redirect = appendQuery(callback, mapOf("cb_version" to "v2grant", "state" to state))
            address = endpoint(endpointName("challenge")).newBuilder().setQueryParameter("id", response.text("challenge_id"))
                .setQueryParameter("cb", redirect).build().toString()
        }
        val challenge = network.address(address).also { require(it.isHttps && it.fragment == null) }
        require(challenge.queryParameterValues("state").size <= 1)
        val nested = listOf("cb", "callback_url", "redirect_uri").mapNotNull { key ->
            require(challenge.queryParameterValues(key).size <= 1)
            challenge.queryParameter(key)
        }
        require(nested.size <= 1)
        val redirect = nested.singleOrNull()?.let(::URI)
        if (redirect != null) {
            val expected = URI(callback)
            require(redirect.scheme.equals(expected.scheme, true) && redirect.rawAuthority == expected.rawAuthority &&
                redirect.rawPath.orEmpty() == expected.rawPath.orEmpty() && redirect.fragment == null)
            parameters(expected.rawQuery.orEmpty()).forEach { (key, value) -> require(parameters(redirect.rawQuery.orEmpty())[key] == value) }
        }
        val nestedState = redirect?.let { parameters(it.rawQuery.orEmpty())["state"] }
        val supplied = challenge.queryParameter("state")
        require(supplied == null || nestedState == null || supplied == nestedState)
        state = supplied ?: nestedState ?: state
        require(state.length in 16..512 && !state.any { it <= ' ' })
        val authUrl = if (supplied == null && nestedState == null) challenge.newBuilder().setQueryParameter("state", state).build().toString() else challenge.toString()
        update(expectedEpoch) { old -> JsonObject(old - "grant" + ("pending" to buildJsonObject {
            put("kind", "signed-grant"); put("scope", scope); put("url", authUrl); put("callback", callback)
            put("state", state); put("createdAt", now()); put("completed", false)
        })) }
        return false
    }
    fun completeCallback(input: String) = synchronized(gate) {
        initialize()
        val pending = requireNotNull(pending()) { "No current operator verification" }
        require(pending["completed"] != JsonPrimitive(true) && input.length in 1..16_384)
        val received = URI(input); val expected = URI(pending.text("callback"))
        require(!received.isOpaque && received.userInfo == null && received.scheme.equals(expected.scheme, true) &&
            received.rawAuthority == expected.rawAuthority && received.rawPath.orEmpty() == expected.rawPath.orEmpty())
        val values = parameters(received.rawQuery ?: received.rawFragment.orEmpty())
        require(values["state"] == pending.text("state") && values["error"].isNullOrBlank())
        parameters(expected.rawQuery.orEmpty()).forEach { (key, value) -> require(values[key] == value) }
        val grant = (values["grant"] ?: values["code"]).orEmpty()
        require(grant.length in 1..8_192 && !(values.containsKey("grant") && values.containsKey("code")))
        update(epoch()) { old ->
            require(old["pending"] == pending)
            JsonObject(old + mapOf("grant" to JsonPrimitive(grant), "pending" to JsonObject(pending + ("completed" to JsonPrimitive(true)))))
        }
    }
    private fun completeGrant(input: String) {
        // Native callbacks finish the exchange immediately. Older packages may still call the SDK action.
        if (input.isBlank() && pending() == null && usable(signed())) return
        val pending = requireNotNull(pending()) { "No current operator verification" }
        require(pending["completed"] == JsonPrimitive(true)) { "Complete provider verification first" }
        val grant = input.ifBlank { auth().text("grant") }
        require(grant.length in 1..8_192)
        exchangeGrant(grant, pending)
    }

    /** Explicit native paste, matching upstream's grant-only handoff; never an OS/guest callback. */
    fun completeUserGrant(input: String) = synchronized(gate) {
        initialize()
        val grant = input.trim().let {
            require(it.matches(Regex("grant=[A-Za-z0-9_-]{1,8192}"))) { "Invalid provider grant" }
            it.removePrefix("grant=")
        }
        // The operator validates expiry and binds this user-provided grant to this install/application.
        // Callback URLs still require the exact local state and three-minute callback lifetime.
        val expectedEpoch = epoch()
        update(expectedEpoch) { old ->
            val previous = (old["pending"] as? JsonObject)?.takeIf { it.text("scope") == scope }
                ?: buildJsonObject { put("kind", "signed-grant"); put("scope", scope) }
            JsonObject(old + mapOf("grant" to JsonPrimitive(grant), "pending" to JsonObject(previous + mapOf(
                "completed" to JsonPrimitive(true), "manual" to JsonPrimitive(true), "createdAt" to JsonPrimitive(now())))))
        }
        completeGrant("")
    }

    private fun exchangeGrant(grant: String, pending: JsonObject?) {
        val expectedEpoch = epoch(); val record = signed()
        val body = buildJsonObject { put("grant", grant); put("install_id", record.text("install_id")); put("app_version", appVersion); put("platform", platform) }
        val response = decoded(request(endpoint(endpointName("exchange")), "POST", body.toString(), mapOf("Content-Type" to "application/json")))
        // The pending proof must still be the one whose user-authorized grant was exchanged.
        require(auth()["pending"] == pending)
        saveSession(expectedEpoch, response)
    }
    private fun verification() = buildJsonObject {
        put("ok", false); put("needsVerification", true); put("error", "VERIFY_REQUIRED")
        put("auth_url", pending()?.text("url").orEmpty()); put("open_auth_url", pending()?.text("url").orEmpty())
    }
    /** A failed challenge mint still means access is required. Cancellation and revocation must
     * keep failing the operation, rather than being disguised as a request to verify again. */
    private fun bootstrapForRequest(): Boolean {
        val expectedEpoch = epoch()
        return try { bootstrap() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); throw interrupted }
        catch (_: Exception) {
            check()
            check(epoch() == expectedEpoch) { "Provider authorization changed" }
            update(expectedEpoch) { old -> JsonObject(old + ("signedSession" to
                JsonObject(signed(old) + ("blocked" to JsonPrimitive(true))))) }
            false
        }
    }
    private fun signedRequest(record: JsonObject, method: String, address: HttpUrl, body: String, headers: Map<String, String>): JsonObject {
        val signature = headers(label, prefix, window, appVersion, platform, record.text("session_id"), record.text("session_secret"), method, address, body, now(), randomHex(16))
        // Operation headers such as X-Zarz-Ticket share the prefix but are not signing fields.
        val protected = signature.keys.map { it.lowercase(Locale.ROOT) }.toSet() + "content-type"
        val safeHeaders = headers.filterKeys { it.lowercase(Locale.ROOT) !in protected }
        val preparing = method == "POST" && address.encodedPath.removePrefix(base.encodedPath.trimEnd('/') + "/").startsWith("dl/")
        return request(address, method, body, safeHeaders + mapOf("Content-Type" to "application/json") +
            signature, responseTimeoutSeconds = if (preparing) 60 else null)
    }
    private fun signedFetch(args: JsonArray): JsonObject {
        val method = (args.getOrNull(0) as? JsonPrimitive)?.contentOrNull.orEmpty().trim().uppercase(Locale.ROOT)
        require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
        val address = endpoint((args.getOrNull(1) as? JsonPrimitive)?.contentOrNull.orEmpty())
        val body = (args.getOrNull(2) as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(body.toByteArray().size <= ProviderNetwork.MAX_METADATA_BYTES && (method !in setOf("GET", "HEAD") || body.isEmpty()))
        val headers = (args.getOrNull(3) as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        if (!bootstrapForRequest()) return verification()
        var record = signed(); val expectedEpoch = epoch()
        val refresh = (config["endpoints"] as? JsonObject)?.text("refresh").orEmpty()
        if (refresh.isNotBlank() && expiry(record.text("expires_at")) - now() <= 3_600_000) {
            runCatching {
                val result = decoded(signedRequest(record, "POST", endpoint(refresh), buildJsonObject { put("install_id", record.text("install_id")) }.toString(), emptyMap()))
                val next = JsonObject(record + result.filterKeys { it in setOf("session_id", "session_secret", "expires_at") })
                saveSession(expectedEpoch, next, record.text("session_id"))
            }
            check(); check(epoch() == expectedEpoch)
            record = signed()
        }
        var providerRetries = 0; var sessionRetries = 0
        while (true) {
            check(); check(epoch() == expectedEpoch)
            if (!usable(record)) { if (!bootstrapForRequest()) return verification(); record = signed() }
            val response = signedRequest(record, method, address, body, headers)
            val contract = runCatching { Json.parseToJsonElement(response.text("body")).jsonObject }.getOrDefault(JsonObject(emptyMap()))
            val status = (response["status"] as? JsonPrimitive)?.intOrNull ?: 0
            val code = contract.text("code").trim().uppercase(Locale.ROOT)
            val origin = contract.text("origin").trim().lowercase(Locale.ROOT)
            val action = contract.text("action").trim().lowercase(Locale.ROOT)
            if (status == 503 && origin == "provider" && code == "PROVIDER_UNAVAILABLE" && contract["retryable"] == JsonPrimitive(true) &&
                contract.text("retry_mode").trim().equals("same_operation", true) && providerRetries++ < 2) {
                val retryAfter = (response["headers"] as? JsonObject)?.text("retry-after").orEmpty()
                val seconds = retryAfter.toLongOrNull() ?: runCatching { java.time.ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().minus(now()).div(1_000) }.getOrNull()
                    ?: (contract["retry_after_seconds"] as? JsonPrimitive)?.longOrNull ?: 1
                val deadline = System.nanoTime() + seconds.coerceIn(1, 120) * 1_000_000_000
                while (System.nanoTime() < deadline) { check(); check(epoch() == expectedEpoch); Thread.sleep(25) }
                continue
            }
            val reset = status == 401 && origin == "gateway" && code == "SESSION_INVALID" && action == "bootstrap_session"
            val verify = status == 428 && origin == "gateway" && code == "VERIFY_REQUIRED" && action == "verify"
            if ((reset || verify) && sessionRetries++ < 1) {
                update(expectedEpoch) { old ->
                    val value = signed(old)
                    check(value.text("session_id") == record.text("session_id"))
                    JsonObject(old + ("signedSession" to if (reset) JsonObject(value - setOf("session_id", "session_secret", "expires_at", "blocked"))
                        else JsonObject(value + ("blocked" to JsonPrimitive(true)))))
                }
                if (!bootstrapForRequest()) return verification()
                record = signed(); continue
            }
            return JsonObject(response + if (code.isBlank()) emptyMap() else mapOf(
                "error_code" to JsonPrimitive(code), "error_origin" to JsonPrimitive(origin), "error_action" to JsonPrimitive(action),
                "retry_after_seconds" to JsonPrimitive((contract["retry_after_seconds"] as? JsonPrimitive)?.longOrNull?.coerceIn(0, 120) ?: 0)))
        }
    }

    companion object {
        fun validateConfig(manifest: ProviderManifest) {
            val config = manifest.signedSession ?: return
            require(manifest.permissions.storage && config.toString().toByteArray().size <= 32 * 1024)
            require(config.text("namespace").length in 1..200)
            val base = config.text("baseUrl").toHttpUrl()
            require(base.isHttps && base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
            require(manifest.permissions.network.any { declared ->
                val domain = declared.lowercase(Locale.ROOT)
                base.host == domain || domain.startsWith("*.") && base.host.endsWith(domain.drop(1))
            }) { "Session origin needs a declared network grant" }
            val prefix = config.text("headerPrefix").ifBlank { "X-Sig-" }
            require(prefix.matches(Regex("[A-Za-z][A-Za-z0-9-]{0,60}")))
            listOf("appVersion", "platform", "schemeLabel").forEach { key ->
                require(config.text(key).let { it.length <= 200 && it.all { c -> c in ' '..'~' } })
            }
            val window = (config["timeWindowSeconds"] as? JsonPrimitive)?.longOrNull ?: 300
            require(window <= 86_400)
            val endpoints = config["endpoints"] as? JsonObject
            require(config["endpoints"] == null || endpoints != null)
            require(endpoints == null || endpoints.size <= 10)
            endpoints?.values?.forEach { value ->
                require(value is JsonPrimitive && value.isString && value.content.isNotBlank())
                checkedEndpoint(base, value.content)
            }
            config.text("callbackUrl").takeIf(String::isNotBlank)?.let { raw ->
                val callback = URI(raw)
                require(raw.length <= 8_192 && callback.scheme != null && callback.host != null && !callback.isOpaque &&
                    callback.userInfo == null && callback.fragment == null && callback.port in -1..65535 && callback.port != 0 &&
                    callback.scheme.lowercase(Locale.ROOT) !in setOf("file", "content", "data", "javascript", "intent"))
                if (callback.scheme.equals("http", true)) require(callback.host.lowercase(Locale.ROOT) in setOf("127.0.0.1", "localhost", "[::1]"))
                require(parameters(callback.rawQuery.orEmpty()).keys.none { it in setOf("state", "code", "grant", "error") })
            }
        }
        private fun checkedEndpoint(base: HttpUrl, path: String): HttpUrl {
            require(path.length in 1..8_192 && !path.contains('\\') && !path.startsWith("//"))
            val prefix = base.encodedPath.trimEnd('/') + "/"
            val address = if (path.startsWith("https://")) path.toHttpUrl()
                else base.newBuilder().encodedPath(prefix).build().resolve(path.trimStart('/')) ?: error("Invalid session endpoint")
            require(address.scheme == base.scheme && address.host == base.host && address.port == base.port &&
                address.encodedPath.startsWith(prefix) && address.fragment == null && address.username.isEmpty() && address.password.isEmpty()) {
                "Signed-session endpoint escaped its origin or base path"
            }
            return address
        }
        private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
        private fun expiry(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0)
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun random(count: Int) = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(count).also(SecureRandom()::nextBytes))
        private fun randomHex(count: Int) = ByteArray(count).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        private fun parameters(query: String): Map<String, String> = LinkedHashMap<String, String>().apply {
            query.split('&').filter(String::isNotEmpty).forEach { item ->
                val key = URLDecoder.decode(item.substringBefore('='), "UTF-8")
                require(!containsKey(key)); put(key, URLDecoder.decode(item.substringAfter('=', ""), "UTF-8"))
            }
        }
        private fun appendQuery(raw: String, fields: Map<String, String>): String {
            val encoded = fields.entries.joinToString("&") { java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }
            return raw + (if (URI(raw).rawQuery == null) "?" else "&") + encoded
        }
        internal fun headers(label: String, prefix: String, window: Long, version: String, platform: String, session: String,
            secret: String, method: String, url: HttpUrl, body: String, now: Long, nonce: String): Map<String, String> {
            fun hmac(key: String, value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
            })
            val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now))
            val hash = digest(body)
            val rolling = hmac(secret, "${now / 1_000 / window}:$session")
            val canonical = listOf(label, method, url.encodedPath, "", hash, timestamp, nonce, session, version, platform).joinToString("\n")
            return mapOf("Session" to session, "Timestamp" to timestamp, "Nonce" to nonce, "Body-SHA256" to hash,
                "Signature" to hmac(rolling, canonical), "App-Version" to version, "Platform" to platform).mapKeys { prefix + it.key }
        }
    }
}
