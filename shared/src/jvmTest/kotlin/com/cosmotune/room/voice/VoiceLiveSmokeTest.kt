package com.cosmotune.room.voice

import com.cosmotune.cloud.CloudSession
import com.cosmotune.cloud.CosmotuneFirebase
import com.cosmotune.cloud.FirebaseAuthClient
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.listentogether.ServerClock
import com.cosmotune.room.RoomClient
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomProfile
import com.cosmotune.room.RtdbRest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Two throwaway guests set up a voice connection through the real database's signal inbox (the audio engine is a pretend one): the rules
 * must let a member write into another member's inbox, only the owner read it, and the offer, answer and candidates must all arrive and
 * be cleared. It runs only with `COSMOTUNE_FIREBASE_LIVE=1`.
 */
class VoiceLiveSmokeTest {
    private suspend fun until(what: String, check: () -> Boolean) {
        try {
            withTimeout(20_000) { while (!check()) delay(100) }
        } catch (late: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    @Test fun twoGuestsSetUpAVoiceConnectionThroughTheRealInbox() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_FIREBASE_LIVE") == "1")
        val config = CosmotuneFirebase.config
        val auth = FirebaseAuthClient(config, Http.client)
        val sessions = mutableListOf<CloudSession>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val a = auth.signInAnonymously().also { sessions += it }
            val b = auth.signInAnonymously().also { sessions += it }
            fun rtdb(s: CloudSession) = RtdbRest(config.databaseUrl, Http.client) { s.idToken }
            fun client(s: CloudSession, name: String) = RoomClient(rtdb(s), { s.uid }, { RoomProfile(name, null, "voice-$name") }, ServerClock(), scope)
            val host = client(a, "Host")
            val guest = client(b, "Guest")
            val code = host.create(maxMembers = 3).getOrThrow()
            guest.join(code).getOrThrow()
            until("both members to see each other") { host.room.value?.members?.size == 2 && guest.room.value?.members?.size == 2 }

            val net = FakeVoiceNet()
            val meshA = VoiceMesh(rtdb(a), code, a.uid, FakeVoiceEngine(a.uid, net), scope).also { it.start(host.room) }
            val meshB = VoiceMesh(rtdb(b), code, b.uid, FakeVoiceEngine(b.uid, net), scope).also { it.start(guest.room) }

            until("both sides to connect") {
                meshA.state.value.peers[b.uid]?.state == PeerState.CONNECTED && meshB.state.value.peers[a.uid]?.state == PeerState.CONNECTED
            }
            val offerer = if (a.uid < b.uid) a.uid else b.uid
            assertEquals(1, net.offersMade.size, "exactly one offer, from $offerer")
            assertEquals(true, net.offersMade.single().startsWith(offerer))
            until("the inboxes to be cleared") {
                runBlocking { rtdb(a).get("signals/$code/${a.uid}") == JsonNull && rtdb(b).get("signals/$code/${b.uid}") == JsonNull }
            }
            println("live: voice setup through the real inbox done (${net.peers.values.sumOf { it.candidates.size }} candidates exchanged)")

            meshA.close(); meshB.close()
            host.leave(); guest.leave()
            assertEquals(RoomConnection.IDLE, host.connection.value)
        } finally {
            scope.cancel()
            sessions.forEach { runCatching { auth.deleteAccount(it) } }
        }
    }
}
