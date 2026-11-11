package com.cosmotune.music.data.cloud

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import com.cosmotune.cloud.CloudAccount
import com.cosmotune.cloud.CloudAutoSync
import com.cosmotune.cloud.CloudSession
import com.cosmotune.cloud.CloudSessionManager
import com.cosmotune.cloud.CloudSessionStore
import com.cosmotune.cloud.CloudStatus
import com.cosmotune.cloud.CosmotuneFirebase
import com.cosmotune.cloud.FileSyncMarks
import com.cosmotune.cloud.JournalSyncSource
import com.cosmotune.cloud.LikeFile
import com.cosmotune.cloud.SnapshotJournal
import com.cosmotune.cloud.SyncArtists
import com.cosmotune.cloud.SyncLikes
import com.cosmotune.cloud.SyncPlaylists
import com.cosmotune.listening.ListeningStore
import com.cosmotune.music.R
import com.cosmotune.music.auth.EncryptedPrefs
import com.cosmotune.music.data.DebugLog
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.LikeState
import com.cosmotune.music.data.model.LikeStatus
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.spotify.LocalPlaylistStore
import com.cosmotune.providers.SourceLibraryStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException

/**
 * The phone's cloud account: playlists, likes and followed artists kept in step with the person's other devices through the shared
 * [CloudAccount]. Opt-in: until the person turns cloud sync on (or signs in with Google), nothing of the library is sent; the only
 * thing that builds the account before then is a room asking for an identity ([sessions]), which may make a guest and syncs
 * nothing. Nothing here is on the playback path, and a failed sync only ever shows on the settings row: the account
 * is built off the main thread (its session lives in an encrypted store, and the first open of one is a Keystore round trip) and
 * every call into it runs on [scope].
 */
object AndroidCloud {
    private const val TAG = "Cosmotune"

    /** Long enough that the first sync never competes with the first screen and the first song for the connection. */
    private const val FIRST_SYNC_DELAY_MS = 5_000L

    /** Off until the person turns cloud sync on or signs in with Google; Sign out and Delete turn it off again. */
    private const val KEY_ENABLED = "cloud_sync_enabled"

