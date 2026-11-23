package com.cosmotune.music.data.audio

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A song's loudness along its length in [BARS] bars, learned from the levels the player already measures while the song plays
 * (no extra download), and kept so the next play shows it whole. A bar nobody has heard yet stays unknown and is drawn flat.
 */
class WaveShape(private val levels: FloatArray = FloatArray(BARS) { UNHEARD }) {
    init { require(levels.size == BARS) }

    /** Notes [level] (0..1) heard at [fraction] of the song; a bar keeps the loudest it has heard. */
    fun record(fraction: Float, level: Float): Boolean {
        if (fraction !in 0f..1f || level.isNaN()) return false
        val bar = (fraction * BARS).toInt().coerceAtMost(BARS - 1)
        val value = level.coerceIn(0f, 1f)
        if (value <= levels[bar]) return false
        levels[bar] = value
        return true
    }

    /** How much of the song has been heard (0..1). */
    fun heard(): Float = levels.count { it >= 0f } / BARS.toFloat()

    /**
     * Bar heights for drawing (0..1, scaled to the loudest bar, never thinner than [FLOOR]); null until enough of the song has
     * been heard to be worth a shape. Unheard bars come back as [FLOOR].
     */
    fun bars(): FloatArray? {
        if (heard() < MIN_HEARD) return null
        val top = levels.max().takeIf { it > 0f } ?: return null
        return FloatArray(BARS) { bar -> if (levels[bar] < 0f) FLOOR else (levels[bar] / top).coerceIn(FLOOR, 1f) }
    }

    fun encode(): ByteArray = ByteArray(BARS) { bar -> if (levels[bar] < 0f) 0 else (1 + levels[bar] * 254).toInt().toByte() }

    fun copy(): WaveShape = WaveShape(levels.copyOf())

    companion object {
        const val BARS = 72
        const val FLOOR = 0.12f
        const val MIN_HEARD = 0.15f
        private const val UNHEARD = -1f

        fun decode(bytes: ByteArray): WaveShape? {
            if (bytes.size != BARS) return null
            return WaveShape(FloatArray(BARS) { bar ->
                val stored = bytes[bar].toInt() and 0xFF
                if (stored == 0) UNHEARD else (stored - 1) / 254f
            })
        }
    }
}

/** The waveforms of the songs played, a few hundred kept in memory and each saved as a 72-byte file once its song moves on. */
object SongWaveforms {
    private const val KEEP = 300
    @Volatile private var folder: File? = null
    private val shapes = object : LinkedHashMap<String, WaveShape>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WaveShape>?) = size > KEEP
    }
    private val revisionFlow = MutableStateFlow(0)

    /** Bumps whenever a shape gains a bar, so the seek line redraws. */
    val revision: StateFlow<Int> = revisionFlow.asStateFlow()

    fun install(directory: File) { folder = directory }

    @Synchronized fun bars(videoId: String): FloatArray? = shape(videoId)?.bars()

    @Synchronized fun record(videoId: String, fraction: Float, level: Float) {
        if (videoId.isBlank()) return
        val shape = shape(videoId) ?: WaveShape().also { shapes[videoId] = it }
        if (shape.record(fraction, level)) revisionFlow.value++
    }

    /** Writes [videoId]'s shape to disk (when the song changes or the player closes). */
    fun save(videoId: String) {
        val directory = folder ?: return
        val bytes = synchronized(this) { shapes[videoId]?.encode() } ?: return
        runCatching {
            directory.mkdirs()
            val target = File(directory, fileName(videoId))
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
        }
    }

    @Synchronized private fun shape(videoId: String): WaveShape? = shapes[videoId] ?: folder
        ?.let { File(it, fileName(videoId)) }?.takeIf { it.isFile }
        ?.let { runCatching { WaveShape.decode(it.readBytes()) }.getOrNull() }
        ?.also { shapes[videoId] = it }

    private fun fileName(videoId: String): String =
        MessageDigest.getInstance("SHA-1").digest(videoId.toByteArray()).joinToString("") { "%02x".format(it) } + ".wave"
}
