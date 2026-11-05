package com.cosmotune.remote

import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import kotlin.test.*

class LanRemoteControllerTest {
    @Test fun startsOnlyOnRequestAndWindowClosureDisconnectsTheSession() = runBlocking<Unit> {
        val host = object : LanRemoteHost {
            override fun playback() = RemotePlayback()
            override suspend fun command(command: RemoteCommand) = Unit
        }
        val controller = LanRemoteController(host)
        assertNull(controller.session.value)
        controller.start(InetAddress.getByName("127.0.0.1"))
        val first = assertNotNull(controller.session.value)
        assertFailsWith<IllegalStateException> { controller.start(InetAddress.getByName("127.0.0.1")) }
        assertEquals(first, controller.session.value)
        controller.stop()
        assertNull(controller.session.value)
        controller.start(InetAddress.getByName("127.0.0.1"))
        val second = assertNotNull(controller.session.value)
        assertNotEquals(first.pairingUrl.substringAfter('#'), second.pairingUrl.substringAfter('#'))
        controller.close()
        assertNull(controller.session.value)
        val target = URI(second.origin)
        assertFails { Socket(target.host, target.port).use {} }
        assertFailsWith<IllegalStateException> { controller.start(InetAddress.getByName("127.0.0.1")) }
    }
}
