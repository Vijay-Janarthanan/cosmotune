package com.cosmotune.desktop.update

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsUpdateScriptTest {
    private val tmp: File = Files.createTempDirectory("cosmotune win update").toFile()

    private fun install(dir: File, writable: Boolean = true) = DesktopInstall.WindowsInstalled(dir, File(dir, "Cosmotune.exe"), writable)

    @Test fun scriptQuotesAwkwardPaths() {
        val dir = File("C:\\Users\\O'Neil\\My Apps\\Cosmotune")
        val text = WindowsUpdateScript.text(1, File("C:\\Users\\O'Neil\\Temp\\it's.msi"), install(dir), File("C:\\r.txt"), File("C:\\l.txt"))
        assertTrue("'C:\\Users\\O''Neil\\My Apps\\Cosmotune'" in text, text)
        assertTrue("'C:\\Users\\O''Neil\\Temp\\it''s.msi'" in text, text)
    }

    @Test fun scriptElevatesOnlyWhenNotWritable() {
        val dir = File("C:\\Cosmotune")
        assertFalse("RunAs" in WindowsUpdateScript.text(1, File("a.msi"), install(dir), File("r"), File("l")))
        assertTrue("RunAs" in WindowsUpdateScript.text(1, File("a.msi"), install(dir, writable = false), File("r"), File("l")))
    }

    @Test fun failedResultBecomesMessageOnce() {
        val result = File(tmp, "update-result.txt")
        result.writeText("failed 1603")
        assertEquals("The update didn't install. Cosmotune is still on 1.0.23.", UpdateResult.take(result, "1.0.23"))
        assertFalse(result.exists())
        assertNull(UpdateResult.take(result, "1.0.23"))
        result.writeText("ok")
        assertNull(UpdateResult.take(result, "1.0.23"))
        assertFalse(result.exists())
    }

    private fun runScript(installerExit: Int): Triple<File, File, File> {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = File(tmp, "O'Neil apps José").apply { mkdirs() }
        val installed = File(tmp, "installed.txt")
        val relaunched = File(tmp, "relaunched.txt")
        val fakeInstaller = File(tmp, "fake installer.cmd").apply {
            writeText("@echo off\r\necho %*> \"${installed.path}\"\r\nexit /b $installerExit\r\n")
        }
        val fakeApp = File(dir, "Cosmotune.cmd").apply { writeText("@echo off\r\necho started> \"${relaunched.path}\"\r\n") }
        val result = File(tmp, "update-result.txt")
        val ended = ProcessBuilder("cmd", "/c", "exit").start().apply { waitFor() }
        val text = WindowsUpdateScript.text(
            ended.pid(), File(tmp, "Cosmotune-9.9.9-windows-x64.msi"),
            DesktopInstall.WindowsInstalled(dir, fakeApp, writable = true), result, File(tmp, "install.log"), installer = fakeInstaller.path,
        )
        val script = WindowsUpdateScript.write(text, tmp)
        val run = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.path).inheritIO().start()
        assertTrue(run.waitFor(60, TimeUnit.SECONDS), "the script finished")
        val deadline = System.currentTimeMillis() + 30_000
        while (!relaunched.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return Triple(installed, relaunched, result)
    }

    @Test fun installsAndRelaunches() {
        val (installed, relaunched, result) = runScript(0)
        assertTrue(installed.readText().contains("/qn"), installed.readText())
        assertTrue(installed.readText().contains("INSTALLDIR="), installed.readText())
        assertTrue(relaunched.exists(), "Cosmotune was opened again")
        assertEquals("ok", result.readText().trim())
    }

    @Test fun failedInstallRelaunchesOldAndRecordsFailure() {
        val (_, relaunched, result) = runScript(1603)
        assertTrue(relaunched.exists(), "the old Cosmotune was opened again")
        assertEquals("failed 1603", result.readText().trim())
    }
}
