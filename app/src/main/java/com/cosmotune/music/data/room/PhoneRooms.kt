package com.cosmotune.music.data.room

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.cosmotune.cloud.CloudSessionManager
import com.cosmotune.cloud.CosmotuneFirebase
import com.cosmotune.music.R
import com.cosmotune.music.data.DebugLog
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.cloud.AndroidCloud
import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.listentogether.PartyRoute
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.ServerClock
import com.cosmotune.music.data.room.voice.AndroidMicAccess
import com.cosmotune.music.data.room.voice.AndroidVoiceEngine
import com.cosmotune.room.RoomClient
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomControl
import com.cosmotune.room.RoomLogic
import com.cosmotune.room.RoomPreview
import com.cosmotune.room.RoomProfile
import com.cosmotune.room.RoomState
import com.cosmotune.room.RtdbRest
import com.cosmotune.room.voice.RoomVoice
import com.cosmotune.room.voice.RoomVoiceUi
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The phone's side of a room on the Realtime Database: one [RoomClient], the identity it joins as, and the bridge that hands the
 * room to [ListenTogether] as a [PartyRoute] so the player follows it exactly as it followed a party on the server.
 *
 * A room needs a Firebase identity (a guest, or the person's Google sign-in) but never turns library sync on: the session comes
 * from [AndroidCloud.sessions], which syncs nothing until the person chooses to. Nothing here is built, and nothing is sent, until
 * somebody creates, looks up or joins a room.
 */
object PhoneRooms {
    private const val TAG = "Cosmotune"
    private const val PREFS = "cosmotune_rooms"
    private const val KEY_DEVICE = "device_id"

    /** How long a control waits for its own echo before the next one is sent; see [sendControls]. */
    private const val ECHO_WAIT_MS = 1_500L

    private lateinit var app: Context
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> DebugLog.w(TAG, "rooms: $failure") },
    )

    private val buildLock = Mutex()
    @Volatile private var built: Pair<RoomClient, CloudSessionManager>? = null

    /** What last went wrong and what the person has read on this device: none of it is shared with the room. */
    data class Local(
        val message: String? = null,
        /** The newest chat line the person has seen, so only newer ones from others count as unread. */
        val seenChatAtMs: Long = Long.MAX_VALUE,
    )

    private val _local = MutableStateFlow(Local())
    val local: StateFlow<Local> = _local.asStateFlow()

    @Volatile private var roomVoice: RoomVoice? = null
    private val voiceCalls = Channel<suspend (RoomVoice) -> Unit>(Channel.UNLIMITED)

    private val _voice = MutableStateFlow(RoomVoiceUi())
    /** Voice in the joined room as the room screen shows it; the microphone and deafen live here, not in [local]. */
    val voice: StateFlow<RoomVoiceUi> = _voice.asStateFlow()

    private val _room = MutableStateFlow<RoomState?>(null)
    /** The joined room, or null when not in one. */
    val room: StateFlow<RoomState?> = _room.asStateFlow()

    private val _connection = MutableStateFlow(RoomConnection.IDLE)
    val connection: StateFlow<RoomConnection> = _connection.asStateFlow()

    private val _code = MutableStateFlow<String?>(null)
    /** The code of the room this device is in, from the moment the join succeeds until it leaves; drives the room icon. */
    val code: StateFlow<String?> = _code.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Messages worth showing wherever the person is, such as a control the room refused while the room screen was closed. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private var followJob: Job? = null
    private val controls = Channel<RoomControl>(Channel.UNLIMITED)

    /** From the Application. Builds nothing and sends nothing. */
    fun install(context: Context) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        scope.launch { sendControls() }
        scope.launch {
            for (call in voiceCalls) {
                val voice = roomVoice ?: continue
                try {
                    call(voice)
                } catch (failure: Exception) {
                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                    DebugLog.w(TAG, "rooms: voice $failure")
                }
            }
        }
    }

    /** This account's id, or blank before the first room. */
    val selfUid: String get() = built?.second?.session?.value?.uid.orEmpty()

    /** The shared playhead now, from the server clock. */
    fun positionMs(): Long = built?.first?.positionMs() ?: 0L

    // ---- making, finding, joining, leaving ----

    suspend fun create(maxMembers: Int): Result<String> = withSession { client ->
        client.create(maxMembers).onSuccess { follow(client, it) }
    }

    /** Who is inside, to someone who has only the code; a code that names no room fails as NOT_FOUND. */
    suspend fun peek(typed: String): Result<RoomPreview> = withSession { client ->
        client.peek(typed).mapCatching {
            it ?: throw com.cosmotune.room.RoomException(com.cosmotune.room.RoomException.Reason.NOT_FOUND, "No room with that code")
        }
    }

    suspend fun join(typed: String): Result<String> = withSession { client ->
        client.join(typed).map { client.currentCode.orEmpty() }.onSuccess { follow(client, it) }
    }

    /** Gives up this device's seat. The room carries on for the others; the last one out takes it down. */
    suspend fun leave() {
        val client = built?.first ?: return
        unfollow()
        withContext(Dispatchers.IO) { client.leave() }
    }

    /** The short message for a failure, for a screen to show. */
    fun messageOf(failure: Throwable): String = app.getString(RoomPartyState.messageFor(failure))

    // ---- what the room screen does ----

    /** Queued and sent in order; see [sendControls]. */
    fun control(control: RoomControl) {
        controls.trySend(control)
    }

    fun sendChat(text: String) = launchGuarded { it.sendChat(text) }

    fun askToPlay(track: PartyTrack) = launchGuarded { it.askToPlay(track) }

    fun setHostOnlyControl(enabled: Boolean) = launchGuarded { it.setHostOnlyControl(enabled) }

    fun removeMember(uid: String) = launchGuarded { it.remove(uid) }

    // Voice, off the main thread. One queue keeps the taps in the order they were made (separate launches on the IO pool could
    // reach the voice out of order), so a volume slider's last position is the one that stays and a mute right after an unmute is
    // not overtaken by it; the first unmute waits there for the microphone permission dialog.

    fun setMicLive(live: Boolean) = voiceCall { it.setMicLive(live) }

    fun setDeafened(deafened: Boolean) = voiceCall { it.setDeafened(deafened) }

    fun setMemberMuted(uid: String, muted: Boolean) = voiceCall { it.setMemberMuted(uid, muted) }

    fun setMemberVolume(uid: String, volume: Float) = voiceCall { it.setMemberVolume(uid, volume) }

    fun openMicSettings() {
        roomVoice?.openMicSettings()
    }

    fun dismissVoiceMessage() {
        roomVoice?.dismissMessage()
    }

    /**
     * The app was swiped away from the recent apps while the microphone was live. The music may play on (and the room with it),
     * but nobody is on screen to see that they are still being heard, so the microphone goes off.
     */
    fun muteForTaskRemoved() = voiceCall { it.setMicLive(false) }

    private fun voiceCall(call: suspend (RoomVoice) -> Unit) {
        if (roomVoice != null) voiceCalls.trySend(call)
    }

    fun dismissMessage() = _local.update { it.copy(message = null) }

    /** Says [message] the way a refused control is said: in the room screen while it is open, wherever the person is otherwise. */
    fun notify(message: String) {
        _local.update { it.copy(message = message) }
        _notices.tryEmit(message)
    }

    fun chatSeen() {
        val newest = _room.value?.chat?.maxOfOrNull { it.atMs } ?: return
        _local.update { it.copy(seenChatAtMs = newest) }
    }

    // ---- internals ----

    /** The identity this device joins as. A Google sign-in names the person; a guest is "Guest" and a short, stable suffix. */
    private fun profile(sessions: CloudSessionManager): RoomProfile {
        val session = sessions.session.value
        val google = session?.takeIf { !it.isAnonymous }
        val nickname = runCatching { ListenTogether.nickname() }.getOrDefault("").trim()
        val name = nickname.ifBlank { google?.displayName?.trim().orEmpty() }
            .ifBlank { app.getString(R.string.room_guest_name, RoomPartyState.guestSuffix(deviceId())) }
        return RoomProfile(name = name.take(40), avatarUrl = google?.photoUrl, deviceId = deviceId())
    }

    private fun deviceId(): String = prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString(KEY_DEVICE, it).apply()
    }

    private suspend fun client(): Pair<RoomClient, CloudSessionManager> = built ?: buildLock.withLock {
        built ?: run {
            val sessions = AndroidCloud.sessions()
            val rtdb = RtdbRest(CosmotuneFirebase.config.databaseUrl, Http.client) { sessions.idToken() }
            val client = RoomClient(
                rtdb = rtdb,
                uid = { sessions.session.value?.uid ?: error("not signed in") },
                profile = { profile(sessions) },
                clock = ServerClock(SystemClock::elapsedRealtime),
                scope = scope,
            )
            // One for the process. It follows the client's active room: the engine is built at the first join, and the connections
            // and the microphone (with its foreground service) go when the room does, after leaving, ending or being removed.
            val voice = RoomVoice(
                rtdb = rtdb,
                myUid = { sessions.session.value?.uid.orEmpty() },
                engineFactory = { AndroidVoiceEngine(app) },
                room = client.activeRoom,
                publishMic = { live -> client.setMicLive(live) },
                mic = AndroidMicAccess(app),
                scope = scope,
            )
            roomVoice = voice
            scope.launch { voice.ui.collect { _voice.value = it } }
            voice.start()
            (client to sessions).also { built = it }
        }
    }

    /** Makes sure there is a session (a guest is made if there is none: the one network call before a room) and runs [block]. */
    private suspend fun <T> withSession(block: suspend (RoomClient) -> Result<T>): Result<T> = withContext(Dispatchers.IO) {
        val (client, sessions) = try {
            client()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            DebugLog.w(TAG, "rooms: no session store ($failure)")
            return@withContext Result.failure(failure)
        }
        try {
            sessions.ensureSession()
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            DebugLog.w(TAG, "rooms: sign-in failed ($failure)")
            return@withContext Result.failure(SignInFailed(failure))
        }
        block(client)
    }

    /** Firebase would not give this device an identity, so no room can be reached. */
    class SignInFailed(cause: Throwable) : Exception(cause.message, cause)

    private fun launchGuarded(call: suspend (RoomClient) -> Result<Unit>) {
        val client = built?.first ?: return
        scope.launch { call(client).onFailure(::fail) }
    }

    private fun fail(failure: Throwable, control: Boolean = false) {
        DebugLog.w(TAG, "rooms: $failure")
        val message = messageOf(failure)
        _local.update { it.copy(message = message) }
        _notices.tryEmit(message)
        // The player may already have acted on the tap; it goes back to the room at once rather than after its quiet window.
        if (control) ListenTogether.reportRefused()
    }

    /**
     * Controls in the order they were made, each after the last one's echo. One tap can be two controls (a new running order,
     * then the song in it), and a write is accepted only with the next sequence number: sent together, the second would be
     * reduced against the state before the first and either be refused or undo it.
     */
    private suspend fun sendControls() {
        for (next in controls) {
            val client = built?.first ?: continue
            val before = _room.value ?: continue
            // A control that changes nothing writes nothing, so there is no echo to wait for.
            val changes = RoomLogic.reduce(before.playback, before.queue, next, client.serverNowMs(), selfUid, "") != null
            val result = client.control(next)
            result.onFailure { fail(it, control = true) }
            if (result.isSuccess && changes) {
                withTimeoutOrNull(ECHO_WAIT_MS) {
                    _room.first { it == null || it.playback.seq > before.playback.seq || it.queue.seq > before.queue.seq }
                }
            }
        }
    }

    /** Mirrors the room into [ListenTogether], which is what the player and the party screens read. */
    private fun follow(client: RoomClient, joined: String) {
        followJob?.cancel()
        _code.value = joined
        _local.value = Local()
        var attached = false
        // The clock is sampled on the client's own schedule and has no flow of its own; reading it twice a second is free.
        val clockTicks = flow {
            while (true) {
                emit(client.clock.synced to client.clock.roundTripMs)
                delay(500)
            }
        }.distinctUntilChanged()
        followJob = scope.launch {
            combine(client.room, client.connection, clockTicks) { room, connection, clock -> Triple(room, connection, clock) }
                .collect { (room, connection, clock) ->
                    _room.value = room
                    _connection.value = connection
                    if (connection == RoomConnection.ENDED || connection == RoomConnection.REMOVED) {
                        ended(client, connection)
                        return@collect
                    }
                    // Only chat that arrives after joining counts as unread; what was said before is history.
                    if (room != null && _local.value.seenChatAtMs == Long.MAX_VALUE) {
                        _local.update { it.copy(seenChatAtMs = room.chat.maxOfOrNull { m -> m.atMs } ?: 0L) }
                    }
                    val snapshot = client.snapshot.value ?: return@collect
                    val state = RoomPartyState.of(
                        snapshot = snapshot,
                        selfUid = selfUid,
                        connection = connection,
                        clockSynced = clock.first,
                        roundTripMs = clock.second,
                        serverNowMs = client.serverNowMs(),
                    )
                    if (!attached) {
                        attached = true
                        ListenTogether.attachRoom(route, state)
                    } else {
                        ListenTogether.showRoom(state)
                    }
                }
        }
    }

    private fun ended(client: RoomClient, how: RoomConnection) {
        val message = app.getString(if (how == RoomConnection.REMOVED) R.string.room_removed else R.string.room_ended)
        unfollow()
        client.close()
        _local.update { it.copy(message = message) }
        _notices.tryEmit(message)
    }

    private fun unfollow() {
        followJob?.cancel()
        followJob = null
        _code.value = null
        _room.value = null
        _connection.value = RoomConnection.IDLE
        ListenTogether.detachRoom()
    }

    /** The room as the player integration drives it. */
    private val route = object : PartyRoute {
        override fun control(control: RoomControl) = PhoneRooms.control(control)
        override fun setHostOnlyControl(enabled: Boolean) = PhoneRooms.setHostOnlyControl(enabled)
        override fun setAutoplay(enabled: Boolean) = launchGuarded { it.setAutoplay(enabled) }
        override fun setMaxMembers(value: Int) = launchGuarded { it.setMaxMembers(value) }
        override fun kick(memberId: String) = removeMember(memberId)
        override suspend fun leave() = PhoneRooms.leave()
        override fun serverNowMs(): Long? = built?.first?.clock?.serverNowMs()
    }
}
