package com.cosmotune.music.wear

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The checks around the watch APK this phone carries, kept apart from
 * [WatchInstaller] (and from Android) so a JVM test can run them.
 *
 * The build puts the companion APK and its SHA-256 in the phone's assets
 * (`wear/CosmotuneWear.apk`, `wear/CosmotuneWear.sha256`, see `BundleWearApk`
 * in app/build.gradle.kts). Both ways onto a watch — the Wearable channel
 * update and the first install over Wireless debugging — send a file copied out
 * of the assets, so that copy is checked against the hash before it is used.
 */
internal object WatchApkFile {

    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use(::sha256Hex)

    /** A bundled hash as the build writes it: 64 lowercase hex digits, or null when it is not one. */
    fun normalizedHash(text: String?): String? =
        text?.trim()?.lowercase()?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    /**
     * [target] holding exactly the APK whose SHA-256 is [expectedSha256]:
     * reused when it already does, otherwise copied from [open] through a
     * temporary file and checked. A copy that does not match (a truncated
     * write on a full disk, a damaged asset) is deleted and an IOException
     * thrown, so a bad file is never sent and never kept for the next time.
     */
    fun materialize(target: File, expectedSha256: String, open: () -> InputStream): File {
        val expected = normalizedHash(expectedSha256) ?: throw IOException("no valid hash for the watch APK")
        if (target.isFile && runCatching { sha256Hex(target) }.getOrNull() == expected) return target
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            open().use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw IOException("the bundled watch APK does not match its SHA-256")
            target.delete()
            if (!part.renameTo(target)) throw IOException("could not keep the watch APK")
            return target
        } catch (e: IOException) {
            part.delete()
            throw e
        }
    }

    /**
     * What one watch's answer to [WearProtocol.PATH_WATCH_INFO_REQUEST] means
     * next to this phone's build. Only an answer proves the app is there (Play
     * services lists a watch's capabilities for a while after an uninstall).
     * An answer without a hash on either side cannot be compared: unknown,
     * which still offers the update.
     */
    fun status(answered: Boolean, watchSha256: String?, bundledSha256: String?): WatchAppStatus {
        if (!answered) return WatchAppStatus.NOT_INSTALLED
        val watch = normalizedHash(watchSha256)
        val bundled = normalizedHash(bundledSha256)
        return when {
            watch == null || bundled == null -> WatchAppStatus.UNKNOWN
            watch == bundled -> WatchAppStatus.UP_TO_DATE
            else -> WatchAppStatus.UPDATE_AVAILABLE
        }
    }

    /** Reads `cmd package install`'s answer up to its verdict, not to an end of stream that may never come. */
    fun installerReply(input: InputStream): String {
        val text = StringBuilder()
        val buffer = ByteArray(1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            text.append(String(buffer, 0, read, Charsets.UTF_8))
            // The verdict arrives as one line; either word ends the wait.
            if ("Success" in text || "Failure" in text) break
        }
        return text.toString()
    }

    /** Whether that answer is the installer's success. */
    fun installSucceeded(reply: String): Boolean = "Success" in reply && "Failure" !in reply
}
