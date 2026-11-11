package com.cosmotune.music.playback

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.cosmotune.audio.AcousticAlignment
import com.cosmotune.music.playback.smart.AudioDecoder
import kotlinx.coroutines.*

/** Only cached bytes: checking padding must never download another copy of the song. */
@UnstableApi
internal suspend fun upgradeOffsetMs(original: Uri, originalKey: String, upgraded: Uri, positionMs: Long): Long? =
    withTimeoutOrNull(2000) {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]!!
            val old = AudioCache.renditionsOf(original).firstOrNull { it.key == originalKey } ?: return@withContext null
            val newKey = AudioCache.cacheKeyOf(upgraded)
            val new = AudioCache.renditionsOf(upgraded).firstOrNull { it.key == newKey } ?: return@withContext null
            val start = ((positionMs - 2600).coerceAtLeast(500) / 10) * 10
            fun read(uri: Uri, rendition: AudioCache.Rendition, from: Long, length: Long): FloatArray? =
                runCatching {
                    AudioCache.renditionDataSource(uri, rendition).use { source ->
                        val (pcm, actualStart) = AudioDecoder.decodeRegion(source, from / 1000.0, (from + length) / 1000.0, abort = { !job.isActive }) ?: return@use null
                        AcousticAlignment.Envelope().apply { add(pcm.samples, pcm.samples.size, actualStart * 1000, pcm.sampleRate.toInt()) }
                            .read(from, (length / 10).toInt())
                    }
                }.getOrNull()
            val reference = read(original, old, start, 2000) ?: return@withContext null
            val copy = read(upgraded, new, start - 500, 3000) ?: return@withContext null
            AcousticAlignment.offset(reference, copy)
        }
    }
