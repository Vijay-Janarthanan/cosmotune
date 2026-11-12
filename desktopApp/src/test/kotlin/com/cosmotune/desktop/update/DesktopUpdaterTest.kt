package com.cosmotune.desktop.update

import com.cosmotune.music.desktop.DesktopUpdateChecker
import com.cosmotune.update.UpdateTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopUpdaterTest {
    private val dir: File = Files.createTempDirectory("cosmotune-updates").toFile()
    private val windows = DesktopInstall.WindowsInstalled(File("C:\\Cosmotune"), File("C:\\Cosmotune\\Cosmotune.exe"), writable = true)
    private val info = DesktopUpdateChecker.UpdateInfo(
        version = "9.9.9",
        releaseUrl = "https://example.invalid/release",
        downloadUrl = "https://example.invalid/setup.exe",
        notes = null,
        assets = listOf(DesktopUpdateChecker.ReleaseAsset("Cosmotune-9.9.9-windows-x64.msi", "https://example.invalid/app.msi", 10)),
    )

    private class Fake {
        var fetches = 0
        val gate = CompletableDeferred<Unit>()
        val opened = mutableListOf<String>()
        val handed = mutableListOf<File>()
    }

    private fun updater(fake: Fake, install: DesktopInstall = windows, check: suspend () -> DesktopUpdateChecker.UpdateInfo? = { info }) =
        DesktopUpdater(
            check = check,
            install = install,
            osArch = "amd64",
            fetch = { _, into, progress ->
                fake.fetches++
                into.writeText("part")
                progress(0.5f)
                fake.gate.await()
                into.writeText("whole")
            },
            updatesDir = dir,
            openPage = { fake.opened += it },
            handOver = { file, _ -> fake.handed += file },
        )

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun secondDownloadJoinsTheFirst() = runBlocking<Unit> {
        val fake = Fake()
        val u = updater(fake)
        u.refresh(UpdateTrigger.LAUNCH)
        val scope = scope()
        u.startUpdate(scope)
        u.startUpdate(scope)
        withTimeout(5_000) { while (u.step.value !is UpdateStep.Downloading) yield() }
        fake.gate.complete(Unit)
        withTimeout(5_000) { while (fake.handed.isEmpty()) yield() }
        assertEquals(1, fake.fetches)
        assertEquals("whole", fake.handed.single().readText())
        assertEquals("Cosmotune-9.9.9-windows-x64.msi", fake.handed.single().name)
        assertIs<UpdateStep.Installing>(u.step.value)
    }

    @Test fun cancelDeletesThePartFile() = runBlocking<Unit> {
        val fake = Fake()
        val u = updater(fake)
        u.refresh(UpdateTrigger.LAUNCH)
        u.startUpdate(scope())
        withTimeout(5_000) { while (u.step.value !is UpdateStep.Downloading) yield() }
        u.cancel()
        withTimeout(5_000) { while (u.step.value !is UpdateStep.Idle) yield() }
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "no part file left: ${dir.listFiles()?.map { it.name }}")
        assertTrue(fake.handed.isEmpty())
    }

    @Test fun checkFailureKeepsNoOffer() = runBlocking<Unit> {
        val u = updater(Fake(), check = { error("offline") })
        u.refresh(UpdateTrigger.LAUNCH)
        assertNull(u.offer.value)
    }

    @Test fun laterCheckFailureKeepsTheOfferFound() = runBlocking<Unit> {
        var answer: DesktopUpdateChecker.UpdateInfo? = info
        val u = updater(Fake(), check = { answer })
        u.refresh(UpdateTrigger.LAUNCH)
        answer = null
        u.refresh(UpdateTrigger.LAUNCH)
        assertEquals("9.9.9", u.offer.value?.version)
    }

    @Test fun postponedOfferStaysReachable() = runBlocking<Unit> {
        val u = updater(Fake())
        u.refresh(UpdateTrigger.LAUNCH)
        u.postpone()
        assertTrue(u.postponed.value)
        assertEquals("9.9.9", u.offer.value?.version)
        u.reopen()
        assertFalse(u.postponed.value)
    }

    @Test fun browserInstallNeverDownloads() = runBlocking<Unit> {
        val fake = Fake()
        val u = updater(fake, install = DesktopInstall.WindowsPortable(File("D:\\Cosmotune")))
        u.refresh(UpdateTrigger.LAUNCH)
        u.startUpdate(scope())
        assertEquals(listOf("https://example.invalid/setup.exe"), fake.opened)
        assertEquals(0, fake.fetches)
    }

    @Test fun downloadRunsOffTheWindowsThread() = runBlocking<Unit> {
        // The app starts the update from the window's coroutine scope (the AWT event thread); the download must not block it.
        val ui = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "window-thread") }
        var fetchedOn = ""
        val u = DesktopUpdater(
            check = { info },
            install = windows,
            osArch = "amd64",
            fetch = { _, into, _ ->
                fetchedOn = Thread.currentThread().name
                into.writeText("whole")
            },
            updatesDir = dir,
            openPage = {},
            handOver = { _, _ -> },
        )
        u.refresh(UpdateTrigger.LAUNCH)
        u.startUpdate(CoroutineScope(SupervisorJob() + ui.asCoroutineDispatcher()))
        withTimeout(5_000) { while (u.step.value !is UpdateStep.Installing) yield() }
        ui.shutdown()
        assertTrue(fetchedOn.isNotEmpty() && !fetchedOn.startsWith("window-thread"), "fetched on $fetchedOn")
    }

    @Test fun failedDownloadSaysWhyAndLeavesNothing() = runBlocking<Unit> {
        val u = DesktopUpdater(
            check = { info },
            install = windows,
            osArch = "amd64",
            fetch = { _, into, _ ->
                into.writeText("part")
                error("Connection reset")
            },
            updatesDir = dir,
            openPage = {},
            handOver = { _, _ -> },
        )
        u.refresh(UpdateTrigger.LAUNCH)
        u.startUpdate(scope())
        withTimeout(5_000) { while (u.step.value !is UpdateStep.Failed) yield() }
        assertEquals("Connection reset", (u.step.value as UpdateStep.Failed).message)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
