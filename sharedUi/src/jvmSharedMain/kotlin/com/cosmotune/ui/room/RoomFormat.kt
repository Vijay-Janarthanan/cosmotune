package com.cosmotune.ui.room

import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomMember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * The room screen's arithmetic, kept out of the composables so it is tested on a plain JVM: how chat lines fold into runs, what
 * the clock and the badge say, where a drag lands in the song, and the order people are listed in.
 */

/** The longest chat line the composer takes; the server refuses longer ones, so the field stops at it rather than failing on send. */
const val ROOM_CHAT_MAX_CHARS = 500

/** Two lines from the same person this close together read as one run under one name and avatar. */
const val ROOM_CHAT_RUN_GAP_MS = 5 * 60_000L

/** How many faces the header's strip shows before it says "+N". */
const val ROOM_AVATAR_STRIP_MAX = 5

/** What one place in the chat list draws. [key] is stable across updates, so the list keeps its scroll when lines arrive. */
sealed interface RoomChatEntry {
    val key: String

    /** Consecutive text lines of one person: one avatar, one name, the time of the first. */
    data class Run(val uid: String, val name: String, val atMs: Long, val messages: List<RoomChatMessage>) : RoomChatEntry {
        override val key: String get() = "run:" + messages.first().id
    }

    /** "Ravi skipped": a centred line, never part of a run. */
    data class System(val message: RoomChatMessage) : RoomChatEntry {
        override val key: String get() = "system:" + message.id
    }

    /** A song someone asked for, drawn as a card with Play now. A request without its song falls back to a plain line in a run. */
    data class Request(val message: RoomChatMessage) : RoomChatEntry {
        override val key: String get() = "request:" + message.id
    }
}

/**
 * Folds [messages] (oldest first, as the room keeps them) into what the chat draws. A text line joins the run before it when that run
 * is the same person's and its last line is at most [gapMs] older; a system line or a request card always ends a run, so a reply
 * after a card starts with the name again and reads as an answer to it. An unknown kind is treated as text, so a newer client's
 * message still shows.
 */
fun groupRoomChat(messages: List<RoomChatMessage>, gapMs: Long = ROOM_CHAT_RUN_GAP_MS): List<RoomChatEntry> {
    val out = ArrayList<RoomChatEntry>()
    for (message in messages) {
        when {
            message.kind == KIND_SYSTEM -> out += RoomChatEntry.System(message)
            message.kind == KIND_REQUEST && message.ref != null -> out += RoomChatEntry.Request(message)
            else -> {
                val last = out.lastOrNull()
                if (last is RoomChatEntry.Run && last.uid == message.uid && message.atMs - last.messages.last().atMs in 0..gapMs) {
                    out[out.lastIndex] = last.copy(messages = last.messages + message)
                } else {
                    out += RoomChatEntry.Run(message.uid, message.name, message.atMs, listOf(message))
                }
            }
        }
    }
    return out
}

/** The time of day a chat line was sent, in the reader's own clock style ("14:05", "2:05 PM"). */
fun formatRoomChatTime(atMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(atMs).atZone(zone))

/** `m:ss`, or `h:mm:ss` from an hour up (a long mix in a room is common); a negative time is zero. */
fun formatRoomClock(ms: Long): String {
    val total = if (ms <= 0L) 0L else ms / 1000L
    val hours = total / 3600L
    val minutes = (total % 3600L) / 60L
    val seconds = total % 60L
    fun two(n: Long) = if (n < 10L) "0$n" else n.toString()
    return if (hours > 0L) "$hours:${two(minutes)}:${two(seconds)}" else "$minutes:${two(seconds)}"
}

/** The badge on the Chat tab: nothing at zero, the count, then "99+" so the badge keeps its size. */
fun roomUnreadBadge(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

/** How far through the song [positionMs] is, 0 to 1; 0 when the length is unknown. */
fun roomPositionFraction(positionMs: Long, durationMs: Long?): Float =
    if (durationMs == null || durationMs <= 0L) 0f else (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

/** Where a released drag at [fraction] seeks to, in the song's milliseconds. */
fun roomSeekTarget(fraction: Float, durationMs: Long): Long = (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong()

/** What the composer sends for [text]: trimmed and cut to the limit, or null when there is nothing to send. */
fun roomChatToSend(text: String): String? = text.trim().take(ROOM_CHAT_MAX_CHARS).takeIf { it.isNotEmpty() }

/** The People list's order: the host, then you, then everyone else by seat, which is the order they joined in. */
fun sortRoomMembers(members: List<RoomMember>, selfUid: String): List<RoomMember> =
    members.sortedWith(compareBy<RoomMember>({ !it.isHost }, { it.uid != selfUid }, { it.seat }))

/** The faces the header shows and how many more there are. */
fun roomAvatarStrip(members: List<RoomMember>, selfUid: String, max: Int = ROOM_AVATAR_STRIP_MAX): Pair<List<RoomMember>, Int> {
    val sorted = sortRoomMembers(members, selfUid)
    return sorted.take(max) to (sorted.size - max).coerceAtLeast(0)
}

/** "Started by" names the member who chose the track: the name saved with it (it survives them leaving), else theirs now. */
fun roomStartedByName(startedByName: String?, startedBy: String?, members: List<RoomMember>): String? =
    startedByName?.takeIf { it.isNotBlank() } ?: startedBy?.let { id -> members.firstOrNull { it.uid == id || it.deviceId == id }?.name }

internal const val KIND_SYSTEM = "system"
internal const val KIND_REQUEST = "request"
