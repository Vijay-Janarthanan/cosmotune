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
 * `TMDB_API_KEY` in local.properties or the environment); blank falls back to Cinemeta. The live scores' keys come the same
 * way (`CRICKETDATA_API_KEY`, `FOOTBALL_DATA_API_KEY`, optional `THESPORTSDB_API_KEY`).
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
                scoreKeys = com.cosmotune.watch.live.LiveScoreKeys(BuildConfig.CRICKETDATA_API_KEY, BuildConfig.FOOTBALL_DATA_API_KEY, BuildConfig.THESPORTSDB_API_KEY),
                torrentExecutable = { File(app.applicationInfo.nativeLibraryDir, "libcosmotorrent.so") },
            ),
            store(app),
        )
    }.also { repositoryRef = it }

    @Volatile private var downloadsRef: com.cosmotune.watch.WatchDownloads? = null

    /** Watch's downloads (direct files only), in the app's own Movies folder (no storage permission), else its files. */
    @Synchronized
    fun downloads(context: Context): com.cosmotune.watch.WatchDownloads = downloadsRef ?: run {
        val app = context.applicationContext
        val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { File(it, "Watch") } ?: File(File(app.filesDir, "watch"), "downloads")
        com.cosmotune.watch.WatchDownloads(dir, Http.client, destinationOptions =
            listOf(File(File(app.filesDir, "watch"), "downloads")) +
                app.getExternalFilesDirs(android.os.Environment.DIRECTORY_MOVIES).filterNotNull().map { File(it, "Watch") })
    }.also { downloadsRef = it }

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
