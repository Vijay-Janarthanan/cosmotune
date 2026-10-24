package com.cosmotune.music.wear

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The phone/watch wire (wearos/protocol, compiled into the phone, TV and watch
 * apps): the commands the watch's controls send and how the phone reads them,
 * the speaker messages and how the watch reads them, and the stream framing.
 */
class WearWireTest {

    @Test
    fun `repeat constants are Media3's`() {
        assertEquals(Player.REPEAT_MODE_OFF, WearWire.REPEAT_OFF)
        assertEquals(Player.REPEAT_MODE_ONE, WearWire.REPEAT_ONE)
        assertEquals(Player.REPEAT_MODE_ALL, WearWire.REPEAT_ALL)
        assertEquals(C.LENGTH_UNSET.toLong(), -1L)
    }

    @Test
    fun `every watch control is a distinct command the phone reads back`() {
        val commands = listOf(
            WearProtocol.CMD_SYNC, WearProtocol.CMD_TOGGLE, WearProtocol.CMD_NEXT, WearProtocol.CMD_PREVIOUS,
            WearProtocol.CMD_LIKE, WearProtocol.CMD_SHUFFLE, WearProtocol.CMD_REPEAT, WearProtocol.CMD_VOLUME,
            WearProtocol.CMD_QUEUE, WearProtocol.CMD_SEEK, WearProtocol.CMD_SKIP_TO, WearProtocol.CMD_OUTPUTS,
            WearProtocol.CMD_SELECT_OUTPUT, WearProtocol.CMD_SPEAKER_WATCH, WearProtocol.CMD_SPEAKER_STATUS,
        )
        assertEquals(commands.size, commands.toSet().size)
        commands.forEach { command ->
            assertFalse(command, '/' in command)
            assertEquals(command, WearWire.commandOf(WearWire.commandPath(command)))
        }
        val speaker = listOf(
            WearProtocol.SPEAKER_LOAD, WearProtocol.SPEAKER_AHEAD, WearProtocol.SPEAKER_JUMP, WearProtocol.SPEAKER_PLAY,
            WearProtocol.SPEAKER_PAUSE, WearProtocol.SPEAKER_SEEK, WearProtocol.SPEAKER_REPEAT,
            WearProtocol.SPEAKER_VOLUME, WearProtocol.SPEAKER_STOP,
        )
        assertEquals(speaker.size, speaker.toSet().size)
        speaker.forEach { assertEquals(it, WearWire.speakerCommandOf(WearWire.speakerPath(it))) }
    }

