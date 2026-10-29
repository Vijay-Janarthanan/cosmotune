package com.cosmotune.video.account

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.flow.StateFlow

/**
 * The listener's own YouTube data in Video mode: recommendations, the subscriptions feed, subscribed channels, playlists
 * (Watch Later and Liked videos included), history, and the writes that go with them. One Google account sign-in serves
 * Music and Video alike: the web session Music already holds is the one this reads, so signing in on either side counts for both.
 *
 * Signed out, every call answers [AccountFailure.NotSignedIn] and the app falls back to its local library, history and
 * subscriptions. Every call returns a [Result] whose failure is always an [AccountFailure]; none throws (cancellation aside).
 * Paged calls take the [PageToken] of the previous [Page.next] and never loop on their own: the caller asks for the next page.
 */
interface VideoAccount {
    /** Whether a Google account is signed in, and who. Starts as the host's current session. */
    val state: StateFlow<AccountState>

    /**
     * The ids of the channels the account is subscribed to, as far as they are known: filled by [subscribedChannels] and kept
     * current by [setSubscribed], so a Subscribe button can show its state without a request of its own. Empty until loaded.
     */
    val subscribedIds: StateFlow<Set<String>>

    /** Asks the host to open its existing sign-in screen (the one Music uses). Returns at once; [state] changes when it succeeds. */
    suspend fun signInRequest()

    /** Switches to another Google account the host has signed in; [state] follows. Caches of the old account are dropped. */
    suspend fun switchAccount(id: String): Result<Unit>

    /** Signs the account out of the whole app (Music too). */
    suspend fun signOut()

    /** Drops what was cached, so the next call asks the network (pull to refresh). */
    fun invalidate()

    // ---- Reading ---------------------------------------------------------------------------------------------------

    /** The personalised Home ("what to watch"); Shorts shelves arrive as items with [VideoItem.isShort]. */
    suspend fun home(continuation: PageToken? = null): Result<Page<VideoItem>>

    /** The subscriptions feed, newest first. */
    suspend fun subscriptionsFeed(continuation: PageToken? = null): Result<Page<VideoItem>>

    /** All the channels the account is subscribed to. */
    suspend fun subscribedChannels(): Result<List<ChannelItem>>

    /** The account's own playlists (not Watch Later or Liked videos: see [watchLater], [liked], [WATCH_LATER], [LIKED]). */
    suspend fun playlists(): Result<List<PlaylistItem>>

    /** The videos of the playlist [id] ("PL...", [WATCH_LATER], [LIKED]); a leading "VL" is accepted. */
    suspend fun playlist(id: String, continuation: PageToken? = null): Result<Page<VideoItem>>

    /** Watch Later. */
    suspend fun watchLater(continuation: PageToken? = null): Result<Page<VideoItem>> = playlist(WATCH_LATER, continuation)

    /** Liked videos. */
    suspend fun liked(continuation: PageToken? = null): Result<Page<VideoItem>> = playlist(LIKED, continuation)

    /** The watch history, newest first. */
    suspend fun history(continuation: PageToken? = null): Result<Page<VideoItem>>

    // ---- Writing ---------------------------------------------------------------------------------------------------

    /** Subscribes to ([subscribed] true) or unsubscribes from the channel [channelId] ("UC..."). */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit>

    /** Likes, dislikes or clears the rating of [videoId]. */
    suspend fun rate(videoId: String, rating: Rating): Result<Unit>

    /** Adds [videoId] to the playlist [playlistId]. */
    suspend fun addToPlaylist(playlistId: String, videoId: String): Result<Unit>

    /** Removes [videoId] from the playlist [playlistId] (every copy of it the account's playlist holds). */
    suspend fun removeFromPlaylist(playlistId: String, videoId: String): Result<Unit>

    /** Adds [videoId] to Watch Later. */
    suspend fun addToWatchLater(videoId: String): Result<Unit> = addToPlaylist(WATCH_LATER, videoId)

    companion object {
        /** The id of Watch Later as a playlist. */
        const val WATCH_LATER = "WL"

        /** The id of Liked videos as a playlist. */
        const val LIKED = "LL"
    }
}

/** What the video side knows of the sign-in. */
sealed interface AccountState {
    /** No Google account: the app uses its local library only. */
    data object SignedOut : AccountState

    /**
     * A Google account is signed in. [displayName] (empty while the host has not said who), [handle] ("@name", when known) and
     * [avatarUrl] describe the [active] one;
     * [accounts] lists every account (and brand channel) the session holds, the active one among them.
     */
    data class SignedIn(
        val displayName: String,
        val handle: String?,
        val avatarUrl: String?,
        val accounts: List<AccountChoice>,
        val active: AccountChoice?,
    ) : AccountState
}

/** One account (or brand channel) of the Google session; [id] is the host's own id for it, handed back to [VideoAccount.switchAccount]. */
data class AccountChoice(val id: String, val displayName: String, val handle: String?, val avatarUrl: String?)

/** A rating of a video. */
enum class Rating { LIKE, NONE, DISLIKE }

/** Why an account call failed. Always the exception inside a failed [Result]. */
sealed class AccountFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No account is signed in: offer sign-in. */
    class NotSignedIn : AccountFailure("Not signed in")

    /** The session was refused (401/403 or a sign-in wall): ask the listener to sign in again. */
    class SessionExpired(m: String = "The sign-in expired") : AccountFailure(m)

    /** Too many requests (429 or a quota answer); try again after [retryAfterSec] when the server said. */
    class RateLimited(val retryAfterSec: Long? = null) : AccountFailure("Rate limited")

    /** YouTube's answer could not be read, or a write was refused with an error body: the site changed, an update may fix this. */
    class Changed(m: String, c: Throwable? = null) : AccountFailure(m, c)

    /** No connection, a timeout, or another HTTP failure. */
    class Network(m: String, c: Throwable? = null) : AccountFailure(m, c)
}
