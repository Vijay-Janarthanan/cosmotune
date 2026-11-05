package com.cosmotune.music.data.innertube.potoken

import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.cosmotune.music.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One loaded BotGuard: a browser page that has run the challenge, holds an
 * integrity token, and mints PoTokens from it until it expires or dies. The
 * phone's is an Android WebView, the desktop's a JavaFX one; both drive the
 * same [PO_TOKEN_HTML].
 */
interface PoTokenMinter {
    /** Past the integrity token's lifetime, less a margin. */
    val isExpired: Boolean

    /** The page crashed, threw after start-up, or a mint timed out. */
    val isDead: Boolean

    /** A PoToken bound to [identifier] — a visitor data or a video id. */
    suspend fun generatePoToken(identifier: String): String

    /** Tears the page down, on whatever thread the browser needs. */
    fun close()
}

/**
 * What InnerTubeXResolver asks of a platform's minter besides InnerTube's own tokens: the two PoTokens of a signed-in Video request.
 * [Tokens.streamingToken] goes on the stream addresses and is bound to the account (the identity); [Tokens.playerToken] goes in the
 * player request and is bound to the video.
 */
interface SessionBoundTokens {
    class Tokens(val playerToken: String, val streamingToken: String)

    /** The tokens for [videoId] under [identity] (the account's data-sync id, or the visitor id), or null where nothing can mint. */
    suspend fun sessionBound(videoId: String, identity: String): Tokens?
}

/**
 * BotGuard PoTokens for YouTube's web clients, minted in a browser page.
 *
 * Keeps one loaded page per session (visitor data): the session's streaming
 * token is minted once when the page is made, and each track's player token is
 * minted from the same page. A page that has expired, died or belongs to
 * another session is replaced; a mint that fails on an old page gets one retry
 * on a fresh one.
 *
 * Video's signed clients share that page through [getSessionBoundTokens]. Their
 * streaming token is bound to the account, so it is minted again only when the
 * account's identity changes (or the page's own integrity token has run out); a
 * rotation of the cookies changes no identity and restarts nothing.
 *
 * @param createMinter loads a new BotGuard page; the platform's browser.
 * @param available whether this platform can host a page at all.
 */
