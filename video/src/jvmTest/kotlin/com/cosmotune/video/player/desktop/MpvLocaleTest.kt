package com.cosmotune.video.player.desktop

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * libmpv needs the process's number format to be plain C before it creates a player (a macOS runner's first run showed
 * `mpv_create` returning null otherwise). Not meaningful on Windows, whose libmpv does not check it.
 */
class MpvLocaleTest {
    @Test
    fun theNumberLocaleIsPlainCAfterTheCall() {
        assumeTrue("a system with a C library locale", MpvNatives.numericLocale() != null)
        MpvNatives.useCNumericLocale()
        assertEquals("C", MpvNatives.numericLocale())
        MpvNatives.useCNumericLocale()
        assertEquals("C", MpvNatives.numericLocale())
    }
}
