package com.cosmotune.music.playback.smart

import java.nio.file.Files
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BundledModelFileTest {
    @Test
    fun extractsNewModelsAndReplacesSameLengthOldWeights() {
        val directory = Files.createTempDirectory("model-upgrade").toFile()
        try {
            val file = directory.resolve("weights.onnx")
            BundledModelFile.install(file) { byteArrayOf(1, 2, 3).inputStream() }
            assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
            BundledModelFile.install(file) { byteArrayOf(4, 5, 6).inputStream() }
            assertContentEquals(byteArrayOf(4, 5, 6), file.readBytes())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun identicalModelKeepsItsExistingFile() {
        val directory = Files.createTempDirectory("model-retained").toFile()
        try {
            val file = directory.resolve("weights.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            file.setLastModified(10000)
            val timestamp = file.lastModified()
            BundledModelFile.install(file) { byteArrayOf(1, 2, 3).inputStream() }
            assertEquals(timestamp, file.lastModified())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun interruptedExtractionPreservesPreviousModelAndRemovesPartialFile() {
        val directory = Files.createTempDirectory("model-interrupted").toFile()
        try {
            val file = directory.resolve("weights.onnx").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            var opens = 0
            assertFailsWith<IOException> {
                BundledModelFile.install(file) {
                    if (++opens == 2) throw IOException("interrupted copy")
                    byteArrayOf(4, 5, 6).inputStream()
                }
            }
            assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
            assertEquals(listOf("weights.onnx"), directory.listFiles()!!.map { it.name })
        } finally { directory.deleteRecursively() }
    }
}
