package com.cosmotune.podio

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Satellite imagery for the radio globe: NASA GIBS "Blue Marble" (shaded relief with sea-floor) as web-mercator tiles. NASA's imagery is
 * public domain and the service needs no account or key. A tile is fetched when the globe first needs it and kept in memory.
 * [enabled] false (a catalogue built for a test double) never touches the network.
 */
class PodioTiles(private val enabled: Boolean = true,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
    private val base: String = "https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/BlueMarble_ShadedRelief_Bathymetry/default/GoogleMapsCompatible_Level8") {
    private val bytes = object : LinkedHashMap<Long, ByteArray>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>) = size > 360
    }
    private val failedAt = HashMap<Long, Long>()
    private val gate = Semaphore(6)

    /** The JPEG of one tile, or null when it does not exist, the network failed (asked again after a minute) or imagery is off. */
    suspend fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (!enabled || z !in 0..MAX_ZOOM || x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return null
        val key = key(z, x, y)
        synchronized(this) {
            bytes[key]?.let { return it }
            failedAt[key]?.takeIf { System.currentTimeMillis() - it < 60_000 }?.let { return null }
        }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val call = client.newCall(Request.Builder().url("$base/$z/$y/$x.jpeg").header("User-Agent", "Cosmotune-Podio/1 (+https://github.com/Vijay-Janarthanan/cosmotune)").build())
                val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { reply ->
                        reply.takeIf { it.isSuccessful }?.body?.bytes()?.takeIf { it.size in 1..2_000_000 }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null } finally { cancel?.dispose() }
            }.also { loaded -> synchronized(this) { if (loaded != null) bytes[key] = loaded else failedAt[key] = System.currentTimeMillis() } }
        }
    }

    companion object {
        const val MAX_ZOOM = 8
        const val ATTRIBUTION = "Imagery: NASA GIBS · Blue Marble"
        fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()
        fun zOf(key: Long): Int = (key ushr 56).toInt()
        fun xOf(key: Long): Int = ((key ushr 28) and 0xfffffff).toInt()
        fun yOf(key: Long): Int = (key and 0xfffffff).toInt()
        /** Web-mercator tile row (fractional) of a latitude at zoom [z]. */
        fun latToY(latitude: Double, z: Int): Double {
            val lat = Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))
            return (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * (1 shl z)
        }
        /** The latitude of a (fractional) tile row. */
        fun yToLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))
        fun lonToX(longitude: Double, z: Int): Double = (longitude + 180) / 360 * (1 shl z)
        fun xToLon(x: Double, z: Int): Double = x / (1 shl z) * 360 - 180

        /** The zoom whose tile pixels are about as big as the screen's, for a globe whose radius is [radiusPx] pixels. */
        fun zoomFor(radiusPx: Float): Int = ceil(ln(2 * PI * radiusPx / 256.0) / ln(2.0)).toInt().coerceIn(2, MAX_ZOOM)

        /** The tiles of zoom [z] that can be in view, for a view centred on ([latitude], [longitude]) with an angular radius of [angle] radians. */
        fun visible(z: Int, latitude: Double, longitude: Double, angle: Double): List<Long> {
            val n = 1 shl z
            val a = Math.toDegrees(min(angle, PI / 2)) + 1
            val top = floor(latToY((latitude + a).coerceAtMost(85.0), z)).toInt().coerceIn(0, n - 1)
            val bottom = floor(latToY((latitude - a).coerceAtLeast(-85.0), z)).toInt().coerceIn(0, n - 1)
            val spanLon = if (abs(latitude) + a >= 80 || a >= 80) 180.0 else min(180.0, a / cos(Math.toRadians(abs(latitude) + a)).coerceAtLeast(.05))
            val first = floor(lonToX(longitude - spanLon, z)).toInt()
            val last = floor(lonToX(longitude + spanLon, z)).toInt()
            val columns = if (last - first + 1 >= n) (0 until n).toList() else (first..last).map { Math.floorMod(it, n) }
            return (top..bottom).flatMap { y -> columns.map { x -> key(z, x, y) } }
        }
    }
}
