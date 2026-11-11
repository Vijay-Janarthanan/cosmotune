package com.cosmotune.settings

import com.cosmotune.audio.SpatialPreset
import com.cosmotune.music.data.NetworkPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * The smaller playback and network options both apps share (most of them first seen in Mochy Music, see `NOTICE.md`):
 * a maximum-volume cap, pitch apart from speed, a proxy of the listener's own and an offline switch. Kept in the same
 * [KeyValueStore] as the look settings on each platform; [Extras.settings] is the one instance a process uses.
 *
 * The network options are handed to [NetworkPolicy] as they change, so the shared HTTP client follows them without a restart.
 */
class ExtrasSettings(private val store: KeyValueStore) {

    private val maxVolumeFlow = MutableStateFlow(clampVolume(store.getInt(KEY_MAX_VOLUME, 100)))
    private val pitchFlow = MutableStateFlow(clampPitch(store.getInt(KEY_PITCH, 0)))
    private val offlineFlow = MutableStateFlow(store.getBoolean(KEY_OFFLINE, false))
    private val proxyFlow = MutableStateFlow(readProxy())
    private val autoLyricsOffsetFlow = MutableStateFlow(store.getBoolean(KEY_AUTO_LYRICS_OFFSET, true))
    private val spatialPresetFlow = MutableStateFlow(SpatialPreset.named(store.getString(KEY_SPATIAL_PRESET, SpatialPreset.DEFAULT.name)))
    private val spatialSurroundFlow = MutableStateFlow(store.getBoolean(KEY_SPATIAL_SURROUND, false))
    private val songRecognitionFlow = MutableStateFlow(store.getBoolean(KEY_SONG_RECOGNITION, false))
    private val lyricStyleFlow = MutableStateFlow(store.getString(KEY_LYRIC_STYLE, "").ifEmpty { null })
    private val lyricStyleSongsFlow = MutableStateFlow(LyricStyleMemory.decode(store.getString(KEY_LYRIC_STYLE_SONGS, "")))

    /** The loudest the app plays, in percent of full scale; 100 is no cap. Applied after every other stage. */
    val maxVolumePercent: StateFlow<Int> = maxVolumeFlow.asStateFlow()

    /** Pitch shift in semitones, -12..12, kept apart from playback speed. 0 leaves the pitch alone. */
    val pitchSemitones: StateFlow<Int> = pitchFlow.asStateFlow()

    /** While on, nothing is fetched from the internet: only local files, downloads and the local network play. */
    val offline: StateFlow<Boolean> = offlineFlow.asStateFlow()

    /** The listener's own proxy; see [ProxySetting]. */
    val proxy: StateFlow<ProxySetting> = proxyFlow.asStateFlow()

    /**
     * Lyrics auto offset: the song is listened to once, in the background, and timed lyrics are moved onto its singing
     * (see `com.cosmotune.lyrics.autosync.LyricsAutoOffset`). Applied only when the match is clear; the manual offset stays.
     */
    val autoLyricsOffset: StateFlow<Boolean> = autoLyricsOffsetFlow.asStateFlow()

    /** What the spatial-audio switch (the existing one in Settings) renders while it is on; see [SpatialPreset]. */
    val spatialPreset: StateFlow<SpatialPreset> = spatialPresetFlow.asStateFlow()

    /** With the spatial switch on, a 5.1 or 7.1 stream is rendered to two ears instead of folded down. */
    val spatialSurround: StateFlow<Boolean> = spatialSurroundFlow.asStateFlow()

    /**
     * Song recognition, off until turned on: it records the microphone and sends a fingerprint to Shazam's unofficial
     * endpoint (see `com.cosmotune.music.data.recognition.SongRecognition`).
     */
    val songRecognition: StateFlow<Boolean> = songRecognitionFlow.asStateFlow()

