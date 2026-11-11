package com.cosmotune.video.signed

import com.cosmotune.music.data.TrackLog
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.video.account.AccountFailure
import com.cosmotune.video.account.InnerTubeComments
import com.cosmotune.video.account.InnertubeWeb
import com.cosmotune.video.account.YoutubeFeedParser
import com.cosmotune.video.account.YoutubeWeb
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.PlaylistPage
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.player.StartupTrace
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import com.cosmotune.youtube.account.YouTubeAuthState
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put

/**
 * Video's source with the signed path in front of [anonymous] (the NewPipe adapter, which stays exactly what it was: no account, no cookie).
 *
 * Signed in, the watch page and its streams come from [SignedPlayerResolver] and the site's `next` answer (asked at the same time), so
 * age-restricted and members' videos and the account's own private and unlisted ones play. The signed clients get a head start of
 * [ANONYMOUS_AFTER_MS]; when they have not answered by then, or failed, [anonymous] is asked too and the first good answer of the two
 * plays. What neither can read is tried once more with the app clients that need no credential ([SignedPlayerClient.RESCUE]). Signed
 * out, or with a session that has expired, every playback call is simply [anonymous]'s.
 *
 * A stream set is asked again soon after it was given only because the player's fetch was refused (a set lasts for hours), so the client
 * that minted it sits out for [REFUSED_MS] and the next one answers. When they have all sat out, [anonymous] answers.
 *
 * Comments are read from the site's own endpoint for everyone ([InnerTubeComments]): it has both orders, and signed in it carries what
 * the rating and posting calls need. NewPipe stays the fallback; each page's token says which of the two it belongs to. An account's
 * playlist is read with the session too, because a private one has no public page; a public one reads the same.
 *
 * What is remembered here belongs to one account: it is dropped when [identity] changes, and a rotation of the cookies does not change it.
 */
