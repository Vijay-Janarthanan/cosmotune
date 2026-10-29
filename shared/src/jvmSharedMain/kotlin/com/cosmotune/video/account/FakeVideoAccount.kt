package com.cosmotune.video.account

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The account in memory, for previews and tests: signed in as a sample listener (or signed out with [signedIn] false) with
 * a few channels, playlists and videos. [failWith] makes every call fail with that failure (to draw the error states);
 * writes change what the reads then return. Pages hold [PAGE_SIZE] items and chain through [PageToken]s ("2", "3", ...).
 */
class FakeVideoAccount(signedIn: Boolean = true) : VideoAccount {
    private val choices = listOf(
        AccountChoice("0", "Asha Raman", "@asharaman", null),
        AccountChoice("1", "Asha's Cooking Club", "@ashacooks", null),
    )
    private val flow = MutableStateFlow<AccountState>(if (signedIn) signedInState(choices[0]) else AccountState.SignedOut)
    private val subscribed = MutableStateFlow(CHANNELS.take(3).map { it.id }.toSet())
    private val playlistVideos = mutableMapOf(
        VideoAccount.WATCH_LATER to VIDEOS.take(6).toMutableList(),
        VideoAccount.LIKED to VIDEOS.drop(4).take(9).toMutableList(),
        "PLfake1" to VIDEOS.take(12).toMutableList(),
        "PLfake2" to VIDEOS.drop(10).take(5).toMutableList(),
    )
    private val ratings = mutableMapOf<String, Rating>()

    /** When set, every call fails with it. */
    @Volatile
    var failWith: AccountFailure? = null

    /** How many times [signInRequest] was called. */
    var signInRequests: Int = 0
        private set

    override val state: StateFlow<AccountState> = flow.asStateFlow()

    override val subscribedIds: StateFlow<Set<String>> = subscribed.asStateFlow()

    override suspend fun signInRequest() {
        signInRequests += 1
        flow.value = signedInState(choices[0])
    }

    override suspend fun switchAccount(id: String): Result<Unit> = guard {
        val choice = choices.firstOrNull { it.id == id } ?: throw AccountFailure.Changed("No such account")
        flow.value = signedInState(choice)
    }

    override suspend fun signOut() {
        flow.value = AccountState.SignedOut
        subscribed.value = emptySet()
    }

    override fun invalidate() = Unit

    override suspend fun home(continuation: PageToken?): Result<Page<VideoItem>> = guard { paged(VIDEOS.shuffledStable(), continuation) }

    override suspend fun subscriptionsFeed(continuation: PageToken?): Result<Page<VideoItem>> = guard {
        paged(VIDEOS.filter { it.channelId in subscribed.value }, continuation)
    }

    override suspend fun subscribedChannels(): Result<List<ChannelItem>> = guard { CHANNELS.filter { it.id in subscribed.value } }

    override suspend fun playlists(): Result<List<PlaylistItem>> = guard {
        listOf(
            PlaylistItem("PLfake1", "Weekend cooking", VIDEOS[0].thumbnailUrl, "Asha Raman", "UCasha", playlistVideos.getValue("PLfake1").size.toLong()),
            PlaylistItem("PLfake2", "Long listens", VIDEOS[3].thumbnailUrl, "Asha Raman", "UCasha", playlistVideos.getValue("PLfake2").size.toLong()),
        )
    }

    override suspend fun playlist(id: String, continuation: PageToken?): Result<Page<VideoItem>> = guard {
        paged(playlistVideos[id.removePrefix("VL")] ?: throw AccountFailure.Changed("No such playlist"), continuation)
    }

    override suspend fun history(continuation: PageToken?): Result<Page<VideoItem>> = guard { paged(VIDEOS.reversed(), continuation) }

    override suspend fun setSubscribed(channelId: String, subscribed: Boolean): Result<Unit> = guard {
        this.subscribed.update { if (subscribed) it + channelId else it - channelId }
    }

