package com.cosmotune.watch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchCacheFilesTest {
    @Test fun trimmingDropsTheOldestFilesFirst() {
        val dir = Files.createTempDirectory("watch-cache").toFile().also { it.deleteOnExit() }
        val old = dir.resolve("old").apply { writeBytes(ByteArray(600)); setLastModified(1_000_000) }
        val mid = dir.resolve("sub/mid").apply { parentFile.mkdirs(); writeBytes(ByteArray(300)); setLastModified(2_000_000) }
        val new = dir.resolve("new").apply { writeBytes(ByteArray(200)); setLastModified(3_000_000) }
        assertEquals(1100, WatchCacheFiles.size(dir))
        WatchCacheFiles.trim(dir, 600)
        assertFalse(old.exists())
        assertTrue(mid.exists() && new.exists())
        WatchCacheFiles.trim(dir, 0)
        assertEquals(0, WatchCacheFiles.size(dir))
    }
}
