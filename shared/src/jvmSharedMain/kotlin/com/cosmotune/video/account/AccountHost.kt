package com.cosmotune.video.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the app that hosts Video (the phone's activity, the desktop window) says about the Google sign-in it already runs for Music.
 * The credential itself is not here: both sides publish the session to the shared Innertube client (`Innertube.cookie`), which
 * [InnerTubeVideoAccount] reads. The host adds what the client cannot know: who the account is, which accounts and channels the
 * session holds, and the sign-in, switch and sign-out actions that belong to the host's own screens.
 */
interface AccountHost {
    /** Who is signed in, or null while the host has no identity (yet). Follows switches and sign-outs. */
    val identity: StateFlow<HostIdentity?>

    /** Opens the host's sign-in screen (the one Music uses). */
    fun openSignIn()

    /** Makes the account or channel with this [AccountChoice.id] the active one; false when it is not known. */
    fun switchAccount(id: String): Boolean

    /** Signs the current account out (the whole app: Music too). */
    fun signOut()
}

/** The signed-in identity as the host knows it: the active account's name, handle and picture, every choice, and which is active. */
data class HostIdentity(
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val accounts: List<AccountChoice>,
    val activeId: String?,
)

/** The process-wide place a host installs its [AccountHost] (once, when it starts) and [InnerTubeVideoAccount] looks it up. */
object AccountHostRegistry {
    private val current = MutableStateFlow<AccountHost?>(null)

    /** The installed host, or null while there is none. */
    val host: StateFlow<AccountHost?> = current.asStateFlow()

    /** Installs [host]; a later host replaces an earlier one. */
    fun install(host: AccountHost) {
        current.value = host
    }

    /** Removes [host] if it is still the installed one. */
    fun uninstall(host: AccountHost) {
        if (current.value === host) current.value = null
    }
}
