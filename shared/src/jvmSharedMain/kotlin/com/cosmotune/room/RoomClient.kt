package com.cosmotune.room

import com.cosmotune.cloud.CloudException
import com.cosmotune.music.data.listentogether.PartySnapshot
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.ServerClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.SecureRandom

/** Six characters people can read aloud: no 0, 1, O, I or L. */
object RoomCode {
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun generate(random: java.util.Random = SecureRandom()): String = String(CharArray(6) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** What someone typed, read charitably: case and spaces and dashes do not matter. */
    fun normalize(typed: String): String = typed.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(code: String): Boolean = code.length == 6 && code.all { it in ALPHABET }
}

/**
 * One device's side of a room, on the Realtime Database. It joins or makes a room, keeps one live stream of it, sends the controls
 * and the chat, and reports presence. The music itself is never here: each device plays its own copy and follows the shared clock.
 *
 * The room is rebuilt from one stream (a member may read the whole room at its path), so a device holds one connection however big the
 * room is. A write that loses a race for the next sequence number is refused by the database's rules; it is retried against the
 * newer state that the stream has by then delivered.
 */
class RoomClient(
    private val rtdb: RtdbApi,
    private val uid: () -> String,
    private val profile: () -> RoomProfile,
    val clock: ServerClock,
    private val scope: CoroutineScope,
    private val wallNowMs: () -> Long = System::currentTimeMillis,
    private val random: java.util.Random = SecureRandom(),
) {
    private val roomState = MutableStateFlow<RoomState?>(null)
    private val connectionState = MutableStateFlow(RoomConnection.IDLE)

    /** The joined room, or null when not in one. */
    val room: StateFlow<RoomState?> = roomState.asStateFlow()
    val connection: StateFlow<RoomConnection> = connectionState.asStateFlow()

    /**
     * The joined room while it is live: null once it has ended or this device was removed. [room] keeps the last room in those two cases
     * so the screen can say what happened, but things that must stop with the room (voice) follow this instead.
     */
    val activeRoom: StateFlow<RoomState?> = combine(roomState, connectionState) { current, link ->
        if (link == RoomConnection.ENDED || link == RoomConnection.REMOVED) null else current
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The same room in the shape the existing Listen Together screens and player code read. */
    val snapshot: StateFlow<PartySnapshot?> = roomState.map { it?.let(RoomMapping::snapshotOf) }.stateIn(scope, SharingStarted.Eagerly, null)

    private var code: String? = null
    private var seat: Int = -1
    private var streamJob: Job? = null
    private var heartbeatJob: Job? = null

    val currentCode: String? get() = code

    /** Whether the clock has a sample yet; until it has, [serverNowMs] is this device's own time. */
    val clockSynced: Boolean get() = clock.synced

    /** The server's clock as this device reads it; this device's own clock until the first sample has come back. */
    fun serverNowMs(): Long = clock.serverNowMs() ?: wallNowMs()

    /** Where the shared playhead is right now. */
    fun positionMs(): Long? = roomState.value?.let { RoomLogic.positionAt(it.playback, serverNowMs()) }

    // ---- making, finding, joining, leaving ----

    suspend fun create(maxMembers: Int = 5): Result<String> = guarded {
        check(code == null) { "Already in a room" }
        val me = uid()
        discardPreviousRoom(me)
        repeat(6) {
            val candidate = RoomCode.generate(random)
            val updates = buildJsonObject {
                put("rooms/$candidate/meta", buildJsonObject {
                    put("hostUid", me)
                    put("createdAt", ServerTimestamp)
                    put("maxMembers", maxMembers.coerceIn(2, 8))
                    put("hostOnlyControl", false)
                    put("autoplay", false)
                })
                put("rooms/$candidate/members/0", RoomMapping.memberJson(profile(), me))
                put("rooms/$candidate/uids/$me", "0")
                put("userRoom/$me", candidate)
            }
            try {
                rtdb.update("", updates)
                enter(candidate, 0)
                return@guarded candidate
            } catch (refused: CloudException) {
                // a code already in use is refused like any other write; a different one is tried
                if (refused.code != "PERMISSION_DENIED") throw refused
            }
        }
        throw RoomException(RoomException.Reason.OTHER, "Could not make a room just now")
    }

    /** Who is inside, to someone who has only the code. Null when there is no such room (or it has ended). */
    suspend fun peek(typedCode: String): Result<RoomPreview?> = guarded {
        val c = RoomCode.normalize(typedCode)
        if (!RoomCode.isValid(c)) return@guarded null
        // the rules cannot tell a room that never existed (or has ended) from one that is closed to the reader, and refuse both
        val meta = try {
            rtdb.get("rooms/$c/meta") as? JsonObject
        } catch (refused: CloudException) {
            if (refused.code == "PERMISSION_DENIED") null else throw refused
        } ?: return@guarded null
        val members = RoomMapping.roomOf(c, buildJsonObject { put("meta", meta); put("members", rtdb.get("rooms/$c/members")) }, null)?.members.orEmpty()
        val host = members.firstOrNull { it.isHost }?.name ?: ""
        RoomPreview(c, host, members.size, (meta["maxMembers"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 5, members.map { it.name })
    }

    suspend fun join(typedCode: String): Result<Unit> = guarded {
        check(code == null) { "Already in a room" }
        val c = RoomCode.normalize(typedCode)
        val preview = peek(c).getOrThrow() ?: throw RoomException(RoomException.Reason.NOT_FOUND, "No room with that code")
        val me = uid()
        val room = RoomMapping.roomOf(c, buildJsonObject { put("meta", rtdb.get("rooms/$c/meta")); put("members", rtdb.get("rooms/$c/members")) }, null)
        val taken = room?.members.orEmpty().associate { it.seat to it.uid }
        // a returning device takes its old seat back; a new one the lowest free seat below the room's size
        val own = taken.entries.firstOrNull { it.value == me }?.key
        val candidates = if (own != null) listOf(own) else (0 until preview.maxMembers).filter { it !in taken }
        if (candidates.isEmpty()) throw RoomException(RoomException.Reason.FULL, "The room is full")
        for (candidate in candidates) {
            val updates = buildJsonObject {
                put("rooms/$c/members/$candidate", RoomMapping.memberJson(profile(), me))
                put("rooms/$c/uids/$me", candidate.toString())
            }
            try {
                rtdb.update("", updates)
                enter(c, candidate)
                return@guarded Unit
            } catch (refused: CloudException) {
                // the seat was taken a moment ago by someone else, or the room ended; the next seat is tried
                if (refused.code != "PERMISSION_DENIED") throw refused
            }
        }
        throw RoomException(RoomException.Reason.FULL, "The room is full")
    }

    /** Leaves the room. The host hands the room to the next person first; the last person out takes the room down. */
    suspend fun leave() {
        val c = code ?: return
        val me = uid()
        val before = roomState.value
        val mySeat = seat // stop() forgets the seat, and it is still needed to give it up
        stop()
        runCatching {
            val others = before?.members.orEmpty().filter { it.uid != me }
            val iAmHost = before?.meta?.hostUid == me
            if (iAmHost && others.isEmpty()) {
                rtdb.delete("rooms/$c")
                rtdb.delete("userRoom/$me")
                return@runCatching
            }
            if (iAmHost) rtdb.put("rooms/$c/meta/hostUid", JsonPrimitive(others.first().uid))
            val updates = buildJsonObject {
                put("rooms/$c/members/$mySeat", JsonNull)
                put("rooms/$c/uids/$me", JsonNull)
                if (iAmHost) put("userRoom/$me", JsonNull)
            }
            rtdb.update("", updates)
        }
    }

    // ---- the music ----

    suspend fun control(control: RoomControl): Result<Unit> = guarded {
        val c = code ?: throw RoomException(RoomException.Reason.NOT_IN_ROOM, "Not in a room")
        repeat(3) { attempt ->
            val current = roomState.value ?: throw RoomException(RoomException.Reason.NOT_IN_ROOM, "Not in a room")
            if (current.meta.hostOnlyControl && current.meta.hostUid != uid()) throw RoomException(RoomException.Reason.HOST_ONLY, "Only the host can control the music here")
            val result = RoomLogic.reduce(current.playback, current.queue, control, serverNowMs(), uid(), profile().name) ?: return@guarded Unit
            val updates = buildJsonObject {
                put("state", RoomMapping.playbackJson(result.playback))
                result.queue?.let { put("queue", RoomMapping.queueJson(it)) }
            }
            try {
                rtdb.update("rooms/$c", updates)
                return@guarded Unit
            } catch (refused: CloudException) {
                // refused because someone else moved the sequence on first: by now the stream has the newer state, so reduce again
                if (refused.code != "PERMISSION_DENIED" || attempt == 2) throw refused
                delay(120L * (attempt + 1))
            }
        }
    }

    suspend fun setHostOnlyControl(enabled: Boolean): Result<Unit> = hostSetting("hostOnlyControl", JsonPrimitive(enabled))
    suspend fun setAutoplay(enabled: Boolean): Result<Unit> = hostSetting("autoplay", JsonPrimitive(enabled))
    suspend fun setMaxMembers(max: Int): Result<Unit> = hostSetting("maxMembers", JsonPrimitive(max.coerceIn(2, 8)))

    private suspend fun hostSetting(name: String, value: JsonPrimitive): Result<Unit> = guarded {
        val c = code ?: throw RoomException(RoomException.Reason.NOT_IN_ROOM, "Not in a room")
        rtdb.put("rooms/$c/meta/$name", value)
        Unit
    }

    /** Host only: removes a member. They may come back; the room holds no grudge. */
    suspend fun remove(memberUid: String): Result<Unit> = guarded {
        val c = code ?: throw RoomException(RoomException.Reason.NOT_IN_ROOM, "Not in a room")
        val target = roomState.value?.member(memberUid) ?: return@guarded Unit
        rtdb.update("", buildJsonObject {
            put("rooms/$c/members/${target.seat}", JsonNull)
            put("rooms/$c/uids/$memberUid", JsonNull)
        })
        Unit
    }

    // ---- talking ----

    /** Plain chat, or (with [ref]) a request that names a song everyone can play from the card. */
    suspend fun sendChat(text: String, kind: String = "text", ref: PartyTrack? = null): Result<Unit> = guarded {
        val c = code ?: throw RoomException(RoomException.Reason.NOT_IN_ROOM, "Not in a room")
        val trimmed = text.trim().take(500)
        if (trimmed.isEmpty() && ref == null) return@guarded Unit
        rtdb.push("rooms/$c/chat", buildJsonObject {
            put("uid", uid())
            put("name", profile().name.take(40))
            put("kind", kind)
            put("text", trimmed)
            ref?.let { put("ref", RoomMapping.trackJson(it)) }
            put("t", ServerTimestamp)
        })
        Unit
    }

    suspend fun askToPlay(track: PartyTrack, message: String = ""): Result<Unit> = sendChat(message, "request", track)

    /** Whether this device's microphone is live to the room. The sound itself does not travel through the database. */
    suspend fun setMicLive(live: Boolean): Result<Unit> = guarded {
        val c = code ?: throw RoomException(RoomException.Reason.NOT_IN_ROOM, "Not in a room")
        rtdb.put("rooms/$c/members/$seat/mic", JsonPrimitive(if (live) "live" else "muted"))
        Unit
    }

    fun close() {
        stop()
    }

    // ---- internals ----

    private suspend fun discardPreviousRoom(me: String) {
        // a crash can leave the room it was hosting behind; this is the only cleanup there is, so it happens before the next one is made
        val old = (rtdb.get("userRoom/$me") as? JsonPrimitive)?.contentOrNull ?: return
        runCatching { rtdb.delete("rooms/$old") }
        runCatching { rtdb.delete("userRoom/$me") }
    }

    private fun enter(joined: String, joinedSeat: Int) {
        code = joined
        seat = joinedSeat
        connectionState.value = RoomConnection.CONNECTING
        streamJob = scope.launch { follow(joined) }
        heartbeatJob = scope.launch { beat(joined) }
    }

    private fun stop() {
        streamJob?.cancel()
        heartbeatJob?.cancel()
        streamJob = null
        heartbeatJob = null
        code = null
        seat = -1
        roomState.value = null
        connectionState.value = RoomConnection.IDLE
    }

    private suspend fun follow(joined: String) {
        var attempt = 0
        var seenLive = false
        while (true) {
            try {
                rtdb.listen("rooms/$joined").collect { tree ->
                    attempt = 0
                    val built = RoomMapping.roomOf(joined, tree, clock.serverNowMs())
                    when {
                        built == null && seenLive -> { roomState.value = null; connectionState.value = RoomConnection.ENDED; return@collect }
                        built == null -> return@collect // the first event can arrive before the writes land
                        built.member(uid()) == null && seenLive -> { roomState.value = built; connectionState.value = RoomConnection.REMOVED; return@collect }
                        else -> { seenLive = true; roomState.value = built; connectionState.value = RoomConnection.LIVE }
                    }
                }
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (failure: Throwable) {
                // after being removed or after the room expired the database refuses the read; that is an ending, not a fault
                if (failure is CloudException && failure.code == "PERMISSION_DENIED" && seenLive) {
                    connectionState.value = if (roomState.value?.member(uid()) == null) RoomConnection.REMOVED else RoomConnection.ENDED
                    return
                }
            }
            if (connectionState.value == RoomConnection.ENDED || connectionState.value == RoomConnection.REMOVED) return
            connectionState.value = RoomConnection.RECONNECTING
            delay(minOf(30_000L, 1_000L shl minOf(attempt++, 5)))
        }
    }

    /** Presence and the clock in one: a stamped write of "last seen" returns the server's time, which is a clock sample. */
    private suspend fun beat(joined: String) {
        repeat(3) { runCatching { stamp(joined); delay(250) } } // a quick burst so the clock converges before the first control
        while (true) {
            delay(30_000)
            runCatching { stamp(joined) }
            runCatching { removeStale(joined) }
        }
    }

    private suspend fun stamp(joined: String) {
        val sent = clock.nowMs()
        val echoed = rtdb.put("rooms/$joined/members/$seat/lastSeen", ServerTimestamp)
        val received = clock.nowMs()
        (echoed as? JsonPrimitive)?.longOrNull?.let { clock.record(sent, it, received) }
    }

    /** The host clears members that stopped reporting, which is the only way a crashed device ever leaves. */
    private suspend fun removeStale(joined: String) {
        val current = roomState.value ?: return
        if (current.meta.hostUid != uid()) return
        val now = serverNowMs()
        for (stale in current.members.filter { it.uid != uid() && now - it.lastSeenMs > STALE_AFTER_MS }) {
            rtdb.update("", buildJsonObject {
                put("rooms/$joined/members/${stale.seat}", JsonNull)
                put("rooms/$joined/uids/${stale.uid}", JsonNull)
            })
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (failure: RoomException) {
        Result.failure(failure)
    } catch (failure: CloudException) {
        Result.failure(RoomException(if (failure.code == "PERMISSION_DENIED") RoomException.Reason.DENIED else RoomException.Reason.OTHER, failure.message ?: failure.code))
    } catch (failure: IOException) {
        Result.failure(RoomException(RoomException.Reason.OFFLINE, "No connection"))
    } catch (failure: Throwable) {
        Result.failure(failure)
    }

    private companion object {
        const val STALE_AFTER_MS = 180_000L
    }
}
