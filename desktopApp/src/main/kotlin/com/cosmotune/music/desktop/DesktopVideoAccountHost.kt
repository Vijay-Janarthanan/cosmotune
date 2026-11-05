package com.cosmotune.music.desktop

import com.cosmotune.video.account.AccountChoice
import com.cosmotune.video.account.AccountHost
import com.cosmotune.video.account.AccountHostRegistry
import com.cosmotune.video.account.HostIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Video mode's view of the Google sign-in the desktop runs for Music: who is signed in and which accounts and channels the
 * session holds, read from [DesktopAccounts], with its select and remove as the actions. The credential itself is not passed:
 * [DesktopYouTubeAuth.adopt] publishes it to `Innertube.cookie`, which Video's account reads. Signing in opens this window's own
 * sign-in dialog ([install]'s `openSignIn`).
 */
internal object DesktopVideoAccountHost : AccountHost {
    private const val SEPARATOR = '\u001F'

    private val state = MutableStateFlow<HostIdentity?>(null)

    override val identity: StateFlow<HostIdentity?> = state.asStateFlow()

    /** Counts the switches and sign-outs Video made, so the window reloads the account list it keeps for its own screens. */
    val revision = MutableStateFlow(0)

    @Volatile
    private var signInOpener: (() -> Unit)? = null

    /** Called once by the window: [openSignIn] shows its sign-in dialog. */
    fun install(openSignIn: () -> Unit) {
        signInOpener = openSignIn
        AccountHostRegistry.install(this)
        refresh()
    }

    /** Reads the stored accounts again; called whenever the session in force changes. */
    fun refresh() {
        state.value = runCatching { identityOf() }.getOrNull()
    }

    override fun openSignIn() {
        signInOpener?.invoke()
    }

    override fun switchAccount(id: String): Boolean {
        val at = id.indexOf(SEPARATOR)
        if (at <= 0) return false
        val accountId = id.substring(0, at)
        val profileId = id.substring(at + 1)
        val known = DesktopAccounts.accounts().any { a -> a.accountId == accountId && a.profiles.any { it.profileId == profileId } }
        if (!known) return false
        DesktopAccounts.select(accountId, profileId)
        revision.value += 1
        return true
    }

    override fun signOut() {
        DesktopAccounts.activeAccountId()?.let { DesktopAccounts.remove(it) }
        revision.value += 1
    }

    private fun identityOf(): HostIdentity? {
        val accounts = DesktopAccounts.accounts()
        val account = DesktopAccounts.active() ?: return null
        val profile = DesktopAccounts.activeProfile()
        val choices = accounts.flatMap { a ->
            a.profiles.map { p ->
                AccountChoice("${a.accountId}$SEPARATOR${p.profileId}", p.name.ifBlank { a.name }, p.handle.takeIf { it.startsWith("@") }, p.avatar)
            }
        }
        return HostIdentity(
            displayName = account.shownName(profile?.profileId).orEmpty(),
            handle = profile?.handle?.takeIf { it.startsWith("@") },
            avatarUrl = account.shownAvatar(profile?.profileId),
            accounts = choices,
            activeId = profile?.let { "${account.accountId}$SEPARATOR${it.profileId}" },
        )
    }
}
