package com.cosmotune.music.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/**
 * Credentials kept by the desktop app (Last.fm session key and API secret, ListenBrainz token, the
 * Discord token and the YouTube Music cookie fallback), never as plain preference values (LK-20).
 *
 * Windows: sealed with current-user DPAPI and stored as `<key>.sealed`. Elsewhere: one owner-only
 * file per credential (directory 0700, file 0600) in the app's data directory, the same rule the
 * provider and source-library stores use. A value found under the old plain key is moved on first
 * read and the plain key is removed. Values are never logged.
 */
internal class DesktopSecretPreferences(
    private val plain: Store,
    private val sealer: Sealer?,
    private val directory: File?,
) {
    interface Store { fun get(key: String): String?; fun put(key: String, value: String); fun remove(key: String) }
    interface Sealer { fun protect(plain: ByteArray): ByteArray?; fun unprotect(sealed: ByteArray): ByteArray? }

    fun read(key: String): String {
        requireKey(key)
        val sealing = sealer
        val stored = if (sealing != null) {
            plain.get("$key$SEALED")?.let { sealed ->
                runCatching { sealing.unprotect(Base64.getDecoder().decode(sealed)) }.getOrNull()?.toString(Charsets.UTF_8)
            }
        } else file(key)?.takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        if (!stored.isNullOrEmpty()) return stored
        val legacy = plain.get(key)?.takeIf { it.isNotEmpty() } ?: return ""
        // Moved out of the plain preference on first read; kept there only if no protected store works.
        runCatching { write(key, legacy) }
        return legacy
    }

    fun write(key: String, value: String) {
        requireKey(key)
        if (value.isEmpty()) {
            plain.remove(key); plain.remove("$key$SEALED"); file(key)?.delete(); return
        }
        val sealing = sealer
        if (sealing != null) {
            val sealed = sealing.protect(value.toByteArray(Charsets.UTF_8)) ?: error("Windows could not protect this credential")
            plain.put("$key$SEALED", Base64.getEncoder().encodeToString(sealed))
            plain.remove(key)
            return
        }
        val target = checkNotNull(file(key)) { "No private folder for credentials" }
        val folder = target.parentFile
        check(folder.isDirectory || folder.mkdirs())
        ownerOnly(folder, directory = true)
        val temporary = File.createTempFile("secret-", ".part", folder)
        try {
            ownerOnly(temporary, directory = false)
            temporary.writeText(value, Charsets.UTF_8)
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            ownerOnly(target, directory = false)
        } finally { temporary.delete() }
        plain.remove(key)
    }

    private fun file(key: String): File? = directory?.let { File(it, key.replace(Regex("[^A-Za-z0-9_-]"), "_")) }

    // Java preference keys are limited to 80 characters; ".sealed" is added to this one.
    private fun requireKey(key: String) = require(key.isNotBlank() && key.length <= 72) { "Invalid credential key" }

    companion object {
        private const val SEALED = ".sealed"

        private fun ownerOnly(file: File, directory: Boolean) {
            val posix = runCatching {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
            }.isSuccess
            if (!posix) {
                file.setReadable(false, false); file.setWritable(false, false)
                file.setReadable(true, true); file.setWritable(true, true)
                if (directory) { file.setExecutable(false, false); file.setExecutable(true, true) }
            }
        }

        /** The app's own credential store: the user's preference node plus DPAPI or the private data folder. */
        val shared: DesktopSecretPreferences by lazy {
            val persistence = DesktopPersistence()
            val store = object : Store {
                override fun get(key: String): String? = DesktopPreferenceChunks.read(persistence.preferences, key)
                override fun put(key: String, value: String) { DesktopPreferenceChunks.write(persistence.preferences, key, value) }
                override fun remove(key: String) { DesktopPreferenceChunks.remove(persistence.preferences, key) }
            }
            val sealer = if (DesktopPlatform.isWindows) object : Sealer {
                override fun protect(plain: ByteArray) = DesktopWindowsCrypto.protect(plain)
                override fun unprotect(sealed: ByteArray) = DesktopWindowsCrypto.unprotect(sealed)
            } else null
            val home = System.getProperty("user.home")
            val data = when {
                DesktopPlatform.isWindows -> null
                System.getProperty("os.name").lowercase(java.util.Locale.ROOT).contains("mac") -> File(home, "Library/Application Support/Cosmotune/credentials")
                else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "cosmotune/credentials")
            }
            DesktopSecretPreferences(store, sealer, data)
        }
    }
}
