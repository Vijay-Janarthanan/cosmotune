package com.cosmotune.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one place that knows who is signed in. It starts a guest session when there is none (no screen, no choice), renews the token
 * before it lapses, and keeps one renewal in flight however many callers ask at once.
 */
class CloudSessionManager(
    private val auth: FirebaseAuthClient,
    private val store: CloudSessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(store.load())

    /** The current session, or null before the first call to [ensureSession]. */
    val session: StateFlow<CloudSession?> = state.asStateFlow()

    /** A valid session: the saved one (renewed if it is about to lapse), or a new guest. */
    suspend fun ensureSession(): CloudSession = lock.withLock {
        val current = state.value
        when {
            current == null -> adopt(auth.signInAnonymously())
            !current.needsRefresh(now()) -> current
            else -> try {
                adopt(auth.refresh(current))
            } catch (failure: CloudException) {
                if (!failure.isAuthLost) throw failure
                // a guest holds nothing the device does not also hold, so a new guest replaces it; a Google sign-in is never swapped silently
                if (current.isAnonymous) adopt(auth.signInAnonymously()) else throw CloudSignInRequired()
            }
        }
    }

    suspend fun idToken(): String = ensureSession().idToken

    /** Signs in with Google, upgrading the current guest in place when there is one. */
    suspend fun signInWithGoogle(googleIdToken: String): GoogleSignIn = lock.withLock {
        val result = auth.signInWithGoogle(googleIdToken, state.value)
        adopt(result.session)
        result
    }

    /** Forgets this device's session. The account and its cloud data stay; the next [ensureSession] starts a new guest. */
    suspend fun signOut() = lock.withLock {
        store.save(null)
        state.value = null
    }

    /** Runs [removeData] with the live session, then deletes the account itself. Both must succeed before the session is forgotten. */
    suspend fun deleteAccount(removeData: suspend (CloudSession) -> Unit) {
        val live = ensureSession()
        removeData(live)
        auth.deleteAccount(live)
        signOut()
    }

    private fun adopt(session: CloudSession): CloudSession {
        store.save(session)
        state.value = session
        return session
    }
}
