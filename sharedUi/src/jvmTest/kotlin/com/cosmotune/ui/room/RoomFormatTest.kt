package com.cosmotune.ui.room

import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomMember
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RoomFormatTest {
    private fun text(id: String, uid: String, atMs: Long, body: String = id) = RoomChatMessage(id, uid, uid.uppercase(), "text", body, null, atMs)
    private val song = PartyTrack("v1", "Kannalane", "A. R. Rahman", durationMs = 300_000)

    @Test fun linesOfOnePersonCloseTogetherFoldIntoOneRun() {
        val entries = groupRoomChat(listOf(text("a", "anu", 0), text("b", "anu", 60_000), text("c", "ravi", 70_000), text("d", "anu", 80_000)))
        assertEquals(3, entries.size)
        val first = assertIs<RoomChatEntry.Run>(entries[0])
        assertEquals(listOf("a", "b"), first.messages.map { it.id })
        assertEquals("ANU", first.name)
        assertEquals(0L, first.atMs)
        assertEquals("ravi", (entries[1] as RoomChatEntry.Run).uid)
        assertEquals(listOf("d"), (entries[2] as RoomChatEntry.Run).messages.map { it.id })
    }

    @Test fun aLongPauseStartsANewRun() {
        val entries = groupRoomChat(listOf(text("a", "anu", 0), text("b", "anu", ROOM_CHAT_RUN_GAP_MS + 1)))
        assertEquals(2, entries.size)
        // the gap is measured from the run's last line, not its first
        val chained = groupRoomChat(listOf(text("a", "anu", 0), text("b", "anu", ROOM_CHAT_RUN_GAP_MS), text("c", "anu", 2 * ROOM_CHAT_RUN_GAP_MS)))
        assertEquals(1, chained.size)
    }

    @Test fun systemLinesAndRequestsBreakRuns() {
        val request = RoomChatMessage("r", "anu", "Anu", "request", "", song, 20)
        val system = RoomChatMessage("s", "ravi", "Ravi", "system", "Ravi skipped", null, 30)
        val entries = groupRoomChat(listOf(text("a", "anu", 10), request, text("b", "anu", 25), system, text("c", "anu", 40)))
        assertEquals(listOf("run:a", "request:r", "run:b", "system:s", "run:c"), entries.map { it.key })
    }

    @Test fun aRequestWithoutItsSongAndAnUnknownKindReadAsText() {
        val entries = groupRoomChat(
            listOf(
                RoomChatMessage("r", "anu", "Anu", "request", "play something", null, 0),
                RoomChatMessage("x", "anu", "Anu", "sticker", "wave", null, 10),
            ),
        )
        val run = assertIs<RoomChatEntry.Run>(entries.single())
        assertEquals(listOf("r", "x"), run.messages.map { it.id })
    }

    @Test fun chatTimesFollowTheReadersClock() {
        val at = 14 * 3_600_000L + 5 * 60_000L
        assertEquals("14:05", formatRoomChatTime(at, ZoneOffset.UTC, Locale.UK))
        assertEquals("19:35", formatRoomChatTime(at, ZoneOffset.ofHoursMinutes(5, 30), Locale.UK))
    }

    @Test fun clocksCountMinutesThenHours() {
        assertEquals("0:00", formatRoomClock(-5))
        assertEquals("0:09", formatRoomClock(9_999))
        assertEquals("4:05", formatRoomClock(245_000))
        assertEquals("1:02:03", formatRoomClock(3_723_000))
    }

    @Test fun theBadgeIsHiddenAtZeroAndCappedPast99() {
        assertNull(roomUnreadBadge(0))
        assertNull(roomUnreadBadge(-1))
        assertEquals("7", roomUnreadBadge(7))
        assertEquals("99+", roomUnreadBadge(150))
    }

    @Test fun thePlayheadAndADragMapOntoTheSong() {
        assertEquals(0.5f, roomPositionFraction(150_000, 300_000))
        assertEquals(1f, roomPositionFraction(400_000, 300_000))
        assertEquals(0f, roomPositionFraction(10_000, null))
        assertEquals(0f, roomPositionFraction(10_000, 0))
        assertEquals(75_000L, roomSeekTarget(0.25f, 300_000))
        assertEquals(300_000L, roomSeekTarget(1.5f, 300_000))
    }

    @Test fun theComposerSendsTrimmedTextWithinTheLimit() {
        assertNull(roomChatToSend("   \n "))
        assertEquals("hi", roomChatToSend("  hi \n"))
        assertEquals(ROOM_CHAT_MAX_CHARS, roomChatToSend("x".repeat(ROOM_CHAT_MAX_CHARS + 20))!!.length)
    }

    private fun member(uid: String, seat: Int, host: Boolean = false) =
        RoomMember(seat, uid, uid.uppercase(), null, "d-$uid", 0, 0, micLive = false, isHost = host, connected = true)

    @Test fun peopleAreListedHostThenYouThenBySeat() {
        val members = listOf(member("c", 3), member("a", 1), member("h", 0, host = true), member("me", 4))
        assertEquals(listOf("h", "me", "a", "c"), sortRoomMembers(members, "me").map { it.uid })
        val (shown, more) = roomAvatarStrip(members, "me", max = 3)
        assertEquals(listOf("h", "me", "a"), shown.map { it.uid })
        assertEquals(1, more)
        assertEquals(0, roomAvatarStrip(members, "me").second)
    }

    @Test fun startedByPrefersTheSavedName() {
        val members = listOf(member("anu", 1))
        assertEquals("Saved", roomStartedByName("Saved", "anu", members))
        assertEquals("ANU", roomStartedByName(null, "anu", members))
        assertEquals("ANU", roomStartedByName("  ", "d-anu", members))
        assertNull(roomStartedByName(null, "gone", members))
    }
}
