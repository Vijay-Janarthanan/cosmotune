package com.cosmotune.video.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cosmotune.ui.account.AccountExpiredBanner
import com.cosmotune.video.account.AccountFailure
import com.cosmotune.video.account.AccountState
import com.cosmotune.video.account.Rating
import com.cosmotune.video.account.VideoAccount
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_account_expired
import com.cosmotune.video.resources.video_account_sign_in_again
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * The signed-in account's own lists for the Library pages and Subscriptions. Each page merges them with its local list
 * (com.cosmotune.video.account.AccountLists: the account first, each video once); signed out, or when the account cannot be read,
 * the account side is empty and the page is its local list as before. An expired sign-in shows [AccountExpiredNotice].
 */

/** Which of the account's lists. */
enum class AccountList { SUBSCRIPTIONS, HISTORY, WATCH_LATER, LIKED }

/** The first page of one of the account's lists, as the page reads it. */
@Stable
class AccountVideos internal constructor(
    private val account: VideoAccount,
    private val list: AccountList,
    private val scope: CoroutineScope,
) {
    /** The account's videos, each once; empty while signed out, loading, or when the list could not be read. */
    var items: List<VideoItem> by mutableStateOf(emptyList())
        internal set

    /** Whether the sign-in expired (the page shows [AccountExpiredNotice]). */
    var expired: Boolean by mutableStateOf(false)
        internal set

    internal var reloads by mutableIntStateOf(0)

    /** Reads the list again, past the account's cache (a pull, "Refresh"). */
    fun reload() {
        reloads += 1
    }

    /** Whether the account holds [id] in this list. */
    operator fun contains(id: String): Boolean = items.any { it.id == id }

    /**
     * Takes [id] off the account's list too (Watch later: the playlist; Liked: the rating), when the account holds it. The row goes
     * at once; a refused write is read back on the next load.
     */
    fun removeOnAccount(id: String) {
        if (id !in this) return
        items = items.filterNot { it.id == id }
        scope.launch {
            when (list) {
                AccountList.WATCH_LATER -> account.removeFromPlaylist(VideoAccount.WATCH_LATER, id)
                AccountList.LIKED -> account.rate(id, Rating.NONE)
                AccountList.SUBSCRIPTIONS, AccountList.HISTORY -> Result.success(Unit)
            }
        }
    }

    internal suspend fun load(state: AccountState.SignedIn?, fresh: Boolean) {
        if (state == null) {
            items = emptyList()
            expired = false
            return
        }
        if (fresh) account.invalidate()
        val result = when (list) {
            AccountList.SUBSCRIPTIONS -> account.subscriptionsFeed()
            AccountList.HISTORY -> account.history()
            AccountList.WATCH_LATER -> account.watchLater()
            AccountList.LIKED -> account.liked()
        }
        result.onSuccess { page ->
            items = page.items.distinctBy { it.id }
            expired = state.expired
        }.onFailure { failure ->
            items = emptyList()
            expired = state.expired || failure is AccountFailure.SessionExpired
        }
    }
}

/** The account's [list] for this page, read when the page opens and again when the account (or its sign-in) changes. */
@Composable
fun rememberAccountVideos(list: AccountList): AccountVideos {
    val account = LocalVideoGraph.current.account
    val scope = rememberCoroutineScope()
    val holder = remember(account, list) { AccountVideos(account, list, scope) }
    val state by account.state.collectAsState()
    val signedIn = state as? AccountState.SignedIn
    LaunchedEffect(holder, signedIn?.active?.id, signedIn != null, signedIn?.expired, holder.reloads) {
        holder.load(signedIn, fresh = holder.reloads > 0)
    }
    return holder
}

/** The account's own playlists, as the page reads them. */
@Stable
class AccountPlaylists internal constructor() {
    var items: List<PlaylistItem> by mutableStateOf(emptyList())
        internal set
    var expired: Boolean by mutableStateOf(false)
        internal set
}

/** The account's own playlists for this page (not Watch later or Liked), read like [rememberAccountVideos]. */
@Composable
fun rememberAccountPlaylists(): AccountPlaylists {
    val account = LocalVideoGraph.current.account
    val holder = remember(account) { AccountPlaylists() }
    val state by account.state.collectAsState()
    val signedIn = state as? AccountState.SignedIn
    LaunchedEffect(holder, signedIn?.active?.id, signedIn != null, signedIn?.expired) {
        if (signedIn == null) {
            holder.items = emptyList()
            holder.expired = false
            return@LaunchedEffect
        }
        account.playlists()
            .onSuccess { holder.items = it; holder.expired = signedIn.expired }
            .onFailure { holder.items = emptyList(); holder.expired = signedIn.expired || it is AccountFailure.SessionExpired }
    }
    return holder
}

/**
 * The sign-in expired: the shared banner with "Sign in again", which opens the app's one sign-in (in place on the phone). What is
 * saved on this device keeps showing under it.
 */
@Composable
fun AccountExpiredNotice(modifier: Modifier = Modifier) {
    val account = LocalVideoGraph.current.account
    val scope = rememberCoroutineScope()
    AccountExpiredBanner(
        onSignIn = { scope.launch { account.signInRequest() } },
        modifier = modifier,
        message = stringResource(Res.string.video_account_expired),
        actionLabel = stringResource(Res.string.video_account_sign_in_again),
    )
}