    @Test
    fun `only command paths are commands`() {
        assertNull(WearWire.commandOf(null))
        assertNull(WearWire.commandOf(WearProtocol.PATH_NOW_PLAYING))
        assertNull(WearWire.commandOf(WearProtocol.PATH_COMMAND))
        assertNull(WearWire.commandOf(WearProtocol.PATH_COMMAND + "/"))
        assertNull(WearWire.commandOf("/other" + WearProtocol.PATH_COMMAND + "/toggle"))
        assertNull(WearWire.commandOf(WearWire.speakerPath(WearProtocol.SPEAKER_PLAY)))
        assertNull(WearWire.speakerCommandOf(WearWire.commandPath(WearProtocol.CMD_TOGGLE)))
        assertEquals("", WearWire.argOf(null))
        assertEquals("Love ♥", WearWire.argOf("Love ♥".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `crown volume steps, seek and skip arguments`() {
        assertEquals(2, WearWire.volumeSteps("2"))
        assertEquals(-1, WearWire.volumeSteps("-1"))
        assertEquals(0, WearWire.volumeSteps(""))
        assertEquals(0, WearWire.volumeSteps("up"))
        assertEquals(100, WearWire.volumeSteps("99999"))
        assertEquals(45_000L, WearWire.seekTarget("45000"))
        assertEquals(0L, WearWire.seekTarget("-20"))
        assertNull(WearWire.seekTarget("soon"))
        assertEquals(3, WearWire.skipTarget("3", count = 4))
        assertNull(WearWire.skipTarget("4", count = 4))
        assertNull(WearWire.skipTarget("-1", count = 4))
        assertNull(WearWire.skipTarget("x", count = 4))
    }

    @Test
    fun `the watch's repeat button cycles like the phone's`() {
        assertEquals(Player.REPEAT_MODE_ALL, WearWire.nextRepeatMode(Player.REPEAT_MODE_OFF))
        assertEquals(Player.REPEAT_MODE_ONE, WearWire.nextRepeatMode(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, WearWire.nextRepeatMode(Player.REPEAT_MODE_ONE))
        assertEquals(Player.REPEAT_MODE_OFF, WearWire.nextRepeatMode(7))
    }

    @Test
    fun `up next is one played row and the rows ahead, inside the queue`() {
        assertEquals(4..9, WearWire.queueWindow(current = 5, ahead = 4, count = 20))
        assertEquals(0..4, WearWire.queueWindow(current = 0, ahead = 4, count = 20))
        assertEquals(17..19, WearWire.queueWindow(current = 18, ahead = 4, count = 20))
        assertEquals(0..0, WearWire.queueWindow(current = 0, ahead = 4, count = 1))
        assertTrue(WearWire.queueWindow(current = 0, ahead = 4, count = 0).isEmpty())
        assertTrue(WearWire.queueWindow(current = C.INDEX_UNSET, ahead = 4, count = 3).isEmpty())
    }

    @Test
    fun `the watch speaker never loops the few tracks it holds`() {
        assertEquals(Player.REPEAT_MODE_ONE, WearWire.watchRepeat(Player.REPEAT_MODE_ONE))
        assertEquals(Player.REPEAT_MODE_OFF, WearWire.watchRepeat(Player.REPEAT_MODE_ALL))
        assertEquals(Player.REPEAT_MODE_OFF, WearWire.watchRepeat(Player.REPEAT_MODE_OFF))
    }

    @Test
    fun `speaker volume specs are steps or an absolute level inside the range`() {
        assertEquals(9, WearWire.volumeTarget("+2", now = 7, max = 15))
        assertEquals(5, WearWire.volumeTarget("-2", now = 7, max = 15))
        assertEquals(8, WearWire.volumeTarget("1", now = 7, max = 15))
        assertEquals(12, WearWire.volumeTarget("=12", now = 7, max = 15))
        assertEquals(15, WearWire.volumeTarget("=40", now = 7, max = 15))
        assertEquals(0, WearWire.volumeTarget("-30", now = 7, max = 15))
        assertNull(WearWire.volumeTarget("=", now = 7, max = 15))
        assertNull(WearWire.volumeTarget("loud", now = 7, max = 15))
    }

    @Test
    fun `the tracks ahead keep what already matches`() {
        assertNull(WearWire.keepAhead(listOf("b", "c"), listOf("b", "c")))
        assertNull(WearWire.keepAhead(emptyList(), emptyList()))
        // The buffered next track is kept; only the tail changes.
        assertEquals(1, WearWire.keepAhead(listOf("b", "c"), listOf("b", "d")))
        assertEquals(2, WearWire.keepAhead(listOf("b", "c"), listOf("b", "c", "d")))
        assertEquals(1, WearWire.keepAhead(listOf("b", "c"), listOf("b")))
        assertEquals(0, WearWire.keepAhead(listOf("b"), listOf("x", "b")))
    }

    @Test
    fun `a stream request and reply survive the wire`() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val request = WearWire.StreamRequest("cosmotune://source/song?id=été", 1_048_576L, -1L)
        request.writeTo(out)
        WearWire.StreamReply(ok = true, length = 3_000_000L).writeTo(out)
        WearWire.StreamReply(ok = false, length = 99L).writeTo(out)
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(request, WearWire.StreamRequest.readFrom(input))
        assertEquals(WearWire.StreamReply(true, 3_000_000L), WearWire.StreamReply.readFrom(input))
        assertEquals(WearWire.StreamReply(false, -1L), WearWire.StreamReply.readFrom(input))
    }

    @Test(expected = IOException::class)
    fun `a negative position is refused`() {
        val bytes = ByteArrayOutputStream()
        WearWire.StreamRequest("u", -5L, -1L).writeTo(DataOutputStream(bytes))
        WearWire.StreamRequest.readFrom(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun `the watch expects its own bound, else what the phone can serve`() {
        assertEquals(500L, WearWire.expectedLength(500L, WearWire.StreamReply(true, 9_000L)))
        assertEquals(9_000L, WearWire.expectedLength(-1L, WearWire.StreamReply(true, 9_000L)))
        assertEquals(-1L, WearWire.expectedLength(-1L, WearWire.StreamReply(true, -1L)))
    }

    @Test
    fun `the stream body stops at its bound and passes an open-ended one through`() {
        val data = ByteArray(1_000) { it.toByte() }
        val bounded = WearWire.StreamBody(ByteArrayInputStream(data), 300L)
        val buffer = ByteArray(256)
        assertEquals(256, bounded.read(buffer, 0, 256))
        assertEquals(44, bounded.read(buffer, 0, 256))
        assertEquals(-1, bounded.read(buffer, 0, 256))
        assertEquals(0L, bounded.remaining)

        val open = WearWire.StreamBody(ByteArrayInputStream(data), -1L)
        var total = 0
        while (true) {
            val read = open.read(buffer, 0, buffer.size)
            if (read < 0) break
            total += read
        }
        assertEquals(1_000, total)
        assertEquals(0, open.read(buffer, 0, 0))
    }
}