class SignedVideoSource internal constructor(
    private val anonymous: VideoSource,
    private val signedIn: () -> Boolean,
    private val identity: () -> Any?,
    private val resolver: SignedPlayerResolver,
    private val web: YoutubeWeb,
    private val clock: () -> Long,
) : VideoSource by anonymous {

    /** The process-wide session, over [anonymous]. */
    constructor(anonymous: VideoSource, clock: () -> Long = System::currentTimeMillis) : this(
        anonymous = anonymous,
        signedIn = { YouTubeSession.shared.state.value is YouTubeAuthState.SignedIn },
        identity = { YouTubeSession.shared.generation to Innertube.chosenChannelKey },
        resolver = SignedPlayerResolver.shared,
        web = InnertubeWeb,
        clock = clock,
    )

    private val comments = InnerTubeComments(web)
    private val lock = Any()
    private var heldIdentity: Any? = null

    /** Signed details by video id, most recently used last; guarded by [lock]. */
    private val cache = object : LinkedHashMap<String, VideoDetails>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoDetails>): Boolean = size > CACHE_SIZE
    }

    /** Which client gave a video's last set and when; guarded by [lock]. */
    private val lastGiven = HashMap<String, Pair<SignedPlayerClient, Long>>()

    /** The clients sitting out for a video, and until when; guarded by [lock]. */
    private val refused = HashMap<String, MutableMap<SignedPlayerClient, Long>>()

    /** Forgets what the last account's requests learned; called before anything here is read. */
    private fun followIdentity() {
        val now = identity()
        synchronized(lock) {
            if (now == heldIdentity) return
            heldIdentity = now
            cache.clear()
            lastGiven.clear()
            refused.clear()
        }
        resolver.ranking.clear()
    }

    override fun setRegion(country: String, language: String) {
        anonymous.setRegion(country, language)
        synchronized(lock) { cache.clear() } // titles and descriptions come back in the new language
    }

    // ---- Watching --------------------------------------------------------------------------------------------------------

    override suspend fun details(videoId: String): VideoDetails {
        if (!signedIn()) {
            StartupTrace.mark(videoId, "source", "anonymous (signed out)")
            return anonymous.details(videoId)
        }
        followIdentity()
        synchronized(lock) { cache[videoId]?.takeIf { clock() - it.fetchedAtMs in 0 until DETAILS_TTL_MS } }?.let { return it }
        StartupTrace.mark(videoId, "source", "signed, the anonymous source in parallel after ${ANONYMOUS_AFTER_MS}ms")
        return coroutineScope {
            // The watch page's second answer goes out with the player request, not after it.
            val next = async { nextAnswer(videoId) }
            try {
                firstGood(
                    videoId,
                    signed = { playback ->
                        SignedPlayerParser.details(videoId, playback, next.await(), clock()).also { details -> synchronized(lock) { cache[videoId] = details } }
                    },
                    anonymous = { anonymous.details(videoId) },
                    rescued = { rescue -> SignedPlayerParser.details(videoId, rescue, next.await(), clock()) },
                )
            } finally {
                next.cancel()
            }
        }
    }

    override suspend fun streams(videoId: String): StreamSet {
        if (!signedIn()) return anonymous.streams(videoId)
        followIdentity()
        noteRefusal(videoId)
        return firstGood(
            videoId,
            signed = { playback ->
                synchronized(lock) { cache[videoId]?.let { cache[videoId] = it.copy(streams = playback.streams) } }
                playback.streams
            },
            anonymous = { anonymous.streams(videoId) },
            rescued = { it.streams },
        )
    }

    /** What a race between the two paths ended with: the signed answer, or the anonymous source's value or error. */
    private class Outcome<T>(val playback: SignedPlayback?, val value: T?, val error: Exception?)

    /**
     * The signed answer when it comes first, else the anonymous source's: the signed clients are asked at once, the anonymous source
     * [ANONYMOUS_AFTER_MS] later if they have not answered by then (or as soon as they failed), and the first good answer of the two
     * is used, the other cancelled. What neither could read goes to the rescue clients ([anonymousErrorOrRescue]).
     */
    private suspend fun <T : Any> firstGood(
        videoId: String,
        signed: suspend (SignedPlayback) -> T,
        anonymous: suspend () -> T,
        rescued: suspend (SignedPlayback) -> T,
    ): T = coroutineScope {
        val signedRace = async { attemptSigned(videoId) }
        val anonymousRace = async(start = CoroutineStart.LAZY) {
            val began = StartupTrace.now()
            try {
                Outcome<T>(null, anonymous(), null).also { StartupTrace.mark(videoId, "anonymous source", "answered in ${StartupTrace.msSince(began)}ms") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StartupTrace.mark(videoId, "anonymous source", "${e.javaClass.simpleName} in ${StartupTrace.msSince(began)}ms")
                Outcome<T>(null, null, e)
            }
        }
        try {
            // A head start: the signed answer is the one with everything the account may see, and it usually comes first anyway.
            val early = withTimeoutOrNull(ANONYMOUS_AFTER_MS) { Outcome<T>(signedRace.await(), null, null) }
            early?.playback?.let { playback ->
                StartupTrace.mark(videoId, "source decided", "signed, within the head start")
                return@coroutineScope signed(playback)
            }
            StartupTrace.mark(videoId, "anonymous source", if (early == null) "asked too: the signed clients are slow" else "asked: the signed clients failed")
            anonymousRace.start()
            var signedOutcome: Outcome<T>? = early
            var anonymousOutcome: Outcome<T>? = null
            while (true) {
                val signedDone = signedOutcome != null
                if (signedDone && anonymousOutcome != null) break
                select {
                    if (!signedDone) signedRace.onAwait { signedOutcome = Outcome<T>(it, null, null) }
                    if (anonymousOutcome == null) anonymousRace.onAwait { anonymousOutcome = it }
                }
                signedOutcome?.playback?.let { playback ->
                    anonymousRace.cancel()
                    StartupTrace.mark(videoId, "source decided", "signed")
                    return@coroutineScope signed(playback)
                }
                anonymousOutcome?.value?.let { value ->
                    signedRace.cancel()
                    StartupTrace.mark(videoId, "source decided", "anonymous")
                    return@coroutineScope value
                }
            }
            val error = anonymousOutcome!!.error!!
            if (error !is VideoSourceError) throw error
            anonymousErrorOrRescue(videoId, error, rescued)
        } finally {
            signedRace.cancel()
            anonymousRace.cancel()
        }
    }

    override suspend fun related(videoId: String): List<ListItem> {
        if (!signedIn()) return anonymous.related(videoId)
        val signed = runCatchingSource { details(videoId) }?.related.orEmpty()
        return if (signed.any { it is ListItem.Video }) signed else anonymous.related(videoId)
    }

    private fun noteRefusal(videoId: String) {
        synchronized(lock) {
            val (client, at) = lastGiven.remove(videoId) ?: return
            val now = clock()
            if (now - at < REFUSED_MS) refused.getOrPut(videoId) { HashMap() }[client] = now + REFUSED_MS
        }
    }

    private fun sittingOut(videoId: String): Set<SignedPlayerClient> = synchronized(lock) {
        val entries = refused[videoId] ?: return@synchronized emptySet()
        val now = clock()
        entries.values.removeAll { it <= now }
        entries.keys.toSet()
    }

    /** The signed answer, or null when none of the clients gave one. */
    private suspend fun attemptSigned(videoId: String): SignedPlayback? = try {
        resolver.resolve(videoId, SignedPlayerClient.SIGNED, sittingOut(videoId)).also { playback ->
            synchronized(lock) { lastGiven[videoId] = playback.client to clock() }
            TrackLog.d(TAG, "signed player: ${playback.client.name} answered", about = videoId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "signed player: falling back to the anonymous source (${e.message})", about = videoId)
        null
    }

    /** What the anonymous source could not read either ([error]) is answered by the rescue clients when they can, else [error] leaves. */
    private suspend fun <T> anonymousErrorOrRescue(videoId: String, error: VideoSourceError, rescued: suspend (SignedPlayback) -> T): T {
        StartupTrace.mark(videoId, "rescue clients", "after the anonymous source's ${error.javaClass.simpleName}")
        val playback = try {
            resolver.resolve(videoId, SignedPlayerClient.RESCUE, sittingOut(videoId))
        } catch (rescue: CancellationException) {
            throw rescue
        } catch (rescue: Exception) {
            throw error
        }
        synchronized(lock) { lastGiven[videoId] = playback.client to clock() }
        return rescued(playback)
    }

    /**
     * Pays the signed path's first costs ahead of the first video ([SignedPlayerResolver.prepare]): only signed in, where they would
     * otherwise be paid on the click.
     */
    suspend fun prepare() {
        if (signedIn()) resolver.prepare()
    }

    /** The watch page's `next` answer. Without it the page still plays: it only has no related videos, chapters or like count. */
    private suspend fun nextAnswer(videoId: String): JsonObject? {
        val began = StartupTrace.now()
        return runCatchingSource {
            web.post("next") {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }.also { response -> YoutubeFeedParser.errorOf(response)?.let { throw it } }
        }.also { StartupTrace.mark(videoId, "next", "${if (it == null) "failed" else "answered"} in ${StartupTrace.msSince(began)}ms") }
    }

    // ---- Comments --------------------------------------------------------------------------------------------------------

    override suspend fun comments(videoId: String, sort: CommentSort, page: PageToken?): CommentPage {
        if (page != null && !page.value.startsWith(InnerTubeComments.TOKEN_PREFIX)) return anonymous.comments(videoId, sort, page)
        if (page != null) return commentCall { comments.more(page) }
        return try {
            comments.first(videoId, sort)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "comments: falling back to the anonymous source (${e.javaClass.simpleName})", about = videoId)
            anonymous.comments(videoId, sort, null)
        }
    }

    override suspend fun replies(token: PageToken): CommentPage =
        if (token.value.startsWith(InnerTubeComments.TOKEN_PREFIX)) commentCall { comments.more(token) } else anonymous.replies(token)

    /** A later page has no fallback (its token means nothing to the other source): what it throws leaves as a source error. */
    private suspend fun commentCall(block: suspend () -> CommentPage): CommentPage = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw sourceError(e)
    }

    private fun sourceError(e: Exception): VideoSourceError = when (val failure = com.cosmotune.video.account.failureOf(e)) {
        is AccountFailure.Network -> VideoSourceError.Network(failure.message.orEmpty(), failure)
        else -> VideoSourceError.Parsing(failure.message.orEmpty(), failure)
    }

    // ---- Playlists -------------------------------------------------------------------------------------------------------

    override suspend fun playlist(playlistId: String, page: PageToken?): PlaylistPage {
        val mine = page?.value?.startsWith(InnerTubeComments.TOKEN_PREFIX) == true
        if (page != null && !mine) return anonymous.playlist(playlistId, page)
        if (!signedIn() && !mine) return anonymous.playlist(playlistId, page)
        val bare = playlistId.removePrefix("VL")
        return try {
            signedPlaylist(bare, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (mine) throw sourceError(e)
            TrackLog.w(TAG, "playlist: falling back to the anonymous source (${e.javaClass.simpleName})")
            anonymous.playlist(playlistId, null)
        }
    }

    private suspend fun signedPlaylist(id: String, page: PageToken?): PlaylistPage {
        val response = web.post("browse") {
            if (page != null) put("continuation", page.value.removePrefix(InnerTubeComments.TOKEN_PREFIX)) else put("browseId", "VL$id")
        }
        YoutubeFeedParser.errorOf(response)?.let { throw it }
        val feed = YoutubeFeedParser.parse(response)
        if (feed.looksChanged) throw AccountFailure.Changed("The playlist could not be read")
        if (page == null && feed.isEmpty && feed.loggedOut) throw AccountFailure.SessionExpired()
        val next = feed.continuation?.let { PageToken(InnerTubeComments.TOKEN_PREFIX + it) }
        val videos = Page(feed.videos, next)
        if (page != null) {
            return PlaylistPage(PlaylistItem(id = id, title = "", thumbnailUrl = null, ownerName = null, ownerId = null, videoCount = null), null, videos)
        }
        val header = YoutubeFeedParser.parsePlaylistHeader(response, id) ?: throw AccountFailure.Changed("The playlist had no header")
        return PlaylistPage(header.playlist, header.description, videos)
    }

    private inline fun <T> runCatchingSource(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TrackLog.w(TAG, "signed watch page: ${e.javaClass.simpleName}")
        null
    }

    internal companion object {
        private const val TAG = "CosmoVideo"
        private const val CACHE_SIZE = 24
        const val DETAILS_TTL_MS = 10 * 60_000L

        /** A set asked for again this soon was refused; its client sits out this long. */
        const val REFUSED_MS = 10 * 60_000L

        /** The signed clients' head start: the anonymous source is asked too when they have not answered by then. */
        const val ANONYMOUS_AFTER_MS = 800L
    }
}
