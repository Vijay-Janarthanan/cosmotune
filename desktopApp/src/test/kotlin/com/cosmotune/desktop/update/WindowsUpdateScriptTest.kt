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
    private val steps = File(tmp, "update-steps.log")
    private val legacyCode = "{45CF57A7-BB69-338B-BAD5-379C7B37609E}"

    private fun install(dir: File, writable: Boolean = true) = DesktopInstall.WindowsInstalled(dir, File(dir, "Cosmotune.exe"), writable)

    @Test fun scriptQuotesAwkwardPaths() {
        val dir = File("C:\\Users\\O'Neil\\My Apps\\Cosmotune")
        val text = WindowsUpdateScript.text(1, File("C:\\Users\\O'Neil\\Temp\\it's.msi"), install(dir), File("C:\\r.txt"), File("C:\\l.txt"), File("C:\\s.txt"))
        assertTrue("'C:\\Users\\O''Neil\\My Apps\\Cosmotune'" in text, text)
        assertTrue("'C:\\Users\\O''Neil\\Temp\\it''s.msi'" in text, text)
    }

    @Test fun installingAlwaysAsksWindowsForPermission() {
        // Cosmotune installs for all users, wherever its folder is, so even a folder this user can write to needs the prompt.
        val text = WindowsUpdateScript.text(1, File("a.msi"), install(File("C:\\Cosmotune")), File("r"), File("l"), File("s"))
        assertTrue("-Verb RunAs" in text, text)
    }

    @Test fun theUpgradeCodeIsTheInstallers() {
        val gradle = File("build.gradle.kts").readText()
        val uuid = Regex("upgradeUuid = \"([0-9a-fA-F-]+)\"").find(gradle)!!.groupValues[1]
        assertEquals("{${uuid.uppercase()}}", WindowsUpdateScript.UPGRADE_CODE)
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

    /**
     * Runs the real script against a stand-in installer (which records each call and exits with [installExit], or [removeExit]
     * for a removal) and a stand-in Cosmotune, without the permission prompt.
     */
    private fun runScript(installExit: Int, legacy: String? = null, removeExit: Int = 0): Triple<File, File, File> {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val dir = File(tmp, "O'Neil apps José").apply { mkdirs() }
        val installed = File(tmp, "installed.txt")
        val relaunched = File(tmp, "relaunched.txt")
        val fakeInstaller = File(tmp, "fake installer.cmd").apply {
            writeText("@echo off\r\necho %*>> \"${installed.path}\"\r\nif \"%1\"==\"/x\" exit /b $removeExit\r\nexit /b $installExit\r\n")
        }
        val fakeApp = File(dir, "Cosmotune.cmd").apply { writeText("@echo off\r\necho started> \"${relaunched.path}\"\r\n") }
        val result = File(tmp, "update-result.txt")
        val ended = ProcessBuilder("cmd", "/c", "exit").start().apply { waitFor() }
        val text = WindowsUpdateScript.text(
            ended.pid(), File(tmp, "Cosmotune-9.9.9-windows-x64.msi"),
            DesktopInstall.WindowsInstalled(dir, fakeApp, writable = true), result, File(tmp, "install.log"), steps,
            upgradeCode = null, legacyProductCode = legacy, installer = fakeInstaller.path, elevate = false,
        )
        val script = WindowsUpdateScript.write(text, tmp)
        val run = ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.path).inheritIO().start()
        assertTrue(run.waitFor(60, TimeUnit.SECONDS), "the script finished")
        val deadline = System.currentTimeMillis() + 30_000
        while (!relaunched.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
        return Triple(installed, relaunched, result)
    }

    @Test fun aStuckReadOnlyScriptFromBeforeDoesNotStopTheUpdate() {
        // Windows answers a write over a read-only file with "(Access is denied)", the error the update showed.
        val dir = File(tmp, "stuck").apply { mkdirs() }
        val stuck = File(dir, "cosmotune-update.ps1").apply { writeText("old"); setWritable(false) }
        try {
            val written = WindowsUpdateScript.write("Write-Output 'new'", dir)
            assertTrue(written.path != stuck.path, "a file of its own: ${written.path}")
            assertTrue(written.readText().endsWith("Write-Output 'new'"))
            assertEquals("old", stuck.readText(), "the stuck one is left as it was")
        } finally {
            stuck.setWritable(true)
        }
    }

    @Test fun installsAndRelaunches() {
        val (installed, relaunched, result) = runScript(0)
        val calls = installed.readText()
        assertTrue(calls.contains("/qn") && calls.contains("INSTALLDIR="), calls)
        assertFalse("/x " in calls, "nothing to remove: $calls")
        assertTrue(relaunched.exists(), "Cosmotune was opened again")
        assertEquals("ok", result.readText().trim())
        val log = steps.readText()
        assertTrue("install finished: 0" in log && "opened" in log, log)
    }

    @Test fun failedInstallRelaunchesOldAndRecordsFailure() {
        val (_, relaunched, result) = runScript(1603)
        assertTrue(relaunched.exists(), "the old Cosmotune was opened again")
        assertEquals("failed 1603", result.readText().trim())
    }

    @Test fun aCopyForThisUserOnlyIsRemovedFirstAndTheNewOneGoesIntoTheSameFolder() {
        val (installed, relaunched, result) = runScript(0, legacy = legacyCode)
        val calls = installed.readText()
        assertTrue("/x $legacyCode" in calls, "the copy for this user only is removed: $calls")
        assertTrue(calls.indexOf("/x ") < calls.indexOf("/i "), "removed before the new one goes into its folder: $calls")
        // Up to the "é", which the stand-in (a .cmd) records in the console's code page; the relaunch below shows the path arrives whole.
        assertTrue("INSTALLDIR=\"${File(tmp, "O'Neil apps Jos").path}" in calls, calls)
        assertTrue(relaunched.exists(), "Cosmotune was opened again from its folder")
        assertEquals("ok", result.readText().trim())
    }

    @Test fun aRemovalThatFailedInstallsNothingAndKeepsTheOldCopy() {
        val (installed, relaunched, result) = runScript(0, legacy = legacyCode, removeExit = 1603)
        assertFalse("/i " in installed.readText(), "no install over a copy that is still there: ${installed.readText()}")
        assertTrue(relaunched.exists(), "the old Cosmotune was opened again")
        assertEquals("failed 1603", result.readText().trim())
    }
}
