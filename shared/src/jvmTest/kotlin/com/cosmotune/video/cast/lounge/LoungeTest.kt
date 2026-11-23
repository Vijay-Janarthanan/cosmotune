package com.cosmotune.video.cast.lounge

import com.cosmotune.video.player.FakeVideoPlayback
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Link with TV code": the files under `resources/video/lounge` are the lounge's real answers to a screen (the first bind
 * and the long poll while a second client, paired with the code, sent a video, pause, seek and volume), fetched without any
 * sign-in; session ids, tokens, device ids and keys are replaced, and each chunk's length is written again for the new text.
 */
class LoungeTest {
    private fun fixture(name: String) = LoungeTest::class.java.getResource("/video/lounge/$name")!!.readText()

    private fun commands(text: String): List<LoungeCommand> =
        LoungeProtocol.split(text).first.flatMap { LoungeProtocol.messages(it) }.map { LoungeProtocol.command(it) }

    @Test fun theFirstBindOpensTheSession() {
        val all = commands(fixture("lounge_screen_bind.txt"))
        assertEquals(LoungeCommand.Session("FAKESID000000001"), all[0])
        assertEquals(LoungeCommand.GSession("FAKE_TOKEN"), all[1])
        assertEquals(LoungeCommand.Remotes(emptyList()), all[2], "only the screen itself is in the lounge")
        assertEquals(LoungeCommand.GetNowPlaying, all[3])
    }

    @Test fun theLongPollBringsThePhoneAndItsCommands() {
        val all = commands(fixture("lounge_screen_poll.txt")).filterNot { it is LoungeCommand.Other }
        val phone = LoungeRemote("fake-device-phone", "Phone")
        assertEquals(
            listOf(
                LoungeCommand.Remotes(listOf(phone)),
                LoungeCommand.RemoteConnected(phone),
                LoungeCommand.GetNowPlaying,
                LoungeCommand.GetVolume,
                LoungeCommand.SetPlaylist("jNQXAC9IVRw", 5_000, listOf("jNQXAC9IVRw"), 0),
                LoungeCommand.Pause,
                LoungeCommand.SeekTo(12_000),
                LoungeCommand.SetVolume(40, muted = false),
            ),
            all,
        )
    }

    @Test fun anArrayCutAcrossChunksWaitsForItsEnd() {
        val text = fixture("lounge_screen_poll.txt")
        var buffer = ""
        val got = ArrayList<String>()
        for (piece in text.chunked(37)) {
            val (arrays, rest) = LoungeProtocol.split(buffer + piece)
            got += arrays
            buffer = rest
        }
        assertEquals(LoungeProtocol.split(text).first, got)
        assertTrue(buffer.isBlank())
    }

    @Test fun eventsAreNumberedFromTheOffset() {
        val form = LoungeProtocol.form(3, listOf("onStateChange" to mapOf("state" to "1"), "nowPlaying" to emptyMap()))
        assertEquals(mapOf("count" to "2", "ofs" to "3", "req0__sc" to "onStateChange", "req0_state" to "1", "req1__sc" to "nowPlaying"), form)
        assertEquals("123 456 789 012", LoungeProtocol.spaced("123456789012"))
        assertTrue(LoungeProtocol.newCode().let { it.length == 12 && it.all(Char::isDigit) })
    }

    private class FakeLink(private val commands: List<LoungeCommand>) : LoungeLink {
        val sent = ArrayList<String>()
        val registered = ArrayList<String>()
        override suspend fun screenId(saved: String?) = saved ?: "screen1"
        override suspend fun connect(screenId: String) = "token"
        override suspend fun register(screenId: String, code: String) { registered += code }
        override suspend fun run(onCommand: suspend (LoungeCommand) -> Unit) { commands.forEach { onCommand(it) } }
        override suspend fun send(events: List<Pair<String, Map<String, String>>>) { events.forEach { sent += it.first } }
    }

    @Test fun thePhonesCommandsDriveThePlayer() = runBlocking {
        val playback = FakeVideoPlayback()
        val opened = ArrayList<Pair<String, Long>>()
        val link = FakeLink(emptyList())
        val receiver = LoungeReceiver(link, playback, null, {}, { id, start -> opened += id to start; playback.play(id, start) })
        receiver.handle(LoungeCommand.SetPlaylist("b", 5_000, listOf("a", "b", "c"), 1))
        assertEquals(listOf("b" to 5_000L), opened)
        playback.update { it.copy(playing = true) }
        receiver.handle(LoungeCommand.Play)
        receiver.handle(LoungeCommand.Pause)
        receiver.handle(LoungeCommand.SeekTo(12_000))
        receiver.handle(LoungeCommand.SetVolume(0, muted = false))
        receiver.handle(LoungeCommand.Next)
        receiver.handle(LoungeCommand.Previous)
        receiver.handle(LoungeCommand.Previous)
        assertEquals(listOf("b" to 5_000L, "c" to 0L, "b" to 0L, "a" to 0L), opened)
        val calls = playback.calls.filterNot { it.startsWith("play(") }
        assertEquals(listOf("togglePlayPause()", "seekTo(12000)", "setMuted(true)"), calls, "play while playing does nothing; pause toggles once")
        assertEquals(listOf("onVolumeChanged"), link.sent)
        receiver.handle(LoungeCommand.RemoteConnected(LoungeRemote("p", "Pixel")))
        assertEquals(listOf("Pixel"), receiver.state.value.remotes.map { it.name })
        receiver.handle(LoungeCommand.RemoteDisconnected("p"))
        assertTrue(receiver.state.value.remotes.isEmpty())
    }

    @Test fun aSessionRegistersACodeAndKeepsANewScreenId() = runBlocking {
        val playback = FakeVideoPlayback()
        val link = FakeLink(listOf(LoungeCommand.Remotes(listOf(LoungeRemote("p", "Pixel")))))
        var saved: String? = null
        val receiver = LoungeReceiver(link, playback, null, { saved = it }, { _, _ -> }, newCode = { "111222333444" })
        val job = launch { receiver.run() }
        kotlinx.coroutines.withTimeout(5_000) { while (receiver.state.value.code == null || receiver.state.value.remotes.isEmpty()) kotlinx.coroutines.delay(10) }
        job.cancel()
        assertEquals("screen1", saved)
        assertEquals(listOf("111222333444"), link.registered)
        assertEquals("111222333444", receiver.state.value.code)
    }
}
