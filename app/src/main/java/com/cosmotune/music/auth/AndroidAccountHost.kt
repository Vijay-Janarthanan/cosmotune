package com.cosmotune.music.auth

import com.cosmotune.music.data.model.Account
import com.cosmotune.video.account.AccountChoice
import com.cosmotune.video.account.AccountHost
import com.cosmotune.video.account.HostIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Video mode's view of the Google sign-in Music runs: who is signed in and which accounts and channels the session holds, read from
 * the flows `MainViewModel` already keeps, with its select-profile and sign-out as the actions. The credential is not passed
 * here: `MainViewModel` publishes it to `Innertube.cookie`, which Video's account reads. Signing in opens Music's own screen
 * through [SignInRequest].
 */
class AndroidAccountHost(
    googleAccounts: StateFlow<List<GoogleAccountSession>>,
    private val activeAccountId: StateFlow<String?>,
    activeProfileId: StateFlow<String?>,
    account: StateFlow<Account?>,
    private val scope: CoroutineScope,
    private val select: (accountId: String, profileId: String) -> Unit,
    private val signOutAction: () -> Unit,
) : AccountHost {
    private val sessions = googleAccounts

    override val identity: StateFlow<HostIdentity?> = combine(googleAccounts, activeAccountId, activeProfileId, account) { all, accountId, profileId, details ->
        identityOf(all, accountId, profileId, details)
    }.stateIn(scope, SharingStarted.Eagerly, identityOf(googleAccounts.value, activeAccountId.value, activeProfileId.value, account.value))

    override fun openSignIn() = SignInRequest.request()

    override fun switchAccount(id: String): Boolean {
        val (accountId, profileId) = split(id) ?: return false
        val known = sessions.value.any { s -> s.accountId == accountId && s.profiles.any { it.profileId == profileId } }
        if (!known) return false
        scope.launch { select(accountId, profileId) }
        return true
    }

    override fun signOut() {
        scope.launch { signOutAction() }
    }

    internal companion object {
        private const val SEPARATOR = '\u001F'

        fun choiceId(accountId: String, profileId: String) = "$accountId$SEPARATOR$profileId"

        fun split(id: String): Pair<String, String>? {
            val at = id.indexOf(SEPARATOR)
            return if (at <= 0) null else id.substring(0, at) to id.substring(at + 1)
        }

        fun identityOf(
            sessions: List<GoogleAccountSession>,
            accountId: String?,
            profileId: String?,
            details: Account?,
        ): HostIdentity? {
            val session = sessions.firstOrNull { it.accountId == accountId } ?: sessions.firstOrNull() ?: return null
            val choices = sessions.flatMap { s ->
                s.profiles.map { p ->
                    AccountChoice(
                        id = choiceId(s.accountId, p.profileId),
                        displayName = p.name.ifBlank { s.name },
                        handle = p.handle.takeIf { it.startsWith("@") },
                        avatarUrl = p.avatar,
                    )
                }
            }
            val profile = session.profiles.firstOrNull { it.profileId == profileId }
                ?: session.profiles.firstOrNull { it.profileId == session.activeProfileId }
                ?: session.profiles.firstOrNull()
            val active = profile?.let { choiceId(session.accountId, it.profileId) }
            return HostIdentity(
                displayName = profile?.name?.ifBlank { null } ?: details?.name ?: session.name,
                handle = profile?.handle?.takeIf { it.startsWith("@") },
                avatarUrl = profile?.avatar ?: details?.thumbnailUrl,
                accounts = choices,
                activeId = active,
            )
        }
    }
}
