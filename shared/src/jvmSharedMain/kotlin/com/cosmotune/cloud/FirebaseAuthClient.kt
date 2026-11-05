package com.cosmotune.cloud

import com.opencanvas.core.stream.fetch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** What a Google sign-in produced. [linkedGuest] is false when the Google account already existed, so the guest's id was left behind. */
data class GoogleSignIn(val session: CloudSession, val linkedGuest: Boolean)

/**
 * Firebase Authentication over its REST interface, so the same code runs on Android, desktop, TV and Wear (there is no official
 * desktop SDK). Anonymous sign-in gives a guest id with no screen; a Google ID token upgrades that same id.
 */
class FirebaseAuthClient(
    private val config: FirebaseConfig,
    private val client: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    suspend fun signInAnonymously(): CloudSession {
        val answer = postJson("${config.identityBase}/accounts:signUp", buildJsonObject { put("returnSecureToken", true) }.toString())
        return session(answer, anonymous = true)
    }

    /**
     * Signs in with the ID token Google gave the app. With a [guest] the guest account is upgraded in place, so what it holds stays under
     * the same id; if that Google account was already used on another device, the guest is left behind and the existing account is used.
     */
    suspend fun signInWithGoogle(googleIdToken: String, guest: CloudSession? = null): GoogleSignIn {
        suspend fun attempt(link: CloudSession?): CloudSession {
            val body = buildJsonObject {
                put("postBody", "id_token=${URLEncoder.encode(googleIdToken, "UTF-8")}&providerId=google.com")
                put("requestUri", "http://localhost")
                put("returnSecureToken", true)
                put("returnIdpCredential", true)
                if (link != null) put("idToken", link.idToken)
            }
            return session(postJson("${config.identityBase}/accounts:signInWithIdp", body.toString()), anonymous = false)
        }
        if (guest != null && guest.isAnonymous) {
            try {
                return GoogleSignIn(attempt(guest), linkedGuest = true)
            } catch (failure: CloudException) {
                if (failure.code != "FEDERATED_USER_ID_ALREADY_LINKED" && failure.code != "EMAIL_EXISTS") throw failure
            }
        }
        return GoogleSignIn(attempt(null), linkedGuest = false)
    }

    /** Trades the refresh token for a new ID token. The profile fields are kept; the answer does not repeat them. */
    suspend fun refresh(current: CloudSession): CloudSession {
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", current.refreshToken).build()
        val request = Request.Builder().url("${config.tokenBase}/token?key=${config.apiKey}").post(form).build()
        val answer = read(client.newCall(request).fetch())
        return session(answer, anonymous = current.isAnonymous, previous = current)
    }

    suspend fun deleteAccount(session: CloudSession) {
        postJson("${config.identityBase}/accounts:delete", buildJsonObject { put("idToken", session.idToken) }.toString())
    }

    private suspend fun postJson(url: String, body: String): JsonObject {
        val request = Request.Builder().url("$url?key=${config.apiKey}").post(body.toRequestBody(jsonType)).build()
        return read(client.newCall(request).fetch())
    }

    private fun read(answer: com.opencanvas.core.stream.Fetched): JsonObject {
        val parsed = runCatching { json.parseToJsonElement(answer.text()).jsonObject }.getOrNull()
        if (!answer.isSuccessful) throw failure(parsed, answer.code)
        return parsed ?: throw CloudException("BAD_RESPONSE", "not JSON", answer.code)
    }

    private fun failure(body: JsonObject?, status: Int): CloudException {
        val message = body?.get("error")?.let { runCatching { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
        val code = message?.substringBefore(' ')?.substringBefore(':')?.ifBlank { null } ?: "HTTP_$status"
        return CloudException(code, message ?: "HTTP $status", status)
    }

    private fun session(answer: JsonObject, anonymous: Boolean, previous: CloudSession? = null): CloudSession {
        fun text(vararg names: String): String? = names.firstNotNullOfOrNull { (answer[it] as? JsonPrimitive)?.contentOrNull }
        val expiresInSeconds = text("expiresIn", "expires_in")?.toLongOrNull() ?: 3600L
        return CloudSession(
            uid = text("localId", "user_id") ?: previous?.uid ?: throw CloudException("BAD_RESPONSE", "no user id", 200),
            idToken = text("idToken", "id_token") ?: throw CloudException("BAD_RESPONSE", "no token", 200),
            refreshToken = text("refreshToken", "refresh_token") ?: previous?.refreshToken ?: throw CloudException("BAD_RESPONSE", "no refresh token", 200),
            expiresAtMs = now() + expiresInSeconds * 1000L,
            isAnonymous = anonymous,
            email = text("email") ?: previous?.email,
            displayName = text("displayName") ?: previous?.displayName,
            photoUrl = text("photoUrl") ?: previous?.photoUrl,
        )
    }
}
