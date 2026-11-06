package com.cosmotune.desktop

import com.cosmotune.music.data.Http
import com.cosmotune.video.graph.desktopVideoGraph
import com.cosmotune.watch.DefaultWatchRepository
import com.cosmotune.watch.WatchConfig
import com.cosmotune.watch.WatchLinks
import com.cosmotune.watch.WatchPlayer
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.store.WatchStore
import java.io.File

/**
 * Watch mode on the desktop: one repository and one store per process. The store file sits beside the listening store
 * (`~/.local/share/cosmotune/watch/watch.json`), sealed with DPAPI on Windows and owner-only elsewhere; the HTTP cache under
 * the media cache. The TMDB key comes from `-Dcosmotune.tmdb.key` (the build passes `TMDB_API_KEY` from local.properties)
 * or the environment.
 */
object DesktopWatch {
    val dataDir: File get() = File(desktopListeningFile().parentFile, "watch")

    /** The one store; cloud sync writes the user's add-on list through it. */
    val store: WatchStore by lazy { WatchStore(File(dataDir, "watch.json")) }

    val repository: DefaultWatchRepository by lazy {
        DefaultWatchRepository(
            WatchConfig(
                client = Http.client,
                dataDir = dataDir,
                cacheDir = com.cosmotune.music.desktop.DesktopMediaCache.directory.resolve("watch").toFile(),
                tmdbKey = System.getProperty("cosmotune.tmdb.key")?.takeIf { it.isNotBlank() } ?: System.getenv("TMDB_API_KEY"),
                videoSource = { desktopVideoGraph(build = true)?.source },
            ),
            store,
        )
    }

    /** The player, on Video mode's libmpv engine (built on first use). */
    val player: com.cosmotune.watch.player.EngineWatchPlayer by lazy {
        val graph = requireNotNull(desktopVideoGraph(build = true))
        com.cosmotune.watch.player.EngineWatchPlayer(repository, graph.playback.engine, onYouTube = { id, startMs -> graph.playback.play(id, startMs) },
            stopOthers = { runCatching { graph.playback.stop() } })
    }

    /** Whether [player] has been built in this process. */
    @Volatile var playerBuilt: Boolean = false
        private set

    /** [player], or null where Video's engine cannot start here (libmpv missing): the player page then says it is not connected. */
    fun playerOrNull(): com.cosmotune.watch.player.EngineWatchPlayer? =
        runCatching { player }.onSuccess { playerBuilt = true }.onFailure { com.cosmotune.music.desktop.DesktopTrackLog.log("watch: no player here: ${it.message}") }.getOrNull()

    val api: WatchRepository get() = repository

    /** A link given on the command line or by the operating system (stremio://, cosmotune://watch/...): the Install sheet shows it. */
    fun offer(link: String?): Boolean = WatchLinks.offer(link)
}
