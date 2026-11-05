package com.cosmotune.youtube.account

import com.cosmotune.music.data.DebugLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one Google session of the app, shared by YouTube Music (WEB_REMIX, music.youtube.com) and Video (WEB, www.youtube.com).
 *
 * It owns what both clients sign with and nothing else:
 * - the cookie jar ([YouTubeCookieJar]), installed by the platform at sign-in, switch and launch ([install], and [installGoogle]
 *   for the accounts.google.com jar when the platform captured one) and kept current from the `Set-Cookie` answers of either client
 *   ([absorbSetCookies]); a changed jar is handed to the platform's [vault], at most once per [persistIntervalMs];
 * - the one visitor id both clients send ([visitorData]);
 * - the per-origin `Authorization` header ([authorization]: SAPISIDHASH, SAPISID1PHASH, SAPISID3PHASH);
 * - [state]: signed out, signed in (with who, as the platform describes it: [describe]), or expired;
 * - the silent refresh that keeps the sign-in alive while Google still holds it ([refresh]).
 *
 * The silent refresh. Google's short-lived `__Secure-1PSIDTS` / `__Secure-3PSIDTS` are rotated the way the site's own page does
 * it, by the rotation call ([ROTATE_YOUTUBE_URL], and [ROTATE_GOOGLE_URL] for the accounts jar): before the first signed request
 * after start or resume ([ensureFresh], [onResume]), whenever the last rotation is older than [ROTATE_EVERY_MS], on the platform's
 * background timer ([startPeriodicRefresh]), and after a refused request ([refreshAfterRejection]: the request is then retried once
 * with the rotated jar). Only when a refused session stays refused is it checked against a signed-in page; when that page says it is
 * signed out, the platform's [recovery] is asked for a fresher copy of the same login; only when that fails too is the sign-in
 * [YouTubeAuthState.Expired]. Refreshes are single-flight, and one that ends after a sign-out or a switch ([generation]) is dropped.
 *
 * Which account and channel a request acts as (`X-Goog-AuthUser`, `X-Goog-PageId`, `onBehalfOfUser`) stays with the Innertube client
 * that already resolves it; this is the credential under it. Cookie values are never logged.
 *
 * [shared] is the process-wide instance; a test makes its own with a fake [transport] and a fixed [clock].
 */
