package com.cosmotune.video.player.smooth

import java.nio.ByteBuffer

/**
 * A delay for raw audio, in whole frames: what goes in comes out [delayFrames] later, the same length for the same length, so a
 * player's clock (which counts the frames it plays) stays right. Raising the delay inserts silence, lowering it drops the oldest
 * audio. Pure (bytes in, bytes out), so the Media3 processor around it is a few lines and this is tested on the JVM.
 *
 * [frameBytes] is the size of one frame (all channels of one sample); [silence] the byte that is silence (0x80 for unsigned 8-bit
 * audio, else 0); [maxDelayFrames] bounds the delay. One thread at a time.
 */
class PcmDelayLine(private val frameBytes: Int, private val maxDelayFrames: Int, private val silence: Byte = 0) {
    private var ring = ByteArray(maxDelayFrames * frameBytes)
    private var head = 0
    private var held = 0

    /** The delay now, in frames: how much audio is held back. */
    val delayFrames: Int get() = held / frameBytes

    /** Takes the delay to [frames] (clamped to the bound): more silence goes in behind what is held, less drops the oldest audio. */
    fun setDelay(frames: Int) {
        val target = frames.coerceIn(0, maxDelayFrames) * frameBytes
        if (target > held) {
            val add = target - held
            var tail = (head + held) % ring.size
            for (i in 0 until add) {
                ring[tail] = silence
                tail = if (tail + 1 == ring.size) 0 else tail + 1
            }
            held = target
        } else if (target < held) {
            val drop = held - target
            head = (head + drop) % ring.size
            held = target
        }
    }

    /** Forgets what is held and starts again from [frames] of silence (after a flush: a seek, a new stream). */
    fun reset(frames: Int) {
        held = 0
        head = 0
        setDelay(frames)
    }

    /**
     * Moves all of [input] through the line into [output] (which has room for as many bytes): the oldest held audio first, then
     * the start of [input]; what is left of [input] is held. Both buffers are advanced.
     */
    fun process(input: ByteBuffer, output: ByteBuffer) {
        val n = input.remaining()
        if (held == 0) {
            output.put(input)
            return
        }
        val fromHeld = minOf(held, n)
        read(output, fromHeld)
        val direct = n - fromHeld
        if (direct > 0) {
            val limit = input.limit()
            input.limit(input.position() + direct)
            output.put(input)
            input.limit(limit)
        }
        val rest = input.remaining()
        if (rest > 0) write(input, rest)
    }

    /** Moves everything held into [output] (the end of the stream: the last of the audio is not lost). */
    fun drain(output: ByteBuffer) {
        read(output, held)
    }

    /** The bytes held, for sizing the output of [drain]. */
    val heldBytes: Int get() = held

    private fun read(output: ByteBuffer, count: Int) {
        var left = count
        while (left > 0) {
            val chunk = minOf(left, ring.size - head)
            output.put(ring, head, chunk)
            head = (head + chunk) % ring.size
            held -= chunk
            left -= chunk
        }
    }

    private fun write(input: ByteBuffer, count: Int) {
        var tail = (head + held) % ring.size
        var left = count
        while (left > 0) {
            val chunk = minOf(left, ring.size - tail)
            input.get(ring, tail, chunk)
            tail = (tail + chunk) % ring.size
            held += chunk
            left -= chunk
        }
    }
}