    /**
     * The lyric style the listener chose for every song (a `com.cosmotune.lyrics.styles.LyricStyle` key), or null while they have
     * not chosen one: then a duet is drawn in Call & Answer by itself and everything else in Orbit Read.
     */
    val lyricStyle: StateFlow<String?> = lyricStyleFlow.asStateFlow()

    /** Songs the listener gave a style of their own, song id to style key, the most recent last. */
    val lyricStyleSongs: StateFlow<Map<String, String>> = lyricStyleSongsFlow.asStateFlow()

    init {
        publishNetwork()
    }

    /** Chooses [key] for every song (null forgets the choice). */
    fun setLyricStyle(key: String?) {
        lyricStyleFlow.value = key?.ifEmpty { null }
        store.putString(KEY_LYRIC_STYLE, key.orEmpty())
    }

    /** Gives song [songId] a style of its own, [key], or with null takes it away so the song follows [lyricStyle] again. */
    fun setLyricStyleFor(songId: String, key: String?) {
        val next = LyricStyleMemory.with(lyricStyleSongsFlow.value, songId, key)
        lyricStyleSongsFlow.value = next
        store.putString(KEY_LYRIC_STYLE_SONGS, LyricStyleMemory.encode(next))
    }

    fun setSongRecognition(value: Boolean) {
        songRecognitionFlow.value = value
        store.putBoolean(KEY_SONG_RECOGNITION, value)
    }

    fun setSpatialPreset(value: SpatialPreset) {
        spatialPresetFlow.value = value
        store.putString(KEY_SPATIAL_PRESET, value.name)
    }

    fun setSpatialSurround(value: Boolean) {
        spatialSurroundFlow.value = value
        store.putBoolean(KEY_SPATIAL_SURROUND, value)
    }

    fun setAutoLyricsOffset(value: Boolean) {
        autoLyricsOffsetFlow.value = value
        store.putBoolean(KEY_AUTO_LYRICS_OFFSET, value)
    }

    fun setMaxVolumePercent(value: Int) {
        val clamped = clampVolume(value)
        maxVolumeFlow.value = clamped
        store.putInt(KEY_MAX_VOLUME, clamped)
    }

    fun setPitchSemitones(value: Int) {
        val clamped = clampPitch(value)
        pitchFlow.value = clamped
        store.putInt(KEY_PITCH, clamped)
    }

    fun setOffline(value: Boolean) {
        offlineFlow.value = value
        store.putBoolean(KEY_OFFLINE, value)
        publishNetwork()
    }

    fun setProxy(value: ProxySetting) {
        proxyFlow.value = value
        store.putBoolean(KEY_PROXY_ON, value.enabled)
        store.putString(KEY_PROXY_TYPE, value.type.name)
        store.putString(KEY_PROXY_ADDRESS, value.address.trim())
        store.putString(KEY_PROXY_USER, value.username)
        store.putString(KEY_PROXY_PASSWORD, value.password)
        publishNetwork()
    }

    /** The gain [maxVolumePercent] stands for, 1.0 when there is no cap. */
    val maxVolumeGain: Float get() = maxVolumeFlow.value / 100f

    /** The pitch factor [pitchSemitones] stands for: 2^(n/12). */
    val pitchFactor: Float get() = pitchFactorOf(pitchFlow.value)

    private fun readProxy() = ProxySetting(
        enabled = store.getBoolean(KEY_PROXY_ON, false),
        type = runCatching { ProxySetting.Type.valueOf(store.getString(KEY_PROXY_TYPE, ProxySetting.Type.HTTP.name)) }
            .getOrDefault(ProxySetting.Type.HTTP),
        address = store.getString(KEY_PROXY_ADDRESS, ""),
        username = store.getString(KEY_PROXY_USER, ""),
        password = store.getString(KEY_PROXY_PASSWORD, ""),
    )

    private fun publishNetwork() {
        NetworkPolicy.offline = offlineFlow.value
        NetworkPolicy.apply(proxyFlow.value.takeIf { it.enabled }?.let(NetworkPolicy::configOf))
    }

