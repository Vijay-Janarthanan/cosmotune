package com.cosmotune.music.desktop

import com.cosmotune.cloud.CloudAccount
import com.cosmotune.cloud.CloudAutoSync
import com.cosmotune.cloud.CloudException
import com.cosmotune.cloud.CloudSession
import com.cosmotune.cloud.CloudSessionManager
import com.cosmotune.cloud.CloudSessionStore
import com.cosmotune.cloud.CloudStatus
import com.cosmotune.cloud.CosmotuneFirebase
import com.cosmotune.cloud.FileSyncMarks
import com.cosmotune.cloud.GoogleDesktopSignIn
import com.cosmotune.cloud.GoogleOAuthConfig
import com.cosmotune.cloud.JournalSyncSource
import com.cosmotune.cloud.LikeFile
import com.cosmotune.cloud.SnapshotJournal
import com.cosmotune.cloud.SyncArtists
import com.cosmotune.cloud.SyncLikes
import com.cosmotune.cloud.SyncPlaylists
import com.cosmotune.cloud.signInThroughBrowser
import com.cosmotune.desktop.desktopListeningFile
import com.cosmotune.listening.ListeningStore
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The desktop's cloud account: playlists, likes and followed artists kept the same on every device, as a guest with nothing asked,
 * or under a Google account once the person signs in. Opt-in: until the person turns cloud sync on (or signs in with Google), the
 * account is never built, no guest is made and nothing is sent. Everything runs on its own scope, so a sync that fails or hangs only
 * ever shows on the settings status line; playback and the library never wait for it.
 */
internal object DesktopCloud {
    /** The JSON of [CloudSession] in the credential store: its refresh token is the credential, so it never goes in plain prefs. */
    private const val KEY_SESSION = "cloud.session"

    /** Off until the person turns cloud sync on or signs in with Google; Sign out and Delete turn it off again. */
    private const val KEY_ENABLED = "cloud_sync_enabled"

    const val NOT_SET_UP = "Google sign-in is not set up in this build"

    private const val FIRST_SYNC_DELAY_MS = 10_000L
    private const val PERIODIC_SYNC_MS = 15 * 60_000L

