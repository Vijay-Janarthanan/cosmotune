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

    private val songPlaybackFlow = MutableStateFlow(SongPlaybackMemory.decode(store.getString(KEY_SONG_PLAYBACK,"")))
    private val inlineLyricsFlow = MutableStateFlow(store.getBoolean(KEY_INLINE_LYRICS,false))
    private val lyricAlignmentFlow = MutableStateFlow(runCatching { LyricTextAlignment.valueOf(store.getString(KEY_LYRIC_ALIGNMENT,"AUTO")) }.getOrDefault(LyricTextAlignment.AUTO))
    private val bluetoothResumeFlow = MutableStateFlow(store.getBoolean(KEY_BLUETOOTH_RESUME,false))
    private val pauseOnMuteFlow = MutableStateFlow(store.getBoolean(KEY_PAUSE_ON_MUTE,false))
    val songPlayback: StateFlow<Map<String,SongPlaybackTuning>> = songPlaybackFlow.asStateFlow()
    val inlineLyrics: StateFlow<Boolean> = inlineLyricsFlow.asStateFlow()
    val lyricAlignment: StateFlow<LyricTextAlignment> = lyricAlignmentFlow.asStateFlow()
    val bluetoothResume: StateFlow<Boolean> = bluetoothResumeFlow.asStateFlow()
    val pauseOnMute: StateFlow<Boolean> = pauseOnMuteFlow.asStateFlow()
    fun setSongPlayback(id: String,tuning: SongPlaybackTuning?) {
        val next=SongPlaybackMemory.with(songPlaybackFlow.value,id,tuning)
        songPlaybackFlow.value=next; store.putString(KEY_SONG_PLAYBACK,SongPlaybackMemory.encode(next))
    }
    fun setInlineLyrics(value: Boolean) { inlineLyricsFlow.value=value; store.putBoolean(KEY_INLINE_LYRICS,value) }
    fun setLyricAlignment(value: LyricTextAlignment) { lyricAlignmentFlow.value=value; store.putString(KEY_LYRIC_ALIGNMENT,value.name) }
    fun setBluetoothResume(value: Boolean) { bluetoothResumeFlow.value=value; store.putBoolean(KEY_BLUETOOTH_RESUME,value) }
    fun setPauseOnMute(value: Boolean) { pauseOnMuteFlow.value=value; store.putBoolean(KEY_PAUSE_ON_MUTE,value) }

    private val maxVolumeFlow = MutableStateFlow(clampVolume(store.getInt(KEY_MAX_VOLUME, 100)))
    private val pitchFlow = MutableStateFlow(clampPitch(store.getInt(KEY_PITCH, 0)))
    private val offlineFlow = MutableStateFlow(store.getBoolean(KEY_OFFLINE, false))
    private val proxyFlow = MutableStateFlow(readProxy())
    private val privateDnsFlow = MutableStateFlow(com.cosmotune.music.data.PrivateDns.named(store.getString(KEY_PRIVATE_DNS, "OFF")))
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

    /** Look names up over HTTPS through this resolver instead of the network's DNS; see [com.cosmotune.music.data.PrivateDnsResolver]. */
    val privateDns: StateFlow<com.cosmotune.music.data.PrivateDns> = privateDnsFlow.asStateFlow()
    fun setPrivateDns(value: com.cosmotune.music.data.PrivateDns) {
        privateDnsFlow.value = value
        store.putString(KEY_PRIVATE_DNS, value.name)
        publishNetwork()
    }

    private fun publishNetwork() {
        com.cosmotune.music.data.PrivateDnsResolver.choice = privateDnsFlow.value
        NetworkPolicy.offline = offlineFlow.value
        NetworkPolicy.apply(proxyFlow.value.takeIf { it.enabled }?.let(NetworkPolicy::configOf))
    }

    // ---- From upstream 1.10 (GPL-3.0, see NOTICE.md) ----
    private val premiumAudioFlow = MutableStateFlow(store.getBoolean(KEY_PREMIUM_AUDIO, false))
    private val sleepFadeOutFlow = MutableStateFlow(store.getBoolean(KEY_SLEEP_FADE_OUT, true))
    private val bestPartFlow = MutableStateFlow(store.getBoolean(KEY_BEST_PART, true))
    private val waveformSeekFlow = MutableStateFlow(store.getBoolean(KEY_WAVEFORM_SEEK, true))
    private val eqUserPresetsFlow = MutableStateFlow(com.cosmotune.music.playback.EqUserPresets.decode(store.getString(KEY_EQ_USER_PRESETS, "")))
    private val screenOnFlow = MutableStateFlow(ScreenOnPolicy.named(store.getString(KEY_SCREEN_ON, ScreenOnPolicy.DEFAULT.name)))

    /**
     * The listener's word that their YouTube account has Premium: a signed-in account then asks for Premium's ~256 kbps Opus
     * (itag 774) instead of the usual 160 kbps. Off until turned on, because nothing confirms the entitlement.
     */
    val premiumAudio: StateFlow<Boolean> = premiumAudioFlow.asStateFlow()

    /** The sleep timer lowers the volume over its last 30 seconds instead of stopping at once. On unless turned off. */
    val sleepFadeOut: StateFlow<Boolean> = sleepFadeOutFlow.asStateFlow()

    /** The player's Best part button, which skips to the part of the song people replay most. On unless turned off. */
    val bestPart: StateFlow<Boolean> = bestPartFlow.asStateFlow()

    fun setPremiumAudio(value: Boolean) { premiumAudioFlow.value = value; store.putBoolean(KEY_PREMIUM_AUDIO, value) }
    fun setSleepFadeOut(value: Boolean) { sleepFadeOutFlow.value = value; store.putBoolean(KEY_SLEEP_FADE_OUT, value) }
    fun setBestPart(value: Boolean) { bestPartFlow.value = value; store.putBoolean(KEY_BEST_PART, value) }

    /** The player's seek line as the song's waveform, with dots for listeners' timed comments. On unless turned off. */
    val waveformSeek: StateFlow<Boolean> = waveformSeekFlow.asStateFlow()
    fun setWaveformSeek(value: Boolean) { waveformSeekFlow.value = value; store.putBoolean(KEY_WAVEFORM_SEEK, value) }

    /** The equaliser curves the listener saved by name (Your presets), newest first. */
    val eqUserPresets: StateFlow<List<com.cosmotune.music.playback.EqUserPreset>> = eqUserPresetsFlow.asStateFlow()
    fun setEqUserPresets(value: List<com.cosmotune.music.playback.EqUserPreset>) {
        eqUserPresetsFlow.value = value
        store.putString(KEY_EQ_USER_PRESETS, com.cosmotune.music.playback.EqUserPresets.encode(value))
    }

    /** When the player keeps the screen on; see [ScreenOnPolicy]. */
    val screenOn: StateFlow<ScreenOnPolicy> = screenOnFlow.asStateFlow()
    fun setScreenOn(value: ScreenOnPolicy) { screenOnFlow.value = value; store.putString(KEY_SCREEN_ON, value.name) }

    private val playlistFoldersFlow = MutableStateFlow(com.cosmotune.listening.PlaylistFolders.decode(store.getString(KEY_PLAYLIST_FOLDERS, "")))

    /** The folders the listener sorted their playlists into (Library, Playlists); see [com.cosmotune.listening.PlaylistFolders]. */
    val playlistFolders: StateFlow<List<com.cosmotune.listening.PlaylistFolder>> = playlistFoldersFlow.asStateFlow()
    fun setPlaylistFolders(value: List<com.cosmotune.listening.PlaylistFolder>) {
        playlistFoldersFlow.value = value
        store.putString(KEY_PLAYLIST_FOLDERS, com.cosmotune.listening.PlaylistFolders.encode(value))
    }

    private val songTagsFlow = MutableStateFlow(com.cosmotune.listening.SongTags.decode(store.getString(KEY_SONG_TAGS, "")))

    /** The songs the listener rated or tagged; see [com.cosmotune.listening.SongTags]. */
    val songTags: StateFlow<List<com.cosmotune.listening.TaggedSong>> = songTagsFlow.asStateFlow()
    fun setSongTags(value: List<com.cosmotune.listening.TaggedSong>) {
        songTagsFlow.value = value
        store.putString(KEY_SONG_TAGS, com.cosmotune.listening.SongTags.encode(value))
    }

    private val listOrdersFlow = MutableStateFlow(com.cosmotune.listening.ListOrders.decode(store.getString(KEY_LIST_ORDERS, "")))

    /** The order each album or playlist page was last put in, by its browse id; see [com.cosmotune.listening.ListOrders]. */
    val listOrders: StateFlow<Map<String, String>> = listOrdersFlow.asStateFlow()
    fun setListOrder(id: String, order: String?) {
        val next = com.cosmotune.listening.ListOrders.with(listOrdersFlow.value, id, order)
        listOrdersFlow.value = next
        store.putString(KEY_LIST_ORDERS, com.cosmotune.listening.ListOrders.encode(next))
    }

    private val notificationSleepFlow = MutableStateFlow(store.getBoolean(KEY_NOTIFICATION_SLEEP, false))

    /** The phone notification's second button is the sleep timer instead of Repeat. Off until turned on. */
    val notificationSleep: StateFlow<Boolean> = notificationSleepFlow.asStateFlow()
    fun setNotificationSleep(value: Boolean) { notificationSleepFlow.value = value; store.putBoolean(KEY_NOTIFICATION_SLEEP, value) }

    private val nowPlayingWebhookFlow = MutableStateFlow(store.getString(KEY_NOW_PLAYING_WEBHOOK, ""))
    private val nowPlayingBroadcastFlow = MutableStateFlow(store.getBoolean(KEY_NOW_PLAYING_BROADCAST, false))
    private val nowPlayingFileFlow = MutableStateFlow(store.getBoolean(KEY_NOW_PLAYING_FILE, false))

    /** A web address of the listener's own that is told each song as it starts, pauses or resumes; empty for none. See [com.cosmotune.listening.NowPlayingOut]. */
    val nowPlayingWebhook: StateFlow<String> = nowPlayingWebhookFlow.asStateFlow()
    fun setNowPlayingWebhook(value: String) { val tidy = value.trim().take(2_000); nowPlayingWebhookFlow.value = tidy; store.putString(KEY_NOW_PLAYING_WEBHOOK, tidy) }

    /** The phone tells other apps (Tasker, scrobblers) the song with the standard music broadcasts. Off until turned on. */
    val nowPlayingBroadcast: StateFlow<Boolean> = nowPlayingBroadcastFlow.asStateFlow()
    fun setNowPlayingBroadcast(value: Boolean) { nowPlayingBroadcastFlow.value = value; store.putBoolean(KEY_NOW_PLAYING_BROADCAST, value) }

    /** The desktop keeps a text file naming the song, for OBS and other streaming software. Off until turned on. */
    val nowPlayingFile: StateFlow<Boolean> = nowPlayingFileFlow.asStateFlow()
    fun setNowPlayingFile(value: Boolean) { nowPlayingFileFlow.value = value; store.putBoolean(KEY_NOW_PLAYING_FILE, value) }

    companion object {
        const val KEY_SONG_PLAYBACK="extras_song_playback"
        const val KEY_PREMIUM_AUDIO = "extras_youtube_premium_audio"
        const val KEY_SLEEP_FADE_OUT = "extras_sleep_fade_out"
        const val KEY_BEST_PART = "extras_best_part"
        const val KEY_SCREEN_ON = "extras_screen_on"
        const val KEY_WAVEFORM_SEEK = "extras_waveform_seek"
        const val KEY_EQ_USER_PRESETS = "extras_eq_user_presets"
        const val KEY_PLAYLIST_FOLDERS = "extras_playlist_folders"
        const val KEY_SONG_TAGS = "extras_song_tags"
        const val KEY_PRIVATE_DNS = "extras_private_dns"
        const val KEY_NOW_PLAYING_WEBHOOK = "extras_now_playing_webhook"
        const val KEY_NOW_PLAYING_BROADCAST = "extras_now_playing_broadcast"
        const val KEY_NOW_PLAYING_FILE = "extras_now_playing_file"
        const val KEY_NOTIFICATION_SLEEP = "extras_notification_sleep"
        const val KEY_LIST_ORDERS = "extras_list_orders"
        const val KEY_INLINE_LYRICS="extras_inline_lyrics"
        const val KEY_LYRIC_ALIGNMENT="extras_lyric_alignment"
        const val KEY_BLUETOOTH_RESUME="extras_bluetooth_resume"
        const val KEY_PAUSE_ON_MUTE="extras_pause_on_mute"
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
