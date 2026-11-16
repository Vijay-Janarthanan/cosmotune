package com.cosmotune.audio

/** STREAMINFO uses a 36-bit PCM sample count, not MP4 ticks or a truncated product. */
object FlacTiming {
    fun totalSamples(ticks: Long, sampleRate: Long, timescale: Long): Long {
        require(ticks > 0 && sampleRate in 1..655_350 && timescale > 0) { "Invalid FLAC timing" }
        val (samples, remainder) = java.math.BigInteger.valueOf(ticks).multiply(java.math.BigInteger.valueOf(sampleRate))
            .divideAndRemainder(java.math.BigInteger.valueOf(timescale))
        require(remainder.signum() == 0 && samples.signum() > 0 && samples.bitLength() <= 36) { "FLAC timing cannot be represented without losing samples" }
        return samples.toLong()
    }
}
