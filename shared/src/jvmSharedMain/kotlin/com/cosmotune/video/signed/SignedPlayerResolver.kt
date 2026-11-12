package com.cosmotune.video.signed

import com.cosmotune.music.data.TrackLog
import com.cosmotune.music.data.innertube.InnerTubeXResolver
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.potoken.SessionBoundTokens
import com.cosmotune.video.account.obj
import com.cosmotune.video.account.str
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.player.StartupTrace
import com.cosmotune.youtube.account.YouTubeCookieJar
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Solves the ciphered links of the TV and web clients. An interface so a test needs no player script. */
internal interface SignedCipher {
    suspend fun signatureTimestamp(videoId: String): Int?

    /** The playable address of a format: its `signatureCipher` solved when it has one, its throttling parameter solved in either case. */
    suspend fun address(videoId: String, url: String?, signatureCipher: String?): String

    /**
     * The playable addresses of every link of one answer (`url` to `signatureCipher`), in their order; null for one that could not be
     * solved. One by one here; the real cipher solves them as one batch.
     */
    suspend fun addresses(videoId: String, links: List<Pair<String?, String?>>): List<String?> = links.map { (url, signatureCipher) ->
        try {
            address(videoId, url, signatureCipher)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** The same player script and solvers Music's stream extraction uses. */
internal object InnerTubeXCipher : SignedCipher {
    override suspend fun signatureTimestamp(videoId: String): Int? = InnerTubeXResolver.signatureTimestamp(videoId)

    override suspend fun address(videoId: String, url: String?, signatureCipher: String?): String {
        val player = checkNotNull(InnerTubeXResolver.playerUrl(videoId)) { "No player script" }
        val plain = url ?: checkNotNull(InnerTubeXResolver.decipher(player, checkNotNull(signatureCipher) { "A format with no address" })) { "The cipher was not solved" }
        return checkNotNull(InnerTubeXResolver.unthrottle(player, plain)) { "The throttling parameter was not solved" }
    }

    override suspend fun addresses(videoId: String, links: List<Pair<String?, String?>>): List<String?> {
        val player = checkNotNull(InnerTubeXResolver.playerUrl(videoId)) { "No player script" }
        return InnerTubeXResolver.solveAll(player, links)
    }
}

/** What a player answer gave: who answered, the answer, and its streams. */
internal class SignedPlayback(val client: SignedPlayerClient, val player: JsonObject, val streams: StreamSet)

/** Every client tried refused or could not be read; [reasons] say why, one per client, and hold nothing of the account. */
internal class SignedPlaybackFailure(val reasons: List<String>) : Exception("No signed client answered: ${reasons.joinToString("; ")}")

/**
 * Asks the player endpoint as the clients of a list ([SignedPlayerClient]), signed with the one [YouTubeSession], and takes the first that
 * answers with streams that can be played. A client that refuses, answers no streams, needs a token that cannot be minted or gives links
 * that cannot be solved is only one that did not answer.
 *
 * The clients race ([resolve]): the first of the list is asked alone, the rest of it together as soon as it fails or once [hedgeMs] has
 * passed without its answer, and the last resorts and the clients this session benched ([ClientRanking]) only when all those failed;
 * the first answer with streams wins and the others are cancelled. When nothing has answered after [deadlineMs] the race is lost and
 * the caller falls back. The race keeps the list's order, so the client that usually answers is asked first and the others cost
 * nothing then.
 *
 * Signed requests carry the jar, the per-origin `Authorization` header, `X-Goog-AuthUser`, `X-Goog-PageId` and `onBehalfOfUser`, exactly
 * as [Innertube.postWeb] does; `Set-Cookie` values go back to the session, a 401 or 403 is a strike and starts the silent refresh (the
 * client is asked once more if that rotated the jar; a burst of refusals shares one refresh), and an answer that says it is logged out
 * is reported.
 *
 * The WEB client's two PoTokens come from [tokens], which binds the streaming one to the account's identity
 * ([Innertube.SignedScope.dataSyncId], or the visitor id without one): a cookie rotation leaves that binding as it was. [prepare] mints
 * them once ahead of the first video, so the page that mints them is up before anything waits on it.
 */
internal class SignedPlayerResolver(
    private val session: YouTubeSession,
    private val scopes: suspend () -> Innertube.SignedScope,
    private val http: SignedHttp,
    private val tokens: suspend (videoId: String, identity: String) -> SessionBoundTokens.Tokens?,
    private val cipher: SignedCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hedgeMs: Long = HEDGE_MS,
    private val deadlineMs: Long = DEADLINE_MS,
    private val partialGraceMs: Long = PARTIAL_GRACE_MS,
) {
    /** What this session learned about the clients. */
    val ranking = ClientRanking(clock)

    /** One client's go: its answer, or null with why it gave none. */
    private class Attempt(val client: SignedPlayerClient, val playback: SignedPlayback?, val reason: String)

    /**
     * The first of [clients] (leaving out [skip]) that answers with streams, in a race (see the class). Throws [SignedPlaybackFailure],
     * whose reasons are in the order of [clients], one per client asked.
     */
    suspend fun resolve(videoId: String, clients: List<SignedPlayerClient>, skip: Set<SignedPlayerClient> = emptySet()): SignedPlayback {
        val asked = clients.filter { it !in skip }
        val reasons = ConcurrentHashMap<SignedPlayerClient, String>()
        val failedBefore = CopyOnWriteArrayList<SignedPlayerClient>()
        val partial = AtomicReference<SignedPlayback?>(null)
        val winner = if (asked.isEmpty()) null else withTimeoutOrNull(deadlineMs) { race(videoId, ranking.waves(asked), reasons, failedBefore, partial) } ?: partial.get()
        if (winner != null) {
            ranking.answered(winner.client, failedBefore)
            return winner
        }
        throw SignedPlaybackFailure(asked.map { client -> "${client.name}: ${reasons[client] ?: "no answer within ${deadlineMs}ms"}" })
    }

    private suspend fun race(
        videoId: String,
        waves: ClientRanking.Waves,
        reasons: MutableMap<SignedPlayerClient, String>,
        failedBefore: MutableList<SignedPlayerClient>,
        partial: AtomicReference<SignedPlayback?>,
    ): SignedPlayback? = coroutineScope {
        // Every attempt's end comes through here, and so do the hedge's and the grace's moments: nothing is waited on but this one channel.
        val answers = Channel<Any>(Channel.UNLIMITED)
        var running = 0
        fun start(clients: List<SignedPlayerClient>) {
            for (client in clients) {
                running++
                launch { answers.send(attempt(videoId, client)) }
            }
        }
        // The late clients go one at a time, each only after everything before it failed.
        val late = ArrayDeque(waves.late)
        start(waves.first)
        var restStarted = waves.rest.isEmpty()
        if (!restStarted) launch {
            delay(hedgeMs)
            answers.send(HEDGE)
        }
        var winner: SignedPlayback? = null
        var graceOver = false
        while (winner == null && !graceOver) {
            if (running == 0) {
                when {
                    !restStarted -> { start(waves.rest); restStarted = true }
                    // With a muxed answer in hand, the benched and last-resort clients are not worth their cost.
                    late.isNotEmpty() && partial.get() == null -> start(listOf(late.removeFirst()))
                    else -> break
                }
                continue
            }
            val got = answers.receive()
            if (got === HEDGE) {
                // The first client kept the rest waiting long enough: they go now.
                if (!restStarted) { start(waves.rest); restStarted = true }
                continue
            }
            if (got === GRACE) {
                graceOver = true
                continue
            }
            got as Attempt
            running--
            val playback = got.playback
            when {
                playback == null -> {
                    reasons[got.client] = got.reason
                    failedBefore += got.client
                }
                hasLadder(playback.streams) -> winner = playback
                else -> {
                    // Only the muxed stream (360p at best, one rung): kept, while the others get a moment to give the whole ladder.
                    reasons[got.client] = "only a muxed stream"
                    if (partial.compareAndSet(null, playback)) {
                        StartupTrace.mark(videoId, "signed ${got.client.name}", "only a muxed stream: the others get ${partialGraceMs}ms")
                        if (!restStarted) { start(waves.rest); restStarted = true }
                        launch {
                            delay(partialGraceMs)
                            answers.send(GRACE)
                        }
                    }
                }
            }
        }
        coroutineContext.cancelChildren()
        winner ?: partial.get()
    }

    /** One client asked, timed and logged; what it throws (but a cancellation) is its reason. */
    private suspend fun attempt(videoId: String, client: SignedPlayerClient): Attempt {
        val began = StartupTrace.now()
        val attempt = try {
            Attempt(client, ask(videoId, client), "no streams")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // By kind only: a message could quote a request.
            Attempt(client, null, (if (e is Refused) e.message else null) ?: e.javaClass.simpleName)
        }
        val ms = StartupTrace.msSince(began)
        if (attempt.playback != null) {
            StartupTrace.mark(videoId, "signed ${client.name}", "answered in ${ms}ms")
        } else {
            TrackLog.d(TAG, "signed player: ${client.name}: ${attempt.reason} (${ms}ms)", about = videoId)
            StartupTrace.mark(videoId, "signed ${client.name}", "${attempt.reason} in ${ms}ms")
        }
        return attempt
    }

    /**
     * Pays the signed path's first costs before a video waits on them: the player script's config (the signature timestamp the TV
     * clients send) and the BotGuard page with the account's streaming token. A failure is only logged: a video then pays it itself.
     */
    suspend fun prepare() {
        val began = StartupTrace.now()
        try {
            timestampOf(PREPARE_VIDEO_ID)
            val scope = scopes()
            val identity = scope.dataSyncId ?: scope.visitorData
            if (identity != null) tokens(PREPARE_VIDEO_ID, identity)
            TrackLog.d(TAG, "signed player: prepared in ${StartupTrace.msSince(began)}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TrackLog.w(TAG, "signed player: preparing failed (${e.javaClass.simpleName})")
        }
    }

    private class Refused(message: String) : Exception(message)

    private suspend fun ask(videoId: String, client: SignedPlayerClient): SignedPlayback? {
        val scope = scopes()
        val minted = if (client.needsPoToken) {
            val identity = scope.dataSyncId ?: scope.visitorData ?: throw Refused("no identity to bind a token to")
            val began = StartupTrace.now()
            val minted = tokens(videoId, identity)
            StartupTrace.mark(videoId, "PoToken", "${if (minted == null) "none" else "minted"} in ${StartupTrace.msSince(began)}ms")
            minted ?: throw Refused("no token")
        } else {
            null
        }
        val timestamp = if (client.takesCookies) timestampOf(videoId) else null
        val body = SignedPlayerRequest.body(client, scope, videoId, timestamp, minted?.playerToken)
        val asked = StartupTrace.now()
        val player = post(client, scope, body)
        val status = player.obj("playabilityStatus").str("status")
        StartupTrace.mark(videoId, "signed ${client.name} player", "${status ?: "no status"} in ${StartupTrace.msSince(asked)}ms")
        if (status != "OK") throw Refused(status ?: "no status")
        val solving = StartupTrace.now()
        var solved = 0
        val streams = SignedPlayerParser.streams(player, client, minted?.streamingToken) { links ->
            // What needs no solving is taken as it is; the rest go to the cipher as one batch.
            val needed = links.indices.filter { i -> links[i].second != null || links[i].first?.let { NEEDS_SOLVING.containsMatchIn(it) } != false }
            solved = needed.size
            val answers = if (needed.isEmpty()) emptyList() else cipher.addresses(videoId, needed.map { links[it] })
            val out = links.map { it.first }.toMutableList()
            needed.forEachIndexed { k, i -> out[i] = answers.getOrNull(k) }
            out
        }
        StartupTrace.mark(videoId, "signed ${client.name} links", "$solved solved in ${StartupTrace.msSince(solving)}ms")
        return if (streams.videoOnly.isEmpty() && streams.muxed.isEmpty() && streams.hlsUrl == null) null else SignedPlayback(client, player, streams)
    }

    /** A timestamp that cannot be read only means a ciphered client's links may not solve, which the walk handles. */
    private suspend fun timestampOf(videoId: String): Int? = try {
        cipher.signatureTimestamp(videoId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** One POST, with the refresh around it for a signed client; an app client sends no credential and reports nothing. */
    private suspend fun post(client: SignedPlayerClient, scope: Innertube.SignedScope, body: String): JsonObject {
        val sent = if (client.takesCookies) session.cookie else null
        val first = http.post(URL, SignedPlayerRequest.headers(client, scope, sent, session), body)
        if (sent == null) return parse(first)
        session.absorbSetCookies(sent, first.setCookies)
        if (first.status != 401 && first.status != 403) return accepted(sent, first)
        session.reportRejected(sent, first.status)
        if (session.refreshAfterRejection(sent) != YouTubeSession.Refresh.ROTATED) throw Refused("HTTP ${first.status}")
        val again = session.cookie
        if (again == null || again == sent || !YouTubeCookieJar.sameLogin(again, sent)) throw Refused("HTTP ${first.status}")
        val second = http.post(URL, SignedPlayerRequest.headers(client, scope, again, session), body)
        session.absorbSetCookies(again, second.setCookies)
        if (second.status == 401 || second.status == 403) session.reportRejected(again, second.status)
        return accepted(again, second)
    }

    private fun accepted(sent: String, answer: SignedHttp.Answer): JsonObject {
        val player = parse(answer)
        val loggedOut = player.obj("responseContext").obj("mainAppWebResponseContext")?.get("loggedOut")?.toString() == "true"
        if (loggedOut) session.reportAnsweredSignedOut(sent) else session.reportAccepted(sent)
        return player
    }

    private fun parse(answer: SignedHttp.Answer): JsonObject {
        if (answer.status !in 200..299) throw Refused("HTTP ${answer.status}")
        return JSON.parseToJsonElement(answer.body) as JsonObject
    }

    companion object {
        private const val TAG = "CosmoVideo"
        const val URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"

        /** How long the first client of a race is waited for alone before the others are asked too. */
        const val HEDGE_MS = 700L

        /**
         * How long a race may take at most before it is lost. Long on purpose: the anonymous source already joins a slow race (see
         * [SignedVideoSource]), so this only stops a request that hangs, and an age-restricted video whose links are slow to solve
         * still gets its signed answer.
         */
        const val DEADLINE_MS = 20_000L

        /**
         * How long the other clients may still take once one answered with only a muxed stream. A TV client's answer, links solved,
         * takes about 1.5 s; a muxed answer (the WEB client's: its adaptive formats have no address) is used only when none came.
         */
        const val PARTIAL_GRACE_MS = 2_500L

        /** The race's moments that are no client's answer: the hedge's and the grace's. */
        private val HEDGE = Any()
        private val GRACE = Any()

        /**
         * Whether [streams] give the whole quality ladder: video-only streams (or a live stream's HLS address). A set with only muxed
         * streams has one rung, 360p at best.
         */
        fun hasLadder(streams: StreamSet): Boolean = streams.videoOnly.isNotEmpty() || streams.hlsUrl != null

        /** The video [prepare] warms the path with: the site's first upload, which will always be there. */
        const val PREPARE_VIDEO_ID = "jNQXAC9IVRw"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** A plain address with an `n` parameter is throttled until that is solved; the app clients' have none. */
        private val NEEDS_SOLVING = Regex("[?&]n=")

        /** The process-wide resolver, on the one session, Music's minter and Music's cipher service. */
        val shared: SignedPlayerResolver by lazy {
            SignedPlayerResolver(
                session = YouTubeSession.shared,
                scopes = Innertube::signedScope,
                http = OkHttpSignedHttp,
                tokens = InnerTubeXResolver::sessionBoundTokens,
                cipher = InnerTubeXCipher,
            )
        }
    }
}

/** What one player request says for its client: the pure part, which a test reads. */
internal object SignedPlayerRequest {
    private const val ORIGIN = YouTubeSession.YOUTUBE_ORIGIN

    fun headers(client: SignedPlayerClient, scope: Innertube.SignedScope, cookie: String?, session: YouTubeSession): Map<String, String> = buildMap {
        put("User-Agent", client.userAgent)
        put("X-YouTube-Client-Name", client.nameId.toString())
        put("X-YouTube-Client-Version", client.version ?: scope.webClientVersion)
        if (client.browser) {
            put("Origin", ORIGIN)
            put("X-Origin", ORIGIN)
            put("Referer", "$ORIGIN/")
        }
        if (client.takesCookies) scope.visitorData?.let { put("X-Goog-Visitor-Id", it) }
        if (client.takesCookies && cookie != null) {
            put("Cookie", cookie)
            session.authorization(ORIGIN)?.let { put("Authorization", it) }
            put("X-Goog-AuthUser", scope.authUser)
            scope.pageId?.let { put("X-Goog-PageId", it) }
        }
    }

    fun body(client: SignedPlayerClient, scope: Innertube.SignedScope, videoId: String, signatureTimestamp: Int?, playerToken: String?): String = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", client.clientName)
                put("clientVersion", client.version ?: scope.webClientVersion)
                client.extra.forEach { (key, value) -> value.toIntOrNull()?.let { put(key, it) } ?: put(key, value) }
                if (client.embedded) put("clientScreen", "EMBED")
                put("hl", scope.language)
                put("gl", scope.region)
                put("userAgent", client.userAgent)
                if (client.takesCookies) scope.visitorData?.let { put("visitorData", it) }
            }
            if (client.takesCookies) {
                putJsonObject("user") { scope.dataSyncId?.let { put("onBehalfOfUser", it) } }
            }
            if (client.embedded) putJsonObject("thirdParty") { put("embedUrl", "$ORIGIN/embed/$videoId") }
        }
        put("videoId", videoId)
        // Skips the "this may be inappropriate" interstitial on age-restricted videos.
        put("racyCheckOk", true)
        put("contentCheckOk", true)
        putJsonObject("playbackContext") {
            putJsonObject("contentPlaybackContext") {
                put("html5Preference", "HTML5_PREF_WANTS")
                signatureTimestamp?.let { put("signatureTimestamp", it) }
            }
        }
        playerToken?.let { token -> putJsonObject("serviceIntegrityDimensions") { put("poToken", token) } }
    }.toString()
}
