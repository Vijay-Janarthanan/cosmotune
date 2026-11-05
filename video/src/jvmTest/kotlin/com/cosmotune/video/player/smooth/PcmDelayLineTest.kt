package com.cosmotune.video.player.smooth

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The audio delay: what goes in comes out later by the delay, the same length for the same length. */
class PcmDelayLineTest {
    /** 16-bit stereo: 4 bytes a frame. */
    private val frame = 4

    /** [frames] frames whose bytes count up from [from], so every byte of the stream is told apart. */
    private fun pcm(from: Int, frames: Int): ByteArray = ByteArray(frames * frame) { (from + it).toByte() }

    private fun run(line: PcmDelayLine, input: ByteArray): ByteArray {
        val output = ByteBuffer.allocate(input.size)
        line.process(ByteBuffer.wrap(input), output)
        assertEquals(input.size, output.position(), "as many bytes out as in")
        return output.array()
    }

    @Test
    fun withNoDelayEverythingPassesThrough() {
        val line = PcmDelayLine(frame, 100)
        val input = pcm(1, 10)
        assertContentEquals(input, run(line, input))
        assertEquals(0, line.delayFrames)
    }

    @Test
    fun aDelayStartsWithSilenceThenTheAudioFollows() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(3)
        val input = pcm(1, 10)
        val out = run(line, input)
        assertContentEquals(ByteArray(3 * frame), out.copyOfRange(0, 3 * frame))
        assertContentEquals(input.copyOfRange(0, 7 * frame), out.copyOfRange(3 * frame, 10 * frame))
        // the last three frames are held and come out with the next buffer
        val next = pcm(50, 4)
        val out2 = run(line, next)
        assertContentEquals(input.copyOfRange(7 * frame, 10 * frame), out2.copyOfRange(0, 3 * frame))
        assertContentEquals(next.copyOfRange(0, frame), out2.copyOfRange(3 * frame, 4 * frame))
    }

    @Test
    fun aBufferShorterThanTheDelayStillComesOutSilentThenDelayed() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(5)
        val a = run(line, pcm(1, 2))
        assertContentEquals(ByteArray(2 * frame), a)
        val b = run(line, pcm(100, 2))
        assertContentEquals(ByteArray(2 * frame), b)
        val c = run(line, pcm(7, 2))
        // 5 frames of silence were in front: 2 + 2 out so far, the fifth silent frame, then the first audio
        assertContentEquals(ByteArray(frame), c.copyOfRange(0, frame))
        assertContentEquals(pcm(1, 2).copyOfRange(0, frame), c.copyOfRange(frame, 2 * frame))
    }

    @Test
    fun theWholeStreamIsKeptInOrderAcrossBuffersOfAnySize() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(7)
        val sizes = listOf(1, 5, 12, 3, 20, 2, 9)
        var next = 0
        val sent = ArrayList<Byte>()
        val got = ArrayList<Byte>()
        for (size in sizes) {
            val input = pcm(next, size)
            next += size * frame
            input.forEach(sent::add)
            run(line, input).forEach(got::add)
        }
        val drained = ByteBuffer.allocate(line.heldBytes)
        line.drain(drained)
        drained.array().forEach(got::add)
        // silence in front, then exactly what was sent, nothing lost at the end
        assertEquals(7 * frame + sent.size, got.size)
        assertContentEquals(ByteArray(7 * frame).toList(), got.take(7 * frame))
        assertContentEquals(sent, got.drop(7 * frame))
        assertEquals(0, line.heldBytes)
    }

    @Test
    fun raisingTheDelayInsertsSilenceBehindWhatIsHeld() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(2)
        run(line, pcm(1, 4)) // 2 frames held
        line.setDelay(5)
        assertEquals(5, line.delayFrames)
        val out = run(line, pcm(60, 6))
        val firstHeld = pcm(1, 4).copyOfRange(2 * frame, 4 * frame)
        assertContentEquals(firstHeld, out.copyOfRange(0, 2 * frame))
        assertContentEquals(ByteArray(3 * frame), out.copyOfRange(2 * frame, 5 * frame))
        assertContentEquals(pcm(60, 6).copyOfRange(0, frame), out.copyOfRange(5 * frame, 6 * frame))
    }

    @Test
    fun loweringTheDelayDropsTheOldestAudio() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(4)
        run(line, pcm(0, 4)) // out: silence; held: the four frames
        line.setDelay(1)
        assertEquals(1, line.delayFrames)
        val out = run(line, pcm(100, 3))
        // three frames of the original are skipped, the fourth comes out, then the new audio
        assertContentEquals(pcm(0, 4).copyOfRange(3 * frame, 4 * frame), out.copyOfRange(0, frame))
        assertContentEquals(pcm(100, 3).copyOfRange(0, 2 * frame), out.copyOfRange(frame, 3 * frame))
    }

    @Test
    fun removingTheDelayPassesAudioStraightAgain() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(3)
        run(line, pcm(0, 5))
        line.setDelay(0)
        val input = pcm(9, 6)
        assertContentEquals(input, run(line, input))
    }

    @Test
    fun resetStartsFromSilenceAgain() {
        val line = PcmDelayLine(frame, 100)
        line.setDelay(3)
        run(line, pcm(0, 10))
        line.reset(3)
        val out = run(line, pcm(200, 5))
        assertContentEquals(ByteArray(3 * frame), out.copyOfRange(0, 3 * frame))
        assertContentEquals(pcm(200, 5).copyOfRange(0, 2 * frame), out.copyOfRange(3 * frame, 5 * frame))
    }

    @Test
    fun theDelayIsBounded() {
        val line = PcmDelayLine(frame, 10)
        line.setDelay(1_000)
        assertEquals(10, line.delayFrames)
    }

    @Test
    fun unsignedEightBitAudioIsSilentAt128() {
        val line = PcmDelayLine(frameBytes = 1, maxDelayFrames = 10, silence = 0x80.toByte())
        line.setDelay(2)
        val out = ByteBuffer.allocate(4)
        line.process(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), out)
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 1, 2), out.array())
    }

    @Test
    fun aBufferThatWrapsTheRingIsStillInOrder() {
        // many small buffers walk the read and write positions round a ring of exactly the delay's size
        val line = PcmDelayLine(frame, 3)
        line.setDelay(3)
        var next = 0
        val sent = ArrayList<Byte>()
        val got = ArrayList<Byte>()
        repeat(20) {
            val input = pcm(next, 2)
            next += 2 * frame
            input.forEach(sent::add)
            run(line, input).forEach(got::add)
        }
        assertContentEquals(sent.take(sent.size - 3 * frame), got.drop(3 * frame))
    }
}
