package com.cosmotune.music

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.providers.ProviderVerificationTarget
import com.cosmotune.ui.providers.ProviderVerificationActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Own emulator app only: a harmless HTTPS page, no provider accounts or human challenge. */
@RunWith(AndroidJUnit4::class)
class ProviderVerificationWindowTest {
    @Test fun verificationUsesAPrivateProcessAndClosingItKeepsThePlayerProcessAlive() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(context, ProviderVerificationActivity::class.java)
        val info = context.packageManager.getActivityInfo(component, 0)
        assertFalse(info.exported)
        assertEquals("${context.packageName}:provider_auth", info.processName)
        val mainPid = Process.myPid()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun child() = activityManager.runningAppProcesses.orEmpty().firstOrNull { it.processName == info.processName }
        val target = ProviderVerificationTarget("https://example.com/", "${context.packageName}://provider-auth/fixture",
            "android-browser-fixture-state", "Verification fixture", listOf("example.com"), System.currentTimeMillis() + 45_000, false)
        context.startActivity(Intent(context, ProviderVerificationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(ProviderVerificationActivity.TARGET, Json.encodeToString(target)))
        withTimeout(15_000) { while (child() == null) delay(100) }
        assertNotEquals(mainPid, child()!!.pid)
        // Wait for the Activity's native Close/Back handler to be installed, then send a real Back.
        delay(2_000)
        val now = SystemClock.uptimeMillis()
        instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true)
        instrumentation.uiAutomation.injectInputEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true)
        withTimeout(10_000) { while (child() != null) delay(100) }
        assertEquals(mainPid, Process.myPid())
        assertTrue(activityManager.runningAppProcesses.orEmpty().any { it.pid == mainPid })
    }
}
