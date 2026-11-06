package com.cosmotune.watch.store

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission as Permission

actual fun watchProtection(): WatchProtection {
    if (!System.getProperty("os.name").contains("Windows", true)) return UnsealedWatchFiles
    return object : WatchProtection {
        override fun seal(bytes: ByteArray): ByteArray = Crypt32Util.cryptProtectData(bytes)
        override fun open(bytes: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(bytes)
    }
}

internal actual fun File.ownerOnly() {
    val view = Files.getFileAttributeView(toPath(), PosixFileAttributeView::class.java) ?: return
    view.setPermissions(if (isDirectory) setOf(Permission.OWNER_READ, Permission.OWNER_WRITE, Permission.OWNER_EXECUTE)
        else setOf(Permission.OWNER_READ, Permission.OWNER_WRITE))
}
