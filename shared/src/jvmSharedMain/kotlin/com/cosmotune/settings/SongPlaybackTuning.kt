package com.cosmotune.settings

/** A recording's override is separate from global defaults and never applies to Podio. */
data class SongPlaybackTuning(
    val speed: Float = 1f,
    val pitchSemitones: Int = 0,
    /** This recording's own sound effect ([com.cosmotune.audio.SoundEffect] by name, "NONE" for none); null follows Settings. */
    val effect: String? = null,
) {
    val pitchFactor: Float get() = ExtrasSettings.pitchFactorOf(pitchSemitones)
    fun normalized() = copy(speed = speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 3f) ?: 1f,
        pitchSemitones = pitchSemitones.coerceIn(-12, 12),
        effect = effect?.takeIf { name -> com.cosmotune.audio.SoundEffect.entries.any { it.name == name } })
}
object SongPlaybackMemory {
    fun isMusic(id: String?): Boolean = !id.isNullOrBlank() && !id.startsWith("podio:")
    fun resolve(id: String?, overrides: Map<String, SongPlaybackTuning>, speed: Float, pitch: Int): SongPlaybackTuning =
        if (!isMusic(id)) SongPlaybackTuning(speed, 0).normalized()
        else (id?.let(overrides::get) ?: SongPlaybackTuning(speed, pitch)).normalized()
    fun decode(raw: String): Map<String, SongPlaybackTuning> {
        val out = linkedMapOf<String, SongPlaybackTuning>()
        raw.take(7_000).lineSequence().forEach { line ->
            val fields = line.split('\t')
            if ((fields.size == 3 || fields.size == 4) && valid(fields[0])) {
                val speed = fields[1].toFloatOrNull()
                val pitch = fields[2].toIntOrNull()
                if (speed != null && speed.isFinite() && speed in 0.25f..3f && pitch != null && pitch in -12..12)
                    out[fields[0]] = SongPlaybackTuning(speed, pitch, fields.getOrNull(3)?.ifBlank { null }).normalized()
            }
        }
        return out.entries.toList().takeLast(100).associate { it.toPair() }
    }
    fun encode(map: Map<String, SongPlaybackTuning>) = map.entries.joinToString("\n") {
        it.key + "\t" + it.value.speed + "\t" + it.value.pitchSemitones + (it.value.effect?.let { effect -> "\t" + effect } ?: "")
    }
    fun with(map: Map<String, SongPlaybackTuning>, id: String, tuning: SongPlaybackTuning?): Map<String, SongPlaybackTuning> {
        val out = LinkedHashMap(map); out.remove(id)
        if (valid(id) && tuning != null) out[id] = tuning.normalized()
        while (out.size > 100 || encode(out).length > 7_000) out.remove(out.keys.first())
        return out
    }
    private fun valid(id: String) = isMusic(id) && id.length <= 512 && id.none { it == '\t' || it == '\n' || it == '\r' }
}

enum class LyricTextAlignment { AUTO, CENTER;
    /** Duet lanes stay distinct even when single-voice text is centered. */
    fun center(duet: Boolean) = this == CENTER && !duet
}

/** Resume only a locally owned music recording interrupted by a Bluetooth disconnect. */
class BluetoothReconnectResume(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var pending: Pair<String, Long>? = null
    @Synchronized fun disconnected(songId: String?, wasPlaying: Boolean) {
        pending = if (wasPlaying && SongPlaybackMemory.isMusic(songId)) songId!! to now() else null
    }
    @Synchronized fun cancel() { pending = null }
    @Synchronized fun reconnect(songId: String?, enabled: Boolean, paused: Boolean, localOwner: Boolean): Boolean {
        val remembered = pending; pending = null
        return remembered != null && enabled && paused && localOwner && remembered.first == songId && now() - remembered.second in 0..600_000
    }
}
