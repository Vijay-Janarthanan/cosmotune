package com.cosmotune.watch

import kotlin.test.Test
import kotlin.test.assertEquals

class MpvOptionsTest {
    @Test fun keepsPlainOptionsAndRefusesWhatReachesOutside() {
        val parsed = MpvOptions.parse("""
            # my look
            deband=yes
            --sub-scale=1.2
            interpolation
            Scale = "ewa_lanczossharp"   # comment
            script=C:/evil.lua
            input-ipc-server=\.\pipe\x
            demuxer-max-bytes=2GiB
            http-header-fields=x://y
            not an option
        """.trimIndent())
        assertEquals(mapOf("deband" to "yes", "sub-scale" to "1.2", "interpolation" to "yes", "scale" to "ewa_lanczossharp", "demuxer-max-bytes" to "2GiB"), parsed.options)
        assertEquals(listOf("script=C:/evil.lua", "input-ipc-server=\\.\\pipe\\x", "http-header-fields=x://y", "not an option"), parsed.refused)
    }
}
