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
}
