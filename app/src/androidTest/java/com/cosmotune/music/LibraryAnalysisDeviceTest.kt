package com.cosmotune.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.audio.LibraryAnalysisStore
import com.cosmotune.audio.analyseLocalLibrary
import com.cosmotune.listening.PhoneLibraryAccess
import com.cosmotune.listening.PhonePcmDecoder
import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** MediaCodec library analysis on a real device or emulator, on an app-private WAV file. Not run in CI by default. */
@RunWith(AndroidJUnit4::class)
class LibraryAnalysisDeviceTest {
    @Test fun mediaCodecDecodeMeasuresATone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val rate = 44_100
        val pcm = ByteBuffer.allocate(rate * 6 * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until rate * 6) { val v = (0.0708 * 32767 * sin(2 * PI * 1000.0 * f / rate)).toInt().toShort(); pcm.putShort(v); pcm.putShort(v) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(pcm.capacity())
        val file = File(context.cacheDir, "analysis-probe.wav").apply { writeBytes(header.array() + pcm.array()) }
        val store = LibraryAnalysisStore(File(context.cacheDir, "analysis-probe.json"))
        val result = analyseLocalLibrary(listOf(Song("local:probe", "Probe", "Test", null, localPath = file.path)),
            PhoneLibraryAccess(context), PhonePcmDecoder(context), store)
        assertEquals(1, result.measured)
        assertEquals(-23.0, result.entries.single().integratedLufs!!, 0.3)
        assertTrue(result.entries.single().lossless)
        file.delete(); File(context.cacheDir, "analysis-probe.json").delete()
    }
}
