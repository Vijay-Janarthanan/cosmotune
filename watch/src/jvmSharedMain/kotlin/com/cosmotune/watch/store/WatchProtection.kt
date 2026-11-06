package com.cosmotune.watch.store

import java.io.File

/** Seals the Watch store at rest, as the provider and source-library stores are: DPAPI on Windows, the Android Keystore on Android, owner-only files elsewhere. */
interface WatchProtection {
    fun seal(bytes: ByteArray): ByteArray
    fun open(bytes: ByteArray): ByteArray
}

object UnsealedWatchFiles : WatchProtection {
    override fun seal(bytes: ByteArray) = bytes
    override fun open(bytes: ByteArray) = bytes
}

expect fun watchProtection(): WatchProtection
internal expect fun File.ownerOnly()
