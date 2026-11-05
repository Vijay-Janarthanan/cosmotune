package com.cosmotune.music.desktop

import com.cosmotune.cloud.CosmotuneFirebase
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.listentogether.JamInvite
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.ServerClock
import com.cosmotune.room.RoomClient
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomControl
import com.cosmotune.room.RoomPreview
import com.cosmotune.room.RoomProfile
import com.cosmotune.room.RoomState
import com.cosmotune.room.RtdbRest
import com.cosmotune.room.voice.RoomVoice
import com.cosmotune.room.voice.RoomVoiceUi
import com.cosmotune.ui.room.RoomActions
import com.cosmotune.ui.room.RoomUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * This device in a room on the Realtime Database: making one, finding and joining one by its code, leaving, and the room's chat and
 * host settings. Rooms need a Firebase identity (a guest, or the Google account signed in for sync) but never turn library sync on.
 *
 * While a room is joined it is attached to [DesktopListenTogether] and its state mirrored there, so the party player code already in
 * the window ([DesktopPartySync], the queue and transport gestures) follows the room as it followed the party server.
 */
internal object DesktopRooms {
    private const val KEY_DEVICE = "room_device_id"
    const val DEFAULT_SIZE = 5
    const val MIN_SIZE = 2
    const val MAX_SIZE = 8

    private const val KEY_MIC_HINT = "room_voice_mic_hint_shown"

