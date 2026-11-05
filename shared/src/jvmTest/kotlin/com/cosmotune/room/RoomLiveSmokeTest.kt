package com.cosmotune.room

import com.cosmotune.cloud.CloudSession
import com.cosmotune.cloud.CosmotuneFirebase
import com.cosmotune.cloud.FirebaseAuthClient
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.listentogether.ServerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two throwaway guests make a room on the real Realtime Database, join it, control the music, chat, ask for a song and leave; both
 * accounts are deleted at the end. It runs only with `COSMOTUNE_FIREBASE_LIVE=1`, so ordinary test runs never touch the project.
 */
class RoomLiveSmokeTest {
    private suspend fun until(what: String, check: () -> Boolean) {
        try {
            withTimeout(15_000) { while (!check()) delay(100) }
        } catch (late: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for: $what")
        }
    }

    @Test fun twoGuestsShareARoomOnTheRealDatabase() = runBlocking<Unit> {
        assumeTrue(System.getenv("COSMOTUNE_FIREBASE_LIVE") == "1")
        val config = CosmotuneFirebase.config
        val auth = FirebaseAuthClient(config, Http.client)
        val sessions = mutableListOf<CloudSession>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val a = auth.signInAnonymously().also { sessions += it }
            val b = auth.signInAnonymously().also { sessions += it }
            fun client(session: CloudSession, name: String) = RoomClient(
                rtdb = RtdbRest(config.databaseUrl, Http.client) { session.idToken },
                uid = { session.uid },
                profile = { RoomProfile(name, null, "live-test-$name") },
                clock = ServerClock(),
                scope = scope,
            )
            val host = client(a, "Host")
            val guest = client(b, "Guest")

            val code = host.create(maxMembers = 3).getOrThrow()
            until("the host's stream to go live") { host.connection.value == RoomConnection.LIVE }
            assertEquals(1, host.room.value!!.members.size)

            val preview = guest.peek(code).getOrThrow()!!
            assertEquals(listOf("Host"), preview.memberNames)
            guest.join(code).getOrThrow()
            until("both to see two members") { host.room.value?.members?.size == 2 && guest.room.value?.members?.size == 2 }
            assertEquals(1, guest.room.value!!.member(b.uid)!!.seat)

            val song = PartyTrack("dQw4w9WgXcQ", "A song", "An artist", null, 212_000)
            guest.control(RoomControl.SetTrack(song, positionMs = 5_000)).getOrThrow()
            until("the host to see the track") { host.room.value?.playback?.track?.videoId == "dQw4w9WgXcQ" }
            assertEquals(1L, host.room.value!!.playback.seq)
            host.control(RoomControl.Pause()).getOrThrow()
            until("the guest to see the pause") { guest.room.value?.playback?.let { !it.isPlaying && it.seq == 2L } == true }

            guest.sendChat("hello from the guest").getOrThrow()
            guest.askToPlay(song, "play this again").getOrThrow()
            until("the host to see the chat") { host.room.value?.chat?.size == 2 }
            assertEquals(listOf("text", "request"), host.room.value!!.chat.map { it.kind })
            assertEquals("dQw4w9WgXcQ", host.room.value!!.chat[1].ref!!.videoId)

            guest.setMicLive(true).getOrThrow()
            until("the host to see the open mic") { host.room.value?.member(b.uid)?.micLive == true }

            // the clock got samples from the heartbeat writes, and the shared playhead reads from it
            until("a clock sample") { host.clock.synced }
            assertTrue(host.positionMs()!! >= 5_000 - 1_000, "position=${host.positionMs()}")

            // a non-host cannot change the host's settings, and the host-only switch refuses members
            host.setHostOnlyControl(true).getOrThrow()
            until("the switch to arrive") { guest.room.value?.meta?.hostOnlyControl == true }
            assertEquals(RoomException.Reason.HOST_ONLY, (guest.control(RoomControl.Play()).exceptionOrNull() as RoomException).reason)

            // leaving: the host hands over, the last one out removes the room
            host.leave()
            until("the guest to become host") { guest.room.value?.meta?.hostUid == b.uid }
            guest.leave()
            assertTrue(host.peek(code).getOrThrow() == null, "the room is gone")
            println("live: room $code made, joined, controlled, chatted in and removed; clock synced=${host.clock.synced}")
        } finally {
            scope.cancel()
            sessions.forEach { runCatching { auth.deleteAccount(it) } }
        }
    }
}
