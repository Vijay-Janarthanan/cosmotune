package com.cosmotune.room.voice

import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.room.FakeRtdb
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomMeta
import com.cosmotune.room.RoomState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeMic(var result: MicAccessResult = MicAccessResult.GRANTED) : MicAccess {
    var asked = 0
    var settingsOpened = 0
    override suspend fun ensure(): MicAccessResult { asked++; return result }
    override fun openSettings() { settingsOpened++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RoomVoiceTest {
    private fun roomOf(vararg uids: String) = RoomState(
        "ABC234", RoomMeta(1, uids.first(), 5),
        uids.mapIndexed { i, u -> RoomMember(i, u, u, null, "d$i", 0, 0, false, i == 0, true) },
        PartyPlayback(), PartyQueue(), emptyList(), null,
    )

    private class Rig(val voice: RoomVoice, val engine: FakeVoiceEngine?, val mic: FakeMic, val net: FakeVoiceNet, val published: MutableList<Boolean>, val room: MutableStateFlow<RoomState?>)

    private fun TestScope.rig(
        room: MutableStateFlow<RoomState?> = MutableStateFlow(null),
        mic: FakeMic = FakeMic(),
        engineBuilds: Boolean = true,
    ): Rig {
        val net = FakeVoiceNet()
        val engine = if (engineBuilds) FakeVoiceEngine("ann", net) else null
        val published = mutableListOf<Boolean>()
        val voice = RoomVoice(FakeRtdb(), { "ann" }, { engine ?: error("no audio") }, room, { published += it }, mic, backgroundScope)
        voice.start()
        return Rig(voice, engine, mic, net, published, room)
    }

    @Test fun voiceStartsWithTheRoomAndNothingOpensTheMicrophoneOnJoining() = runTest {
        val r = rig()
        runCurrent()
        assertFalse(r.voice.ui.value.available, "no room, no voice")
        r.room.value = roomOf("ann", "bob")
        runCurrent()
        assertTrue(r.voice.ui.value.available)
        assertEquals(0, r.mic.asked, "the permission is asked at the first unmute, not at join")
        assertFalse(r.engine!!.captureStarted)
    }

    @Test fun unmutingAsksOnceThenGoesLiveAndIsPublishedToTheRoom() = runTest {
        val r = rig()
        r.room.value = roomOf("ann", "bob"); runCurrent()
        r.voice.setMicLive(true)
        assertEquals(1, r.mic.asked)
        assertTrue(r.voice.ui.value.micLive)
        assertEquals(listOf(true), r.published)
        r.voice.setMicLive(false)
        assertFalse(r.voice.ui.value.micLive)
        assertEquals(listOf(true, false), r.published)
    }

    @Test fun aRefusedPermissionIsReportedAndAPermanentOneSaysToOpenTheSettings() = runTest {
        val r = rig(mic = FakeMic(MicAccessResult.DENIED))
        r.room.value = roomOf("ann", "bob"); runCurrent()
        r.voice.setMicLive(true)
        assertFalse(r.voice.ui.value.micLive)
        assertFalse(r.voice.ui.value.micBlocked)
        assertEquals("The microphone permission was not given", r.voice.ui.value.message)
        assertTrue(r.published.isEmpty(), "the room is not told the mic is live")
        r.mic.result = MicAccessResult.BLOCKED
        r.voice.setMicLive(true)
        assertTrue(r.voice.ui.value.micBlocked)
        r.voice.openMicSettings()
        assertEquals(1, r.mic.settingsOpened)
        r.voice.dismissMessage()
        assertNull(r.voice.ui.value.message)
    }

    @Test fun anEngineThatCannotBeBuiltLeavesVoiceUnavailableAndTheRoomAlone() = runTest {
        val r = rig(engineBuilds = false)
        r.room.value = roomOf("ann", "bob"); runCurrent()
        assertFalse(r.voice.ui.value.available)
        assertEquals("Voice is not available on this device", r.voice.ui.value.message)
        r.voice.setMicLive(true) // does nothing, and does not throw
        assertEquals(0, r.mic.asked)
    }

    @Test fun mutingAndVolumeShowUpAsTheScreenReadsThem() = runTest {
        val r = rig()
        r.room.value = roomOf("ann", "bob", "cat"); runCurrent(); runCurrent()
        r.voice.setMemberMuted("bob", true)
        r.voice.setMemberVolume("cat", 0.5f)
        r.voice.setDeafened(true)
        runCurrent()
        val ui = r.voice.ui.value
        assertEquals(setOf("bob"), ui.mutedHere)
        assertEquals(mapOf("cat" to 0.5f), ui.volumes)
        assertTrue(ui.deafened)
    }

    @Test fun movingStraightToAnotherRoomStartsANewCall() = runTest {
        val r = rig()
        r.room.value = roomOf("ann", "bob"); runCurrent(); runCurrent()
        val first = r.net.peers.values.toList()
        r.room.value = roomOf("ann", "bob").copy(code = "XYZ789"); runCurrent(); runCurrent()
        assertTrue(first.all { it.closed }, "the old room's connections are closed")
        assertTrue(r.net.peers.values.any { !it.closed }, "and a fresh one is made for the new room")
    }

    @Test fun leavingTheRoomReleasesVoice() = runTest {
        val r = rig()
        r.room.value = roomOf("ann", "bob"); runCurrent(); runCurrent()
        assertTrue(r.net.peers.isNotEmpty())
        r.room.value = null
        runCurrent()
        assertFalse(r.voice.ui.value.available)
        assertTrue(r.net.peers.values.all { it.closed })
    }
}
