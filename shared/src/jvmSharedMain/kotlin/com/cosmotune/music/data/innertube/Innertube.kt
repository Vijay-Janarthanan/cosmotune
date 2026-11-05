package com.cosmotune.music.data.innertube

import com.cosmotune.music.auth.normalizeDataSyncId
import com.cosmotune.music.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.music.data.model.PlaylistMove
import com.cosmotune.music.data.model.PlaylistPrivacy
import com.cosmotune.youtube.account.SapisidAuth
import com.cosmotune.youtube.account.YouTubeCookieJar
import com.cosmotune.youtube.account.YouTubePageConfig
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.util.Base64
import java.util.Locale

/**
 * Minimal Innertube (youtubei) client.
 *
 * WEB_REMIX against music.youtube.com for browse/search/library. It returns
 * the full YT Music shelf layout and honours the signed-in session. Stream
 * URLs are not fetched here; see [InnerTubeXResolver].
 *
 * Authenticated requests are signed with Google's SAPISIDHASH scheme derived
 * from the stored cookie; no long-lived token is ever minted or stored.
 *
 * The credential itself lives in [YouTubeSession.shared], the one Google
 * session Music and Video share: the cookie jar (kept current from the
 * `Set-Cookie` answers of both clients), the visitor id and the expiry state.
 * [cookie] is a pass-through to it, so every caller that set or read the cookie
 * before still does.
 */
object Innertube {
    /**
     * The app's own language when the platform lets the listener pick one
     * separately from the system's — the phone's per-app locale. Null means
     * follow the JVM default.
     */
    @Volatile
    var appLanguage: () -> String? = { null }

    val currentLocaleTag: String
        get() {
            val raw = appLanguage()?.ifEmpty { null }
                ?: Locale.getDefault().toLanguageTag().ifEmpty { "en" }
            return com.cosmotune.music.data.LocaleTags.normalizeAppTag(raw)
        }

    val currentLanguage: String
        get() = com.cosmotune.music.data.LocaleTags.ytHl(currentLocaleTag)

    val currentRegion: String
        get() = com.cosmotune.music.data.LocaleTags.ytGl(currentLocaleTag)

    private val acceptLanguageHeader: String
        get() = com.cosmotune.music.data.LocaleTags.acceptLanguage(currentLocaleTag)

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /**
     * Fallback WEB_REMIX version, used until [SessionScope] reads the live one
     * out of the music.youtube.com shell. Only a starting point: the real
     * version moves every few days, and the one that matters is the one
     * [webRemixVersion] reports.
     */
    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    private const val TAG = "Cosmotune"

    /** The one Google session both modes sign with. */
    private val session: YouTubeSession get() = YouTubeSession.shared

    /**
     * Session cookie captured by the login WebView; null = browse as guest.
     *
     * Reads the shared session's jar as it stands (a rotated cookie included);
     * setting it installs a session there. Installing the value already in
     * force, or the platform's stored copy of a login a rotation has since moved
     * past, changes nothing.
     */
    var cookie: String?
        get() = session.cookie
        set(value) {
            if (session.install(value)) {
                // Both belong to the session that just left. A scope kept across
                // a sign-in would credit the new account's plays to the old one,
                // and a visitor id minted under the old session is not bound to
                // the new one — see [SessionScope] and [visitorData].
                scope = null
                visitorData = null
                visitorDataIsSessionBound = false
                // The chosen channel belonged to the account that just left.
                // A `dataSyncId` from one login sent under another's cookie is
                // answered with 401 on every request, so it goes with it.
                channelOverride = null
                // A new session is worth a fresh look at the site's page: its
                // client version, and whether it is signed in at all.
                webVersionCheckedAtMs = 0L
            }
        }

    /** [cookie] as a flow, so a screen that is not Music's (Video mode's account) follows a sign-in, a switch and a sign-out. */
    val cookieState: StateFlow<String?> get() = session.cookieState

    /**
     * Google's per-session visitor id.
     *
     * Far more load-bearing than "an id for stats". A `player` request that
     * carries no visitor id is treated as a client with no session at all, and
     * Google answers it in one of two ways: the honest one, `LOGIN_REQUIRED` /
     * "Sign in to confirm you're not a bot", or the quiet one — a perfectly
     * ordinary-looking response whose stream URLs serve a byte to anything that
     * asks and then refuse every real read with 403. The second is what
     * "it loads and then doesn't play" is made of.
     *
     * So it is fetched deliberately by [ensureVisitorData] rather than being
     * hoped for: browse responses carry one only sometimes, and a session that
     * never happened to see one would silently never play anything.
     */
    private var visitorData: String?
        get() = session.visitorData
        set(value) { session.visitorData = value }

    /**
     * Whether [visitorData] came from the signed-in shell rather than being
     * minted anonymously.
     *
     * A session-bound id outranks an anonymous one and must not be replaced by
     * it. Both are fetched near startup and nothing orders them, so without this
     * the better id was lost to whichever request happened to finish second.
     */
    private var visitorDataIsSessionBound: Boolean
        get() = session.visitorDataIsSessionBound
        set(value) { session.visitorDataIsSessionBound = value }

