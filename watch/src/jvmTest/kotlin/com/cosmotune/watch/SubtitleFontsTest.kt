package com.cosmotune.watch

import com.cosmotune.watch.store.UnsealedWatchFiles
import com.cosmotune.watch.store.WatchStore
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleFontsTest {
    private val ttf = byteArrayOf(0, 1, 0, 0) + ByteArray(60) { 7 }

    @Test fun fontFilesAreKnownByTheirFirstBytes() {
        assertEquals("ttf", SubtitleFonts.extensionOf(ttf))
        assertEquals("otf", SubtitleFonts.extensionOf("OTTO".toByteArray() + ByteArray(20)))
        assertEquals("ttc", SubtitleFonts.extensionOf("ttcf".toByteArray() + ByteArray(20)))
        assertNull(SubtitleFonts.extensionOf("<html>not a font</html>".toByteArray()))
    }

    @Test fun anImportedFontIsKeptOnceAndAnythingElseIsRefused() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("watch-font").toFile().also { it.deleteOnExit() }
        val repository = DefaultWatchRepository(WatchConfig(OkHttpClient(), dir, null), WatchStore(File(dir, "watch.json"), UnsealedWatchFiles), builtIns = { _, _ -> emptyList() })
        val path = repository.importSubtitleFont("Atkinson.ttf", ttf)
        assertTrue(File(path).isFile && path.endsWith(".ttf"))
        assertEquals(path, repository.importSubtitleFont("Again.ttf", ttf))
        assertFailsWith<IllegalStateException> { repository.importSubtitleFont("notes.txt", "hello there, not a font".toByteArray()) }
    }

    @Test fun yourFontWithoutAFileFallsBackToTheAppFont() {
        assertEquals(SubtitleFont.APP, WatchSubtitleStyle(font = SubtitleFont.YOURS).coerced().font)
        assertEquals(SubtitleFont.YOURS, WatchSubtitleStyle(font = SubtitleFont.YOURS, fontPath = "/x.ttf").coerced().font)
    }
}
