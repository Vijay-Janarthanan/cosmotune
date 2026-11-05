package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop Listen Together object calls `refreshServerHealth()` from its `init` block, which reads `healthMonitor`. A Kotlin
 * property is initialised in the order it is written, so the monitor must be declared above that block, or the object fails to
 * start with a NullPointerException (found by the first run of the desktop tests on GitHub's machines).
 */
class ListenTogetherInitOrderTest {
    @Test fun theHealthMonitorIsDeclaredBeforeTheInitBlockThatUsesIt() {
        val src = SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopListenTogether.kt")
        val monitor = src.indexOf("private val healthMonitor by lazy")
        val init = src.indexOf("\n    init {")
        assertTrue(monitor >= 0 && init >= 0, "both declarations exist")
        assertTrue(monitor < init, "healthMonitor must come before the init block that calls refreshServerHealth()")
    }
}
