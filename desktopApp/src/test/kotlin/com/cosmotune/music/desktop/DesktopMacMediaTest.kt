package com.cosmotune.music.desktop

import kotlin.test.*

class DesktopMacMediaTest {
    @Test fun packagedMacBridgeLinksTheSystemFrameworkAndExportsItsLifecycle() {
        org.junit.Assume.assumeTrue(DesktopPlatform.isMac)
        val input = assertNotNull(javaClass.getResourceAsStream("/native/libcosmotune-media.dylib"))
        val file = java.nio.file.Files.createTempFile("cosmotune-media-smoke-", ".dylib").toFile().apply { deleteOnExit() }
        input.use { source -> file.outputStream().use { output -> source.copyTo(output) } }
        val library = com.sun.jna.NativeLibrary.getInstance(file.absolutePath)
        for (name in listOf("cosmo_media_start", "cosmo_media_update", "cosmo_media_artwork", "cosmo_media_stop"))
            assertNotNull(library.getFunction(name))
    }
    @Test fun commandsReachOnlyTheirNamedTransportActionAndSeekRejectsInvalidTimes() {
        val calls = mutableListOf<String>()
        val actions = DesktopMacMedia.Actions({ calls += "play" }, { calls += "pause" }, { calls += "toggle" },
            { calls += "next" }, { calls += "previous" }, { calls += "seek:$it" })
        for (command in 0..6) DesktopMacMedia.dispatch(actions, command, 1.25)
        assertEquals(listOf("play", "pause", "toggle", "next", "previous", "seek:1250", "pause"), calls)
        calls.clear()
        for (seconds in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) DesktopMacMedia.dispatch(actions, 5, seconds)
        DesktopMacMedia.dispatch(actions, 100, 0.0)
        assertTrue(calls.isEmpty())
    }
}