    /** What the settings page shows beside [CloudStatus]: the browser step and the outcome of the last button pressed. */
    data class Activity(val waitingForBrowser: Boolean = false, val busy: Boolean = false, val message: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var directory: File? = null

    /** Where the marks, the journal and the like details live: a `cloud` folder beside the providers and downloads. Called once from main. */
    fun configure(directory: File) {
        this.directory = directory
        SyncLikes.sourceTrack = com.cosmotune.providers.SourceLibraryStore::ownsTrack
    }

    /** Injected by the build from local.properties or the release environment; a blank id means sign-in is unavailable here. */
    val google = GoogleOAuthConfig(
        clientId = System.getProperty("cosmotune.google.client.id").orEmpty().trim(),
        clientSecret = System.getProperty("cosmotune.google.client.secret").orEmpty().trim(),
    )

    private val persistence by lazy { DesktopPersistence() }
    private val marks by lazy { FileSyncMarks(directory?.let { File(it, "marks.json") }) }

    /**
     * What a liked song looked like (title, artist, cover), beside the liked ids the library keeps: the ids alone are what the
     * desktop stores, and the phone's likes arrive with these. A local file, written whether or not sync is on.
     */
    private val likeDetails by lazy { LikeFile(directory?.let { File(it, "likes.json") }) }

    private val revision = MutableStateFlow(0L)

    /** Goes up each time the sync wrote playlists into the store; the library re-reads them when it does. */
    val playlistRevision: StateFlow<Long> = revision.asStateFlow()

    private val likeRevisionState = MutableStateFlow(0L)

    /** Goes up each time the sync wrote likes into the store; the liked set is read again when it does. */
    val likeRevision: StateFlow<Long> = likeRevisionState.asStateFlow()

    private val activityState = MutableStateFlow(Activity())
    val activity: StateFlow<Activity> = activityState.asStateFlow()

    private val statusState = MutableStateFlow(CloudStatus())

    /** The account's status, mirrored once sync is on: reading the account's own would build it. */
    val status: StateFlow<CloudStatus> = statusState.asStateFlow()

    /** The playlists as the sync last saw them, so the library re-reading what the sync wrote does not ask for another sync. */
    @Volatile private var lastSeen: Map<String, JsonObject>? = null
    @Volatile private var lastSeenLikes: Map<String, JsonObject>? = null
    @Volatile private var lastSeenArtists: Map<String, JsonObject>? = null

    private fun likeSnapshot(likedIds: Collection<String>) = SyncLikes.snapshot(likedIds, likeDetails.likes.value)

    private fun artistSnapshot(): Map<String, JsonObject> =
        SyncArtists.snapshot(ListeningStore(desktopListeningFile()).state.value.artists).also { lastSeenArtists = it }

    private val source by lazy {
        object : JournalSyncSource(SnapshotJournal(directory?.let { File(it, "journal.json") })) {
            override suspend fun snapshot(kind: String): Map<String, JsonObject> = withContext(Dispatchers.IO) {
                when (kind) {
                    SyncPlaylists.KIND -> cloudPlaylistSnapshot(persistence.playlists()).also { lastSeen = it }
                    SyncLikes.KIND -> likeSnapshot(persistence.likedIds()).also { lastSeenLikes = it }
                    // A listening store that cannot be read fails the sync, rather than reading as "every artist unfollowed".
                    SyncArtists.KIND -> artistSnapshot()
                    // Watch's add-ons: manifest URLs and switches only, never servers or sign-ins.
                    com.cosmotune.watch.SyncWatchPlugins.KIND -> com.cosmotune.watch.SyncWatchPlugins.snapshot(com.cosmotune.desktop.DesktopWatch.store.state.value)
                    com.cosmotune.watch.SyncWatchLibrary.KIND -> com.cosmotune.watch.SyncWatchLibrary.snapshot(com.cosmotune.desktop.DesktopWatch.store.state.value)
                    else -> emptyMap()
                }
            }

            override suspend fun write(kind: String, upserts: Map<String, JsonObject>, deletes: Set<String>) {
                if (upserts.isEmpty() && deletes.isEmpty()) return
                withContext(Dispatchers.IO) {
                    when (kind) {
                        SyncPlaylists.KIND -> writePlaylists(upserts, deletes)
                        SyncLikes.KIND -> {
                            // Into the liked set only: YouTube is not asked to rate them, the like was sent from where it was made.
                            val before = persistence.likedIds()
                            val next = SyncLikes.applyToIds(before, upserts, deletes)
                            likeDetails.applyFromCloud(upserts.filterKeys(SyncLikes::travels), deletes)
                            if (next != before) {
                                persistence.saveLikedIds(next)
                                likeRevisionState.update { it + 1 }
                            }
                        }
                        SyncArtists.KIND -> ListeningStore(desktopListeningFile()).update { SyncArtists.apply(it, upserts, deletes) }
                        com.cosmotune.watch.SyncWatchPlugins.KIND ->
                            com.cosmotune.desktop.DesktopWatch.store.update { com.cosmotune.watch.SyncWatchPlugins.apply(it, upserts, deletes) }
                        com.cosmotune.watch.SyncWatchLibrary.KIND ->
                            com.cosmotune.desktop.DesktopWatch.store.update { com.cosmotune.watch.SyncWatchLibrary.apply(it, upserts, deletes) }
                    }
                }
            }
        }
    }

    private fun writePlaylists(upserts: Map<String, JsonObject>, deletes: Set<String>) {
        // Compare-and-swap: an edit made in the window between reading and saving is kept, and the cloud's changes
        // are laid over it on the next pass instead of replacing it.
        repeat(5) {
            val before = persistence.playlists()
            val next = applyCloudPlaylists(before, upserts, deletes)
            if (next == before) return
            if (persistence.savePlaylistsIfUnchanged(before, next)) {
                revision.update { it + 1 }
                return
            }
        }
        error("Playlists kept changing while the sync wrote them")
    }

    private val sessionStore = object : CloudSessionStore {
        override fun load(): CloudSession? = runCatching {
            DesktopSecretPreferences.shared.read(KEY_SESSION).takeIf { it.isNotBlank() }?.let { json.decodeFromString<CloudSession>(it) }
        }.getOrNull()

        override fun save(session: CloudSession?) {
            // A failure leaves the session in memory for this run; the next launch is a fresh guest, which is harmless.
            runCatching { DesktopSecretPreferences.shared.write(KEY_SESSION, session?.let { json.encodeToString(it) }.orEmpty()) }
                .onFailure { DesktopTrackLog.log("cloud: could not keep the session (${it.javaClass.simpleName})") }
        }
    }

    /** Built the first time sync is on and something needs it, or a room needs [sessions]; it never syncs while sync is off. */
    private val account: CloudAccount by lazy {
        CloudAccount(
            CosmotuneFirebase.config, Http.client, sessionStore, source, marks,
            listOf(SyncPlaylists.KIND, SyncLikes.KIND, SyncArtists.KIND, com.cosmotune.watch.SyncWatchPlugins.KIND, com.cosmotune.watch.SyncWatchLibrary.KIND), scope,
        )
    }

    /**
     * Who this device is to Firebase, for rooms: the same manager the account signs in and syncs with, so a Google sign-in made for
     * sync is also the name a room shows. Building it sends nothing and does not turn sync on; only [CloudSessionManager.ensureSession]
     * goes to the network, and only a room (or a sync the person turned on) calls it.
     */
    val sessions: CloudSessionManager get() = account.sessions

    private var started = false
    private var signInJob: Job? = null

    private val enabledState by lazy { MutableStateFlow(runCatching { persistence.boolean(KEY_ENABLED, false) }.getOrDefault(false)) }

    /** Whether cloud sync is on. While it is off, the settings page offers to turn it on (or to sign in) and nothing else. */
    val enabled: StateFlow<Boolean> get() = enabledState

    private fun setEnabled(value: Boolean) {
        if (!value) autoSync.stopped()
        if (enabledState.value == value) return
        enabledState.value = value
        runCatching { persistence.saveBoolean(KEY_ENABLED, value) }
    }

    /** The only way an automatic sync reaches the account, so with sync off the account is never even built. */
    private val autoSync = CloudAutoSync(
        enabled = { enabledState.value },
        // No reliable "online" here; an offline sync fails quietly onto the status line and the next periodic one retries.
        online = { true },
        sync = { quietMs -> if (quietMs == null) scope.launch { account.syncNow() } else account.requestSync(quietMs) },
    )

    /** The first sync a little after launch (out of the way of the first song), then every 15 minutes, while sync is on. Safe to call again. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous: Map<String, JsonObject>? = null
            com.cosmotune.desktop.DesktopWatch.store.state.collect { state ->
                val current = com.cosmotune.watch.SyncWatchLibrary.changeSignal(state) +
                    com.cosmotune.watch.SyncWatchPlugins.snapshot(state)
                if (previous != null && previous != current) autoSync.changed()
                previous = current
            }
        }
        scope.launch { com.cosmotune.watch.WatchSyncSignals.changes.collect { autoSync.changed() } }
        scope.launch {
            enabledState.first { it }
            // A button's outcome ("Signed out...") gives way once a sync starts, so a later sync failure is not hidden behind it.
            account.status.collect {
                statusState.value = it
                if (it.syncing) activityState.update { activity -> activity.copy(message = null) }
            }
        }
        scope.launch {
            delay(FIRST_SYNC_DELAY_MS)
            while (true) {
                autoSync.due()
                delay(PERIODIC_SYNC_MS)
            }
        }
        // Any listening-store save (a saved queue, a smart playlist, a follow) bumps this; only a change to the followed artists syncs.
        scope.launch {
            ListeningStore.saves.drop(1).collect {
                val seen = lastSeenArtists ?: return@collect
                if (runCatching { artistSnapshot() }.getOrNull() != seen) autoSync.changed()
            }
        }
    }

    /** The library's playlists changed: sync after a quiet spell, unless this is only the library catching up with the sync's own write. */
    fun playlistsChanged(playlists: List<DesktopPlaylist>) {
        // Before the first sync there is nothing to compare with, and that sync is coming anyway.
        val seen = lastSeen ?: return
        if (!enabledState.value) return
        scope.launch {
            if (cloudPlaylistSnapshot(playlists) != seen) autoSync.changed()
        }
    }

    /** The liked set changed, as [playlistsChanged]: the YouTube account's own likes merged in count too, so they reach the phone. */
    fun likesChanged(likedIds: Set<String>) {
        val seen = lastSeenLikes ?: return
        if (!enabledState.value) return
        scope.launch {
            if (likeSnapshot(likedIds) != seen) autoSync.changed()
        }
    }

    /** A rating made here (not a source track's): remembers what the song looked like, for the phone's list of likes. */
    fun rated(song: Song, status: LikeStatus) {
        if (!SyncLikes.travels(song.videoId)) return
        scope.launch {
            if (status == LikeStatus.LIKE) likeDetails.like(song.videoId, SyncLikes.data(song)) else likeDetails.unlike(song.videoId)
        }
    }

    /** "Turn on cloud sync": the person chose it, so the first sync (as a guest, until they sign in) starts now. */
    fun turnOn() {
        setEnabled(true)
        syncNow()
    }

    fun syncNow() {
        if (!enabledState.value) return
        scope.launch {
            activityState.update { it.copy(message = null) }
            account.syncNow()
        }
    }

    fun beginGoogleSignIn() {
        if (!google.isConfigured) {
            activityState.update { it.copy(message = NOT_SET_UP) }
            return
        }
        if (signInJob?.isActive == true) return
        activityState.update { it.copy(waitingForBrowser = true, message = null) }
        signInJob = scope.launch {
            val message = try {
                val token = GoogleDesktopSignIn(google, Http.client).signInThroughBrowser(
                    openBrowser = { url -> DesktopExternalLinks.open(url).getOrElse { throw BrowserUnavailable(it) } },
                )
                activityState.update { it.copy(waitingForBrowser = false, busy = true) }
                // Signing in is choosing sync; if it does not go through, sync stays as it was.
                val wasEnabled = enabledState.value
                setEnabled(true)
                val before = account.sessions.session.value?.uid
                val result = account.signInWithGoogle(token)
                val session = account.sessions.session.value
                when {
                    session == null || session.isAnonymous -> {
                        if (!wasEnabled) setEnabled(false)
                        readableCloudError(result.exceptionOrNull())
                    }
                    else -> {
                        // A Google account that already had data elsewhere has a different id from this device's guest; the marks
                        // still describe the guest's history, so the new account is read and sent whole.
                        if (before != null && before != session.uid) {
                            marks.pulledMs = 0
                            marks.pushedMs = 0
                            account.syncNow()
                        }
                        null
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                "Your browser did not finish signing in within 3 minutes. Try again."
            } catch (cancelled: CancellationException) {
                activityState.update { it.copy(waitingForBrowser = false, busy = false, message = "Sign-in cancelled") }
                throw cancelled
            } catch (failure: Throwable) {
                readableCloudError(failure)
            }
            activityState.update { it.copy(waitingForBrowser = false, busy = false, message = message) }
        }
    }

    /** Stops waiting for the browser; the one-time page on this computer closes with it. */
    fun cancelGoogleSignIn() {
        signInJob?.cancel()
    }

    /** Forgets the session and turns sync off: a new guest would otherwise be made and the library sent to it unasked. */
    fun signOut() {
        setEnabled(false)
        scope.launch {
            val message = runCatching {
                account.cancelPendingSync()
                account.signOut()
                // Turned on again later, this device is a new guest with an empty cloud: send it everything.
                marks.pulledMs = 0
                marks.pushedMs = 0
            }.exceptionOrNull()?.let(::readableCloudError)
            activityState.update { it.copy(message = message ?: "Signed out. Cloud sync is off on this device.") }
        }
    }

    fun deleteAccount() {
        scope.launch {
            activityState.update { it.copy(busy = true, message = null) }
            account.cancelPendingSync()
            val result = account.deleteAccount()
            if (result.isSuccess) setEnabled(false)
            activityState.update {
                it.copy(
                    busy = false,
                    message = if (result.isSuccess) {
                        "Cloud account and data deleted. Your library on this device stays. Cloud sync is off."
                    } else {
                        "Could not delete the cloud account: ${readableCloudError(result.exceptionOrNull())}"
                    },
                )
            }
        }
    }

    private class BrowserUnavailable(cause: Throwable) : Exception(cause.message, cause)

    /** What the person reads when something went wrong: Firebase's and Google's own codes are kept only as a hint at the end. */
    private fun readableCloudError(failure: Throwable?): String = when (failure) {
        null -> "Sign-in did not finish"
        is BrowserUnavailable -> "Could not open your browser" + (failure.message?.let { ": $it" } ?: "")
        is CloudException -> when (failure.code) {
            "access_denied" -> "You declined the sign-in in your browser"
            "STATE_MISMATCH", "NO_CODE" -> "The browser's answer did not match this sign-in. Try again."
            else -> "Sign-in failed (${failure.code})"
        }
        is java.io.IOException -> "Could not reach Google. Check your connection and try again."
        else -> failure.message ?: failure.javaClass.simpleName
    }
}

/** Every playlist as a sync entry: the entry id is the playlist id without `synced:`, so it is the same id on every device. */
internal fun cloudPlaylistSnapshot(playlists: List<DesktopPlaylist>): Map<String, JsonObject> {
    val out = LinkedHashMap<String, JsonObject>()
    // Two local playlists naming one entry (`x` and `synced:x`) cannot both travel; the first is the one [applyCloudPlaylists] writes.
    for (playlist in playlists) out.putIfAbsent(SyncPlaylists.entryId(playlist.id), SyncPlaylists.data(playlist.title, playlist.songs))
    return out
}

/**
 * The library with the cloud's changes laid over it. A playlist the device has keeps its own id (with or without `synced:`) and its
 * place in the list; a new one takes the entry id and goes at the end. A song the playlist already had keeps what only this device
 * knows about it (its file, its local cover), so a synced edit never makes a local track unplayable.
 */
internal fun applyCloudPlaylists(current: List<DesktopPlaylist>, upserts: Map<String, JsonObject>, deletes: Set<String>): List<DesktopPlaylist> {
    val next = current.filterNot { SyncPlaylists.entryId(it.id) in deletes && SyncPlaylists.entryId(it.id) !in upserts }.toMutableList()
    for ((entryId, data) in upserts) {
        val index = next.indexOfFirst { SyncPlaylists.entryId(it.id) == entryId }
        val previous = next.getOrNull(index)
        val known = previous?.songs.orEmpty().associateBy { it.videoId }
        val songs = SyncPlaylists.songs(data).map { incoming ->
            val old = known[incoming.videoId] ?: return@map incoming
            old.copy(
                title = incoming.title,
                artist = incoming.artist,
                thumbnailUrl = incoming.thumbnailUrl ?: old.thumbnailUrl,
                durationText = incoming.durationText ?: old.durationText,
                artistId = incoming.artistId ?: old.artistId,
                albumId = incoming.albumId ?: old.albumId,
                albumName = incoming.albumName ?: old.albumName,
                isVideo = incoming.isVideo,
            )
        }
        val updated = DesktopPlaylist(previous?.id ?: entryId, SyncPlaylists.title(data), songs)
        if (index >= 0) next[index] = updated else next += updated
    }
    return next
}

/** The status row of the Cloud sync group: who this device is in the cloud, then what the sync is doing or last did; or that it is off. */
/** What the status row says while cloud sync is off: the app works fully without it. */
internal const val CLOUD_SYNC_OFF = "Cloud sync is off. Your library stays on this device."

internal fun cloudStatusText(status: CloudStatus, activity: DesktopCloud.Activity, enabled: Boolean, nowMs: Long): Pair<String, String> {
    if (!enabled) {
        val detail = when {
            activity.waitingForBrowser -> "Waiting for your browser..."
            activity.busy -> "Signing in..."
            else -> activity.message ?: "Nothing is sent until you turn it on or sign in with Google"
        }
        return CLOUD_SYNC_OFF to detail
    }
    val title = if (status.signedIn && !status.isGuest) {
        val name = status.name?.takeIf { it.isNotBlank() }
        val email = status.email?.takeIf { it.isNotBlank() }
        "Signed in as " + when {
            name != null && email != null -> "$name ($email)"
            else -> name ?: email ?: "your Google account"
        }
    } else {
        "Guest on this device"
    }
    val message = activity.message
    val error = status.lastError
    val detail = when {
        activity.waitingForBrowser -> "Waiting for your browser..."
        status.syncing || activity.busy -> "Syncing..."
        message != null -> message
        status.needsSignIn -> "Sign in with Google again to keep syncing"
        error != null -> "Last sync failed: ${error.take(160)}"
        else -> null
    }
    val last = if (status.lastSyncMs > 0) "Last synced ${relativeTime(status.lastSyncMs, nowMs)}" else "Not synced yet"
    return title to listOfNotNull(detail, last.takeUnless { activity.waitingForBrowser }).joinToString(" · ")
}

internal fun relativeTime(thenMs: Long, nowMs: Long): String {
    val minutes = (nowMs - thenMs).coerceAtLeast(0) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
            .format(java.time.Instant.ofEpochMilli(thenMs).atZone(java.time.ZoneId.systemDefault()))
    }
}