    /**
     * A visitor id for this session, minting one if there isn't one yet.
     *
     * @param refresh discard the current id and take a fresh one — worth doing
     *   exactly once when a request comes back accusing us of being a bot,
     *   since an id can be burned while the session around it is fine.
     */
    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching { fetchVisitorData() }
            .onFailure { Log.w(TAG, "could not mint a visitor id: ${it.message}") }
            .getOrNull()
            ?.let {
                if (refresh || !visitorDataIsSessionBound) {
                    visitorData = it
                    visitorDataIsSessionBound = false
                }
            }
        return visitorData
    }

    /**
     * The service worker bootstrap the web player loads before anything else,
     * which is where a fresh visitor id comes from without needing a page.
     * It answers with an anti-hijacking prefix and then plain nested arrays,
     * so the id is found by shape rather than by a path that would rot.
     */
    private suspend fun fetchVisitorData(): String? {
        val body = client.get("https://www.youtube.com/sw.js_data") {
            header("User-Agent", WEB_USER_AGENT)
        }.bodyAsText()
        val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        return findVisitorData(payload)
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
        else -> null
    }

    /** Protobuf-in-base64; always this shape, and nothing else in there is. */
    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    // ---- Which account is this, exactly -------------------------------------

    /**
     * Who the session cookie actually acts as, and which client version it acts
     * with — read out of the signed-in music.youtube.com shell.
     *
     * A cookie is not an account. One Google login carries every account the
     * browser has ever signed into, plus every brand channel hanging off them,
     * and *nothing in the cookie says which one is meant*. The web client
     * resolves that from its page config and then says so on every request. An
     * app that skips this step is not making an ambiguous request — it is
     * making a request about the first account in the jar, whoever that is.
     *
     * That is the whole of "history works for me and not for them": for a
     * listener whose YouTube Music account *is* the first one, guessing is
     * indistinguishable from asking. For anyone with two Google accounts, or a
     * brand channel — the account YouTube Music itself pushes you onto when you
     * have one — every play was being credited to the wrong identity, so their
     * own history stayed empty no matter how many pings went out successfully.
     *
     * @param dataSyncId the account, as `context.user.onBehalfOfUser`. Only
     *   ever taken from a shell that reported itself signed in: Google answers
     *   an `onBehalfOfUser` it cannot tie to a session with 401, so a guessed
     *   value would break every request in the app rather than just history.
     * @param pageId the brand channel, as `X-Goog-PageId` — the header the
     *   stats endpoints ask for by name as `PLUS_PAGE_ID`. Absent for a plain
     *   personal account, which is why it is nullable rather than defaulted.
     * @param authUser which entry in the cookie jar, as `X-Goog-AuthUser`.
     *   Hardcoded `0` before this, which is the same guess by another name.
     */
    private class SessionScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?,
    )

    @Volatile
    private var scope: SessionScope? = null

    /** When [scope] was last read (or adopted), and under which recovery of the jar. */
    @Volatile
    private var scopeCheckedAtMs = 0L

    @Volatile
    private var scopeRecoveries = 0L

    /** How long a scope read from the shell stands before it is read again: Google rotates `DATASYNC_ID`. */
    private const val SCOPE_TTL_MS = 24 * 60 * 60 * 1000L

    /** Whether [scope] can be used as it is: read today, under the jar in force. */
    private fun scopeIsCurrent(): Boolean =
        scope != null &&
            System.currentTimeMillis() - scopeCheckedAtMs < SCOPE_TTL_MS &&
            scopeRecoveries == session.recoveries

    private val scopeLock = Mutex()

    /** A channel the listener picked, standing in for the shell's default. */
    class ChannelSelection(
        val pageId: String?,
        val dataSyncId: String?,
        /**
         * Which Google account in the cookie jar the channel belongs to. Null
         * leaves the shell's own answer alone — a brand channel sits under the
         * account that owns it, so this only differs when the listener switched
         * to a channel of a *different* signed-in Google account.
         */
        val authUser: String? = null,
    )

    /**
     * The channel to act as, when the listener has said which.
     *
     * [fetchSessionScope] can only report the one music.youtube.com serves by
     * default, and for an account whose music lives on a brand channel that is
     * the wrong one — the library, the likes and the history all belong to the
     * other identity. Once a channel has been chosen it outranks the shell
     * completely, including when what it chose is the account's own channel and
     * the shell is the one offering a brand page.
     */
    @Volatile
    private var channelOverride: ChannelSelection? = null

    /**
     * Act as this channel from now on; both null goes back to the shell's
     * default. Takes effect on the next request — nothing is cached from it.
     */
    fun selectChannel(pageId: String?, dataSyncId: String?, authUser: String? = null) {
        channelOverride = if (pageId == null && dataSyncId == null) {
            null
        } else {
            ChannelSelection(pageId, dataSyncId, authUser)
        }
        Log.d(
            TAG,
            "acting as channel pageId=${pageId ?: "none"} authUser=${authUser ?: "as-is"} " +
                "(override=${channelOverride != null})",
        )
    }

    /**
     * Takes the session scope from a page the listener was actually looking at,
     * rather than working it out later from a fetch of our own.
     *
     * The shell fetch in [fetchSessionScope] can only ever report the channel
     * music.youtube.com serves this app by default, and the whole reason the
     * in-app browser exists is that the listener has just told it, by hand,
     * that they want a different one. That answer is written into the page's
     * own `ytcfg`, so it is read from there and adopted whole.
     *
     * Adopting also settles [ensureSessionScope] — a scope already in hand is
     * not refetched — so the shell cannot quietly overwrite the choice with its
     * default on the next request.
     *
     * A page that reported itself signed out is ignored apart from its client
     * version: its `DATASYNC_ID` belongs to no account, and sending one Google
     * cannot tie to the session is answered with 401 on every request.
     */
    fun adoptSessionScope(
        pageId: String?,
        dataSyncId: String?,
        authUser: String?,
        visitorData: String?,
        clientVersion: String?,
        loggedIn: Boolean,
    ) {
        val version = clientVersion ?: scope?.clientVersion
        if (!loggedIn) {
            Log.w(TAG, "captured page was signed out; not scoping requests to it")
            scope = version?.let { SessionScope(null, null, "0", it) }
            return
        }
        scopeCheckedAtMs = System.currentTimeMillis()
        scopeRecoveries = session.recoveries
        scope = SessionScope(
            dataSyncId = dataSyncId?.takeIf { it.isNotBlank() },
            pageId = pageId?.takeIf { it.isNotBlank() },
            authUser = authUser?.takeIf { it.isNotBlank() } ?: "0",
            clientVersion = version,
        )
        // The page's own visitor id, bound to this session — strictly better
        // than the anonymous one [fetchVisitorData] mints. See [visitorData].
        visitorData?.takeIf { it.isNotBlank() }?.let {
            this.visitorData = it
            visitorDataIsSessionBound = true
        }
        Log.d(TAG, "adopted page scope: pageId=${pageId ?: "none"} authUser=${authUser ?: "0"}")
    }

    /** The brand channel to send, chosen one first. */
    private fun pageIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.pageId).pageId

    /**
     * The account to send as `onBehalfOfUser`, chosen one first.
     *
     * No falling back to the shell's value once a channel has been chosen: the
     * shell's id names the default identity, and pairing it with another
     * channel's [pageId] describes an account/page combination that doesn't
     * exist.
     */
    private fun dataSyncIdFor(session: SessionScope?): String? =
        (channelOverride ?: return session?.dataSyncId).dataSyncId

    /** Which account in the cookie jar, chosen channel's first. */
    private fun authUserFor(session: SessionScope?): String =
        channelOverride?.authUser ?: session?.authUser ?: "0"

    /**
     * The WEB_REMIX version to claim, live if the shell has been read.
     *
     * Worth taking from the shell rather than pinning: the stats pings carry it
     * as `cver`, and a version Google has never shipped is a standing invitation
     * to be treated as something other than a music client.
     */
    private val webRemixVersion: String
        get() = scope?.clientVersion ?: WEB_REMIX_VERSION

    /**
     * Reads the session scope, once per cookie, before anything that depends on
     * being the right account.
     *
     * Cheap to be wrong about and expensive to skip, so it fails open: a shell
     * that cannot be fetched or parsed leaves [scope] null and every request
     * behaves exactly as it did before. What it must never do is invent a
     * [SessionScope.dataSyncId] — see that field.
     */
    suspend fun ensureSessionScope() {
        cookie ?: return
        // Before the first signed request after start or resume, and whenever
        // the last one is old: the short-lived sign-in cookies are rotated.
        this.session.ensureFresh()
        val session = cookie ?: return
        if (scopeIsCurrent()) return
        val generation = this.session.generation
        scopeLock.withLock {
            if (scopeIsCurrent() || this.session.generation != generation) return
            val previous = scope
            scopeCheckedAtMs = System.currentTimeMillis()
            scopeRecoveries = this.session.recoveries
            runCatching { fetchSessionScope(session) }
                .onFailure { Log.w(TAG, "could not read the session scope: ${it.message}") }
                .getOrNull()
                ?.let { fresh ->
                    // A login/profile switch can happen while the shell is in
                    // flight. Never install the old cookie's answer under the
                    // new one: that is a guaranteed 401 and, worse, can credit
                    // a play to the profile that just left.
                    if (this.session.generation != generation) {
                        Log.d(TAG, "discarding a session scope from an account that is no longer active")
                        return@let
                    }
                    scope = fresh
                    // A scope read again (a day on, or under a recovered jar)
                    // that names another identity: the PoToken binding and the
                    // resolver verdicts belonged to the old one. Not for a mere
                    // rotation, which changes no identity.
                    if (previous != null && previous.dataSyncId != null &&
                        dataSyncIdFor(previous) != dataSyncIdFor(fresh)
                    ) {
                        Log.d(TAG, "the session's identity changed on a re-read; resetting the stream session")
                        StreamResolver.onSessionChanged()
                    }
                    channelOverride?.let { selected ->
                        if (selected.pageId != fresh.pageId || selected.dataSyncId != fresh.dataSyncId) {
                            Log.w(TAG, "server shell identity differs from selected profile; retaining override")
                        }
                    }
                    Log.d(
                        TAG,
                        "session scope: authUser=${fresh.authUser} " +
                            "pageId=${fresh.pageId ?: "none"} " +
                            "dataSyncId=${if (fresh.dataSyncId != null) "present" else "none"} " +
                            "cver=${fresh.clientVersion ?: WEB_REMIX_VERSION}",
                    )
                }
        }
    }

    /**
     * Who a signed Video player request acts as, read the way [postWeb] reads it: the account scope and the channel the listener
     * chose, the session's visitor id and the live WEB client version.
     */
    class SignedScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val visitorData: String?,
        val webClientVersion: String,
        val language: String,
        val region: String,
    )

    /** Which channel the listener chose, as a value that changes with the choice; null while the shell's default stands. */
    val chosenChannelKey: String? get() = channelOverride?.let { it.pageId ?: it.dataSyncId }

    /** The current [SignedScope]; rotates the jar first when it is due, as any signed request does. */
    suspend fun signedScope(): SignedScope {
        if (cookie != null) ensureSessionScope()
        ensureVisitorData()
        ensureWebClientVersion()
        val session = scope
        return SignedScope(
            dataSyncId = dataSyncIdFor(session),
            pageId = pageIdFor(session),
            authUser = authUserFor(session),
            visitorData = visitorData,
            webClientVersion = webClientVersion,
            language = currentLanguage,
            region = currentRegion,
        )
    }

    /** Re-read request context once after an authenticated rejection. */
    suspend fun refreshSessionScope() {
        if (cookie == null) return
        scopeLock.withLock { scope = null }
        ensureSessionScope()
    }

    /**
     * The music.youtube.com shell, fetched with the session, for its `ytcfg`.
     *
     * Read by regex rather than by evaluating the config blob: it is one script
     * assignment among hundreds of kilobytes of app JavaScript, and the four
     * values wanted are flat strings in it. A key that moves reads as absent,
     * which is the same as not having asked.
     */
    private suspend fun fetchSessionScope(session: String): SessionScope? {
        val html = client.get("$MUSIC_ORIGIN/") {
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", acceptLanguageHeader)
            header("Cookie", session)
            SapisidAuth.authorization(session, MUSIC_ORIGIN)?.let { header("Authorization", it) }
        }.keepRotatedCookies(session).bodyAsText()

        // The one value that must not be guessed. A shell that says it is
        // signed out either has a dead cookie or was served to nobody in
        // particular; either way its DATASYNC_ID belongs to no account, and
        // sending it would 401 every request in the app.
        val loggedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1)
        val signedIn = loggedIn == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)
        // Only an explicit answer counts: a consent or error page says neither.
        if (loggedIn == "false") this.session.reportLoggedOut(session, "music.youtube.com is signed out")
        if (signedIn) this.session.reportLoggedIn(session)
        if (!signedIn) {
            Log.w(TAG, "music.youtube.com served a signed-out shell; not scoping requests")
            // Still worth the client version — that part is true either way.
            return clientVersion?.let { SessionScope(null, null, "0", it) }
        }

        val pageId = CONFIG_PAGE_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val dataSyncId = pageId ?: normalizeDataSyncId(
            CONFIG_DATASYNC_ID.find(html)?.groupValues?.get(1),
        )
        val authUser = CONFIG_SESSION_INDEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        // The shell's own visitor id, which is bound to this session. Strictly
        // better than the anonymous one [fetchVisitorData] mints: the stats
        // pings are attributed against the visitor the player response was
        // issued to, so a signed-in play reported under an anonymous id is a
        // play reported about nobody.
        CONFIG_VISITOR_DATA.find(html)?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let {
                visitorData = it
                visitorDataIsSessionBound = true
            }

        return SessionScope(dataSyncId, pageId, authUser ?: "0", clientVersion)
    }

    private val CONFIG_LOGGED_IN = Regex(""""LOGGED_IN"\s*:\s*(true|false)""")
    private val CONFIG_DATASYNC_ID = Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_PAGE_ID = Regex(""""DELEGATED_SESSION_ID"\s*:\s*"([^"]+)"""")
    private val CONFIG_SESSION_INDEX = Regex(""""SESSION_INDEX"\s*:\s*"?(\d+)""")
    private val CONFIG_VISITOR_DATA = Regex(""""VISITOR_DATA"\s*:\s*"([^"]+)"""")
    private val CONFIG_CLIENT_VERSION = Regex(""""INNERTUBE_CLIENT_VERSION"\s*:\s*"([^"]+)"""")

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(OkHttp) {
        // Same OkHttp instance ExoPlayer streams through — see Http.
        engine { preconfigured = com.cosmotune.music.data.Http.client }
        install(ContentNegotiation) { json(json) }
        // Without this the only bound is OkHttp's own read timeout, and the
        // failure it raises reads as "Socket timeout has expired […]
        // socket_timeout=unknown" — Ktor reporting a limit it was never told.
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 20_000
        }
        expectSuccess = true
    }

    /**
     * Runs [block], giving transport failures another go before letting them
     * reach the caller.
     *
     * A connection reset on mobile data is weather, not information: the
     * request was fine and asking again generally answers. That matters most
     * on a shared connection pool, where a socket torn down under one request
     * — an abandoned search, a network handover — surfaces as
     * "Software caused connection abort" on whichever request picked that
     * connection up next, which had nothing to do with it.
     *
     * Only transport failures. An HTTP error status is an answer, and
     * repeating the question won't change it. Cancellation isn't caught at
     * all: [delay] throws when the coroutine is cancelled, so a search the
     * user has typed past stops here instead of retrying on behalf of a query
     * nobody is waiting for.
     */
    private suspend fun <T> withRetry(attempts: Int = 3, block: suspend () -> T): T {
        var backoff = 500L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: HttpRequestTimeoutException) {
                // Not weather, and not worth repeating. A timeout is this app's
                // own decision that the request had long enough — so trying it
                // again cannot learn anything the first attempt didn't, and the
                // cost is multiplied rather than shared: [HttpRequestTimeoutException]
                // is an [IOException], so before this branch existed every timed-out
                // `player` call was quietly attempted three times. That turned a
                // six-second ceiling into a nineteen-second one on a walk of
                // seven clients, which is worse than the unbounded call the
                // ceiling was added to prevent. Give up on this client and let
                // the caller move to the next one.
                Log.d(TAG, "not retrying, request timed out: ${e.message}")
                throw e
            } catch (e: IOException) {
                Log.d(TAG, "retrying: ${e.message}")
            }
            delay(backoff)
            backoff *= 2
        }
        return block()
    }

    // ---- Public API ---------------------------------------------------------

    /** Music charts have an explicit country form; ZZ means global, independent of app UI locale. */
    suspend fun discoveryCharts(country: String): JsonObject {
        require(country == "ZZ" || country in java.util.Locale.getISOCountries())
        return postMusic("browse") {
            put("browseId", "FEmusic_charts")
            putJsonObject("formData") { putJsonArray("selectedValues") { add(country) } }
        }
    }

    suspend fun browse(browseId: String, params: String? = null): JsonObject =
        postMusic("browse") {
            put("browseId", browseId)
            params?.let { put("params", it) }
        }

    /** Public podcast discovery never imports the listener's account cookies or adds account history. */
    suspend fun browseAnonymous(browseId: String? = null, continuation: String? = null): JsonObject =
        postMusicAnonymous("browse") {
            require((browseId == null) != (continuation == null))
            browseId?.let { put("browseId", it) }
            continuation?.let { put("continuation", it) }
        }

    /**
     * The next page of a paged browse response — playlists and library feeds
     * come back roughly 100 rows at a time. YouTube Music takes the token as
     * query parameters rather than in the body, and answers with a bare
     * continuation envelope carrying the same row renderers.
     */
    suspend fun browseContinuation(token: String): JsonObject = postMusic(
        endpoint = "browse",
        // The web client passes the token in the body and the older query-string
        // form is still honoured; both are sent so either is enough.
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    /** Signed-in profile: display name, email/handle and avatar. */
    suspend fun accountMenu(): JsonObject = postMusic("account/account_menu") {}

    /**
     * Every channel this session can act as, as Innertube's account switcher
     * lists them: the account's own channel first, then its brand channels.
     *
     * Each entry carries the two things a request needs to be made *as* that
     * channel — a `pageIdToken` and a `datasyncIdToken` — which is the whole
     * reason to ask rather than to reason about it. See [selectChannel].
     */
    suspend fun accountsList(): JsonObject = postMusic("account/accounts_list") {}

    /**
     * The same list from youtube.com's own switcher, as a second route.
     *
     * Worth having both. `accounts_list` is the tidier call but it is not
     * uniformly answered for every client identity, and a listener whose music
     * is on a brand channel is stuck with the wrong library until *something*
     * enumerates their channels. This endpoint is what the youtube.com avatar
     * menu itself calls, and it answers a plain signed GET.
     *
     * The body is JSON behind Google's XSSI guard — a `)]}'` line that exists
     * to make the response invalid JavaScript — so it is trimmed before parsing
     * rather than being handed to the JSON reader as-is.
     */
    suspend fun accountSwitcher(): JsonObject {
        requireSession()
        ensureSessionScope()
        val session = scope
        val text = withRetry {
            signed { sent ->
                client.get("$YOUTUBE_ORIGIN/getAccountSwitcherEndpoint") {
                    header("User-Agent", WEB_USER_AGENT)
                    header("Accept-Language", acceptLanguageHeader)
                    header("X-Origin", YOUTUBE_ORIGIN)
                    header("Referer", "$YOUTUBE_ORIGIN/")
                    sent?.let { c ->
                        header("Cookie", c)
                        header("X-Goog-AuthUser", authUserFor(session))
                        SapisidAuth.authorization(c, YOUTUBE_ORIGIN)?.let { header("Authorization", it) }
                    }
                }.keepRotatedCookies(sent).bodyAsText()
            }
        }
        val body = text.substringAfter(")]}'", text).trim()
        return json.parseToJsonElement(body).jsonObject
    }

    /**
     * The watch queue that YouTube Music would play after [videoId] — the
     * "RDAMVM" radio mix. Used to keep AutoPlay going past the last track.
     */
    suspend fun next(videoId: String): JsonObject = postMusic("next") {
        put("videoId", videoId)
        put("playlistId", "RDAMVM$videoId")
        put("isAudioOnly", true)
    }

    /** Timed caption transcript used as a last-resort lyrics source. */
    suspend fun transcript(videoId: String): JsonObject = postMusic("get_transcript") {
        // get_transcript expects a tiny protobuf: field 1, length, video id.
        val bytes = byteArrayOf(10, videoId.toByteArray().size.toByte()) + videoId.toByteArray()
        put("params", Base64.getEncoder().encodeToString(bytes))
    }

    suspend fun search(query: String, params: String? = null, anonymous: Boolean = true): JsonObject =
        if (anonymous) {
            postMusicAnonymous("search") {
                put("query", query)
                params?.let { put("params", it) }
            }
        } else {
            postMusic("search") {
                put("query", query)
                params?.let { put("params", it) }
            }
        }

    /** The next page of a filtered search result. */
    suspend fun searchContinuation(token: String): JsonObject = postMusic(
        endpoint = "search",
        query = mapOf("ctoken" to token, "continuation" to token, "type" to "next"),
    ) {
        put("continuation", token)
    }

    /**
     * The typeahead list YouTube Music's own search box shows for a
     * half-typed query — query strings, not results.
     *
     * A different endpoint from [search] rather than a cheap mode of it, and
     * far cheaper than one: the response is a few hundred bytes of text with
     * no shelves, thumbnails or playback endpoints in it, which is what makes
     * it affordable per keystroke where a search is not.
     */
    suspend fun searchSuggestions(input: String): JsonObject =
        postMusic("music/get_search_suggestions") {
            put("input", input)
        }

    /**
     * Live media results for the typeahead phase — same shape as [search] but
     * deliberately unauthenticated so YouTube Music does not log each debounced
     * keystroke to the account's server-side search history.
     *
     * The regular [search] endpoint records every call against the signed-in
     * account, which turns a slow typist's intermediate queries ("P", "Pe",
     * "Perf…") into polluting history entries.  By omitting the session cookie
     * here we still get full search results (tracks, artists, albums) but they
     * land as anonymous lookups that don't touch the user's account history.
     */
    suspend fun searchTypeahead(query: String): JsonObject =
        postMusicAnonymous("search") {
            put("query", query)
        }

    /** The stats endpoints a player response nominates for one playback. */
    data class PlaybackTracking(
        val playbackUrl: String,
        val watchtimeUrl: String?,
        /** The ad-tracking ping real clients fire a few seconds in. */
        val atrUrl: String?,
        /** How far in [atrUrl] is due, per the response's own schedule. */
        val atrAfterSeconds: Long,
    )

    /**
     * Player response fetched *with* the session cookie, purely to read back
     * `playbackTracking` — [player] deliberately skips auth so its device
     * clients are answered at all, so it never sees this block. Null for
     * guests: there's no account history to update.
     *
     * [signatureTimestamp] is not optional in practice, and that is the bug
     * this whole file was reported for.
     *
     * WEB_REMIX is a browser identity, and a browser proves it is running
     * YouTube's current player by quoting that player's timestamp. Without one
     * — or with a stale one — Google does not refuse the request in any way a
     * caller would notice: it answers HTTP 200, `playabilityStatus` `UNPLAYABLE`,
     * reason "Video unavailable", subreason "The page needs to be reloaded",
     * and simply omits `playbackTracking` entirely. So every play registration
     * this app made returned null here, logged one line, and stopped. No ping
     * was ever sent; no history was ever written. Nothing failed loudly enough
     * to notice, which is why it read as working.
     *
     * It is the *only* gate. Verified against the live endpoint: with a current
     * timestamp and nothing else — no visitor id, no referer, no
     * `html5Preference` — the block comes back. With every one of those and a
     * timestamp one revision old, it does not.
     */
    suspend fun playbackTracking(videoId: String, signatureTimestamp: Int?): PlaybackTracking? {
        if (cookie == null) return null
        ensureSessionScope()
        val response = postMusic("player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            // Real clients always describe where playback is happening; the
            // response's tracking block is scoped to it.
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    put("referer", "$MUSIC_ORIGIN/watch?v=$videoId")
                    signatureTimestamp?.let { put("signatureTimestamp", it) }
                }
            }
        }
        val tracking = response["playbackTracking"]?.jsonObject
        if (tracking == null) {
            val playability = response["playabilityStatus"]?.jsonObject
            Log.w(
                TAG,
                "player response has no playbackTracking for $videoId " +
                    "(status=${playability?.get("status")?.jsonPrimitive?.content}, " +
                    "reason=${playability?.get("reason")?.jsonPrimitive?.content}, " +
                    "sts=${signatureTimestamp ?: "none"})",
            )
            return null
        }
        val playbackUrl = tracking.trackingUrl("videostatsPlaybackUrl") ?: return null
        return PlaybackTracking(
            playbackUrl = playbackUrl,
            watchtimeUrl = tracking.trackingUrl("videostatsWatchtimeUrl"),
            atrUrl = tracking.trackingUrl("atrUrl"),
            atrAfterSeconds = tracking["atrUrl"]?.jsonObject
                ?.get("elapsedMediaTimeSeconds")?.jsonPrimitive?.contentOrNull
                ?.toLongOrNull() ?: DEFAULT_ATR_SECONDS,
        )
    }

    /** What YouTube Music itself schedules `atr` for, when it doesn't say. */
    private const val DEFAULT_ATR_SECONDS = 5L

    private fun JsonObject.trackingUrl(key: String): String? =
        this[key]?.jsonObject?.get("baseUrl")?.jsonPrimitive?.content

    /**
     * The "playback started" ping real YouTube Music clients send once a track
     * becomes audible. This is what creates the history entry the home feed
     * feeds off. [cpn] is the client-playback-nonce identifying this one play:
     * it must be the same value used for every [pingWatchtime] that follows.
     *
     * No `el` here. The base URL already carries `el=detailpage` — Google puts
     * it there — and a repeated query parameter is not a stronger statement of
     * the same thing, it is an ambiguous request whose resolution is Google's
     * to decide.
     */
    suspend fun pingPlayback(baseUrl: String, cpn: String) = pingStats(baseUrl, cpn) {}

    /**
     * The follow-up ping reporting how much of the track was actually heard.
     * A history entry with no watchtime behind it reads as a skip, so it
     * carries little weight in recommendations — [seconds] is what makes the
     * play count. `st`/`et` are the watched segment's bounds, in seconds.
     *
     * @param final whether this is the last report for the play, which is what
     *   lets Google close the play out rather than leave it looking abandoned.
     */
    suspend fun pingWatchtime(baseUrl: String, cpn: String, seconds: Long, final: Boolean = false) =
        pingStats(baseUrl, cpn) {
            parameter("st", "0")
            parameter("et", seconds.toString())
            // Where the playhead is, as distinct from how much was watched.
            // The web client sends both and they are not redundant: `et` bounds
            // a segment, `cmt` is a position.
            parameter("cmt", seconds.toString())
            parameter("state", if (final) "paused" else "playing")
            if (final) parameter("final", "1")
        }

    /**
     * The `atr` ping, fired a few seconds into a play.
     *
     * Not analytics garnish. It is the third leg of the sequence a real client
     * performs — playback, atr, watchtime — and the one that distinguishes a
     * play that started from a play that happened. Its base URL already carries
     * `ver`, `c` and `cver`, so unlike the others it is sent as-is.
     */
    suspend fun pingAtr(baseUrl: String, cpn: String): Int {
        // Playback can outlive the Activity that restored the session. Ensure
        // the selected profile's headers exist before this first history ping.
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
            parameter("cpn", cpn)
            statsHeaders(session)
        }.status.value
    }

    /** Shared shape of the s.youtube.com stats pings, including session auth. */
    private suspend fun pingStats(
        baseUrl: String,
        cpn: String,
        extras: HttpRequestBuilder.() -> Unit,
    ): Int {
        if (cookie != null) ensureSessionScope()
        val session = scope
        return client.get(baseUrl) {
            parameter("ver", "2")
            parameter("c", "WEB_REMIX")
            parameter("cver", webRemixVersion)
            parameter("cpn", cpn)
            // What the web client says about itself. Cheap, and the pings are
            // weighted by how much they look like a real session.
            parameter("cplayer", "UNIPLAYER")
            parameter("cbr", "Chrome")
            parameter("cbrver", "141.0.0.0")
            parameter("cos", "Windows")
            parameter("cosver", "10.0")
            parameter("hl", currentLanguage.replace('-', '_'))
            parameter("cr", currentRegion)
            extras()
            statsHeaders(session)
        }.status.value
    }

    /**
     * The three headers the tracking block asks for by name — `USER_AUTH`,
     * `VISITOR_ID` and `PLUS_PAGE_ID`. Google lists them per ping URL in the
     * player response; sending fewer is what makes a ping land somewhere other
     * than the listener's own history.
     */
    private fun HttpRequestBuilder.statsHeaders(session: SessionScope?) {
        header("X-Origin", MUSIC_ORIGIN)
        header("Origin", MUSIC_ORIGIN)
        header("Referer", "$MUSIC_ORIGIN/")
        header("User-Agent", WEB_USER_AGENT)
        visitorData?.let { header("X-Goog-Visitor-Id", it) }
        cookie?.let { c ->
            header("Cookie", c)
            header("X-Goog-AuthUser", authUserFor(session))
            pageIdFor(session)?.let { header("X-Goog-PageId", it) }
            SapisidAuth.authorization(c, MUSIC_ORIGIN)?.let { header("Authorization", it) }
        }
    }

    // ---- Writes -------------------------------------------------------------
    //
    // Everything below changes something on the account, so all of it needs
    // the session cookie [postMusic] already signs with. None of it needs a
    // new credential or a different client — the same WEB_REMIX identity that
    // reads the library is the one allowed to edit it.

    /** A write attempted without a session; the caller has a sign-in prompt to show. */
    class NotSignedInException : IllegalStateException("Sign in to YouTube Music to do that")

    private fun requireSession() {
        if (cookie == null) throw NotSignedInException()
    }

    /**
     * Thumbs up / down / neither, for [videoId].
     *
     * The response is inspected rather than discarded. Innertube answers a
     * refused write with HTTP 200 and an `error` object in the body, so the
     * status line alone will happily report a rating that never happened.
     */
    suspend fun rate(videoId: String, status: LikeStatus) {
        requireSession()
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("videoId", videoId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the rating: ${message ?: error}")
        }
        // YouTube states what it did in the toast it would have shown. Worth
        // keeping: a rating it declines to act on still answers 200, and this
        // one line is the difference between "the call was made" and "the
        // call did something".
        Log.d(TAG, "$endpoint $videoId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Saves an album or playlist to the library, or takes it back out.
     *
     * The same endpoints [rate] uses, aimed at a playlist instead of a video:
     * YouTube has no separate "save" verb for a release — a saved album *is* a
     * liked one, which is why the Library tab's Albums and Playlists shelves and
     * the account's likes are the same list. [playlistId] is the id the page
     * itself named, not its browse id; see
     * [com.cosmotune.music.data.model.LibraryState].
     *
     * No dislike half, unlike [rate]: nothing in YouTube Music reads a disliked
     * release, so the only two states worth expressing are saved and not.
     */
    suspend fun ratePlaylist(playlistId: String, saved: Boolean) {
        requireSession()
        val endpoint = if (saved) "like/like" else "like/removelike"
        val response = postMusic(endpoint) {
            putJsonObject("target") { put("playlistId", playlistId) }
        }
        // As in [rate]: a refusal arrives as HTTP 200 with an error in the body.
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $playlistId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Subscribes to an artist's channel, or unsubscribes from it.
     *
     * Not one of the `like/…` endpoints: a subscription is a YouTube-wide
     * relationship rather than a Music one, and it is addressed by channel id —
     * the `UC…` the artist page is served under. See
     * [com.cosmotune.music.data.model.SubscriptionState].
     *
     * As in [rate], the body is read rather than the status line: Innertube
     * answers a refused write with HTTP 200 and an `error` object.
     */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        requireSession()
        val endpoint = if (subscribed) "subscription/subscribe" else "subscription/unsubscribe"
        val response = postMusic(endpoint) {
            putJsonArray("channelIds") { add(channelId) }
        }
        response["error"]?.let { error ->
            val message = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
            error("YouTube Music refused the change: ${message ?: error}")
        }
        Log.d(TAG, "$endpoint $channelId -> ${findString(response, "text") ?: "no confirmation"}")
    }

    /**
     * Adds or removes a track from the library, using a token minted by
     * YouTube for exactly that transition — see [com.cosmotune.music.data.model.SongMenu].
     * There is no video-id form of this call; the token *is* the request.
     */
    suspend fun sendFeedback(token: String) {
        requireSession()
        postMusic("feedback") {
            putJsonArray("feedbackTokens") { add(token) }
        }
    }

    /**
     * Creates a playlist and returns its id.
     *
     * [videoIds] seeds it in the same request, which is what "add to a new
     * playlist" is: one round trip rather than a create followed by an edit
     * that could half-succeed.
     */
    suspend fun createPlaylist(
        title: String,
        privacy: PlaylistPrivacy,
        description: String? = null,
        videoIds: List<String> = emptyList(),
    ): String {
        requireSession()
        val response = postMusic("playlist/create") {
            put("title", title)
            put("description", description.orEmpty())
            put("privacyStatus", privacy.apiValue)
            if (videoIds.isNotEmpty()) {
                putJsonArray("videoIds") { videoIds.forEach { add(it) } }
            }
        }
        // Normally a bare top-level id; occasionally only inside the command
        // that would navigate the web client to the new page, so fall back to
        // finding it by name rather than by a path that would rot.
        return response["playlistId"]?.jsonPrimitive?.contentOrNull
            ?: findString(response, "playlistId")
            ?: error("playlist created but no id came back")
    }

    suspend fun deletePlaylist(playlistId: String) {
        requireSession()
        postMusic("playlist/delete") { put("playlistId", playlistId.removePrefix("VL")) }
    }

    /**
     * One or more edits to a playlist, applied together.
     *
     * The endpoint answers `STATUS_SUCCEEDED` rather than an HTTP error when
     * it refuses — a playlist the account merely saved rather than owns is
     * the usual reason — so the body is checked as well as the status line.
     */
    private suspend fun editPlaylist(
        playlistId: String,
        actions: JsonArrayBuilder.() -> Unit,
    ): JsonObject {
        requireSession()
        val response = postMusic("browse/edit_playlist") {
            // The edit endpoint takes the raw id; `VL` is the browse prefix.
            put("playlistId", playlistId.removePrefix("VL"))
            putJsonArray("actions", actions)
        }
        val status = response["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status != "STATUS_SUCCEEDED") {
            error("YouTube Music refused the edit ($status)")
        }
        return response
    }

    /**
     * Adds tracks to a playlist, and reports the per-entry id each one landed
     * under — video id to set-video-id, for the tracks the response named.
     *
     * Worth reading rather than discarding, because it is the only chance to
     * learn it without re-fetching the whole playlist: a set-video-id is minted
     * by this call, and it is what a later removal has to be expressed in (see
     * [removeFromPlaylist]). A row added to a playlist already on screen is
     * otherwise one the user can see but not take back out until the page is
     * reopened.
     *
     * Absences are normal and not an error — the add still happened; only the
     * id for undoing it is unknown.
     */
    suspend fun addToPlaylist(playlistId: String, videoIds: List<String>): Map<String, String> {
        val response = editPlaylist(playlistId) {
            videoIds.forEach { videoId ->
                addJsonObject {
                    put("action", "ACTION_ADD_VIDEO")
                    put("addedVideoId", videoId)
                }
            }
        }
        return (response["playlistEditResults"] as? JsonArray)
            .orEmpty()
            .mapNotNull { result ->
                val added = (result as? JsonObject)
                    ?.get("playlistEditVideoAddedResultData") as? JsonObject
                    ?: return@mapNotNull null
                val videoId = (added["videoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                val setVideoId = (added["setVideoId"] as? JsonPrimitive)?.contentOrNull
                    ?: return@mapNotNull null
                videoId to setVideoId
            }
            .toMap()
    }

    /**
     * Removes entries from a playlist. Keyed by set-video-id as well as video
     * id: the same track added twice is two entries, and only the pair says
     * which of them to drop.
     */
    suspend fun removeFromPlaylist(playlistId: String, entries: List<Pair<String, String>>) {
        editPlaylist(playlistId) {
            entries.forEach { (setVideoId, videoId) ->
                addJsonObject {
                    put("action", "ACTION_REMOVE_VIDEO")
                    put("setVideoId", setVideoId)
                    put("removedVideoId", videoId)
                }
            }
        }
    }

    /**
     * Rearranges a playlist, one [PlaylistMove] after another in the order
     * given — see [com.cosmotune.music.data.model.playlistMoves] for building
     * them. A move with no successor goes to the end.
     */
    suspend fun movePlaylistItems(playlistId: String, moves: List<PlaylistMove>) {
        if (moves.isEmpty()) return
        editPlaylist(playlistId) {
            moves.forEach { move ->
                addJsonObject {
                    put("action", "ACTION_MOVE_VIDEO_BEFORE")
                    put("setVideoId", move.setVideoId)
                    move.before?.let { put("movedSetVideoIdSuccessor", it) }
                }
            }
        }
    }

    suspend fun renamePlaylist(playlistId: String, title: String) {
        editPlaylist(playlistId) {
            addJsonObject {
                put("action", "ACTION_SET_PLAYLIST_NAME")
                put("playlistName", title)
            }
        }
    }

    /** A fresh client-playback-nonce, identifying one play of one track. */
    fun newCpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

    private const val CPN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"

    // ---- Request plumbing ---------------------------------------------------

    private suspend fun postMusic(
        endpoint: String,
        query: Map<String, String> = emptyMap(),
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        // This is the common authenticated request path: Home, Library,
        // likes, playlists, account menus and all their continuations pass
        // through it. Waiting here makes restoration process-wide rather than
        // a special case implemented by whichever screen happened to open.
        if (cookie != null) ensureSessionScope()
        val session = scope
        val clientVersion = webRemixVersion
        val response = withRetry {
          signed { sent ->
            client.post("$MUSIC_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                parameter("hl", currentLanguage)
                header("Accept-Language", acceptLanguageHeader)
                query.forEach { (key, value) -> parameter(key, value) }
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                // Stats pings are only honoured for a session Google recognises
                // as a real client, so identify as one here too — the visitor
                // id is minted on the first call and reused for the session.
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", clientVersion)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                sent?.let { c ->
                    header("Cookie", c)
                    // Which account in the jar, and which brand channel of it.
                    // Both were fixed at "the first one" before — see
                    // [SessionScope].
                    header("X-Goog-AuthUser", authUserFor(session))
                    pageIdFor(session)?.let { header("X-Goog-PageId", it) }
                    SapisidAuth.authorization(c, MUSIC_ORIGIN)?.let { header("Authorization", it) }
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                put("gl", currentRegion)
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                // Only ever a value read back from a shell that
                                // said it was signed in: Google answers an
                                // `onBehalfOfUser` it cannot tie to the cookie
                                // with 401, so a guess here would take the
                                // whole app down rather than just history.
                                dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.keepRotatedCookies(sent).body<JsonObject>()
          }
        }
        noteSignedAnswer(cookie, response)

        if (visitorData == null) {
            visitorData = response["responseContext"]?.jsonObject
                ?.get("visitorData")?.jsonPrimitive?.content
        }
        return response
    }

    // ---- The YouTube site, for Video mode ------------------------------------

    private const val WEB_BASE = "https://www.youtube.com/youtubei/v1"
    private const val WEB_CLIENT_ID = "1"

    /**
     * The WEB client version claimed for the YouTube site. Google keeps answering old ones for months, so a pinned value
     * is a starting point only; [webClientVersion] can be set by a host that has read a newer one.
     */
    private const val WEB_CLIENT_VERSION = "2.20251001.01.00"

    @Volatile
    var webClientVersion: String = WEB_CLIENT_VERSION

    /**
     * The `Authorization` header value the YouTube site (not Music) expects for [cookie]: SAPISIDHASH over
     * `https://www.youtube.com`, or null when the jar holds no signing secret. [nowSeconds] is a parameter so a test can pin the clock.
     */
    fun webAuthorization(cookie: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String? =
        SapisidAuth.authorization(cookie, YOUTUBE_ORIGIN, nowSeconds)

    /** When [ensureWebClientVersion] last read the site's page (0: not since the session changed). */
    @Volatile
    private var webVersionCheckedAtMs = 0L

    private val webVersionLock = Mutex()

    /**
     * Reads the live WEB client version (`INNERTUBE_CLIENT_VERSION`) out of the
     * www.youtube.com page, with the session when there is one, at the first web
     * call and again every [WEB_VERSION_TTL_MS] or after a sign-in; until then,
     * and whenever the page cannot be read, the pinned [WEB_CLIENT_VERSION]
     * stands. A signed request to the page also says whether the session is
     * signed in at all, which is reported to [YouTubeSession].
     */
    suspend fun ensureWebClientVersion() {
        val now = System.currentTimeMillis()
        if (webVersionCheckedAtMs != 0L && now - webVersionCheckedAtMs < WEB_VERSION_TTL_MS) return
        webVersionLock.withLock {
            if (webVersionCheckedAtMs != 0L && System.currentTimeMillis() - webVersionCheckedAtMs < WEB_VERSION_TTL_MS) return
            webVersionCheckedAtMs = System.currentTimeMillis()
            val sent = cookie
            val config = runCatching {
                val html = client.get("$YOUTUBE_ORIGIN/") {
                    header("User-Agent", WEB_USER_AGENT)
                    header("Accept-Language", acceptLanguageHeader)
                    sent?.let { c ->
                        header("Cookie", c)
                        SapisidAuth.authorization(c, YOUTUBE_ORIGIN)?.let { header("Authorization", it) }
                    }
                }.keepRotatedCookies(sent).bodyAsText()
                YouTubePageConfig.parse(html)
            }.onFailure { Log.w(TAG, "could not read the web client version: ${it.message}") }.getOrNull() ?: return
            config.clientVersion?.takeIf(YouTubePageConfig::isWebVersion)?.let {
                if (it != webClientVersion) Log.d(TAG, "web client version $it (was $webClientVersion)")
                webClientVersion = it
            }
            if (sent != null) {
                when (config.loggedIn) {
                    false -> session.reportLoggedOut(sent, "www.youtube.com is signed out")
                    true -> session.reportLoggedIn(sent)
                    null -> Unit
                }
            }
        }
    }

    private const val WEB_VERSION_TTL_MS = 6 * 60 * 60 * 1000L

    /**
     * One youtubei call as the YouTube site's own web client (www.youtube.com, WEB), for Video mode: the signed-in feeds, the
     * playlists and the account's writes. It is signed with the very session [postMusic] uses (same cookie, same account and brand
     * channel from [SessionScope]); only the origin the hash is taken over and the client identity differ. With no session it
     * is an anonymous request. Failures surface as Ktor exceptions (HTTP status) or [IOException], as for the other calls.
     */
    suspend fun postWeb(
        endpoint: String,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        if (cookie != null) ensureSessionScope()
        ensureVisitorData()
        ensureWebClientVersion()
        val session = scope
        val version = webClientVersion
        val response = withRetry {
          signed { sent ->
            client.post("$WEB_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                parameter("hl", currentLanguage)
                header("User-Agent", WEB_USER_AGENT)
                header("Accept-Language", acceptLanguageHeader)
                header("X-Origin", YOUTUBE_ORIGIN)
                header("Origin", YOUTUBE_ORIGIN)
                header("Referer", "$YOUTUBE_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_CLIENT_ID)
                header("X-YouTube-Client-Version", version)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                sent?.let { c ->
                    header("Cookie", c)
                    header("X-Goog-AuthUser", authUserFor(session))
                    pageIdFor(session)?.let { header("X-Goog-PageId", it) }
                    webAuthorization(c)?.let { header("Authorization", it) }
                }
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB")
                                put("clientVersion", version)
                                put("hl", currentLanguage)
                                put("gl", currentRegion)
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                if (sent != null) dataSyncIdFor(session)?.let { put("onBehalfOfUser", it) }
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.keepRotatedCookies(sent).body<JsonObject>()
          }
        }
        noteSignedAnswer(cookie, response)
        return response
    }

    /**
     * Like [postMusic] but deliberately strips the session cookie so YouTube
     * Music does not record the call against any account.
     *
     * Used for typeahead lookups where intermediate keystrokes must remain
     * anonymous — see [searchTypeahead].
     */
    private suspend fun postMusicAnonymous(
        endpoint: String,
        bodyExtras: JsonObjectBuilder.() -> Unit,
    ): JsonObject {
        val clientVersion = webRemixVersion
        return withRetry {
            client.post("$MUSIC_BASE/$endpoint") {
                contentType(ContentType.Application.Json)
                parameter("prettyPrint", "false")
                header("X-Origin", MUSIC_ORIGIN)
                header("Origin", MUSIC_ORIGIN)
                header("Referer", "$MUSIC_ORIGIN/")
                header("X-YouTube-Client-Name", WEB_REMIX_CLIENT_ID)
                header("X-YouTube-Client-Version", clientVersion)
                header("Accept-Language", acceptLanguageHeader)
                parameter("hl", currentLanguage)
                visitorData?.let { header("X-Goog-Visitor-Id", it) }
                // No Cookie / Authorization headers — anonymous request.
                setBody(
                    buildJsonObject {
                        putJsonObject("context") {
                            putJsonObject("client") {
                                put("clientName", "WEB_REMIX")
                                put("clientVersion", clientVersion)
                                put("hl", currentLanguage)
                                put("gl", currentRegion)
                                visitorData?.let { put("visitorData", it) }
                            }
                            putJsonObject("user") {
                                put("lockedSafetyMode", false)
                                // No onBehalfOfUser — no account context.
                            }
                            putJsonObject("request") { put("useSsl", true) }
                        }
                        bodyExtras()
                    },
                )
            }.body<JsonObject>()
        }
    }

    /** First string value under [key] anywhere in [element], depth-first. */
    private fun findString(element: JsonElement, key: String): String? = when (element) {
        is JsonObject -> (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }

    // ---- The shared session: rotation and expiry ----------------------------

    /**
     * Hands this response's `Set-Cookie` values to [YouTubeSession], when the
     * request was signed with [sent]: a rotated `*PSIDTS`/`*SIDCC` is kept for
     * every later request of either mode, and for the next launch.
     */
    private fun HttpResponse.keepRotatedCookies(sent: String?): HttpResponse {
        if (sent != null) {
            val values = headers.getAll(HttpHeaders.SetCookie).orEmpty()
            if (values.isNotEmpty()) session.absorbSetCookies(sent, values)
        }
        return this
    }

    /**
     * Runs one request with the cookie in force (null: anonymous), telling
     * [YouTubeSession] how it fared: a 401/403 is a strike, and starts the
     * silent refresh; when that rotated the jar, the request is made once more
     * with it. Anonymous requests report nothing.
     */
    private suspend fun <T> signed(block: suspend (sent: String?) -> T): T {
        val sent = cookie
        try {
            return block(sent)
        } catch (e: ResponseException) {
            val status = e.response.status.value
            if (sent == null || (status != 401 && status != 403)) throw e
            session.reportRejected(sent, status)
            // The silent refresh: rotate (and check, and recover) the sign-in,
            // then try this request once more if the jar moved.
            if (session.refreshAfterRejection(sent) != YouTubeSession.Refresh.ROTATED) throw e
            val again = cookie
            if (again == null || again == sent || !YouTubeCookieJar.sameLogin(again, sent)) throw e
            Log.d(TAG, "retrying once with the renewed sign-in (was HTTP $status)")
            try {
                return block(again)
            } catch (retry: ResponseException) {
                session.reportRejected(again, retry.response.status.value)
                throw retry
            }
        }
    }

    /**
     * A signed answer that says it was answered as nobody (`loggedOut`) is a
     * strike; any other is the session working, which clears strikes.
     */
    private fun noteSignedAnswer(sent: String?, response: JsonObject) {
        if (sent == null) return
        val context = response["responseContext"] as? JsonObject
        val loggedOut = (context?.get("mainAppWebResponseContext") as? JsonObject)
            ?.let { (it["loggedOut"] as? JsonPrimitive)?.contentOrNull } == "true"
        // The tracking block's own word for it: `logged_in` "0".
        val trackedOut = (context?.get("serviceTrackingParams") as? JsonArray).orEmpty().any { service ->
            ((service as? JsonObject)?.get("params") as? JsonArray).orEmpty().any { param ->
                val p = param as? JsonObject
                p != null && (p["key"] as? JsonPrimitive)?.contentOrNull == "logged_in" && (p["value"] as? JsonPrimitive)?.contentOrNull == "0"
            }
        }
        if (loggedOut || trackedOut) session.reportAnsweredSignedOut(sent) else session.reportAccepted(sent)
    }
}
