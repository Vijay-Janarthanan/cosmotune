package com.cosmotune.room

import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartySnapshot
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.PartyWatch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Reading and writing the room's JSON, in the layout the security rules describe. */
internal object RoomMapping {
    /** A member counts as connected if it was heard from this recently; each device reports every 30 seconds. */
    const val CONNECTED_WITHIN_MS = 90_000L

    private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    fun trackOf(o: JsonObject): PartyTrack? = PartyTrack(
        videoId = o.text("videoId") ?: return null,
        title = o.text("title").orEmpty(),
        artist = o.text("artist").orEmpty(),
        thumbnailUrl = o.text("thumbnailUrl"),
        durationMs = o.long("durationMs"),
        fromAutoplay = o.flag("fromAutoplay") ?: false,
        // A kind from a newer app is kept as it is, never read as music (an old version once played `m:tmdb:603` as a song): every
        // player sits it out and the room says to update. A link that is not https is dropped: another member's device fetches it.
        kind = o.text("kind")?.takeIf { it.isNotBlank() }?.take(16) ?: PartyTrack.KIND_MUSIC,
        url = o.text("url")?.takeIf { it.startsWith("https://") },
        watch = o.obj("watch")?.let(::watchOf),
    )

    private fun watchOf(o: JsonObject): PartyWatch = PartyWatch(
        tmdb = o.long("tmdb")?.toInt(), imdb = o.text("imdb")?.take(16), season = o.long("season")?.toInt(), episode = o.long("episode")?.toInt(),
        year = o.long("year")?.toInt(), runtimeMin = o.long("runtimeMin")?.toInt(), size = o.long("size"), name = o.text("name")?.take(200),
        durationMs = o.long("durationMs"), binge = o.text("binge")?.take(100), plugin = o.text("plugin")?.take(32),
        tvgId = o.text("tvgId")?.take(64), channel = o.text("channel")?.take(80),
    )

    fun watchJson(w: PartyWatch): JsonObject = buildJsonObject {
        w.tmdb?.let { put("tmdb", it) }
        w.imdb?.let { put("imdb", it.take(16)) }
        w.season?.let { put("season", it) }
        w.episode?.let { put("episode", it) }
        w.year?.let { put("year", it) }
        w.runtimeMin?.let { put("runtimeMin", it) }
        w.size?.let { put("size", it) }
        w.name?.let { put("name", it.take(200)) }
        w.durationMs?.let { put("durationMs", it) }
        w.binge?.let { put("binge", it.take(100)) }
        w.plugin?.let { put("plugin", it.take(32)) }
        w.tvgId?.let { put("tvgId", it.take(64)) }
        w.channel?.let { put("channel", it.take(80)) }
    }

    fun trackJson(t: PartyTrack): JsonObject = buildJsonObject {
        put("videoId", t.videoId)
        put("title", t.title.take(200))
        put("artist", t.artist.take(200))
        t.thumbnailUrl?.let { put("thumbnailUrl", it.take(500)) }
        t.durationMs?.let { put("durationMs", it) }
        if (t.fromAutoplay) put("fromAutoplay", true)
        if (t.kind != PartyTrack.KIND_MUSIC) put("kind", t.kind)
        t.url?.takeIf { it.startsWith("https://") }?.let { put("url", it.take(500)) }
        t.watch?.takeIf { t.kind == PartyTrack.KIND_WATCH }?.let { put("watch", watchJson(it)) }
    }

    /** Children are keyed by index (seats, queue positions) or by push id (chat); either way they come back in key order. */
    private fun children(o: JsonObject?): List<Pair<String, JsonObject>> =
        o?.entries?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.orEmpty()
            .sortedWith(compareBy({ it.first.toIntOrNull() ?: Int.MAX_VALUE }, { it.first }))

    fun playbackOf(o: JsonObject?, queueLength: Int, autoplay: Boolean): PartyPlayback {
        if (o == null) return PartyPlayback(autoplayEnabled = autoplay)
        return PartyPlayback(
            seq = o.long("seq") ?: 0,
            track = o.obj("track")?.let(::trackOf),
            queueSeq = o.long("queueSeq") ?: 0,
            queueLength = queueLength,
            queueIndex = (o.long("queueIndex") ?: -1).toInt(),
            isPlaying = o.flag("isPlaying") ?: false,
            positionMs = o.long("positionMs") ?: 0,
            anchorMs = o.long("anchorMs") ?: 0,
            updatedBy = o.text("updatedBy"),
            startedBy = o.text("startedBy"),
            startedByName = o.text("startedByName"),
            autoplayEnabled = autoplay,
        )
    }

