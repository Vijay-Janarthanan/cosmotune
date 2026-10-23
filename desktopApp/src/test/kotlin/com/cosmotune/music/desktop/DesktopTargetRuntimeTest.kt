package com.cosmotune.music.desktop

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
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
            }
        }
    }
}
