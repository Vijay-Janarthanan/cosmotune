package com.cosmotune.settings

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

    /** The loudest the app plays, in percent of full scale; 100 is no cap. Applied after every other stage. */
    val maxVolumePercent: StateFlow<Int> = maxVolumeFlow.asStateFlow()

    /** Pitch shift in semitones, -12..12, kept apart from playback speed. 0 leaves the pitch alone. */
    val pitchSemitones: StateFlow<Int> = pitchFlow.asStateFlow()

    /** While on, nothing is fetched from the internet: only local files, downloads and the local network play. */
    val offline: StateFlow<Boolean> = offlineFlow.asStateFlow()

    /** The listener's own proxy; see [ProxySetting]. */
    val proxy: StateFlow<ProxySetting> = proxyFlow.asStateFlow()

    init {
        publishNetwork()
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

        const val MIN_VOLUME_PERCENT = 10

        fun clampVolume(value: Int) = value.coerceIn(MIN_VOLUME_PERCENT, 100)
        fun clampPitch(value: Int) = value.coerceIn(-12, 12)
        fun pitchFactorOf(semitones: Int): Float = 2.0.pow(semitones / 12.0).toFloat()
    }
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
