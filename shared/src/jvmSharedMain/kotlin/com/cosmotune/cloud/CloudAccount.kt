package com.cosmotune.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Where the sync is up to; survives a restart. */
class FileSyncMarks(private val file: File?) : SyncMarks {
    @Serializable
    private data class State(val pulledMs: Long = 0, val pushedMs: Long = 0, val lastSyncMs: Long = 0, val uid: String = "")

    private val json = Json { ignoreUnknownKeys = true }
    private var state: State = runCatching { json.decodeFromString<State>(file!!.readText()) }.getOrDefault(State())

    override var pulledMs: Long
        get() = state.pulledMs
        set(value) { state = state.copy(pulledMs = value); save() }
    override var pushedMs: Long
        get() = state.pushedMs
        set(value) { state = state.copy(pushedMs = value); save() }
    var lastSyncMs: Long
        get() = state.lastSyncMs
        set(value) { state = state.copy(lastSyncMs = value); save() }

    /** The account these marks belong to. Marks from another account say nothing about this one's cloud data. */
    var uid: String
        get() = state.uid
        set(value) { state = state.copy(uid = value); save() }

    @Synchronized private fun save() {
        val target = file ?: return
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(json.encodeToString(state))
        if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
    }
}

/** What the settings page shows. */
data class CloudStatus(
    val signedIn: Boolean = false,
    val isGuest: Boolean = true,
    val name: String? = null,
    val email: String? = null,
    val syncing: Boolean = false,
    val lastSyncMs: Long = 0,
    val lastError: String? = null,
    val lastReport: SyncReport? = null,
    /** True while the person must sign in with Google again (a lapsed Google session). */
    val needsSignIn: Boolean = false,
)

/**
 * The cloud account of this device, as the screens see it: who is signed in, a "sync now" that is safe to call from anywhere, and a
 * "something changed" nudge that waits for quiet before syncing. Everything works with no account; a failure here never affects playback.
 */
class CloudAccount(
    config: FirebaseConfig,
    client: okhttp3.OkHttpClient,
    sessionStore: CloudSessionStore,
    source: SyncSource,
    private val marks: FileSyncMarks,
    kinds: List<String>,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val auth = FirebaseAuthClient(config, client, now)
    val sessions = CloudSessionManager(auth, sessionStore, now)
    private val docs = FirestoreRest(config, client) { sessions.idToken() }
    private val sync = CloudSync(docs, { sessions.session.value?.uid ?: error("not signed in") }, source, marks, kinds, now)
    private val lock = Mutex()
    private var pending: Job? = null

    private val state = MutableStateFlow(snapshot(sessions.session.value))
    val status: StateFlow<CloudStatus> = state.asStateFlow()

    private fun snapshot(session: CloudSession?, previous: CloudStatus = CloudStatus()) = previous.copy(
        signedIn = session != null,
        isGuest = session?.isAnonymous ?: true,
        name = session?.displayName,
        email = session?.email,
        lastSyncMs = marks.lastSyncMs,
    )

    /** Starts a guest session and syncs. Nothing is asked of the person. */
    suspend fun syncNow(): Result<SyncReport> = lock.withLock {
        state.value = state.value.copy(syncing = true, lastError = null)
        try {
            val session = sessions.ensureSession()
            if (marks.uid != session.uid) {
                // a new account (signed out, signed in elsewhere, deleted): it has never been read, and nothing was ever sent to it
                marks.pulledMs = 0
                marks.pushedMs = 0
                marks.uid = session.uid
            }
            val report = sync.run()
            marks.lastSyncMs = now()
            state.value = snapshot(sessions.session.value, state.value).copy(syncing = false, lastReport = report, needsSignIn = false)
            Result.success(report)
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            state.value = snapshot(sessions.session.value, state.value).copy(
                syncing = false,
                lastError = readableCloudError(failure),
                needsSignIn = failure is CloudSignInRequired,
            )
            Result.failure(failure)
        }
    }

    /** Something in the library changed: sync after a short quiet spell, so a burst of edits is one sync. */
    fun requestSync(delayMs: Long = 8_000L) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    /** Drops a sync still waiting for quiet, for when cloud sync was turned off. */
    fun cancelPendingSync() {
        pending?.cancel()
        pending = null
    }

    suspend fun signInWithGoogle(googleIdToken: String): Result<SyncReport> {
        val result = runCatching { sessions.signInWithGoogle(googleIdToken) }
        if (result.isFailure) {
            state.value = snapshot(sessions.session.value, state.value).copy(lastError = result.exceptionOrNull()?.message)
            return Result.failure(result.exceptionOrNull()!!)
        }
        state.value = snapshot(sessions.session.value, state.value).copy(needsSignIn = false)
        return syncNow()
    }

    /** Forgets this device's session (cloud data stays). */
    suspend fun signOut() {
        sessions.signOut()
        state.value = snapshot(null, state.value)
    }

    /** Deletes the cloud data and the account. The library on this device is untouched. */
    suspend fun deleteAccount(): Result<Unit> = runCatching {
        sessions.deleteAccount { sync.deleteAllCloudData() }
        marks.pulledMs = 0
        marks.pushedMs = 0
        state.value = snapshot(null, state.value).copy(lastReport = null)
    }
}

/** A message for the settings page, not a stack trace: what happened and, where it helps, what to do. */
internal fun readableCloudError(failure: Throwable): String = when {
    failure is CloudSignInRequired || (failure is CloudException && failure.isAuthLost) -> "Sign in again to keep syncing"
    failure is CloudException && failure.code == "PERMISSION_DENIED" -> "The cloud refused this account's data. Try signing out and in again"
    failure is CloudException && failure.code == "UNAUTHENTICATED" -> "Sign in again to keep syncing"
    failure is CloudException && failure.code == "RESOURCE_EXHAUSTED" -> "The sync service is busy. It will try again later"
    failure is java.io.IOException -> "No connection. It will try again later"
    else -> failure.message ?: failure.javaClass.simpleName
}
