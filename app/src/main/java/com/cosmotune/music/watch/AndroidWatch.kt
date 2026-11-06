package com.cosmotune.music.watch

import android.content.Context
import android.content.Intent
import com.cosmotune.music.BuildConfig
import com.cosmotune.music.data.Http
import com.cosmotune.video.graph.androidVideoGraph
import com.cosmotune.watch.DefaultWatchRepository
import com.cosmotune.watch.WatchConfig
import com.cosmotune.watch.WatchLinks
import com.cosmotune.watch.player.EngineWatchPlayer
import com.cosmotune.watch.store.WatchStore
import java.io.File

/**
 * Watch mode on the phone: one repository and one store per process. The store is `files/watch/watch.json`, sealed with
 * the Android Keystore; the HTTP cache is under the cache folder. The TMDB key is `BuildConfig.TMDB_API_KEY` (from
 * `TMDB_API_KEY` in local.properties or the environment); blank falls back to Cinemeta.
 */
object AndroidWatch {
    @Volatile private var storeRef: WatchStore? = null
    @Volatile private var repositoryRef: DefaultWatchRepository? = null
    @Volatile private var playerRef: EngineWatchPlayer? = null

    @Synchronized
    fun store(context: Context): WatchStore = storeRef ?: WatchStore(File(File(context.applicationContext.filesDir, "watch"), "watch.json")).also { storeRef = it }

    @Synchronized
    fun repository(context: Context): DefaultWatchRepository = repositoryRef ?: run {
        val app = context.applicationContext
        DefaultWatchRepository(
            WatchConfig(
                client = Http.client,
                dataDir = File(app.filesDir, "watch"),
                cacheDir = File(app.cacheDir, "watch"),
                tmdbKey = BuildConfig.TMDB_API_KEY,
                videoSource = { androidVideoGraph(app).source },
            ),
            store(app),
        )
    }.also { repositoryRef = it }

    /** Whether [player] has been built in this process. */
    val playerBuilt: Boolean get() = playerRef != null

    /** The player, on Video mode's Media3 engine. */
    @Synchronized
    fun player(context: Context): EngineWatchPlayer = playerRef ?: run {
        val graph = androidVideoGraph(context.applicationContext)
        EngineWatchPlayer(repository(context), graph.playback.engine, onYouTube = { id, at -> graph.playback.play(id, at) },
            stopOthers = { runCatching { graph.playback.stop() } })
    }.also { playerRef = it }

    /** A `stremio://` or `cosmotune://watch/...` link opened from another app: the Install sheet shows it. */
    fun consume(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        return WatchLinks.offer(intent.dataString)
    }
}
