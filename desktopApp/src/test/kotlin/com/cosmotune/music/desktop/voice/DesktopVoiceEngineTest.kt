package com.cosmotune.music.desktop.voice

import com.cosmotune.room.voice.IceCandidate
import com.cosmotune.room.voice.PeerEvents
import com.cosmotune.room.voice.PeerState
import com.cosmotune.room.voice.VoicePeer
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.AudioTrackSource
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A steady tone instead of a microphone, pushed in 10 ms chunks the way WebRTC takes them. */
private class ToneInput(private val hz: Double = 440.0, private val amplitude: Double = 0.5) : DesktopVoiceInput {
    private var timer: ScheduledExecutorService? = null

    override fun open(factory: PeerConnectionFactory): AudioTrackSource {
        val source = CustomAudioSource()
        val frames = SAMPLE_RATE / 100
        val chunk = ByteArray(frames * 2)
        var n = 0L
        timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-test-tone").apply { isDaemon = true } }.also {
            it.scheduleAtFixedRate({
                for (i in 0 until frames) {
                    val s = (sin(2 * PI * hz * n++ / SAMPLE_RATE) * amplitude * Short.MAX_VALUE).toInt()
                    chunk[2 * i] = s.toByte()
                    chunk[2 * i + 1] = (s shr 8).toByte()
                }
                runCatching { source.pushAudio(chunk, 16, SAMPLE_RATE, 1, frames) }
            }, 0, 10, TimeUnit.MILLISECONDS)
        }
        return source
    }

    // the engine frees the source right after this, so nothing may still be pushing into it
    override fun close() {
        timer?.shutdownNow()
        timer?.awaitTermination(1, TimeUnit.SECONDS)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
    }
}

/**
 * Hands candidates across in memory, holding them until both descriptions are in place (as the mesh does with its inbox).
 */
private class Wire : PeerEvents {
    lateinit var to: VoicePeer
    @Volatile var state = PeerState.NEW
    private val held = ConcurrentLinkedQueue<IceCandidate>()
    @Volatile private var open = false

    override fun onIceCandidate(candidate: IceCandidate) {
        if (open) to.addIceCandidate(candidate) else held += candidate
    }

    override fun onState(state: PeerState) { this.state = state }

    fun release() {
        open = true
        while (true) to.addIceCandidate(held.poll() ?: break)
    }
}

/**
 * Two engines in one process, connected directly with no server and no network beyond this machine's own addresses. Neither opens
 * a microphone (one sends a tone) nor plays anything (the headless audio module). Skipped where the WebRTC natives do not load.
 */
class DesktopVoiceEngineTest {
    private val engines = mutableListOf<DesktopVoiceEngine>()

    @After
    fun closeEngines() = engines.forEach { it.close() }

    private fun engine(input: DesktopVoiceInput): DesktopVoiceEngine =
        DesktopVoiceEngine(input = input, audioModule = { HeadlessAudioDeviceModule() }, iceServers = emptyList())
            .also { engines += it }
            .also { assumeTrue("WebRTC natives unavailable: ${it.unavailableReason}", it.available) }

    private class Pair(val a: VoicePeer, val wireA: Wire, val b: VoicePeer, val wireB: Wire)

    private fun connect(sender: DesktopVoiceEngine, receiver: DesktopVoiceEngine): Pair = runBlocking {
        val wireA = Wire()
        val wireB = Wire()
        val a = sender.createPeer("b", wireA)
        val b = receiver.createPeer("a", wireB)
        wireA.to = b
        wireB.to = a
        val answer = b.acceptOffer(a.createOffer())
        a.acceptAnswer(answer)
        wireA.release()
        wireB.release()
        Pair(a, wireA, b, wireB)
    }

    private fun waitFor(seconds: Long, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun `a tone sent by one engine is heard by the other`() {
        val talker = engine(ToneInput())
        val listener = engine(ToneInput())
        assertTrue(runBlocking { talker.startCapture() })
        talker.setSending(true)
        val link = connect(talker, listener)

        assertTrue(waitFor(10) { link.wireA.state == PeerState.CONNECTED && link.wireB.state == PeerState.CONNECTED }, "both sides connect")
        assertTrue(waitFor(10) { link.b.remoteLevel.value > 0.05f }, "the listener hears the tone (level ${link.b.remoteLevel.value})")
        assertTrue(waitFor(5) { talker.localLevel.value > 0.05f }, "the talker sees its own level (level ${talker.localLevel.value})")
    }

    @Test
    fun `a person muted here reports no level`() {
        val talker = engine(ToneInput())
        val listener = engine(ToneInput())
        assertTrue(runBlocking { talker.startCapture() })
        talker.setSending(true)
        val link = connect(talker, listener)
        link.b.setRemoteMuted(true)

        assertTrue(waitFor(10) { link.wireB.state == PeerState.CONNECTED }, "connects")
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < end) {
            assertEquals(0f, link.b.remoteLevel.value)
            Thread.sleep(50)
        }
        // unmuting brings the voice back on the same connection
        link.b.setRemoteMuted(false)
        assertTrue(waitFor(10) { link.b.remoteLevel.value > 0.05f }, "heard again after unmuting")
    }

    @Test
    fun `a microphone that is not sending is heard as silence, and unmuting needs no new offer`() {
        val talker = engine(ToneInput())
        val listener = engine(ToneInput())
        // connected before the capture opens: the sender is empty and gets the track later
        val link = connect(talker, listener)
        assertTrue(waitFor(10) { link.wireB.state == PeerState.CONNECTED }, "connects")
        assertTrue(runBlocking { talker.startCapture() })
        Thread.sleep(1_500)
        assertTrue(link.b.remoteLevel.value < 0.05f, "silent while not sending (level ${link.b.remoteLevel.value})")
        assertEquals(0f, talker.localLevel.value)

        talker.setSending(true)
        assertTrue(waitFor(10) { link.b.remoteLevel.value > 0.05f }, "heard once sending (level ${link.b.remoteLevel.value})")
    }
}
