package com.cosmotune.video.signed

import com.cosmotune.music.data.TrackLog
import com.cosmotune.music.data.innertube.InnerTubeXResolver
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.innertube.potoken.SessionBoundTokens
import com.cosmotune.video.account.obj
import com.cosmotune.video.account.str
import com.cosmotune.video.model.StreamSet
import com.cosmotune.youtube.account.YouTubeCookieJar
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Solves the ciphered links of the TV and web clients. An interface so a test needs no player script. */
internal interface SignedCipher {
    suspend fun signatureTimestamp(videoId: String): Int?

    /** The playable address of a format: its `signatureCipher` solved when it has one, its throttling parameter solved in either case. */
    suspend fun address(videoId: String, url: String?, signatureCipher: String?): String
}

/** The same player script and solvers Music's stream extraction uses. */
internal object InnerTubeXCipher : SignedCipher {
    override suspend fun signatureTimestamp(videoId: String): Int? = InnerTubeXResolver.signatureTimestamp(videoId)

    override suspend fun address(videoId: String, url: String?, signatureCipher: String?): String {
        val player = checkNotNull(InnerTubeXResolver.playerUrl(videoId)) { "No player script" }
        val plain = url ?: checkNotNull(InnerTubeXResolver.decipher(player, checkNotNull(signatureCipher) { "A format with no address" })) { "The cipher was not solved" }
        return checkNotNull(InnerTubeXResolver.unthrottle(player, plain)) { "The throttling parameter was not solved" }
    }
}

/** What a player answer gave: who answered, the answer, and its streams. */
internal class SignedPlayback(val client: SignedPlayerClient, val player: JsonObject, val streams: StreamSet)

/** Every client tried refused or could not be read; [reasons] say why, one per client, and hold nothing of the account. */
internal class SignedPlaybackFailure(val reasons: List<String>) : Exception("No signed client answered: ${reasons.joinToString("; ")}")

/**
 * Asks the player endpoint as each client in turn ([SignedPlayerClient]), signed with the one [YouTubeSession], until one answers with
 * streams that can be played. A client that refuses, answers no streams, needs a token that cannot be minted or gives links that cannot
 * be solved only moves the walk on to the next one.
 *
 * Signed requests carry the jar, the per-origin `Authorization` header, `X-Goog-AuthUser`, `X-Goog-PageId` and `onBehalfOfUser`, exactly
 * as [Innertube.postWeb] does; `Set-Cookie` values go back to the session, a 401 or 403 is a strike and starts the silent refresh (the
 * client is asked once more if that rotated the jar), and an answer that says it is logged out is reported.
 *
 * The WEB client's two PoTokens come from [tokens], which binds the streaming one to the account's identity
 * ([Innertube.SignedScope.dataSyncId], or the visitor id without one): a cookie rotation leaves that binding as it was.
 */
internal class SignedPlayerResolver(
    private val session: YouTubeSession,
    private val scopes: suspend () -> Innertube.SignedScope,
    private val http: SignedHttp,
    private val tokens: suspend (videoId: String, identity: String) -> SessionBoundTokens.Tokens?,
    private val cipher: SignedCipher,
) {
    /** The first of [clients] that answers with streams, leaving out [skip]. Throws [SignedPlaybackFailure]. */
    suspend fun resolve(videoId: String, clients: List<SignedPlayerClient>, skip: Set<SignedPlayerClient> = emptySet()): SignedPlayback {
        val reasons = ArrayList<String>()
        for (client in clients) {
            if (client in skip) continue
            try {
                ask(videoId, client)?.let { return it }
                reasons += "${client.name}: no streams"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // By kind only: a message could quote a request.
                reasons += "${client.name}: ${if (e is Refused) e.message else e.javaClass.simpleName}"
            }
            TrackLog.d(TAG, "signed player: ${reasons.last()}", about = videoId)
        }
        throw SignedPlaybackFailure(reasons)
    }

    private class Refused(message: String) : Exception(message)

    private suspend fun ask(videoId: String, client: SignedPlayerClient): SignedPlayback? {
        val scope = scopes()
        val minted = if (client.needsPoToken) {
            val identity = scope.dataSyncId ?: scope.visitorData ?: throw Refused("no identity to bind a token to")
            tokens(videoId, identity) ?: throw Refused("no token")
        } else {
            null
        }
        val timestamp = if (client.takesCookies) timestampOf(videoId) else null
        val body = SignedPlayerRequest.body(client, scope, videoId, timestamp, minted?.playerToken)
        val player = post(client, scope, body)
        val status = player.obj("playabilityStatus").str("status")
        if (status != "OK") throw Refused(status ?: "no status")
        val streams = SignedPlayerParser.streams(player, client, minted?.streamingToken) { url, signatureCipher ->
            if (signatureCipher == null && url != null && !NEEDS_SOLVING.containsMatchIn(url)) url else cipher.address(videoId, url, signatureCipher)
        }
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
