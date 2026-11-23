package com.cosmotune.desktop

import com.cosmotune.music.desktop.DesktopAudioDecoder
import com.cosmotune.music.desktop.DesktopPcmFormat
import com.cosmotune.watch.PcmClip
import com.cosmotune.watch.WatchSpeechSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Sync automatically's ears on the computer: FFmpeg decodes a stretch of the copy's sound, mixed to one channel. */
internal object DesktopSpeechSampler : WatchSpeechSampler {
    override suspend fun sample(url: String, headers: Map<String, String>, fromMs: Long, lengthMs: Long): PcmClip? = withContext(Dispatchers.IO) {
        val address = if (url.startsWith("file:")) runCatching { java.io.File(java.net.URI(url)).path }.getOrDefault(url) else url
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(address, headers, DesktopPcmFormat(sampleRate = 16_000, channels = 1, bytesPerSample = 4, isFloat = true)).getOrNull() ?: return@withContext null
            if (fromMs > 0 && !decoder.seek(fromMs * 1000)) return@withContext null
            val rate = decoder.outputFormat.sampleRate
            val wanted = (rate.toLong() * lengthMs / 1000).toInt()
            val out = FloatArray(wanted)
            var filled = 0
            while (filled < wanted) {
                ensureActive()
                val block = decoder.readSamples() ?: break
                val count = minOf(decoder.sampleCount, wanted - filled)
                System.arraycopy(block, 0, out, filled, count)
                filled += count
            }
            if (filled < rate * 20) null else PcmClip(fromMs, rate, if (filled == wanted) out else out.copyOf(filled))
        } finally {
            decoder.close()
        }
    }
}