    private lateinit var app: Context

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> DebugLog.w(TAG, "cloud sync: $failure") },
    )

    private val folder: File get() = File(app.filesDir, "cloud")

    /** The listening store's file: its followed artists (the release watch) travel as the "artists" kind. */
    private val listeningFile: File get() = File(app.filesDir, "cosmotune-listening.json")

    /** Plain preferences: the flag is no secret, and the encrypted store behind the session is opened only once sync is on. */
    private val flags: SharedPreferences by lazy { app.getSharedPreferences("cosmotune_cloud_flags", Context.MODE_PRIVATE) }

    /**
     * Likes made in this app. The YouTube account keeps its own Liked Music, but with no change times and no record of an unlike, so
     * the sync carries what was liked here and lets the journal work out what changed. Kept whether or not sync is on: it is a local
     * file, and what was liked before sync was turned on goes up with the first sync.
     */
    private val likes: LikeFile by lazy { LikeFile(File(folder, "likes.json")) }

    private val account: CloudAccount by lazy {
        CloudAccount(
            config = CosmotuneFirebase.config,
            client = Http.client,
            sessionStore = EncryptedSessionStore(app),
            source = LibrarySource(SnapshotJournal(File(folder, "journal.json"))),
            marks = FileSyncMarks(File(folder, "marks.json")),
            kinds = listOf(SyncPlaylists.KIND, SyncLikes.KIND, SyncArtists.KIND, com.cosmotune.watch.SyncWatchPlugins.KIND, com.cosmotune.watch.SyncWatchLibrary.KIND),
            scope = scope,
        )
    }

    private val _status = MutableStateFlow(CloudStatus())

    /** What the settings row shows; a guest with no sync until the account has been opened. */
    val status: StateFlow<CloudStatus> = _status.asStateFlow()

    private val _enabled = MutableStateFlow(false)

    /** Whether cloud sync is on. While it is off, the settings page offers to turn it on and nothing else. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The only way an automatic sync reaches the account, so with sync off the account is never even built. */
    private val autoSync = CloudAutoSync(
        enabled = { _enabled.value },
        online = ::online,
        sync = { quietMs -> if (quietMs == null) scope.launch { account.syncNow() } else account.requestSync(quietMs) },
    )

    /** The followed artists as the sync last saw them, so its own write into the store does not ask for another sync. */
    @Volatile private var lastSeenArtists: Map<String, JsonObject>? = null

    /** From the Application, once [LocalPlaylistStore] is open. */
    fun install(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        scope.launch {
            var previous: Map<String, JsonObject>? = null
            com.cosmotune.music.watch.AndroidWatch.store(app).state.collect { state ->
                val current = com.cosmotune.watch.SyncWatchLibrary.changeSignal(state) +
                    com.cosmotune.watch.SyncWatchPlugins.snapshot(state)
                if (previous != null && previous != current) autoSync.changed()
                previous = current
            }
        }
        scope.launch { com.cosmotune.watch.WatchSyncSignals.changes.collect { autoSync.changed() } }
        SyncLikes.sourceTrack = SourceLibraryStore::ownsTrack
        scope.launch {
            _enabled.value = flags.getBoolean(KEY_ENABLED, false)
            // Likes that came from other devices (or were made here) in an earlier run; a rating changed in this session still wins.
            LikeState.seedLiked(likes.likes.value.keys.filterTo(HashSet(), SyncLikes::travels))
            // The account's status is followed only once sync is on: reading it is what would build the account.
            _enabled.first { it }
            account.status.collect { _status.value = it }
        }
        scope.launch {
            delay(FIRST_SYNC_DELAY_MS)
            autoSync.due()
        }
        // An edit is sent after a quiet spell (see CloudAccount.requestSync), so renaming a playlist and then adding three songs to
        // it is one sync. The first value is the store as it opened, which the first sync above already covers.
        scope.launch {
            LocalPlaylistStore.playlists.drop(1).collect { playlists ->
                if (playlists !== LocalPlaylistStore.lastCloudWrite) autoSync.changed()
            }
        }
        scope.launch {
            likes.likes.drop(1).collect { current -> if (current !== likes.lastCloudWrite) autoSync.changed() }
        }
        // Any listening-store save (a saved queue, a smart playlist, a follow) bumps this; only a change to the followed artists syncs.
        scope.launch {
            ListeningStore.saves.drop(1).collect {
                val seen = lastSeenArtists ?: return@collect
                if (artistSnapshot() != seen) autoSync.changed()
            }
        }
        watchNetwork()
    }

    /**
     * Sends what waited for the network: an edit made offline, or a sync that failed for want of a connection. Debounced by
     * [CloudAccount.requestSync], so a network that flaps while the phone moves between Wi-Fi and mobile data is one sync.
     */
    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // The mirrored status, not the account's: with sync off the account must not be built.
                val status = _status.value
                autoSync.networkBack(lastSyncFailed = status.lastError != null && !status.needsSignIn)
            }
        }
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback) }
            .onFailure { DebugLog.w(TAG, "cloud sync: no network callback ($it)") }
    }

    private fun setEnabled(value: Boolean) {
        _enabled.value = value
        flags.edit().putBoolean(KEY_ENABLED, value).apply()
        if (!value) autoSync.stopped()
    }

    /**
     * A rating YouTube accepted, from [com.cosmotune.music.ui.MainViewModel.setLike]. A like is kept for the sync; any other rating
     * takes the song out of the likes (a dislike itself does not travel). Source ratings and local files are not synced.
     */
    fun rememberRating(videoId: String, status: LikeStatus, song: Song?) {
        if (!this::app.isInitialized || !SyncLikes.travels(videoId)) return
        scope.launch {
            if (status == LikeStatus.LIKE) {
                likes.like(videoId, song?.takeIf { it.videoId == videoId }?.let(SyncLikes::data) ?: JsonObject(emptyMap()))
            } else {
                likes.unlike(videoId)
            }
        }
    }

    /**
     * Who this device is to Firebase, for the rooms, without turning library sync on. The same session manager the account syncs
     * with, so turning sync on later (or signing in with Google) keeps the room identity, and a Google sign-in names the person in a
     * room. Building it makes no network call and sends nothing: only [CloudSessionManager.ensureSession] does, and with sync off
     * nothing else here reaches the account. Off the main thread, since the encrypted store behind it is a Keystore round trip.
     */
    suspend fun sessions(): CloudSessionManager = withContext(Dispatchers.IO) { account.sessions }

    /** "Turn on cloud sync": the person chose it, so the first sync (as a guest, until they sign in) starts now. */
    fun turnOn() {
        scope.launch {
            setEnabled(true)
            account.syncNow()
        }
    }

    fun syncNow() {
        if (!_enabled.value) return
        scope.launch { account.syncNow() }
    }

    /** Signs in and syncs. Returns what to tell the person when sign-in failed, or null; a failed sync shows on the row instead. */
    @StringRes
    suspend fun signInWithGoogle(context: Context): Int? {
        val token = try {
            GoogleCloudSignIn.idToken(context)
        } catch (failure: GoogleCloudSignIn.Failure) {
            DebugLog.w(TAG, "Google sign-in: ${failure.cause ?: failure}")
            return failure.messageRes
        }
        val wasEnabled = _enabled.value
        // Signing in is choosing sync; if it does not go through, sync stays as it was.
        val result = withContext(Dispatchers.IO) {
            setEnabled(true)
            account.signInWithGoogle(token)
        }
        val failure = result.exceptionOrNull() ?: return null
        // signInWithGoogle also syncs; if only that part failed, the person is signed in and the row says what went wrong.
        if (account.status.value.let { it.signedIn && !it.isGuest }) return null
        if (!wasEnabled) withContext(Dispatchers.IO) { setEnabled(false) }
        DebugLog.w(TAG, "cloud sign-in: $failure")
        return if (failure is IOException) R.string.cloud_sign_in_offline else R.string.cloud_sign_in_failed
    }

    /** Forgets the session and turns sync off: a new guest would otherwise be made and the library sent to it unasked. */
    fun signOut() {
        scope.launch {
            setEnabled(false)
            account.cancelPendingSync()
            account.signOut()
            GoogleCloudSignIn.forget(app)
        }
    }

    /** Deletes the cloud copy and the account, and turns sync off. Returns what to tell the person. */
    @StringRes
    suspend fun deleteAccount(): Int = withContext(Dispatchers.IO) {
        account.cancelPendingSync()
        account.deleteAccount().fold(
            onSuccess = {
                setEnabled(false)
                GoogleCloudSignIn.forget(app)
                R.string.cloud_deleted
            },
            onFailure = {
                DebugLog.w(TAG, "cloud account delete: $it")
                R.string.cloud_delete_failed
            },
        )
    }

    private fun online(): Boolean = runCatching {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** The followed artists as entries. A store that cannot be read fails the sync rather than reading as "every artist unfollowed". */
    private fun artistSnapshot(): Map<String, JsonObject> =
        SyncArtists.snapshot(ListeningStore(listeningFile).state.value.artists).also { lastSeenArtists = it }

    /**
     * The library as the sync sees it: playlists, likes made here and followed artists. The journal works out what changed, since
     * none of these stores keeps edit times of its own.
     */
    private class LibrarySource(journal: SnapshotJournal) : JournalSyncSource(journal) {
        override suspend fun snapshot(kind: String): Map<String, JsonObject> = when (kind) {
            SyncPlaylists.KIND -> CloudPlaylists.snapshot(LocalPlaylistStore.playlists.value)
            SyncLikes.KIND -> CloudLikes.snapshot(likes.likes.value)
            SyncArtists.KIND -> artistSnapshot()
            // Watch's add-ons: manifest URLs and switches only, never servers or sign-ins.
            com.cosmotune.watch.SyncWatchPlugins.KIND -> com.cosmotune.watch.SyncWatchPlugins.snapshot(com.cosmotune.music.watch.AndroidWatch.store(app).state.value)
            com.cosmotune.watch.SyncWatchLibrary.KIND -> com.cosmotune.watch.SyncWatchLibrary.snapshot(com.cosmotune.music.watch.AndroidWatch.store(app).state.value)
            else -> emptyMap()
        }

        override suspend fun write(kind: String, upserts: Map<String, JsonObject>, deletes: Set<String>) {
            if (upserts.isEmpty() && deletes.isEmpty()) return
            when (kind) {
                SyncPlaylists.KIND -> LocalPlaylistStore.applyFromCloud { CloudPlaylists.merge(it, upserts, deletes) }
                SyncLikes.KIND -> {
                    // Shown as liked straight away; YouTube is not asked to rate them, the like was already sent from where it was made.
                    val applied = CloudLikes.travelling(upserts, deletes)
                    likes.applyFromCloud(applied.first, applied.second)
                    CloudLikes.overrides(applied.first.keys, applied.second, LikeState.overrides.value).forEach(LikeState::set)
                }
                SyncArtists.KIND -> ListeningStore(listeningFile).update { SyncArtists.apply(it, upserts, deletes) }
                com.cosmotune.watch.SyncWatchPlugins.KIND ->
                    com.cosmotune.music.watch.AndroidWatch.store(app).update { com.cosmotune.watch.SyncWatchPlugins.apply(it, upserts, deletes) }
                com.cosmotune.watch.SyncWatchLibrary.KIND ->
                    com.cosmotune.music.watch.AndroidWatch.store(app).update { com.cosmotune.watch.SyncWatchLibrary.apply(it, upserts, deletes) }
            }
        }
    }

    /**
     * The session in encrypted preferences, like the other credentials: its refresh token signs this device in for good. Excluded
     * from backups (backup_rules.xml), since a restored copy could not be decrypted anyway.
     */
    private class EncryptedSessionStore(context: Context) : CloudSessionStore {
        private val prefs: SharedPreferences = EncryptedPrefs.open(context, "cosmotune_cloud", "cosmotune_cloud_plain")
        private val json = Json { ignoreUnknownKeys = true }

        override fun load(): CloudSession? =
            prefs.getString(KEY_SESSION, null)?.let { runCatching { json.decodeFromString<CloudSession>(it) }.getOrNull() }

        override fun save(session: CloudSession?) {
            // commit, not apply: a renewed refresh token lost to a process death would sign a Google user out.
            val edit = prefs.edit()
            if (session == null) edit.remove(KEY_SESSION) else edit.putString(KEY_SESSION, json.encodeToString(session))
            edit.commit()
        }

        private companion object {
            const val KEY_SESSION = "session_json"
        }
    }
}
