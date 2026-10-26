package com.cosmotune.music.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import kotlinx.serialization.json.*

/** Isolated helper entry: before singleton, stores, player or tray startup. No native objects in JS. */
internal object ProviderVerificationBrowserMain {
    fun run() {
        val input = System.`in`.readNBytes(48_001)
        require(input.size <= 48_000)
        val launch = Json.decodeFromString<BrowserLaunch>(input.toString(Charsets.UTF_8))
        require(launch.port in 1024..65535 && launch.token.length in 32..200 && File(launch.profile).isDirectory)
        require(System.currentTimeMillis() < launch.target.expiresAt)
        ProviderChromiumWindow.run(launch, requireNotNull(ProviderChromiumWindow.executable()))
    }

    internal fun send(launch: BrowserLaunch, result: String?): String = Socket("127.0.0.1", launch.port).use { socket ->
        socket.soTimeout = 30_000
        val body = buildJsonObject { put("token", launch.token); if (result == null) put("closed", true) else put("result", result) }
        DataOutputStream(socket.getOutputStream()).apply { writeUTF(body.toString()); flush() }
        if (result == null) "closed" else DataInputStream(socket.getInputStream()).readUTF()
    }
}