class PoTokenGenerator(
    private val createMinter: suspend () -> PoTokenMinter,
    private val available: () -> Boolean = { true },
) {
    private val TAG = "PoTokenGenerator"

    private var webViewBadImpl = false // whether the platform's browser turned out to be unusable

    private val webPoTokenGenLock = Mutex()
    private var webPoTokenSessionId: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenMinter? = null
    private var boundIdentity: String? = null
    private var boundStreamingPot: String? = null

    suspend fun getWebClientPoToken(videoId: String, sessionId: String): PoTokenResult? {
        val supported = available()
        TrackLog.d(TAG, "WebView state: supported=$supported, badImpl=$webViewBadImpl")
        if (!supported || webViewBadImpl) {
            TrackLog.d(TAG, "WebView not available: supported=$supported, badImpl=$webViewBadImpl")
            return null
        }
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) {
                getWebClientPoToken(videoId, sessionId, forceRecreate = false)
            }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w(TAG, "poToken generation timed out after ${POTOKEN_TIMEOUT_MS}ms; proceeding without PoToken")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            TrackLog.e(TAG, "Could not obtain PO token because WebView is unavailable")
            webViewBadImpl = true
            null
        } catch (e: Exception) {
            TrackLog.e(TAG, "PO token generation failed type=${e::class.simpleName ?: "unknown"}")
            throw e
        }
    }

    suspend fun close() {
        clearGenerator()
    }

    private suspend fun clearGenerator() {
        webPoTokenGenLock.withLock {
            try {
                webPoTokenGenerator?.close()
            } catch (error: Exception) {
                TrackLog.e(TAG, "PO token WebView cleanup failed type=${error::class.simpleName ?: "unknown"}")
            }
            webPoTokenGenerator = null
            webPoTokenStreamingPot = null
            webPoTokenSessionId = null
            boundIdentity = null
            boundStreamingPot = null
        }
    }

    /**
     * The tokens of a signed-in Video request: the streaming token bound to [identity] and a player token bound to [videoId], from the
     * page in force. Null where the platform has no browser or the page did not answer in time.
     */
    suspend fun getSessionBoundTokens(videoId: String, identity: String): SessionBoundTokens.Tokens? {
        if (!available() || webViewBadImpl) return null
        return try {
            withTimeout(POTOKEN_TIMEOUT_MS) { sessionBoundTokens(videoId, identity, forceRecreate = false) }
        } catch (e: TimeoutCancellationException) {
            TrackLog.w(TAG, "session-bound poToken timed out after ${POTOKEN_TIMEOUT_MS}ms")
            clearGenerator()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BadWebViewException) {
            webViewBadImpl = true
            null
        }
    }

    private suspend fun sessionBoundTokens(videoId: String, identity: String, forceRecreate: Boolean): SessionBoundTokens.Tokens {
        val (minter, streamingPot, fresh) = webPoTokenGenLock.withLock {
            val current = webPoTokenGenerator
            val replace = forceRecreate || current == null || current.isExpired || current.isDead
            if (replace) {
                current?.close()
                webPoTokenGenerator = null
                webPoTokenStreamingPot = null
                webPoTokenSessionId = null
                boundIdentity = null
                boundStreamingPot = null
                webPoTokenGenerator = createMinter()
            }
            val page = webPoTokenGenerator!!
            if (boundIdentity != identity || boundStreamingPot == null) {
                boundStreamingPot = page.generatePoToken(identity)
                boundIdentity = identity
                TrackLog.d(TAG, "session-bound streaming PO token generated")
            }
            Triple(page, boundStreamingPot!!, replace)
        }
        val playerPot = try {
            minter.generatePoToken(videoId)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (fresh) throw t
            TrackLog.e(TAG, "session-bound PO token failed; recreating the page")
            return sessionBoundTokens(videoId, identity, forceRecreate = true)
        }
        return SessionBoundTokens.Tokens(playerToken = playerPot, streamingToken = streamingPot)
    }

    private companion object {
        const val POTOKEN_TIMEOUT_MS = 8_000L
    }

    private suspend fun getWebClientPoToken(videoId: String, sessionId: String, forceRecreate: Boolean): PoTokenResult {
        val (poTokenGenerator, streamingPot, hasBeenRecreated) =
            webPoTokenGenLock.withLock {
                // A page Video's signed clients made has no session of this path yet: it is minted on below, not reloaded.
                val shouldRecreate =
                    forceRecreate || webPoTokenGenerator == null || webPoTokenGenerator!!.isExpired ||
                        webPoTokenGenerator!!.isDead ||
                        (webPoTokenSessionId != null && webPoTokenSessionId != sessionId)
                if (shouldRecreate) {
                    TrackLog.d(TAG, "Creating new PoTokenWebView (forceRecreate=$forceRecreate)")
                    webPoTokenGenerator?.close()
                    webPoTokenGenerator = null
                    webPoTokenStreamingPot = null
                    webPoTokenSessionId = null
                    val newGenerator = createMinter()
                    val newStreamingPot = try {
                        newGenerator.generatePoToken(sessionId)
                    } catch (t: Throwable) {
                        runCatching { newGenerator.close() }
                        throw t
                    }
                    webPoTokenGenerator = newGenerator
                    webPoTokenStreamingPot = newStreamingPot
                    webPoTokenSessionId = sessionId
                    TrackLog.d(TAG, "Streaming PO token generated")
                } else if (webPoTokenSessionId == null) {
                    webPoTokenStreamingPot = webPoTokenGenerator!!.generatePoToken(sessionId)
                    webPoTokenSessionId = sessionId
                }
                Triple(webPoTokenGenerator!!, webPoTokenStreamingPot!!, shouldRecreate)
            }
        val playerPot = try {
            poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                TrackLog.e(TAG, "PO-token generation failed; recreating WebView")
                return getWebClientPoToken(videoId = videoId, sessionId = sessionId, forceRecreate = true)
            }
        }
        TrackLog.d(TAG, "PO token generated successfully")
        return PoTokenResult(
            playerRequestPoToken = streamingPot,
            streamingDataPoToken = playerPot,
        )
    }

    /** This generator as InnerTubeX's token provider, for WEB_REMIX and the other BotGuard clients. */
    fun asTokenProvider(): TokenProvider = object : TokenProvider, SessionBoundTokens {
        override suspend fun sessionBound(videoId: String, identity: String): SessionBoundTokens.Tokens? =
            getSessionBoundTokens(videoId, identity)

        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(
            videoId: String,
            visitorData: String,
            cookie: String?,
        ): com.metrolist.innertubex.extraction.PoTokenResult? =
            getWebClientPoToken(videoId, visitorData)?.let { token ->
                com.metrolist.innertubex.extraction.PoTokenResult(
                    playerRequestToken = token.playerRequestPoToken,
                    streamingDataToken = token.streamingDataPoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun close() {
            this@PoTokenGenerator.close()
        }
    }
}
