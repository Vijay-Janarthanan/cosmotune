package com.cosmotune.room.voice

import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.room.FakeRtdb
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomMeta
import com.cosmotune.room.RoomState
import com.cosmotune.room.ServerTimestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A pretend network for fake engines: connecting two peers means both sides see CONNECTED once the answer has gone back. */
internal class FakeVoiceNet {
    val peers = mutableMapOf<Pair<String, String>, FakePeer>()
    var offersMade = mutableListOf<String>()
}

internal class FakePeer(val owner: String, val remote: String, val events: PeerEvents, val net: FakeVoiceNet) : VoicePeer {
    val candidates = mutableListOf<IceCandidate>()
    var closed = false
    var offerAccepted = false
    var muted = false
    var volume = 1f
    val level = MutableStateFlow(0f)
    override val remoteLevel: StateFlow<Float> = level

    override suspend fun createOffer(): String {
        net.offersMade += "$owner>$remote"
        events.onState(PeerState.CONNECTING)
        events.onIceCandidate(IceCandidate("0", 0, "candidate-from-$owner"))
        return "offer:$owner>$remote"
    }

    override suspend fun acceptOffer(offer: String): String {
        offerAccepted = true
        events.onState(PeerState.CONNECTING)
        events.onIceCandidate(IceCandidate("0", 0, "candidate-from-$owner"))
        return "answer:$owner>$remote"
    }

    override suspend fun acceptAnswer(answer: String) {
        events.onState(PeerState.CONNECTED)
        net.peers[remote to owner]?.events?.onState(PeerState.CONNECTED)
    }

    override fun addIceCandidate(candidate: IceCandidate) { candidates += candidate }
    override fun setRemoteMuted(muted: Boolean) { this.muted = muted }
    override fun setRemoteVolume(volume: Float) { this.volume = volume }
    override fun close() { closed = true }
}

internal class FakeVoiceEngine(val uid: String, val net: FakeVoiceNet, var captureWorks: Boolean = true, var failToConnect: Boolean = false) : VoiceEngine {
    var transmitting = false
    var captureStarted = false
    private val own = MutableStateFlow(0f)
    override val localLevel: StateFlow<Float> = own
    override fun createPeer(remoteUid: String, events: PeerEvents): VoicePeer {
        check(!failToConnect) { "no native library" }
        return FakePeer(uid, remoteUid, events, net).also { net.peers[uid to remoteUid] = it }
    }
    override suspend fun startCapture(): Boolean { captureStarted = captureWorks; return captureWorks }
    override fun setSending(sending: Boolean) { transmitting = sending }
    override fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceMeshTest {
    private val code = "ABC234"

    private fun room(vararg uids: String): MutableStateFlow<RoomState?> = MutableStateFlow(roomOf(*uids))

    private fun roomOf(vararg uids: String) = RoomState(
        code, RoomMeta(1, uids.first(), 5),
        uids.mapIndexed { i, u -> RoomMember(i, u, u, null, "d$i", 0, 0, false, i == 0, true) },
        PartyPlayback(), PartyQueue(), emptyList(), null,
    )

    private fun TestScope.mesh(db: FakeRtdb, net: FakeVoiceNet, uid: String, room: StateFlow<RoomState?>, engine: FakeVoiceEngine = FakeVoiceEngine(uid, net)) =
        VoiceMesh(db, code, uid, engine, backgroundScope, restartAfterMs = 1_000).also { it.start(room) }

    @Test fun theLowerIdOffersOnceAndBothSidesConnect() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        val ann = mesh(db, net, "ann", members)
        val bob = mesh(db, net, "bob", members)
        runCurrent(); runCurrent()
        assertEquals(listOf("ann>bob"), net.offersMade, "only the lower id offers")
        assertEquals(PeerState.CONNECTED, ann.state.value.peers["bob"]!!.state)
        assertEquals(PeerState.CONNECTED, bob.state.value.peers["ann"]!!.state)
        assertTrue(net.peers["bob" to "ann"]!!.offerAccepted)
        // the candidates each side offered reached the other, and the inbox was cleared as it was read
        assertTrue(net.peers["ann" to "bob"]!!.candidates.any { it.candidate == "candidate-from-bob" })
        assertTrue(net.peers["bob" to "ann"]!!.candidates.any { it.candidate == "candidate-from-ann" })
        assertEquals(JsonNull, db.get("signals/$code/ann"))
        assertEquals(JsonNull, db.get("signals/$code/bob"))
    }

