package com.cosmotune.desktop.update

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacUpdateScriptTest {
    private val tmp: File = Files.createTempDirectory("cosmotune mac update").toFile()

    private fun bundle(path: String, writable: Boolean = true) = DesktopInstall.MacBundle(File(path), writable)

    @Test fun scriptQuotesAwkwardPaths() {
        val text = MacUpdateScript.text(1, File("/Users/o'neil/Downloads/it's.zip"), bundle("/Users/o'neil/My Apps/Cosmotune.app"), File("/r"), File("/l"))
        assertTrue("'/Users/o'\\''neil/My Apps/Cosmotune.app'" in text, text)
        assertTrue("'/Users/o'\\''neil/Downloads/it'\\''s.zip'" in text, text)
    }

    @Test fun scriptUsesAdminOnlyWhenNotWritable() {
        assertFalse("administrator privileges" in MacUpdateScript.text(1, File("/a.zip"), bundle("/Applications/Cosmotune.app"), File("/r"), File("/l")))
        assertTrue(
            "administrator privileges" in
                MacUpdateScript.text(1, File("/a.zip"), bundle("/Applications/Cosmotune.app", writable = false), File("/r"), File("/l")),
        )
    }

    @Test fun swapRestoresOldBundleOnFailure() {
        val swap = MacUpdateScript.SWAP
        // The old bundle is moved aside first and moved back when the new one cannot take its place.
        assertTrue("mv \"\$bundle\" \"\$old\" || exit 10" in swap, swap)
        assertTrue("then mv \"\$old\" \"\$bundle\"; exit 11; fi" in swap, swap)
    }

    @Test fun swapsAndOpens() {
        assumeTrue(File("/usr/bin/ditto").exists())
        val apps = File(tmp, "My Apps").apply { mkdirs() }
        val target = File(apps, "Cosmotune.app").apply { File(this, "Contents").mkdirs(); File(this, "Contents/version").writeText("old") }
        val built = File(tmp, "build/Cosmotune.app").apply { File(this, "Contents").mkdirs(); File(this, "Contents/version").writeText("new") }
        val zip = File(tmp, "Cosmotune-9.9.9-macos-arm64.zip")
        assertEquals(0, ProcessBuilder("ditto", "-c", "-k", "--keepParent", built.path, zip.path).start().waitFor())
        val opened = File(tmp, "opened.txt")
        val opener = File(tmp, "opener.sh").apply { writeText("#!/bin/bash\necho \"$1\" > '${opened.path}'\n"); setExecutable(true) }
        val result = File(tmp, "result.txt")
        val ended = ProcessBuilder("true").start().apply { waitFor() }
        val script = File(tmp, "update.sh").apply {
            writeText(MacUpdateScript.text(ended.pid(), zip, DesktopInstall.MacBundle(target, true), result, File(tmp, "log.txt"), opener = opener.path))
        }
        assertTrue(ProcessBuilder("/bin/bash", script.path).inheritIO().start().waitFor(60, TimeUnit.SECONDS))
        assertEquals("new", File(target, "Contents/version").readText())
        assertEquals(target.path, opened.readText().trim())
        assertEquals("ok", result.readText().trim())
    }
}
