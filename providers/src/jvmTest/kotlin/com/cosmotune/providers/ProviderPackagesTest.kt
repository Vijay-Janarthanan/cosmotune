package com.cosmotune.providers

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProviderPackagesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val manifest = """{"name":"fixture","displayName":"Fixture","version":"1.2.0","type":["metadata_provider"],"permissions":{"network":["example.com"],"storage":true}}"""
    private fun archive(extra: String? = null, contents: ByteArray = "asset".toByteArray()): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            listOf("manifest.json" to manifest.toByteArray(), "index.js" to "throw new Error('Must not run during inspection');".toByteArray())
                .plus(if (extra == null) emptyList() else listOf(extra to contents)).forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
                }
        }; out.toByteArray()
    }

    @Test fun importInspectsWithoutExecutingAndPinsTheReviewedBytes() = runBlocking<Unit> {
        val packages = ProviderPackages(temporary.newFolder())
        val review = packages.inspect { archive().inputStream() }
        assertEquals("fixture", review.manifest.name)
        assertEquals(64, review.sha256.length)
        assertEquals(review.sha256, packages.verify(review).sha256)
        review.file.appendBytes(byteArrayOf(1))
        assertFailsWith<IllegalArgumentException> { packages.verify(review) }
    }

    @Test fun unsafeArchivePathsAndBrokenPackagesLeaveNoStagedFiles() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val packages = ProviderPackages(root)
        for (path in listOf("../outside", "/outside", "C:/outside", "folder\\outside", "folder/./outside")) {
            assertFailsWith<IllegalArgumentException> { packages.inspect { archive(path).inputStream() } }
        }
        assertFails { packages.inspect { "not a zip".byteInputStream() } }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun actualExpandedBytesAreLimitedEvenForHighlyCompressedAssets() = runBlocking<Unit> {
        val packed = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("oversized.bin"))
                val chunk = ByteArray(1024 * 1024)
                repeat(65) { zip.write(chunk) }
                zip.closeEntry()
            }; out.toByteArray()
        }
        val root = temporary.newFolder()
        assertTrue(packed.size < 1024 * 1024)
        assertFailsWith<IllegalArgumentException> { ProviderPackages(root).inspect { packed.inputStream() } }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun requiredFeaturesAndSettingsRemainAvailableForCompatibilityAndReview() = runBlocking<Unit> {
        val review = ProviderPackages(temporary.newFolder()).inspect { archive().inputStream() }
        assertEquals(listOf("example.com"), review.manifest.permissions.network)
        assertTrue(review.manifest.permissions.storage)
        assertFalse(review.manifest.homeFeed)
        assertFailsWith<IllegalArgumentException> { review.manifest.copy(permissions = ProviderPermissions(network = listOf("*.com/path"))).validate() }
    }
}
