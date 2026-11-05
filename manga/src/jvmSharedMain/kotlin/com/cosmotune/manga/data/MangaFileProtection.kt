package com.cosmotune.manga.data

import java.io.File

interface MangaFileProtection {
    fun seal(bytes: ByteArray): ByteArray
    fun open(bytes: ByteArray): ByteArray
}

internal object UnsealedMangaFiles : MangaFileProtection {
    override fun seal(bytes: ByteArray) = bytes
    override fun open(bytes: ByteArray) = bytes
}

internal expect fun platformMangaFileProtection(): MangaFileProtection

/** State and HTTP cookies are private app data, never shareable exports. */
internal expect fun File.ownerOnly()