    /** What only this device knows about its place in the room. The mic and deafen are the voice's own ([voiceUi]). */
    data class Local(
        val message: String? = null,
        val chatSeenAtMs: Long = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistence by lazy { DesktopPersistence() }
    private val sessions get() = DesktopCloud.sessions
    @Volatile private var deviceIdCache: String? = null

    private val rtdb: RtdbRest by lazy { RtdbRest(CosmotuneFirebase.config.databaseUrl, Http.client) { sessions.idToken() } }

    private val client: RoomClient by lazy {
        RoomClient(
            rtdb = rtdb,
            uid = ::selfUid,
            profile = ::profile,
            clock = ServerClock(),
            scope = scope,
        )
    }

    /**
     * The room voice follows: the joined room, and nothing once it ended or this device was removed (the client keeps a removed
     * device's last view of the room for its screen, but the voice must let go of the others then, not at Leave).
     */
    private val voiceRoom: StateFlow<RoomState?> by lazy {
        combine(client.room, client.connection) { room, connection ->
            room.takeIf { connection != RoomConnection.ENDED && connection != RoomConnection.REMOVED }
        }.stateIn(scope, SharingStarted.Eagerly, null)
    }

    /** One per app run. The engine is only built when a room is joined, and the voice ends with the room and with the app. */
    private val voice: RoomVoice by lazy {
        RoomVoice(
            rtdb = rtdb,
            myUid = ::selfUid,
            engineFactory = { DesktopRoomVoice.engine() },
            room = voiceRoom,
            publishMic = { live -> client.setMicLive(live).onFailure { log("could not tell the room about the microphone", it) } },
            mic = DesktopMicAccess(),
            scope = scope,
        ).also { made ->
            made.start()
            // Both the window's close and the tray's Quit end the process this way; the engine waits for its connections at most 2 s.
            Runtime.getRuntime().addShutdownHook(
                Thread({ runBlocking { withTimeoutOrNull(3_000) { made.close() } } }, "cosmotune-room-voice-close"),
            )
        }
    }

    private val micHint by lazy {
        DesktopMicHint(
            windows = DesktopPlatform.isWindows,
            shown = { runCatching { persistence.boolean(KEY_MIC_HINT, false) }.getOrDefault(false) },
            markShown = { runCatching { persistence.saveBoolean(KEY_MIC_HINT, true) } },
        )
    }

    /** Voice calls go out one at a time and in order, so a quick unmute then mute ends muted. */
    private val voiceCalls = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val voiceWorker: Job by lazy {
        scope.launch {
            for (call in voiceCalls) {
                try {
                    call()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    log("a voice action failed", failure)
                }
            }
        }
    }

    /** The latest volume asked for each person and not yet applied: a moving slider queues one call, which takes the newest value. */
    private val pendingVolumes = ConcurrentHashMap<String, Float>()

    private val joinedState = MutableStateFlow(false)

    /** From a create or a join until Leave; a room that ended stays "joined" so its screen can say so until it is left. */
    val joined: StateFlow<Boolean> = joinedState.asStateFlow()

    private val localState = MutableStateFlow(Local())
    val local: StateFlow<Local> = localState.asStateFlow()

    val room: StateFlow<RoomState?> get() = client.room
    val connection: StateFlow<RoomConnection> get() = client.connection
    val voiceUi: StateFlow<RoomVoiceUi> get() = voice.ui
    val code: String? get() = if (joinedState.value) client.currentCode else null

    private val summaryState = MutableStateFlow(DesktopRoomPlayback.LauncherSummary())

    /** What every page's room icon and "Play in room" read; kept up to date while a room is joined, and empty otherwise. */
    val summary: StateFlow<DesktopRoomPlayback.LauncherSummary> = summaryState.asStateFlow()

    private val pendingState = MutableStateFlow<PartyTrack?>(null)

    /** An item "Play in room" chose before there was a room: it plays for everyone as soon as this device makes or joins one. */
    val pendingItem: StateFlow<PartyTrack?> = pendingState.asStateFlow()

    /** The item a room this device just entered is being started with, until the room says it is playing something. */
    @Volatile private var startingItem: PartyTrack? = null

    /** Whether a room just made here is about to start with the person's own item, so the music playing is not seeded into it. */
    val startingWithItem: Boolean get() = startingItem != null

    @Volatile private var viewVisible = false
    private var mirrorJob: Job? = null

    /** Controls go out one at a time and in order: a queue followed by its track must land in that order. */
    private val outbox = Channel<RoomControl>(Channel.UNLIMITED)
    private val sender: Job by lazy {
        scope.launch {
            for (control in outbox) client.control(control).onFailure(::report)
        }
    }

    fun selfUid(): String = sessions.session.value?.uid.orEmpty()

    /** Where the shared playhead is now; 0 outside a room. */
    fun positionMs(): Long = if (joinedState.value) client.positionMs() ?: 0 else 0

    /** The room screen says when it is open, so a failure is shown there rather than as a toast as well. */
    fun setViewVisible(visible: Boolean) {
        viewVisible = visible
    }

    // ---- making, finding, joining, leaving ----

    /** Makes a room and joins it; [autoplay] carries the person's own AutoPlay setting into it, as a new party did. */
    suspend fun create(maxMembers: Int = DEFAULT_SIZE, autoplay: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        refuseIfBusy()?.let { return@withContext Result.failure(it) }
        signIn()?.let { return@withContext Result.failure(it) }
        client.create(maxMembers.coerceIn(MIN_SIZE, MAX_SIZE))
            .onSuccess {
                enter()
                if (autoplay) client.setAutoplay(true).onFailure { failure -> log("could not turn AutoPlay on in the room", failure) }
            }
            .onFailure { log("could not make a room", it) }
    }

    /** Who is inside, before joining; null when there is no room with that code. */
    suspend fun peek(typedCode: String): Result<RoomPreview?> = withContext(Dispatchers.IO) {
        signIn()?.let { return@withContext Result.failure(it) }
        client.peek(typedCode).onFailure { log("could not look up a room", it) }
    }

    suspend fun join(typedCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        refuseIfBusy()?.let { return@withContext Result.failure(it) }
        signIn()?.let { return@withContext Result.failure(it) }
        client.join(typedCode).onSuccess { enter() }.onFailure { log("could not join a room", it) }
    }

    suspend fun leave() = withContext(Dispatchers.IO) {
        mirrorJob?.cancel()
        mirrorJob = null
        DesktopListenTogether.detachRoom()
        joinedState.value = false
        localState.value = Local()
        summaryState.value = DesktopRoomPlayback.LauncherSummary()
        pendingState.value = null
        startingItem = null
        pendingVolumes.clear()
        client.leave() // the room goes null, and with it the voice: connections and the microphone are released
    }

    /** Keeps [item] to play once a room is made or joined here ("Play in room" with no room yet). */
    fun startWhenJoined(item: PartyTrack) {
        pendingState.value = item
    }

    /** The make-or-join panel closed without a room: the chosen item is forgotten. */
    fun cancelPendingStart() {
        pendingState.value = null
    }

    // ---- the room's music, chat and settings ----

    fun control(control: RoomControl) {
        sender.start()
        outbox.trySend(control)
    }

    fun sendChat(text: String) = launchReported { client.sendChat(text) }

    fun askToPlay(track: PartyTrack) = launchReported { client.askToPlay(track) }

    fun setHostOnlyControl(enabled: Boolean) = launchReported { client.setHostOnlyControl(enabled) }

    fun removeMember(uid: String) = launchReported { client.remove(uid) }

    /** The first unmute that goes live on Windows also says, once, where its microphone privacy setting is. */
    fun setMicLive(live: Boolean) = voiceCall {
        voice.setMicLive(live)
        if (live) micHint.afterUnmute(voice.ui.value.micLive)?.let { hint -> localState.update { it.copy(message = hint) } }
    }

    fun setDeafened(deafened: Boolean) = voiceCall { voice.setDeafened(deafened) }

    fun setMemberMuted(uid: String, muted: Boolean) = voiceCall { voice.setMemberMuted(uid, muted) }

    fun setMemberVolume(uid: String, volume: Float) {
        if (pendingVolumes.put(uid, volume) != null) return // the call already queued for this person will carry it
        voiceCall { pendingVolumes.remove(uid)?.let { voice.setMemberVolume(uid, it) } }
    }

    fun openMicSettings() {
        scope.launch { voice.openMicSettings() }
    }

    fun dismissVoiceMessage() = voice.dismissMessage()

    fun dismissMessage() = localState.update { it.copy(message = null) }

    fun chatSeen() {
        val latest = client.room.value?.chat?.maxOfOrNull { it.atMs } ?: return
        localState.update { it.copy(chatSeenAtMs = maxOf(it.chatSeenAtMs, latest)) }
        refreshSummary(client.connection.value)
    }

    fun inviteUrl(code: String): String = JamInvite.url(code)

    /** The screen's state from the room, the connection, this device's own choices, its voice and the playhead read just now. */
    fun uiState(room: RoomState?, connection: RoomConnection, local: Local, voice: RoomVoiceUi, positionMs: Long): RoomUiState {
        val self = selfUid()
        return DesktopRoomVoice.withVoice(
            RoomUiState(
                room = room,
                connection = connection,
                selfUid = self,
                positionMs = positionMs,
                message = local.message,
                unreadChat = room?.let { DesktopRoomPlayback.unreadCount(it.chat, self, local.chatSeenAtMs) } ?: 0,
            ),
            voice,
        )
    }

    // ---- internals ----

    /** The link [DesktopListenTogether] calls while a room is joined. */
    private val link = object : DesktopListenTogether.Room {
        override fun send(control: RoomControl) = this@DesktopRooms.control(control)

        override fun positionMs(): Long? = client.positionMs()

        override fun msUntilStart(): Long {
            val playback = client.room.value?.playback ?: return 0
            if (!playback.isPlaying || !client.clock.synced) return 0
            return (playback.anchorMs - client.serverNowMs()).coerceAtLeast(0)
        }

        override fun setHostOnlyControl(enabled: Boolean) = this@DesktopRooms.setHostOnlyControl(enabled)

        override fun setAutoplay(enabled: Boolean) = launchReported { client.setAutoplay(enabled) }

        override fun setMaxMembers(value: Int) = launchReported { client.setMaxMembers(value.coerceIn(MIN_SIZE, MAX_SIZE)) }

        override fun remove(memberId: String) = removeMember(memberId)

        override suspend fun leave() = this@DesktopRooms.leave()
    }

    private fun enter() {
        // What was said before this device came in is not news to it.
        localState.value = Local(chatSeenAtMs = client.serverNowMs())
        voice.start() // made by the first room and following it since; it builds the engine itself once the room is there
        joinedState.value = true
        DesktopListenTogether.attachRoom(link)
        refreshSummary(client.connection.value)
        // "Play in room" before there was a room: the item it chose is the first thing the room plays.
        pendingState.value?.let { item ->
            pendingState.value = null
            startingItem = item
            control(RoomControl.SetTrack(item))
        }
        mirrorJob?.cancel()
        // A tick as well as the room's own changes: the clock settles between them, and the player waits for it before seeking.
        val ticks = flow {
            while (true) {
                emit(Unit)
                delay(1_000)
            }
        }
        mirrorJob = scope.launch {
            combine(client.snapshot, client.connection, ticks) { snapshot, connection, _ -> snapshot to connection }.collect { (snapshot, connection) ->
                refreshSummary(connection)
                if (snapshot?.playback?.track != null) startingItem = null
                if (connection == RoomConnection.ENDED || connection == RoomConnection.REMOVED) {
                    // The screen stays up to say so; the player goes back to the person's own queue now.
                    if (DesktopListenTogether.inRoom) {
                        DesktopTrackLog.log("room: ${if (connection == RoomConnection.ENDED) "the room ended" else "removed from the room"}")
                        DesktopListenTogether.detachRoom()
                    }
                    return@collect
                }
                DesktopListenTogether.mirrorRoom(
                    DesktopRoomPlayback.mirrorOf(snapshot, client.currentCode, connection, selfUid(), client.clock.synced, client.clock.roundTripMs),
                )
            }
        }
    }

    private fun refreshSummary(connection: RoomConnection) {
        summaryState.value = DesktopRoomPlayback.launcherSummary(joinedState.value, client.room.value, connection, selfUid(), localState.value.chatSeenAtMs)
    }

    private fun refuseIfBusy(): Throwable? = when {
        joinedState.value -> IllegalStateException("Leave the room you are in first.")
        DesktopListenTogether.state.value.inParty -> IllegalStateException("Leave the party you are in first.")
        else -> null
    }

    /** A guest is made on first use (no screen, nothing asked); a Google sign-in from Account & sync is used as it is. */
    private suspend fun signIn(): Throwable? = try {
        sessions.ensureSession()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        log("could not sign in for rooms", failure)
        failure
    }

    private fun profile(): RoomProfile {
        val google = sessions.session.value?.takeIf { !it.isAnonymous }
        val name = google?.displayName?.takeIf(String::isNotBlank)
            ?: google?.email?.substringBefore('@')?.takeIf(String::isNotBlank)
            ?: DesktopRoomPlayback.guestName(deviceId())
        return RoomProfile(name = name, avatarUrl = google?.photoUrl?.takeIf { it.startsWith("http") }, deviceId = deviceId())
    }

    /** This installation, remembered so a rejoin takes the same seat. */
    private fun deviceId(): String {
        deviceIdCache?.let { return it }
        val stored = runCatching { persistence.string(KEY_DEVICE) }.getOrDefault("")
        val id = stored.ifBlank { UUID.randomUUID().toString().also { fresh -> runCatching { persistence.saveString(KEY_DEVICE, fresh) } } }
        deviceIdCache = id
        return id
    }

    private fun voiceCall(call: suspend () -> Unit) {
        voiceWorker.start()
        voiceCalls.trySend(call)
    }

    private fun launchReported(block: suspend () -> Result<Unit>) {
        scope.launch { block().onFailure(::report) }
    }

    /** A failed room action: on the room screen when it is open, otherwise as the player's short message. */
    private fun report(failure: Throwable) {
        val message = DesktopRoomPlayback.errorMessage(failure)
        log("a room action failed", failure)
        // The item a new room was to start with did not go out: the room is free to take the music playing instead.
        startingItem = null
        localState.update { it.copy(message = message) }
        if (!viewVisible) DesktopPlayerHost.showMessage(message)
    }

    private fun log(what: String, failure: Throwable) {
        DesktopTrackLog.log("room: $what (${failure.javaClass.simpleName}: ${failure.message?.take(160)})")
    }
}

/**
 * What the room screen's buttons do on the desktop. Each is a call into [DesktopRooms]; the player follows the room by itself.
 * [openPlaybackOptions] opens this device's own options for the item (Video's quality, captions and speed for a video).
 */
internal class DesktopRoomActions(
    private val openSongPicker: () -> Unit,
    private val playbackOptions: () -> Unit = {},
) : RoomActions {
    override fun openPlaybackOptions() = playbackOptions()
    override fun play() = DesktopRooms.control(RoomControl.Play())
    override fun pause() = DesktopRooms.control(RoomControl.Pause())
    override fun seekTo(positionMs: Long) = DesktopRooms.control(RoomControl.Seek(positionMs))
    override fun next() = DesktopRooms.control(RoomControl.Next)
    override fun previous() = DesktopRooms.control(RoomControl.Previous)
    override fun playTrack(track: PartyTrack) = DesktopRooms.control(RoomControl.SetTrack(track))
    override fun removeFromQueue(videoId: String) = DesktopRooms.control(RoomControl.QueueRemove(videoId))
    override fun clearQueue() = DesktopRooms.control(RoomControl.QueueClear)
    override fun sendChat(text: String) = DesktopRooms.sendChat(text)
    override fun askToPlay() = openSongPicker()
    override fun setMicLive(live: Boolean) = DesktopRooms.setMicLive(live)
    override fun setDeafened(deafened: Boolean) = DesktopRooms.setDeafened(deafened)
    override fun setMemberMuted(uid: String, muted: Boolean) = DesktopRooms.setMemberMuted(uid, muted)
    override fun setMemberVolume(uid: String, volume: Float) = DesktopRooms.setMemberVolume(uid, volume)
    override fun openMicSettings() = DesktopRooms.openMicSettings()
    override fun dismissVoiceMessage() = DesktopRooms.dismissVoiceMessage()
    override fun setHostOnlyControl(enabled: Boolean) = DesktopRooms.setHostOnlyControl(enabled)
    override fun removeMember(uid: String) = DesktopRooms.removeMember(uid)

    override fun copyInviteCode(code: String) {
        DesktopExternalLinks.copy(code)
            .onSuccess { DesktopPlayerHost.showMessage("Room code copied") }
            .onFailure { DesktopPlayerHost.showMessage("Could not copy the code") }
    }

    override fun shareInvite(code: String) {
        DesktopExternalLinks.copy(DesktopRooms.inviteUrl(code))
            .onSuccess { DesktopPlayerHost.showMessage("Invite link copied") }
            .onFailure { DesktopPlayerHost.showMessage("Could not copy the invite link") }
    }

    override fun leave() {
        roomScope.launch { DesktopRooms.leave() }
    }

    override fun dismissMessage() = DesktopRooms.dismissMessage()
    override fun chatSeen() = DesktopRooms.chatSeen()

    private companion object {
        val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
