package com.cosmotune.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Interactive browser QA against a test player. No user accounts, music files or real playback. */
object LanRemotePreview {
    @JvmStatic fun main(args: Array<String>) {
        val file = File(args.single())
        require(file.name == "remote-preview-link.txt")
        val songs = listOf(RemoteTrack("fixture:one", "Preview track one", "Test player"), RemoteTrack("fixture:two", "Preview track two", "Test player"))
        val state = MutableStateFlow(RemotePlayback(songs[0].title, songs[0].artist, false, 10_000, 180_000, 0.5f))
        val host = object : LanRemoteHost {
            override fun playback() = state.value
            override suspend fun search(query: String) = songs.filter { query.isBlank() || it.title.contains(query, true) }
            override suspend fun command(command: RemoteCommand) {
                when (command.action) {
                    RemoteAction.PLAY -> state.update { it.copy(playing = true) }
                    RemoteAction.PAUSE -> state.update { it.copy(playing = false) }
                    RemoteAction.TOGGLE -> state.update { it.copy(playing = !it.playing) }
                    RemoteAction.SEEK -> state.update { it.copy(positionMs = checkNotNull(command.positionMs).coerceAtMost(it.durationMs ?: 0)) }
                    RemoteAction.VOLUME -> state.update { it.copy(volume = command.volume) }
                    RemoteAction.NEXT, RemoteAction.PREVIOUS, RemoteAction.PLAY_TRACK -> {
                        val song = when (command.action) {
                            RemoteAction.NEXT -> songs[1]
                            RemoteAction.PREVIOUS -> songs[0]
                            else -> songs.first { it.id == command.trackId }
                        }
                        state.update { it.copy(title = song.title, artist = song.artist, playing = true, positionMs = 0) }
                    }
                }
            }
        }
        val server = LanRemoteServer.start(host)
        val timer = Executors.newSingleThreadScheduledExecutor { action -> Thread(action, "remote-preview-clock").apply { isDaemon = true } }
        timer.scheduleAtFixedRate({ state.update { if (it.playing) it.copy(positionMs = (it.positionMs + 500).coerceAtMost(it.durationMs ?: 0)) else it } }, 500, 500, TimeUnit.MILLISECONDS)
        val stopped = CountDownLatch(1)
        fun cleanup() { server.close(); timer.shutdownNow(); file.delete(); stopped.countDown() }
        val shutdown = Thread({ cleanup() }, "remote-preview-close")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            file.parentFile.mkdirs()
            file.writeText(server.session.pairingUrl, Charsets.UTF_8)
            println("Local remote browser fixture running. The pairing link is in remote/build/reports/remote-preview-link.txt")
            stopped.await(10, TimeUnit.MINUTES)
        } finally {
            cleanup()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }
}
