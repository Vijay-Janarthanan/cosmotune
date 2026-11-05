package com.cosmotune.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Firebase project's public configuration. The API key is not a secret (every Firebase web page ships it); what protects the data
 * is the security rules in `firebase/`. The base addresses are parameters so tests can point them at a local server.
 */
data class FirebaseConfig(
    val apiKey: String,
    val projectId: String,
    val databaseUrl: String,
    val identityBase: String = "https://identitytoolkit.googleapis.com/v1",
    val tokenBase: String = "https://securetoken.googleapis.com/v1",
    val firestoreBase: String = "https://firestore.googleapis.com/v1",
) {
    /** `projects/{id}/databases/(default)`, the prefix of every Firestore resource name. */
    val databaseName: String get() = "projects/$projectId/databases/(default)"
    val documentsBase: String get() = "$firestoreBase/$databaseName/documents"
}

object CosmotuneFirebase {
    val config = FirebaseConfig(
        apiKey = "AIzaSyDoCRExmagA3UFlvXbFHTao-vdE-6lrTdU",
        projectId = "portfolio-c4394",
        databaseUrl = "https://portfolio-c4394-default-rtdb.asia-southeast1.firebasedatabase.app",
    )
}

/** Who is signed in. A guest ([isAnonymous]) has a stable id on this device and nothing else; a Google sign-in keeps the same id. */
@Serializable
data class CloudSession(
    val uid: String,
    val idToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val isAnonymous: Boolean,
    val email: String? = null,
    val displayName: String? = null,
    val photoUrl: String? = null,
) {
    /** ID tokens last an hour; one is renewed when five minutes remain so a request never starts with a token about to lapse. */
    fun needsRefresh(nowMs: Long, marginMs: Long = 300_000L): Boolean = expiresAtMs - nowMs <= marginMs
}

/** Where the session survives a restart. The refresh token is the credential, so a store must keep it private to the app. */
interface CloudSessionStore {
    fun load(): CloudSession?
    fun save(session: CloudSession?)
}

class InMemorySessionStore(private var session: CloudSession? = null) : CloudSessionStore {
    override fun load(): CloudSession? = session
    override fun save(session: CloudSession?) { this.session = session }
}

/** A JSON file in the app's private folder; written to a temporary file first so a crash never leaves half a session. */
class FileSessionStore(private val file: File) : CloudSessionStore {
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): CloudSession? = runCatching { json.decodeFromString<CloudSession>(file.readText()) }.getOrNull()

    override fun save(session: CloudSession?) {
        if (session == null) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.encodeToString(session))
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }
}

/** A Firebase call that came back with an error. [code] is Firebase's own word for it (`INVALID_REFRESH_TOKEN`, `PERMISSION_DENIED`...). */
open class CloudException(val code: String, message: String, val httpStatus: Int) : Exception("$code: $message") {
    /** The saved sign-in can never work again, so the person has to sign in again. */
    val isAuthLost: Boolean get() = code in AUTH_LOST

    private companion object {
        val AUTH_LOST = setOf(
            "INVALID_REFRESH_TOKEN", "TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND", "INVALID_ID_TOKEN", "CREDENTIAL_TOO_OLD_LOGIN_AGAIN",
        )
    }
}

/** A write that was refused because the document changed since it was read; the caller reads again and retries. */
class CloudConflict(message: String) : CloudException("CONFLICT", message, 409)

/** A Google account's sign-in has lapsed or been revoked: a guest is quietly replaced by a new guest, a Google user is asked again. */
class CloudSignInRequired : Exception("Sign in again to keep syncing")