    override suspend fun rate(videoId: String, rating: Rating): Result<Unit> = guard {
        ratings[videoId] = rating
        val liked = playlistVideos.getValue(VideoAccount.LIKED)
        liked.removeAll { it.id == videoId }
        if (rating == Rating.LIKE) VIDEOS.firstOrNull { it.id == videoId }?.let { liked.add(0, it) }
    }

    override suspend fun addToPlaylist(playlistId: String, videoId: String): Result<Unit> = guard {
        val list = playlistVideos[playlistId] ?: throw AccountFailure.Changed("No such playlist")
        val video = VIDEOS.firstOrNull { it.id == videoId } ?: throw AccountFailure.Changed("No such video")
        if (list.none { it.id == videoId }) list.add(0, video)
    }

    override suspend fun removeFromPlaylist(playlistId: String, videoId: String): Result<Unit> = guard {
        val list = playlistVideos[playlistId] ?: throw AccountFailure.Changed("No such playlist")
        list.removeAll { it.id == videoId }
    }

    /** The rating last set for [videoId], or [Rating.NONE]. */
    fun ratingOf(videoId: String): Rating = ratings[videoId] ?: Rating.NONE

    private fun <T> guard(block: () -> T): Result<T> {
        if (flow.value is AccountState.SignedOut) return Result.failure(AccountFailure.NotSignedIn())
        failWith?.let { return Result.failure(it) }
        return try {
            Result.success(block())
        } catch (e: AccountFailure) {
            Result.failure(e)
        }
    }

    private fun paged(all: List<VideoItem>, continuation: PageToken?): Page<VideoItem> {
        val page = continuation?.value?.toIntOrNull() ?: 1
        val from = (page - 1) * PAGE_SIZE
        val items = all.drop(from).take(PAGE_SIZE)
        val next = if (from + PAGE_SIZE < all.size) PageToken((page + 1).toString()) else null
        return Page(items, next)
    }

    private fun List<VideoItem>.shuffledStable(): List<VideoItem> = sortedBy { it.id.hashCode() }

    private fun signedInState(active: AccountChoice) = AccountState.SignedIn(active.displayName, active.handle, active.avatarUrl, choices, active)

    companion object {
        /** Items per fake page. */
        const val PAGE_SIZE = 8

        /** The sample channels. */
        val CHANNELS: List<ChannelItem> = listOf(
            ChannelItem("UCkitchen", "Kitchen Table", null, 1_200_000, 340, "Home cooking, slowly.", verified = true, handle = "@kitchentable"),
            ChannelItem("UCtrails", "Trail Notes", null, 85_000, 120, "Hiking with a camera.", handle = "@trailnotes"),
            ChannelItem("UCcircuit", "Circuit Garden", null, 430_000, 210, "Electronics for people who garden.", handle = "@circuitgarden"),
            ChannelItem("UCharbor", "Harbor Radio", null, 2_900_000, 900, "Long mixes for long evenings.", verified = true, handle = "@harborradio"),
        )

        /** The sample videos, round-robin over [CHANNELS]; every fifth is a Short. */
        val VIDEOS: List<VideoItem> = (0 until 24).map { n ->
            val channel = CHANNELS[n % CHANNELS.size]
            VideoItem(
                id = "fakevid%05d".format(n),
                title = "${listOf("A calm hour with", "Ten things about", "Why I love", "Building", "Slow morning:", "Notes on")[n % 6]} sample $n",
                channelId = channel.id,
                channelName = channel.name,
                channelAvatarUrl = channel.avatarUrl,
                thumbnailUrl = "https://media.cosmotune.invalid/thumb/fakevid%05d.jpg".format(n),
                durationSec = if (n % 5 == 4) 45L else 300L + n * 97L,
                viewCount = 1_000L + n * 40_321L,
                uploadedAtMs = null,
                uploadedText = "${n + 1} days ago",
                isShort = n % 5 == 4,
            )
        }
    }
}
