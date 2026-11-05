package com.cosmotune.music.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Installing and updating the watch app from the phone: the bundled APK, its hash and the watch's answer. */
class WatchApkFileTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val apk = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @Test
    fun `the hash is the same lowercase hex the build writes`() {
        assertEquals(sha, WatchApkFile.sha256Hex(ByteArrayInputStream(apk)))
        assertEquals(sha, WatchApkFile.normalizedHash(" ${sha.uppercase()}\n"))
        assertNull(WatchApkFile.normalizedHash(sha.dropLast(1)))
        assertNull(WatchApkFile.normalizedHash("z".repeat(64)))
        assertNull(WatchApkFile.normalizedHash(null))
    }

    @Test
    fun `the bundled APK is copied out and checked`() {
        val target = File(temp.root, "wear/CosmotuneWear.apk")
        var opened = 0
        val file = WatchApkFile.materialize(target, sha) { opened++; ByteArrayInputStream(apk) }
        assertArrayEquals(apk, file.readBytes())
        assertEquals(1, opened)
        // A good copy is reused, without reading the asset again.
        WatchApkFile.materialize(target, sha) { opened++; ByteArrayInputStream(apk) }
        assertEquals(1, opened)
        assertFalse(File(target.parentFile, "CosmotuneWear.apk.part").exists())
    }

    @Test
    fun `a short or damaged copy is never kept or sent`() {
        val target = File(temp.root, "wear/CosmotuneWear.apk")
        val failure = runCatching {
            WatchApkFile.materialize(target, sha) { ByteArrayInputStream(apk.copyOf(apk.size / 2)) }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(target.exists())
        assertFalse(File(target.parentFile, "CosmotuneWear.apk.part").exists())
    }

    @Test
    fun `a stale file in the cache is replaced, not trusted`() {
        val target = File(temp.root, "wear/CosmotuneWear.apk")
        target.parentFile!!.mkdirs()
        target.writeBytes(apk.copyOf(1_000))
        WatchApkFile.materialize(target, sha) { ByteArrayInputStream(apk) }
        assertEquals(sha, WatchApkFile.sha256Hex(target))
    }

    @Test
    fun `a build without a valid hash refuses to copy`() {
        val failure = runCatching {
            WatchApkFile.materialize(File(temp.root, "a.apk"), "") { ByteArrayInputStream(apk) }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
    }

    @Test
    fun `what the watch's answer means`() {
        assertEquals(WatchAppStatus.NOT_INSTALLED, WatchApkFile.status(answered = false, watchSha256 = null, bundledSha256 = sha))
        assertEquals(WatchAppStatus.UP_TO_DATE, WatchApkFile.status(true, sha, sha))
        assertEquals(WatchAppStatus.UP_TO_DATE, WatchApkFile.status(true, sha.uppercase(), "$sha\n"))
        assertEquals(WatchAppStatus.UPDATE_AVAILABLE, WatchApkFile.status(true, "0".repeat(64), sha))
        // An answer with no hash, or a phone build that carries none, cannot be compared.
        assertEquals(WatchAppStatus.UNKNOWN, WatchApkFile.status(true, null, sha))
        assertEquals(WatchAppStatus.UNKNOWN, WatchApkFile.status(true, sha, null))
    }

    @Test
    fun `the installer's verdict is read without waiting for the end of the stream`() {
        val chunks = listOf("Performing Streamed Install\n", "Succ", "ess\n")
        var served = 0
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // A stream that would block forever after the verdict.
                check(served < chunks.size) { "read past the verdict" }
                val bytes = chunks[served++].toByteArray()
                System.arraycopy(bytes, 0, b, off, bytes.size)
                return bytes.size
            }
        }
        val reply = WatchApkFile.installerReply(input)
        assertTrue(WatchApkFile.installSucceeded(reply))
        assertFalse(WatchApkFile.installSucceeded("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]"))
        assertFalse(WatchApkFile.installSucceeded(""))
    }
}
