package com.cosmotune.music.desktop

/** Little-endian PCM written to the selected endpoint; container depth never comes from source tags. */
internal fun encodeDesktopPcm(samples: FloatArray, count: Int, format: DesktopPcmFormat, gain: Float, reuse: ByteArray? = null): ByteArray {
    require(count in 0..samples.size)
    require(format.bytesPerSample in 2..4 && (!format.isFloat || format.bytesPerSample == 4))
    val size = count * format.bytesPerSample
    val out = reuse?.takeIf { it.size >= size } ?: ByteArray(size)
    for (index in 0 until count) {
        val finite = (samples[index] * gain).takeIf { it.isFinite() } ?: 0f
        val sample = if (format.isFloat) finite else finite.coerceIn(-1f, 1f)
        val value = when {
            format.isFloat -> sample.toRawBits()
            format.bytesPerSample == 4 -> kotlin.math.round(sample.toDouble() * 2_147_483_648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            format.bytesPerSample == 3 -> kotlin.math.round(sample.toDouble() * 8_388_608.0).toInt().coerceIn(-8_388_608, 8_388_607)
            else -> kotlin.math.round(sample * 32_768).toInt().coerceIn(-32_768, 32_767)
        }
        repeat(format.bytesPerSample) { byte -> out[index * format.bytesPerSample + byte] = (value shr (byte * 8)).toByte() }
    }
    return out
}
