package com.cosmotune.manga.data

import com.sun.jna.platform.win32.Crypt32Util

internal actual fun platformMangaFileProtection(): MangaFileProtection {
    if (!System.getProperty("os.name").contains("Windows", true)) return UnsealedMangaFiles
    return object : MangaFileProtection {
        override fun seal(bytes: ByteArray): ByteArray = Crypt32Util.cryptProtectData(bytes)
        override fun open(bytes: ByteArray): ByteArray = Crypt32Util.cryptUnprotectData(bytes)
    }
}

internal actual fun java.io.File.ownerOnly() {
    if (java.nio.file.Files.getFileStore(toPath()).supportsFileAttributeView("posix")) {
        val permissions = if (isDirectory) setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE, java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
            else setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
        java.nio.file.Files.setPosixFilePermissions(toPath(), permissions)
    }
}
