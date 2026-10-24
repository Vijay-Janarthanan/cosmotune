package com.cosmotune.desktop

import com.cosmotune.audio.LocalPcmDecoder
import com.cosmotune.audio.LocalPcmStream
import com.cosmotune.audio.WavPcmDecoder
import com.cosmotune.listening.LocalLibraryFile
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.desktop.DesktopAudioDecoder
import com.cosmotune.music.desktop.DesktopPcmFormat
import java.io.File

/**
 * Library analysis through the same FFmpeg decoder desktop playback uses. Only local files are
 * opened; the decoder output is float stereo at the file's own sample rate.
 */
internal object DesktopLibraryPcmDecoder : LocalPcmDecoder {
    override fun open(song: Song, file: LocalLibraryFile): LocalPcmStream {
        val path = File(file.identity)
        if (!path.isAbsolute || !path.isFile) throw UnsupportedOperationException("Only local files are analysed")
        val decoder = DesktopAudioDecoder()
        val opened = decoder.open(path.path, requested = DesktopPcmFormat(44_100, 2, 4, isFloat = true))
        if (opened.isFailure) {
            decoder.close()
            // A WAV file can still be measured when the native decoder is not installed.
            return runCatching { WavPcmDecoder.open(song, file) }.getOrElse { throw UnsupportedOperationException("This file could not be decoded") }
        }
        val measured = decoder.measuredFormat
        val genre = decoder.genre
        return object : LocalPcmStream {
            override val sampleRate = decoder.outputFormat.sampleRate
            override val channels = decoder.outputFormat.channels
            override val codec = measured?.codec
            override val bitsPerSample = measured?.bitDepth
            override val bitrate = measured?.kbps?.toLong()?.times(1000)
            override val tags = listOfNotNull(genre?.let { "genre" to it }).toMap()
            override var count = 0
                private set
            override fun read(): FloatArray? {
                while (true) {
                    val block = decoder.readSamples() ?: return null
                    count = decoder.sampleCount
                    if (count > 0) return block
                }
            }
            override fun close() = decoder.close()
        }
    }
}
