package com.opencanvas.core

import com.opencanvas.core.stream.InnerTubeBackend
import com.opencanvas.core.stream.StreamBackend
import okhttp3.OkHttpClient
import java.io.File

/**
 * Everything [OpenCanvas] needs from its host, with defaults that work on their own.
 *
 * @property streamBackend The resolver for the sharp video of the canvas. Defaults to the built-in
 *   [InnerTubeBackend.visionOs], which reaches 720p and up on its own; replace it to bring your own
 *   resolver (for example one that already holds the app's YouTube session).
 * @property instantBackend A fast resolver asked at the same time so the canvas can start playing at
 *   once (360p, about 0.2 s) while [streamBackend] is still working; when [streamBackend] then returns
 *   a taller stream, the track is re-emitted with it. It also supplies the small MP4 files the sync
 *   measurement reads. Defaults to [InnerTubeBackend.android]; null disables the two-step start.
 * @property cacheDirectory Where measured song-to-video maps are kept so a song starts instantly the
 *   next time; null keeps them in memory only. Defaults to `~/.opencanvas/sync` on the desktop JVM
 *   and to null on Android, where the host should pass a directory inside its own storage.
 * @property communityMapsUrl Base URL of a read-only service of precomputed maps, one
 *   `<trackVideoId>.json` per song. A hit starts a song instantly without measuring anything;
 *   null disables the lookup.
 * @property httpClient Client for the library's own requests (search, community maps, audio ranges).
 * @property log Receives one line per stage (video ready, map, failures) with its timing;
 *   silent by default. Wire it to your logger to see why a song has no canvas.
 */
class OpenCanvasConfig(
    val streamBackend: StreamBackend = InnerTubeBackend.visionOs(),
    val instantBackend: StreamBackend? = InnerTubeBackend.android(),
    val cacheDirectory: File? = defaultCacheDirectory(),
    val communityMapsUrl: String? = DEFAULT_COMMUNITY_MAPS_URL,
    val httpClient: OkHttpClient = InnerTubeBackend.defaultHttpClient(),
    val log: (String) -> Unit = {},
) {
    companion object {
        /** The maps published with the project, served by jsDelivr. */
        const val DEFAULT_COMMUNITY_MAPS_URL =
            "https://cdn.jsdelivr.net/gh/Vijay-Janarthanan/OpenCanvas@main/opencanvas-db/sync/v1"

        private fun defaultCacheDirectory(): File? {
            if (System.getProperty("java.vm.vendor") == "The Android Project") return null
            val home = System.getProperty("user.home")?.let(::File)?.takeIf { it.isDirectory && it.canWrite() }
            return home?.resolve(".opencanvas")?.resolve("sync")
        }
    }
}
