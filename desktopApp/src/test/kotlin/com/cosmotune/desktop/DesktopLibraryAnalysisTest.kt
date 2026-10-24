package com.cosmotune.desktop

import com.cosmotune.audio.LibraryAnalysisStore
import com.cosmotune.audio.LibraryLoudness
import com.cosmotune.audio.analyseLocalLibrary
import com.cosmotune.listening.FileLibraryAccess
import com.cosmotune.listening.LocalTagEditor
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.*

/** The desktop library analysis through the real FFmpeg decoder, on temporary files only. */
class DesktopLibraryAnalysisTest {
    private val directory = Files.createTempDirectory("cosmotune-analysis-").toFile()

    @AfterTest fun cleanUp() { directory.deleteRecursively() }

    private fun tone(name: String, dbfs: Double, seconds: Int = 6, rate: Int = 48_000): File {
        val amplitude = 10.0.pow(dbfs / 20.0)
        val bytes = ByteArray(rate * seconds * 4)
        for (frame in 0 until rate * seconds) {
            val v = (amplitude * 32767 * sin(2 * PI * 1000.0 * frame / rate)).toInt()
            for (c in 0..1) { bytes[frame * 4 + c * 2] = v.toByte(); bytes[frame * 4 + c * 2 + 1] = (v shr 8).toByte() }
        }
        val format = AudioFormat(rate.toFloat(), 16, 2, true, false)
        return File(directory, name).also { AudioSystem.write(AudioInputStream(ByteArrayInputStream(bytes), format, (rate * seconds).toLong()), AudioFileFormat.Type.WAVE, it) }
    }

    @Test fun ffmpegDecodeMeasuresLoudnessFormatAndFeedsPlayback() = runBlocking<Unit> {
        val quiet = tone("quiet.wav", -23.0)
        val loud = tone("loud.wav", -9.0)
        val store = LibraryAnalysisStore(File(directory, "analysis.json"))
        val songs = listOf(Song("local:quiet", "Quiet", "A", null, localPath = quiet.path), Song("local:loud", "Loud", "B", null, localPath = loud.path))
        val result = analyseLocalLibrary(songs, FileLibraryAccess, DesktopLibraryPcmDecoder, store)
        assertEquals(2, result.measured); assertEquals(0, result.unsupported + result.failed)
        val q = result.entries.single { it.id == "local:quiet" }
        assertEquals(-23.0, q.integratedLufs!!, 0.2)
        assertEquals(48_000, q.sampleRate); assertTrue(q.codec!!.contains("pcm"), q.codec); assertTrue(q.lossless)
        assertEquals(-9.0, result.entries.single { it.id == "local:loud" }.integratedLufs!!, 0.2)
        assertEquals(5.0, LibraryLoudness.loudnessDbFor("local:loud")!!, 0.2)
        assertEquals(6.0, q.durationSeconds, 0.1)
    }

    @Test fun anUndecodableFileIsCountedAndNothingIsWritten() = runBlocking<Unit> {
        val junk = File(directory, "junk.flac").apply { writeBytes("fLaC".toByteArray() + ByteArray(512) { 3 }) }
        val before = junk.readBytes()
        val result = analyseLocalLibrary(listOf(Song("local:junk", "J", "A", null, localPath = junk.path)), FileLibraryAccess,
            DesktopLibraryPcmDecoder, LibraryAnalysisStore(File(directory, "a.json")))
        assertEquals(0, result.measured); assertEquals(1, result.unsupported + result.failed)
        assertContentEquals(before, junk.readBytes())
        assertTrue(LocalTagEditor.read(File(directory, "missing.mp3").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte())) }).isEmpty())
    }
}