    @Test fun threePeopleMakeThreeConnectionsAndSomeoneLeavingClosesTheirs() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob", "cat")
        val meshes = listOf("ann", "bob", "cat").associateWith { mesh(db, net, it, members) }
        repeat(4) { runCurrent() }
        assertEquals(setOf("ann>bob", "ann>cat", "bob>cat"), net.offersMade.toSet())
        meshes.forEach { (uid, m) -> assertEquals(2, m.state.value.peers.size, uid) }
        members.value = roomOf("ann", "bob")
        repeat(2) { runCurrent() }
        assertTrue(net.peers["ann" to "cat"]!!.closed)
        assertEquals(setOf("bob"), meshes.getValue("ann").state.value.peers.keys)
    }

    @Test fun candidatesThatArriveBeforeTheOfferAreKeptUntilItIsHandled() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        // bob's inbox already holds ann's candidate, written before her offer
        db.push("signals/$code/bob/ann", buildJsonObject {
            put("type", "ice"); put("payload", """{"sdpMid":"0","sdpMLineIndex":0,"candidate":"early"}"""); put("t", ServerTimestamp)
        })
        val bob = mesh(db, net, "bob", members)
        runCurrent()
        assertTrue(net.peers["bob" to "ann"]!!.candidates.isEmpty(), "held back: no offer yet")
        mesh(db, net, "ann", members)
        repeat(3) { runCurrent() }
        assertTrue(net.peers["bob" to "ann"]!!.candidates.any { it.candidate == "early" }, "added once the offer was taken")
        assertEquals(PeerState.CONNECTED, bob.state.value.peers["ann"]!!.state)
    }

    @Test fun theMicrophoneOpensOnlyWhenAskedAndAFailureIsReported() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        val denied = FakeVoiceEngine("ann", net, captureWorks = false)
        val ann = mesh(db, net, "ann", members, denied)
        runCurrent()
        assertFalse(denied.captureStarted, "joining does not open the microphone")
        assertFalse(ann.setMicLive(true))
        assertFalse(ann.state.value.micLive)
        assertEquals("The microphone is not available", ann.state.value.message)
        denied.captureWorks = true
        assertTrue(ann.setMicLive(true))
        assertTrue(denied.transmitting)
        assertTrue(ann.state.value.micLive)
        ann.setMicLive(false)
        assertFalse(denied.transmitting)
    }

    @Test fun muteVolumeAndDeafenAreLocalAndReachTheRightPeer() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob", "cat")
        val ann = mesh(db, net, "ann", members)
        repeat(3) { runCurrent() }
        ann.setMemberMuted("bob", true)
        ann.setMemberVolume("cat", 0.4f)
        assertTrue(net.peers["ann" to "bob"]!!.muted)
        assertFalse(net.peers["ann" to "cat"]!!.muted)
        assertEquals(0.4f, net.peers["ann" to "cat"]!!.volume)
        ann.setDeafened(true)
        assertTrue(net.peers["ann" to "cat"]!!.muted)
        ann.setDeafened(false)
        assertFalse(net.peers["ann" to "cat"]!!.muted)
        assertTrue(net.peers["ann" to "bob"]!!.muted, "still muted by choice")
    }

    @Test fun aPersonSpeakingShowsUpAndAMutedOneDoesNot() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        val ann = mesh(db, net, "ann", members)
        mesh(db, net, "bob", members)
        repeat(3) { runCurrent() }
        net.peers["ann" to "bob"]!!.level.value = 0.4f
        runCurrent()
        assertEquals(setOf("bob"), ann.state.value.speakingUids)
        ann.setMemberMuted("bob", true)
        assertEquals(emptySet(), ann.state.value.speakingUids)
    }

    @Test fun aFailedConnectionIsMadeAgainAfterAPause() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        val ann = mesh(db, net, "ann", members)
        mesh(db, net, "bob", members)
        repeat(3) { runCurrent() }
        val first = net.peers["ann" to "bob"]!!
        first.events.onState(PeerState.FAILED)
        runCurrent()
        advanceTimeBy(1_500); repeat(4) { runCurrent() }
        assertTrue(first.closed)
        assertEquals(listOf("ann>bob", "ann>bob"), net.offersMade, "a second offer after the failure")
        assertEquals(PeerState.CONNECTED, ann.state.value.peers["bob"]!!.state)
    }

    @Test fun anEngineThatCannotConnectTurnsVoiceOffWithoutEndingTheRoom() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        val broken = FakeVoiceEngine("ann", net, failToConnect = true)
        val ann = mesh(db, net, "ann", members, broken)
        runCurrent()
        assertEquals("Voice is not available on this device", ann.state.value.message)
        assertTrue(ann.state.value.peers.isEmpty())
        // the job that follows the room is still alive: a later change in the room is still read without error
        members.value = roomOf("ann", "bob", "cat")
        runCurrent()
        assertEquals("Voice is not available on this device", ann.state.value.message)
    }

    @Test fun closingTheMeshClosesEveryConnection() = runTest {
        val db = FakeRtdb(); val net = FakeVoiceNet(); val members = room("ann", "bob")
        val ann = mesh(db, net, "ann", members)
        mesh(db, net, "bob", members)
        repeat(3) { runCurrent() }
        ann.close()
        assertTrue(net.peers["ann" to "bob"]!!.closed)
        assertNull(ann.state.value.peers["bob"])
    }
}
