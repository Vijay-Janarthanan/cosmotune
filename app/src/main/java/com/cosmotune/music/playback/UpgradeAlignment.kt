package com.cosmotune.music.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.cosmotune.audio.AcousticAlignment
import com.cosmotune.music.playback.smart.AudioDecoder
import kotlinx.coroutines.*

/**
 * How far the upgraded copy's media clock sits from the original's at the same sound, measured
 * from cached bytes only: checking padding must never download another copy of the song.
 *
 * [originalKey] is the original rendition's cache key, captured before the upgrade was forced,
 * because by now [AudioCache.cacheKeyOf] on the original URI already answers with the upgrade.
 * Both reads are pinned to their own key, so neither can fall through to the network.
 *
 * Bounded at two seconds; the decode polls the coroutine, so the timeout or a cancelled swap
 * stops it rather than leaving it to run on. Null on any doubt, which callers treat as "seek to
 * the same position as before". Only called when [AcousticAlignment.applyToUpgrades] is on.
 */
@UnstableApi
internal suspend fun upgradeOffsetMs(original: Uri, originalKey: String, upgraded: Uri, positionMs: Long): Long? =
    withTimeoutOrNull(2000) {
        withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val old = AudioCache.renditionsOf(original).firstOrNull { it.key == originalKey } ?: return@withContext null
            val newKey = AudioCache.cacheKeyOf(upgraded)
            if (newKey == originalKey) return@withContext null
            val new = AudioCache.renditionsOf(upgraded).firstOrNull { it.key == newKey } ?: return@withContext null
            val start = AcousticAlignment.comparisonStartMs(positionMs)
            fun meter(uri: Uri, rendition: AudioCache.Rendition, fromMs: Long, lengthMs: Long): AcousticAlignment.Envelope? =
                runCatching {
                    AudioCache.renditionDataSource(uri, rendition).use { source ->
                        // Mono, at the stream's own rate; the second value is the real start in
                        // seconds, at or before the one asked for (the extractor lands on a sync
                        // sample), so bins sit on the song's own clock.
                        val (pcm, actualStartSeconds) = AudioDecoder.decodeRegion(source, fromMs / 1000.0, (fromMs + lengthMs) / 1000.0,
                            abort = { !job.isActive }) ?: return@use null
                        AcousticAlignment.Envelope().apply {
                            add(pcm.samples, pcm.samples.size, actualStartSeconds * 1000, pcm.sampleRate.toInt(), channels = 1)
                        }
                    }
                }.getOrNull()
            val reference = meter(original, old, start, AcousticAlignment.WINDOW_MS) ?: return@withContext null
            ensureActive()
            val copy = meter(upgraded, new, start - AcousticAlignment.MARGIN_MS, AcousticAlignment.WINDOW_MS + 2 * AcousticAlignment.MARGIN_MS)
                ?: return@withContext null
            AcousticAlignment.alignedOffset(reference, copy, positionMs)
        }
    }
