package com.cosmotune.providers

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderPatternedTransformTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun hex(raw: String) = raw.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val plain = hex("6bc1bee22e409f96e93d7e117393172a")
    private fun cipher(mode: String, decrypt: Boolean = false) = Cipher.getInstance("AES/$mode/NoPadding").apply {
        init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE,
            SecretKeySpec(hex("2b7e151628aed2a6abf7158809cf4f3c"), "AES"),
            IvParameterSpec(hex(if (mode == "CTR") "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff" else "000102030405060708090a0b0c0d0e0f")))
    }
    private fun options(mode: String = "ctr", partial: Boolean = false, size: Int = 16) = buildJsonObject {
        put("mode", mode); put("segmentSize", size); put("transformEvery", 2); put("transformPartial", partial)
    }

    @Test fun selectedSegmentsRestartTheirIvAndPreserveSkippedAndPartialBytes() {
        val directory = temporary.newFolder(); val source = File(directory, "source"); val output = File(directory, "output")
        val tail = byteArrayOf(1, 2, 3, 4)
        source.writeBytes(plain + plain + plain + plain + tail)
        val counts = mutableListOf<Long>()
        val result = ProviderPatternedTransform.run(source, output, options(), cipher("CTR"), 1_000, {}) { bytes, total ->
            assertEquals(source.length(), total); counts += bytes
        }
        val encrypted = hex("874d6191b620e3261bef6864990db6ce")
        assertContentEquals(encrypted + plain + encrypted + plain + tail, output.readBytes())
        assertEquals(JsonPrimitive(2L), result["segments_transformed"])
        assertEquals(listOf(0L, 16L, 32L, 48L, 64L, 68L), counts)
        val restored = File(directory, "restored")
        ProviderPatternedTransform.run(output, restored, options(), cipher("CTR", true), 1_000, {}) { _, _ -> }
        assertContentEquals(source.readBytes(), restored.readBytes())
    }

    @Test fun inPlaceCbcTransformPublishesOnlyAfterClosingTheSource() {
        val source = File(temporary.newFolder(), "same-file").apply { writeBytes(plain + plain + plain) }
        ProviderPatternedTransform.run(source, source, options("cbc"), cipher("CBC"), 1_000, {}) { _, _ -> }
        val encrypted = hex("7649abac8119b246cee98e9b12e9197d")
        assertContentEquals(encrypted + plain + encrypted, source.readBytes())
        assertEquals(listOf(source.name), source.parentFile.list()!!.toList())
    }

    @Test fun cancellationAndBadPartialBlocksKeepThePreviousOutputAndRemoveStaging() {
        val directory = temporary.newFolder(); val source = File(directory, "source").apply { writeBytes(plain + plain + byteArrayOf(1)) }
        val output = File(directory, "output").apply { writeText("original") }
        assertFailsWith<CancellationException> {
            ProviderPatternedTransform.run(source, output, options(), cipher("CTR"), 1_000, {}) { bytes, _ ->
                if (bytes > 0) throw CancellationException("fixture cancellation")
            }
        }
        assertEquals("original", output.readText())
        assertFailsWith<IllegalArgumentException> {
            ProviderPatternedTransform.run(source, output, options("cbc", partial = true), cipher("CBC"), 1_000, {}) { _, _ -> }
        }
        assertEquals("original", output.readText())
        assertEquals(setOf("source", "output"), directory.list()!!.toSet())
    }

    @Test fun quotaAndSegmentValidationFailBeforeReplacingFiles() {
        val directory = temporary.newFolder(); val source = File(directory, "source").apply { writeBytes(plain) }
        val output = File(directory, "output").apply { writeText("original") }
        assertFailsWith<IllegalArgumentException> {
            ProviderPatternedTransform.run(source, output, options(), cipher("CTR"), 15, {}) { _, _ -> }
        }
        for (size in listOf(0, 262_145, 17)) assertFailsWith<IllegalArgumentException> {
            ProviderPatternedTransform.run(source, output, options("cbc", size = size), cipher("CBC"), 1_000, {}) { _, _ -> }
        }
        assertEquals("original", output.readText())
    }
}