    companion object {
        const val KEY_MAX_VOLUME = "extras_max_volume_percent"
        const val KEY_PITCH = "extras_pitch_semitones"
        const val KEY_OFFLINE = "extras_offline_mode"
        const val KEY_PROXY_ON = "extras_proxy_enabled"
        const val KEY_PROXY_TYPE = "extras_proxy_type"
        const val KEY_PROXY_ADDRESS = "extras_proxy_address"
        const val KEY_PROXY_USER = "extras_proxy_user"
        const val KEY_PROXY_PASSWORD = "extras_proxy_password"
        const val KEY_AUTO_LYRICS_OFFSET = "extras_auto_lyrics_offset"
        const val KEY_SPATIAL_PRESET = "extras_spatial_preset"
        const val KEY_SPATIAL_SURROUND = "extras_spatial_surround"
        const val KEY_SONG_RECOGNITION = "extras_song_recognition"
        const val KEY_LYRIC_STYLE = "extras_lyric_style"
        const val KEY_LYRIC_STYLE_SONGS = "extras_lyric_style_songs"

        const val MIN_VOLUME_PERCENT = 10

        fun clampVolume(value: Int) = value.coerceIn(MIN_VOLUME_PERCENT, 100)
        fun clampPitch(value: Int) = value.coerceIn(-12, 12)
        fun pitchFactorOf(semitones: Int): Float = 2.0.pow(semitones / 12.0).toFloat()
    }
}

/**
 * How the songs that have a lyric style of their own are kept: one `id<TAB>style` per line, in one string, at most [LIMIT] of
 * them and [MAX_CHARS] characters (the desktop's preferences take 8 KB a value), the oldest choice going first. An id that could
 * break the format (a tab or a line break in it) is not kept.
 */
object LyricStyleMemory {
    const val LIMIT = 200
    const val MAX_CHARS = 7_000

    fun decode(raw: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (row in raw.split('\n')) {
            val tab = row.indexOf('\t')
            if (tab <= 0 || tab == row.length - 1) continue
            out[row.substring(0, tab)] = row.substring(tab + 1)
        }
        return out
    }

    fun encode(map: Map<String, String>): String = map.entries.joinToString("\n") { "${it.key}\t${it.value}" }

    /** [map] with [songId] set to [key] as its newest entry (or taken out, for null), cut to [LIMIT] and [MAX_CHARS]. */
    fun with(map: Map<String, String>, songId: String, key: String?): Map<String, String> {
        val out = LinkedHashMap(map)
        out.remove(songId)
        if (!key.isNullOrEmpty() && songId.isNotEmpty() && songId.none(::breaks) && key.none(::breaks)) out[songId] = key
        while (out.size > LIMIT || (out.isNotEmpty() && encode(out).length > MAX_CHARS)) out.remove(out.keys.first())
        return out
    }

    private fun breaks(c: Char): Boolean = c == '\t' || c == '\n'
}

/** A proxy the listener typed in: "host:port", HTTP or SOCKS, with an optional login. */
data class ProxySetting(
    val enabled: Boolean = false,
    val type: Type = Type.HTTP,
    val address: String = "",
    val username: String = "",
    val password: String = "",
) {
    enum class Type { HTTP, SOCKS }
}

/** The process's one [ExtrasSettings]. Each app calls [install] at start with its own store; until then a memory store stands in. */
object Extras {
    @Volatile private var installed: ExtrasSettings? = null

    val settings: ExtrasSettings
        get() = installed ?: synchronized(this) { installed ?: ExtrasSettings(MemoryStore()).also { installed = it } }

    fun install(store: KeyValueStore): ExtrasSettings = synchronized(this) {
        ExtrasSettings(store).also { installed = it }
    }

    private class MemoryStore : KeyValueStore {
        private val values = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun getInt(key: String, default: Int) = values[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { values[key] = value }
        override fun getString(key: String, default: String) = values[key] as? String ?: default
        override fun putString(key: String, value: String) { values[key] = value }
    }
}