class YouTubeSession(
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistIntervalMs: Long = DEFAULT_PERSIST_INTERVAL_MS,
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** The rotation and page calls; null turns the silent refresh off (an expiry is then declared on the server's word alone). */
    @Volatile var transport: SessionTransport? = null,
) {
    /** Where a changed jar is written; installed by the platform at start. Null keeps rotations in memory only. */
    @Volatile
    var vault: CredentialVault? = null

    /** The platform's last way back before an expiry; see [SessionRecovery]. */
    @Volatile
    var recovery: SessionRecovery? = null

    private val lock = Any()
    private var jar: YouTubeCookieJar = YouTubeCookieJar.EMPTY
    private var googleJar: YouTubeCookieJar = YouTubeCookieJar.EMPTY

    /** The header the platform last installed, verbatim: what it will install again for the same login. */
    private var installed: String? = null

    /** The headers last handed to the [vault]. */
    private var persisted: String? = null
    private var googlePersisted: String? = null
    private var googleInstalled: String? = null
    private var lastPersistMs = 0L
    private var everPersisted = false
    private var persistScheduled = false

    private var strikes = 0
    private var expiredReason: String? = null

    /** Whether the expiry is the final word of a refresh (or the platform's), which only a new sign-in or a signed-in page clears. */
    private var expiredExplicitly = false
    private var account: YouTubeAccountInfo? = null
    private var profile: YouTubeProfileInfo? = null

    private val cookieFlow = MutableStateFlow<String?>(null)
    private val stateFlow = MutableStateFlow<YouTubeAuthState>(YouTubeAuthState.SignedOut)

    /** The `Cookie` header both clients send, or null to browse as a guest. Follows sign-ins, switches, sign-outs and rotations. */
    val cookieState: StateFlow<String?> = cookieFlow.asStateFlow()

    /** [cookieState]'s value. */
    val cookie: String? get() = cookieFlow.value

    /** The jar behind [cookie]. */
    val currentJar: YouTubeCookieJar get() = synchronized(lock) { jar }

    /** The accounts.google.com jar of the same login, or null when the platform has not given one. */
    val googleCookie: String? get() = synchronized(lock) { googleJar.takeUnless { it.isEmpty }?.header() }

    /** The sign-in, as both modes show it. */
    val state: StateFlow<YouTubeAuthState> = stateFlow.asStateFlow()

    /**
     * Counts installs of a different session (not rotations of the same one). Work started under one value must not be applied
     * under another: a scope read for the account that just left would credit the new one's plays to it.
     */
    @Volatile
    var generation: Long = 0L
        private set

    /** Counts jars taken from [recovery]; a client that read an account scope under the old jar reads it again. */
    @Volatile
    var recoveries: Long = 0L
        private set

    /** Google's per-session visitor id, one for both clients; dropped when the session changes. */
    @Volatile
    var visitorData: String? = null

    /** Whether [visitorData] came from a signed-in page rather than being minted anonymously; see Innertube. */
    @Volatile
    var visitorDataIsSessionBound: Boolean = false

    // ---- Installing ------------------------------------------------------------------------------------------------------

    /**
     * Puts [header] in force (null or blank signs out). Returns whether the session changed: false when it is the value already in
     * force, or when the platform installs again what it installed before for the same login (its stored copy, which a rotation
     * since has moved past and which must not undo it).
     */
    fun install(header: String?): Boolean {
        val value = header?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            val current = cookieFlow.value
            if (value == current) {
                if (value == null && expiredReason != null) {
                    expiredReason = null
                    expiredExplicitly = false
                    publish()
                }
                return false
            }
            if (value != null && current != null && (value == installed || value == persisted) &&
                YouTubeCookieJar.sameLogin(value, current)
            ) {
                return false
            }
            installed = value
            persisted = null
            jar = YouTubeCookieJar.parse(value)
            googleJar = YouTubeCookieJar.EMPTY
            googleInstalled = null
            googlePersisted = null
            cookieFlow.value = value
            generation += 1
            strikes = 0
            expiredReason = null
            expiredExplicitly = false
            visitorData = null
            visitorDataIsSessionBound = false
            lastRotationMs = 0L
            lastVerifiedMs = 0L
            needsFreshBeforeUse = value != null
            publish()
            return true
        }
    }

    /**
     * The accounts.google.com jar of the session in force (after [install]); the rotation call keeps it fresh too. Installing the
     * value already given again changes nothing.
     */
    fun installGoogle(header: String?) {
        val value = header?.takeIf { it.isNotBlank() }
        synchronized(lock) {
            if (cookieFlow.value == null) return
            if (value == googleInstalled || value != null && value == googlePersisted) return
            googleInstalled = value
            googlePersisted = null
            googleJar = YouTubeCookieJar.parse(value)
        }
    }

    /** Who the session is, as the platform knows it (both null while it does not know). Only changes what [state] shows. */
    fun describe(account: YouTubeAccountInfo?, profile: YouTubeProfileInfo?) {
        synchronized(lock) {
            if (this.account == account && this.profile == profile) return
            this.account = account
            this.profile = profile
            publish()
        }
    }

    /** The `Authorization` header for [origin] over the jar in force, or null when signed out or the jar cannot sign. */
    fun authorization(origin: String, nowSeconds: Long = clock() / 1000): String? =
        SapisidAuth.authorization(currentJar, origin, nowSeconds)

    /**
     * Applies the `Set-Cookie` values of a response to a request that was sent with [sent]. Ignored when [sent] is not the login in
     * force (the listener switched while it was in flight), when nothing changes, and when the answer would take the signing secret
     * away (that is a sign-out elsewhere, which the next refused request reports, not a rotation to keep).
     */
    fun absorbSetCookies(sent: String?, setCookies: List<String>) {
        if (sent == null || setCookies.isEmpty()) return
        val changed = synchronized(lock) {
            val current = cookieFlow.value ?: return
            if (!YouTubeCookieJar.sameLogin(sent, current)) return
            applyYouTube(setCookies)
        }
        if (changed) schedulePersist()
    }

    // ---- What the server said ---------------------------------------------------------------------------------------------

    /** A signed request sent with [sent] was answered: strikes are forgotten, and an expiry they caused is lifted. */
    fun reportAccepted(sent: String?) {
        synchronized(lock) {
            if (!isCurrent(sent)) return
            strikes = 0
            if (expiredReason != null && !expiredExplicitly) {
                expiredReason = null
                publish()
            }
        }
    }

    /** A signed request sent with [sent] was answered [status]; a 401 or 403 counts, and [STRIKES] in a row is looked into. */
    fun reportRejected(sent: String?, status: Int) {
        if (status != 401 && status != 403) return
        strike(sent, "HTTP $status")
    }

    /** A signed request sent with [sent] was answered as if by nobody (`loggedOut` in the answer): a strike, like a 401. */
    fun reportAnsweredSignedOut(sent: String?) = strike(sent, "answered as signed out")

    /**
     * A page fetched with [sent] said `LOGGED_IN: false`. With a [transport] the session is refreshed and checked first (and
     * expires only if that fails); without one it expires at once.
     */
    fun reportLoggedOut(sent: String?, reason: String) {
        synchronized(lock) { if (!isCurrent(sent)) return }
        escalate(reason)
    }

    /** A page fetched with [sent] said `LOGGED_IN: true`: whatever expiry stood is over. */
    fun reportLoggedIn(sent: String?) {
        synchronized(lock) {
            if (!isCurrent(sent)) return
            strikes = 0
            lastVerifiedMs = clock()
            if (expiredReason != null) {
                expiredReason = null
                expiredExplicitly = false
                publish()
            }
        }
    }

    /** The platform found the stored session dead (it could not be renewed); shown as expired until the next sign-in. */
    fun markExpired(reason: String) {
        synchronized(lock) { expire(reason, explicitly = true) }
    }

    /** Hands a changed jar that is still waiting to the [vault] now (the app is going to the background, say). */
    fun flush() = persistNow()

    // ---- The silent refresh -------------------------------------------------------------------------------------------------

    /** What a [refresh] came to. */
    enum class Refresh {
        /** The jar changed (rotated, or a fresher copy was recovered) and the session is believed alive: worth retrying a request. */
        ROTATED,

        /** Nothing changed, and nothing says the session is gone. */
        ALIVE,

        /** It could not be told (no network, no transport): nothing was concluded. */
        UNKNOWN,

        /** Every way back failed: the state is now [YouTubeAuthState.Expired]. */
        EXPIRED,

        /** There was no session, or it changed while the refresh ran: its result was dropped. */
        DISCARDED,
    }

    private val refreshLock = Mutex()

    /** Bumped by every refresh that ran, so a caller that waited for the lock sees that one ran meanwhile and takes its answer. */
    private var refreshSeq = 0L
    private var lastRefresh: Refresh = Refresh.UNKNOWN
    private var lastRefreshGeneration = -1L
    private var lastRefreshAtMs = 0L

    @Volatile
    private var lastRotationMs = 0L

    @Volatile
    private var lastVerifiedMs = 0L

    /** Whether the next signed request waits for a rotation first (after start, resume or a sign-in). */
    @Volatile
    private var needsFreshBeforeUse = true

    @Volatile
    private var routineRunning = false

    /**
     * Rotates the jar now and, when [verify] (or the rotation was refused), checks the session against a signed-in page, recovers a
     * fresher copy from the platform when it is signed out, and declares the expiry only when all of that failed ([reason] is what
     * the expiry then says). Single-flight: a call that waited for one already running takes its answer.
     */
    suspend fun refresh(verify: Boolean, reason: String = "the sign-in was refused"): Refresh {
        val seq = synchronized(lock) { refreshSeq }
        val gen = generation
        refreshLock.withLock {
            synchronized(lock) {
                if (refreshSeq != seq && lastRefreshGeneration == gen && (!verify || lastRefresh != Refresh.UNKNOWN)) return lastRefresh
            }
            val outcome = try {
                runRefresh(gen, verify, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the silent refresh failed: ${e::class.java.simpleName}")
                Refresh.UNKNOWN
            }
            synchronized(lock) {
                refreshSeq += 1
                lastRefresh = outcome
                lastRefreshGeneration = gen
                lastRefreshAtMs = clock()
            }
            return outcome
        }
    }

    /**
     * A request sent with [sent] was refused (401/403): rotate and check, at most once per [REJECTION_REFRESH_MS] (a burst of refused
     * requests shares one refresh). [Refresh.ROTATED] means the request is worth one more try with the jar now in force.
     */
    suspend fun refreshAfterRejection(sent: String?): Refresh {
        val gen = synchronized(lock) {
            if (!isCurrent(sent)) return Refresh.DISCARDED
            if (lastRefreshGeneration == generation && clock() - lastRefreshAtMs < REJECTION_REFRESH_MS) {
                // One ran a moment ago: a retry is worth it only if the jar moved since the request was sent.
                return if (cookieFlow.value != sent && lastRefresh != Refresh.EXPIRED) Refresh.ROTATED else lastRefresh
            }
            generation
        }
        val outcome = refresh(verify = true)
        return if (generation != gen) Refresh.DISCARDED else outcome
    }

    /**
     * Called before a signed request. The first one after start, resume or sign-in waits (at most [FIRST_REFRESH_TIMEOUT_MS]) for a
     * rotation; later ones start one in the background when the last is older than [ROTATE_EVERY_MS] and never wait.
     */
    suspend fun ensureFresh() {
        if (cookie == null || transport == null) return
        if (needsFreshBeforeUse) {
            withTimeoutOrNull(FIRST_REFRESH_TIMEOUT_MS) { refresh(verify = false) }
            needsFreshBeforeUse = false
            return
        }
        if (clock() - lastRotationMs >= ROTATE_EVERY_MS) refreshInBackground(verify = false)
    }

    /** The app came back to the foreground: the next signed request waits for a rotation first. */
    fun onResume() {
        if (cookie != null) needsFreshBeforeUse = true
    }

    /**
     * A refresh every [everyMs] while [scope] lives (the desktop while it runs, the phone while it is on screen); one with a page
     * check once a day. Returns the job, to cancel.
     */
    fun startPeriodicRefresh(scope: CoroutineScope, everyMs: Long = ROTATE_EVERY_MS): Job = scope.launch {
        while (isActive) {
            delay(everyMs)
            if (cookie == null || transport == null) continue
            refresh(verify = clock() - lastVerifiedMs >= VERIFY_EVERY_MS)
        }
    }

    private fun refreshInBackground(verify: Boolean, reason: String = "the sign-in was refused") {
        synchronized(lock) {
            if (routineRunning) return
            routineRunning = true
        }
        background.launch {
            try {
                refresh(verify, reason)
            } finally {
                synchronized(lock) { routineRunning = false }
            }
        }
    }

    private suspend fun runRefresh(gen: Long, verify: Boolean, reason: String): Refresh {
        val transport = transport ?: return Refresh.UNKNOWN
        val sent = cookie ?: return Refresh.DISCARDED
        var changed = false

        // 1. The rotation, for the YouTube jar and (when there is one) the accounts jar.
        val rotation = runCatching { transport.rotate(ROTATE_YOUTUBE_URL, sent, YOUTUBE_ORIGIN) }.getOrNull()
        if (generation != gen) return Refresh.DISCARDED
        if (rotation != null && synchronized(lock) { applyYouTube(rotation.setCookies) }) changed = true
        val google = googleCookie
        if (google != null) {
            val googleRotation = runCatching { transport.rotate(ROTATE_GOOGLE_URL, google, YOUTUBE_ORIGIN) }.getOrNull()
            if (generation != gen) return Refresh.DISCARDED
            if (googleRotation != null && synchronized(lock) { applyGoogle(googleRotation.setCookies) }) changed = true
        }
        val refused = rotation?.status == 401 || rotation?.status == 403
        // The attempt counts, whatever it answered: an endpoint that keeps failing is not asked again on every request.
        lastRotationMs = clock()
        if (changed) persistNow()

        if (!verify && !refused) {
            return when {
                changed -> Refresh.ROTATED
                rotation == null -> Refresh.UNKNOWN
                else -> Refresh.ALIVE
            }
        }

        // 2. Is the session (now) signed in?
        when (checkSignedIn(gen)) {
            true -> return if (changed) Refresh.ROTATED else Refresh.ALIVE
            null -> return if (generation != gen) Refresh.DISCARDED else Refresh.UNKNOWN
            false -> Unit
        }
        if (generation != gen) return Refresh.DISCARDED

        // 3. The platform's fresher copy of the same login.
        val current = cookie ?: return Refresh.DISCARDED
        val recovered = runCatching { recovery?.freshCookie(current) }.getOrNull()
        if (generation != gen) return Refresh.DISCARDED
        if (recovered != null && adoptRecovered(recovered, gen)) {
            when (checkSignedIn(gen)) {
                true -> return Refresh.ROTATED
                null -> return if (generation != gen) Refresh.DISCARDED else Refresh.UNKNOWN
                false -> Unit
            }
            if (generation != gen) return Refresh.DISCARDED
        }

        // 4. Nothing brought it back.
        synchronized(lock) {
            if (generation != gen) return Refresh.DISCARDED
            Log.w(TAG, "the sign-in could not be renewed ($reason); asking for a new sign-in")
            expire(reason, explicitly = true)
        }
        return Refresh.EXPIRED
    }

    /** True or false when the page said `LOGGED_IN`, null when it could not be read. Its `Set-Cookie` values are kept. */
    private suspend fun checkSignedIn(gen: Long): Boolean? {
        val transport = transport ?: return null
        val sent = cookie ?: return null
        val answer = runCatching { transport.page(VERIFY_URL, sent, SapisidAuth.authorization(sent, YOUTUBE_ORIGIN, clock() / 1000)) }
            .getOrNull() ?: return null
        if (generation != gen) return null
        if (synchronized(lock) { applyYouTube(answer.setCookies) }) schedulePersist()
        if (answer.status == 401 || answer.status == 403) return false
        if (answer.status !in 200..299) return null
        val loggedIn = YouTubePageConfig.parse(answer.body).loggedIn
        if (loggedIn == true) {
            synchronized(lock) {
                if (generation != gen) return null
                strikes = 0
                lastVerifiedMs = clock()
                if (expiredReason != null) {
                    expiredReason = null
                    expiredExplicitly = false
                    publish()
                }
            }
        }
        return loggedIn
    }

    /** Takes a recovered jar of the same login, laid over the one in force. False when it is another login or the session moved. */
    private fun adoptRecovered(recovered: SessionRecovery.Recovered, gen: Long): Boolean {
        synchronized(lock) {
            if (generation != gen) return false
            val current = cookieFlow.value ?: return false
            if (!YouTubeCookieJar.sameLogin(recovered.youtube, current)) {
                Log.w(TAG, "the platform's copy of the sign-in belongs to another login; not taking it")
                return false
            }
            val next = jar.mergedWith(YouTubeCookieJar.parse(recovered.youtube))
            if (next == jar && recovered.google == null) return false
            jar = next
            cookieFlow.value = next.header()
            recovered.google?.let { googleJar = googleJar.mergedWith(YouTubeCookieJar.parse(it)) }
            recoveries += 1
            strikes = 0
        }
        Log.d(TAG, "took a fresher copy of the sign-in from the platform")
        persistNow()
        return true
    }

    // ---- Plumbing ----------------------------------------------------------------------------------------------------------

    /** Under [lock]: applies YouTube `Set-Cookie` values; whether the jar changed. */
    private fun applyYouTube(setCookies: List<String>): Boolean {
        if (setCookies.isEmpty() || cookieFlow.value == null) return false
        val next = jar.withSetCookies(setCookies, clock())
        if (next == jar || !next.canSign) return false
        jar = next
        cookieFlow.value = next.header()
        return true
    }

    /** Under [lock]: applies accounts.google.com `Set-Cookie` values; whether that jar changed. */
    private fun applyGoogle(setCookies: List<String>): Boolean {
        if (setCookies.isEmpty() || googleJar.isEmpty) return false
        val next = googleJar.withSetCookies(setCookies, clock(), YouTubeCookieJar.GOOGLE_DOMAIN)
        if (next == googleJar) return false
        googleJar = next
        return true
    }

    private fun strike(sent: String?, reason: String) {
        val escalate = synchronized(lock) {
            if (!isCurrent(sent)) return
            strikes += 1
            strikes >= STRIKES && expiredReason == null
        }
        if (escalate) escalate(reason)
    }

    /** The server keeps saying no: with a transport, refresh and check before concluding; without one, conclude. */
    private fun escalate(reason: String) {
        if (transport != null) {
            refreshInBackground(verify = true, reason = reason)
        } else {
            synchronized(lock) { expire(reason, explicitly = false) }
        }
    }

    /** Under [lock]. */
    private fun expire(reason: String, explicitly: Boolean) {
        if (expiredReason == reason && expiredExplicitly == explicitly) return
        expiredReason = reason
        expiredExplicitly = expiredExplicitly || explicitly
        publish()
    }

    /** Under [lock]: whether [sent] is the login in force. */
    private fun isCurrent(sent: String?): Boolean {
        val current = cookieFlow.value ?: return false
        return sent != null && YouTubeCookieJar.sameLogin(sent, current)
    }

    /** Under [lock]. */
    private fun publish() {
        val reason = expiredReason
        stateFlow.value = when {
            reason != null -> YouTubeAuthState.Expired(reason, account, profile)
            cookieFlow.value != null && jar.canSign -> YouTubeAuthState.SignedIn(account, profile)
            else -> YouTubeAuthState.SignedOut
        }
    }

    private fun schedulePersist() {
        val wait: Long
        synchronized(lock) {
            if (persistScheduled) return
            val since = clock() - lastPersistMs
            wait = if (!everPersisted || since >= persistIntervalMs) 0L else persistIntervalMs - since
            if (wait > 0) persistScheduled = true
        }
        if (wait <= 0L) {
            persistNow()
        } else {
            background.launch {
                delay(wait)
                synchronized(lock) { persistScheduled = false }
                persistNow()
            }
        }
    }

    private fun persistNow() {
        val target = vault ?: return
        var youtube: String? = null
        var google: String? = null
        synchronized(lock) {
            val current = cookieFlow.value ?: return
            if (current != persisted && current != installed) {
                persisted = current
                youtube = current
            }
            val googleNow = googleJar.takeUnless { it.isEmpty }?.header()
            if (googleNow != null && googleNow != googlePersisted && googleNow != googleInstalled) {
                googlePersisted = googleNow
                google = googleNow
            }
            if (youtube != null || google != null) {
                lastPersistMs = clock()
                everPersisted = true
            }
        }
        youtube?.let { value ->
            runCatching { target.store(value) }.onFailure { Log.w(TAG, "could not keep the renewed sign-in: ${it::class.java.simpleName}") }
        }
        google?.let { value ->
            runCatching { target.storeGoogle(value) }.onFailure { Log.w(TAG, "could not keep the renewed accounts sign-in: ${it::class.java.simpleName}") }
        }
    }

    companion object {
        /** Refused requests in a row that make the session be looked into. One is often a stale account scope. */
        const val STRIKES = 3

        /** How often at most a changed jar is written to the vault. */
        const val DEFAULT_PERSIST_INTERVAL_MS = 30_000L

        /** How old the last rotation may be before the next signed request starts another (browsers do it about this often). */
        const val ROTATE_EVERY_MS = 10 * 60 * 1000L

        /** How often the periodic refresh also checks a signed-in page. */
        const val VERIFY_EVERY_MS = 24 * 60 * 60 * 1000L

        /** How long the first signed request after start or resume waits for its rotation. */
        const val FIRST_REFRESH_TIMEOUT_MS = 8_000L

        /** A burst of refused requests shares one refresh in this window. */
        const val REJECTION_REFRESH_MS = 60_000L

        /** The rotation call for the youtube.com cookies (answered with `Set-Cookie` for `.youtube.com`). */
        const val ROTATE_YOUTUBE_URL = "https://accounts.youtube.com/RotateCookies"

        /** The rotation call for the accounts.google.com cookies. */
        const val ROTATE_GOOGLE_URL = "https://accounts.google.com/RotateCookies"

        /** The page whose `LOGGED_IN` says whether the session is signed in. */
        const val VERIFY_URL = "https://www.youtube.com/"

        const val YOUTUBE_ORIGIN = "https://www.youtube.com"

        private const val TAG = "Cosmotune"

        /** The process-wide session: the one Innertube signs with, the one both modes show. */
        val shared: YouTubeSession by lazy { YouTubeSession(transport = KtorSessionTransport()) }
    }
}
