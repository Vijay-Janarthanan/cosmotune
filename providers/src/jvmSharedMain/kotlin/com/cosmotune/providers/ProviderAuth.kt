package com.cosmotune.providers

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** The package's own authorization state, never the music app's account cookies or tokens. */
internal class ProviderAuth(private val install: ProviderInstall, private val store: ProviderStore, private val network: ProviderNetwork,
    private val now: () -> Long = System::currentTimeMillis) {
    private val name get() = install.manifest.name
    private fun record() = store.state.value.auth[name] ?: JsonObject(emptyMap())
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun change(epoch: String? = null, edit: (JsonObject) -> JsonObject): JsonObject {
        require(install.manifest.permissions.storage) { "Provider credential storage permission denied" }
        check(!network.isCancelled)
        var saved: JsonObject? = null
        runBlocking { store.update { old ->
            check(!network.isCancelled)
            val current = old.auth[name] ?: JsonObject(emptyMap())
            check(epoch == null || current.text("_epoch") == epoch) { "Provider authorization changed" }
            val next = edit(current)
            require(next.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Provider authorization is too large" }
            saved = next
            old.copy(auth = old.auth + (name to next))
        } }
        return requireNotNull(saved)
    }

    fun call(action: String, args: JsonArray): JsonElement {
        require(install.manifest.permissions.storage) { "Provider credential storage permission denied" }
        val value = args.getOrNull(0)
        return when (action) {
            "getAuthCode" -> record()["code"] ?: JsonNull
            "getTokens" -> buildJsonObject {
                val saved = record()
                put("access_token", saved.text("access_token")); put("refresh_token", saved.text("refresh_token"))
                put("expires_at", saved["expires_at"] ?: JsonNull)
            }
            "isAuthenticated" -> record().let { saved -> JsonPrimitive(saved.text("access_token").isNotBlank() &&
                (saved["expires_at"] == null || saved["expires_at"] == JsonNull ||
                    (saved["expires_at"] as? JsonPrimitive)?.longOrNull?.let { it > now() } == true)) }
            "clearAuth" -> { clear(); JsonPrimitive(true) }
            "setAuthCode" -> {
                change { old -> if (value is JsonPrimitive && value.isString) {
                    require(value.content.length <= 65_536); JsonObject(old + ("code" to value))
                } else {
                    val fields = value as? JsonObject ?: error("Invalid authorization state")
                    require(fields.keys.none { it.startsWith('_') || it in setOf("pkce", "pending") })
                    require(fields["expires_at"] == null || fields["expires_at"] == JsonNull ||
                        (fields["expires_at"] as? JsonPrimitive)?.longOrNull?.let { it > 0 } == true)
                    JsonObject(old + fields)
                } }
                JsonPrimitive(true)
            }
            "generatePKCE" -> generatePkce((value as? JsonPrimitive)?.intOrNull ?: 64)
            "getPKCE" -> record()["pkce"] ?: JsonObject(emptyMap())
            "openAuthUrl", "startOAuthWithPKCE" -> begin(action == "startOAuthWithPKCE", args)
            "exchangeCodeWithPKCE" -> exchange(value as? JsonObject ?: error("Invalid OAuth configuration"))
            else -> error("Unsupported provider authentication API")
        }
    }

    fun clear() {
        change { buildJsonObject { put("_epoch", UUID.randomUUID().toString()) } }
        ProviderCookieJar.clear(store, name)
        network.clearCookies()
    }

    private fun generatePkce(requested: Int): JsonObject {
        val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(96).also(SecureRandom()::nextBytes)).take(requested.coerceIn(43, 128))
        val value = buildJsonObject { put("verifier", verifier); put("challenge", challenge(verifier)); put("method", "S256") }
        change { old -> JsonObject(old - setOf("pending", "code") + mapOf("pkce" to value, "_epoch" to JsonPrimitive(UUID.randomUUID().toString()))) }
        return value
    }

    private fun begin(pkce: Boolean, args: JsonArray): JsonObject {
        val config = args.getOrNull(0) as? JsonObject ?: buildJsonObject {
            put("authUrl", args.getOrNull(0) ?: JsonNull); put("redirectUri", args.getOrNull(1) ?: JsonNull)
        }
        val address = network.address(config.text("authUrl"))
        require(address.isHttps) { "Provider sign-in requires HTTPS" }
        val callback = config.text("redirectUri")
        require(callback.length in 1..8_192)
        val redirect = URI(callback)
        require(redirect.scheme != null && !redirect.isOpaque && redirect.userInfo == null && redirect.fragment == null &&
            redirect.scheme.lowercase() !in setOf("file", "javascript", "data", "intent", "content")) { "Invalid provider callback" }
        if (redirect.scheme.equals("http", true) || redirect.scheme.equals("https", true))
            require(!redirect.host.isNullOrBlank() && redirect.port in -1..65535 && redirect.port != 0) { "Invalid HTTP provider callback" }
        if (redirect.scheme.equals("http", true)) require(redirect.host?.lowercase(java.util.Locale.ROOT) in setOf("127.0.0.1", "localhost", "[::1]")) { "OAuth HTTP callbacks must use loopback" }
        require(parameters(redirect.rawQuery.orEmpty()).keys.none { it in setOf("code", "state", "error") })
        val extras = (config["extraParams"] as? JsonObject).orEmpty()
        val scope = config.text("scope").ifBlank { (config["scopes"] as? JsonArray).orEmpty().joinToString(" ") { it.jsonPrimitive.content } }
        if (pkce) {
            require(config.text("clientId").isNotBlank())
            extras.forEach { (key, value) -> require(key !in RESERVED_AUTH && value is JsonPrimitive && value != JsonNull) }
        }
        // Reject malformed requests before replacing a previous, still usable proof or callback.
        val proof = if (pkce) generatePkce(64) else null
        if (!pkce) change { old -> JsonObject(old - setOf("pending", "code") + ("_epoch" to JsonPrimitive(UUID.randomUUID().toString()))) }
        val current = record()
        val state = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(SecureRandom()::nextBytes))
        val builder = address.newBuilder().setQueryParameter("state", state)
        if (pkce) {
            builder.setQueryParameter("client_id", config.text("clientId")).setQueryParameter("redirect_uri", callback)
                .setQueryParameter("response_type", "code").setQueryParameter("code_challenge", proof!!.text("challenge"))
                .setQueryParameter("code_challenge_method", "S256")
            if (scope.isNotBlank()) builder.setQueryParameter("scope", scope)
            extras.forEach { (key, value) ->
                builder.setQueryParameter(key, value.jsonPrimitive.content)
            }
        }
        val url = builder.build().toString()
        val pending = buildJsonObject { put("url", url); put("state", state); put("createdAt", now()); put("callback", callback)
            put("pkce", pkce); put("completed", false) }
        change(current.text("_epoch")) { old -> JsonObject(old + ("pending" to pending)) }
        return buildJsonObject {
            put("success", true); put("open_auth_url", url); put("authUrl", url)
            if (proof != null) put("pkce", proof)
            put("message", "Continue this provider's sign-in in Sources. Supported local callbacks return automatically; manual callback recovery is also available.")
        }
    }

    /** Native input is bound to the reviewed provider, pending redirect and one unexpired state. */
    fun completeCallback(input: String) {
        require(input.length in 1..16_384)
        val current = record()
        val pending = current["pending"] as? JsonObject ?: error("No provider sign-in is pending")
        require((pending["completed"] as? JsonPrimitive)?.booleanOrNull != true)
        require(now() - requireNotNull((pending["createdAt"] as? JsonPrimitive)?.longOrNull) in 0..600_000)
        val received = URI(input.trim())
        val expected = URI(pending.text("callback"))
        fun path(uri: URI) = uri.rawPath.orEmpty().let { value ->
            if (value.isEmpty() && (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))) "/" else value
        }
        fun authority(uri: URI): String? = if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true))
            uri.host?.lowercase(java.util.Locale.ROOT) + ":" + (uri.port.takeIf { it >= 0 } ?: if (uri.scheme.equals("https", true)) 443 else 80)
            else uri.rawAuthority
        require(received.scheme.equals(expected.scheme, true) && authority(received) == authority(expected) &&
            path(received) == path(expected) && received.userInfo == null && !received.isOpaque)
        val values = parameters(received.rawQuery ?: received.rawFragment.orEmpty())
        require(values["error"].isNullOrBlank() && values["state"] == pending.text("state"))
        parameters(expected.rawQuery.orEmpty()).forEach { (key, value) -> require(values[key] == value) }
        val code = values["code"]?.takeIf { it.isNotBlank() && it.length <= 8_192 } ?: error("Provider returned no authorization code")
        change(current.text("_epoch")) { old ->
            require(old["pending"] == pending)
            JsonObject(old + mapOf("code" to JsonPrimitive(code), "pending" to JsonObject(pending + ("completed" to JsonPrimitive(true)))))
        }
    }

    private fun exchange(config: JsonObject): JsonObject {
        val current = record()
        val proof = current["pkce"] as? JsonObject ?: error("No PKCE proof is pending")
        val verifier = proof.text("verifier").also { require(it.matches(Regex("[A-Za-z0-9._~-]{43,128}"))) }
        val code = config.text("code").ifBlank { current.text("code") }.also { require(it.isNotBlank() && it.length <= 8_192) }
        val address = network.address(config.text("tokenUrl")); require(address.isHttps)
        val callback = config.text("redirectUri")
        val pending = current["pending"] as? JsonObject
        require(pending == null || callback.isEmpty() || callback == pending.text("callback"))
        val fields = linkedMapOf("grant_type" to "authorization_code", "client_id" to config.text("clientId"), "code" to code, "code_verifier" to verifier)
        require(fields.getValue("client_id").isNotBlank())
        if (callback.isNotBlank()) fields["redirect_uri"] = callback
        (config["extraParams"] as? JsonObject).orEmpty().forEach { (key, value) ->
            require(key !in RESERVED_TOKEN && value is JsonPrimitive && value != JsonNull); fields[key] = value.content
        }
        val response = network.request(address.toString(), buildJsonObject {
            put("method", "POST"); put("body", fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) })
            putJsonObject("headers") { put("Content-Type", "application/x-www-form-urlencoded") }
        })
        require(response["ok"] == JsonPrimitive(true)) { "Provider token exchange failed" }
        val tokens = Json.parseToJsonElement(response.text("body")) as? JsonObject ?: error("Invalid provider tokens")
        require(tokens.keys.none { it.startsWith('_') || it in setOf("code", "pending", "pkce") })
        require(tokens.text("access_token").isNotBlank())
        val seconds = ((tokens["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3_600).also { require(it in 1..31_536_000) }
        change(current.text("_epoch")) { old ->
            JsonObject(old - setOf("code", "pending", "pkce") + tokens + ("expires_at" to JsonPrimitive(now() + seconds * 1000)))
        }
        return buildJsonObject { put("success", true); tokens.forEach { (key, value) -> put(key, value) } }
    }

    companion object {
        private val RESERVED_AUTH = setOf("state", "client_id", "redirect_uri", "response_type", "code_challenge", "code_challenge_method")
        private val RESERVED_TOKEN = setOf("grant_type", "client_id", "redirect_uri", "code", "code_verifier")
        fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        internal fun callbackState(input: String): String? = runCatching {
            require(input.length in 1..16_384)
            val uri = URI(input)
            parameters(uri.rawQuery ?: uri.rawFragment.orEmpty())["state"]?.takeIf { it.length in 16..512 }
        }.getOrNull()
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun parameters(query: String): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            query.split('&').filter { it.isNotEmpty() }.forEach { pair ->
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
                require(key !in values)
                values[key] = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            return values
        }
    }
}