    fun playbackJson(p: PartyPlayback): JsonObject = buildJsonObject {
        put("seq", p.seq)
        put("positionMs", p.positionMs)
        put("anchorMs", p.anchorMs)
        put("isPlaying", p.isPlaying)
        p.track?.let { put("track", trackJson(it)) }
        put("queueIndex", p.queueIndex)
        put("queueSeq", p.queueSeq)
        put("updatedBy", p.updatedBy ?: "")
        p.startedBy?.let { put("startedBy", it) }
        p.startedByName?.let { put("startedByName", it.take(40)) }
    }

    fun queueOf(o: JsonObject?, index: Int): PartyQueue =
        if (o == null) PartyQueue(index = index) else PartyQueue(
            seq = o.long("seq") ?: 0,
            index = index,
            items = children(o.obj("items")).mapNotNull { trackOf(it.second) },
        )

    fun queueJson(q: PartyQueue): JsonObject = buildJsonObject {
        put("seq", q.seq)
        if (q.items.isNotEmpty()) put("items", JsonObject(q.items.withIndex().associate { it.index.toString() to trackJson(it.value) }))
    }

    fun memberJson(profile: RoomProfile, uid: String, nowStamp: JsonElement = ServerTimestamp, mic: String = "muted"): JsonObject = buildJsonObject {
        put("uid", uid)
        put("name", profile.name.take(40))
        profile.avatarUrl?.let { put("avatarUrl", it.take(500)) }
        put("deviceId", profile.deviceId.take(64))
        put("joinedAt", nowStamp)
        put("lastSeen", nowStamp)
        put("mic", mic)
    }

    fun roomOf(code: String, root: JsonElement, serverNowMs: Long?): RoomState? {
        val o = root as? JsonObject ?: return null
        val metaJson = o.obj("meta") ?: return null
        val meta = RoomMeta(
            createdAtMs = metaJson.long("createdAt") ?: return null,
            hostUid = metaJson.text("hostUid") ?: return null,
            maxMembers = (metaJson.long("maxMembers") ?: 5).toInt(),
            hostOnlyControl = metaJson.flag("hostOnlyControl") ?: false,
            autoplay = metaJson.flag("autoplay") ?: false,
        )
        val members = children(o.obj("members")).mapNotNull { (seat, m) ->
            val uid = m.text("uid") ?: return@mapNotNull null
            val lastSeen = m.long("lastSeen") ?: 0
            RoomMember(
                seat = seat.toIntOrNull() ?: return@mapNotNull null,
                uid = uid,
                name = m.text("name").orEmpty(),
                avatarUrl = m.text("avatarUrl"),
                deviceId = m.text("deviceId").orEmpty(),
                joinedAtMs = m.long("joinedAt") ?: 0,
                lastSeenMs = lastSeen,
                micLive = m.text("mic") == "live",
                isHost = uid == meta.hostUid,
                connected = serverNowMs == null || serverNowMs - lastSeen < CONNECTED_WITHIN_MS,
            )
        }
        val state = o.obj("state")
        val queue = queueOf(o.obj("queue"), (state?.long("queueIndex") ?: -1).toInt())
        val chat = children(o.obj("chat")).mapNotNull { (id, m) ->
            RoomChatMessage(id, m.text("uid") ?: return@mapNotNull null, m.text("name").orEmpty(), m.text("kind") ?: "text", m.text("text").orEmpty(), m.obj("ref")?.let(::trackOf), m.long("t") ?: 0)
        }.sortedWith(compareBy({ it.atMs }, { it.id }))
        return RoomState(code, meta, members, playbackOf(state, queue.items.size, meta.autoplay), queue, chat, serverNowMs)
    }

    /** The same room as the party screens already understand, so existing Listen Together UI and playback code can read it. */
    fun snapshotOf(room: RoomState): PartySnapshot = PartySnapshot(
        code = room.code,
        createdAtMs = room.meta.createdAtMs,
        maxMembers = room.meta.maxMembers,
        hostOnlyControl = room.meta.hostOnlyControl,
        members = room.members.map {
            PartyMember(it.uid, it.uid, it.name, it.avatarUrl, it.isHost, it.connected, it.joinedAtMs, it.lastSeenMs)
        },
        playback = room.playback,
        queue = room.queue,
        serverMs = room.serverNowMs ?: 0,
    )
}

internal val NullJson: JsonElement = JsonNull
