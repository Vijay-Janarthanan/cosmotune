package com.cosmotune.music.data.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.cosmotune.music.data.recognition.SongRecognition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** The phone's microphone for song recognition: mono 16-bit at 44.1 kHz, only once RECORD_AUDIO has been granted. */
class AndroidMicrophone(private val context: Context) : SongRecognition.Microphone {

    @SuppressLint("MissingPermission")
    override suspend fun record(millis: Long): SongRecognition.Recording? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        val rate = 44_100
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return@withContext null
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minimum * 2)
        }.getOrNull() ?: return@withContext null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }
        val total = (rate * millis / 1_000).toInt()
        val samples = ShortArray(total)
        var filled = 0
        try {
            record.startRecording()
            while (filled < total && isActive) {
                val read = record.read(samples, filled, minOf(minimum, total - filled))
                if (read <= 0) break
                filled += read
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        if (filled == 0) null else SongRecognition.Recording(samples.copyOf(filled), rate)
    }
}
