package com.cosmotune.music.desktop

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OnnxTensor
import java.nio.FloatBuffer
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The filtered jars must still initialise JNI and retain their service/resource metadata. */
class DesktopTargetRuntimeTest {
    @Test
    fun sqliteLoadsItsTargetNativeAndExecutesSql() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 40 + 2").use { result ->
                    assertTrue(result.next())
                    assertEquals(42, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun onnxLoadsItsTargetNativeAndTheBundledBeatModel() {
        val model = javaClass.getResourceAsStream("/models/beat_this_int8.onnx")!!.use { it.readBytes() }
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1)
            OrtEnvironment.getEnvironment().createSession(model, options).use { session ->
                assertTrue(session.inputNames.isNotEmpty())
                val input = FloatArray(256 * 128) { -4f + kotlin.math.sin(it * 0.013).toFloat() }
                OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input), longArrayOf(1, 256, 128)).use { tensor ->
                    session.run(mapOf("input_spectrogram" to tensor)).use { result ->
                        assertEquals(2, result.size(), "beat and downbeat outputs")
                        repeat(result.size()) { index ->
                            val output = (result[index] as OnnxTensor).floatBuffer
                            assertEquals(256, output.remaining())
                            while (output.hasRemaining()) assertTrue(output.get().isFinite())
                        }
                    }
                }
            }
        }
    }
}
