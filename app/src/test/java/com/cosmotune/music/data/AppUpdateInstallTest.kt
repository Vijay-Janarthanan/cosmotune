package com.cosmotune.music.data

import android.content.pm.PackageInstaller
import com.cosmotune.update.UpdateTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateInstallTest {
    @Test fun noUserActionFrom31() {
        assertFalse(AppUpdateChecker.sessionRequiresNoUserAction(30))
        assertTrue(AppUpdateChecker.sessionRequiresNoUserAction(31))
    }

    @Test fun statusFailureBecomesFailedState() {
        assertNull(AppUpdateChecker.installStatusFailure(PackageInstaller.STATUS_SUCCESS, null))
        assertNull(AppUpdateChecker.installStatusFailure(PackageInstaller.STATUS_PENDING_USER_ACTION, null))
        assertEquals("Not enough storage", AppUpdateChecker.installStatusFailure(PackageInstaller.STATUS_FAILURE_STORAGE, "Not enough storage"))
        assertEquals("The update was not installed.", AppUpdateChecker.installStatusFailure(PackageInstaller.STATUS_FAILURE_ABORTED, null))
    }

    @Test fun foregroundCheckRespectsGap() {
        val now = 50_000_000_000L
        AppUpdateChecker.lastAttemptMs = now - 30 * 60_000L
        assertFalse(AppUpdateChecker.isCheckDue(now, UpdateTrigger.RETURNED))
        AppUpdateChecker.lastAttemptMs = now - 61 * 60_000L
        assertTrue(AppUpdateChecker.isCheckDue(now, UpdateTrigger.RETURNED))
    }

    @Test fun manifestsAllowUpdatesWithoutUserAction() {
        for (manifest in listOf("src/main/AndroidManifest.xml", "../tv/src/main/AndroidManifest.xml")) {
            val text = java.io.File(manifest).readText()
            assertTrue(manifest, "android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION" in text)
        }
    }

    @Test fun foregroundReturnIsAComebackNotALaunch() {
        val tracker = ForegroundTracker()
        assertFalse("the launch's own start", tracker.started())
        assertFalse("a second screen while one is showing", tracker.started())
        tracker.stopped()
        tracker.stopped()
        assertTrue("back after every screen had gone", tracker.started())
        assertTrue(tracker.inFront)
    }

    @Test fun watchForegroundOnlyInTheMainProcessAfterItsSetup() {
        val app = java.io.File("src/main/java/com/cosmotune/music/CosmotuneApplication.kt").readText()
        val auxiliaryReturn = app.indexOf("endsWith(\":mangaverification\") }) return")
        val proxy = app.indexOf("ExtrasStore.install(this)")
        val watch = app.indexOf("AppUpdateChecker.watchForeground(this)")
        assertTrue("registered after the side processes leave and after the proxy is set", watch > auxiliaryReturn && watch > proxy && auxiliaryReturn > 0)
    }

    @Test fun secondInstallTapIsIgnored() {
        AppUpdateChecker.endInstall()
        assertTrue(AppUpdateChecker.beginInstall())
        assertFalse(AppUpdateChecker.beginInstall())
        AppUpdateChecker.endInstall()
        assertTrue(AppUpdateChecker.beginInstall())
        AppUpdateChecker.endInstall()
    }
}
