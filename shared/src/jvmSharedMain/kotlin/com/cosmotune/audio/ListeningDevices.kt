package com.cosmotune.audio

import com.cosmotune.music.playback.EqLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.net.URI
import java.net.URLDecoder
import kotlin.math.ln

/** Product specifications are guidance, never a reading of the connected transport. */
enum class ListeningDeviceModel(val label: String, val advertised: String, val guide: String, val setup: String) {
    CUSTOM("Other / custom device", "No model capability has been verified", "", "Use the device manufacturer's setup instructions."),
    SONY_WH1000XM5("Sony WH-1000XM5", "Advertised A2DP codecs: SBC, AAC, LDAC",
        "https://helpguide.sony.net/mdr/wh1000xm5/v1/en/contents/TP1000534600.html",
        "In Android Bluetooth device settings, enable LDAC if offered. In Sony Sound Connect, check sound-quality priority. Android and the headset choose the active codec; Cosmotune cannot force it."),
    SAMSUNG_BUDS4_PRO("Samsung Galaxy Buds4 Pro", "Samsung advertises SSC UHQ up to 24-bit / 96 kHz on compatible Galaxy phones",
        "https://www.samsung.com/us/audio-sound/galaxy-buds4-pro/",
        "On compatible Galaxy software, open Galaxy Wearable / Buds settings → Sound quality and effects → Advanced quality options → Ultra high quality (UHQ). Availability depends on phone software. This does not confirm the active codec."),
    AUDIOCULAR_CS_PRO("AUDIOCULAR CS-Pro CS46L41", "Seller specifications are not USB endpoint or DSD transport measurements",
        "https://conceptkart.com/products/audiocular-d08-cs-pro-cs46l41-typec-to-3-5mm-dac-dongle-demo-unit",
        "Connect the USB DAC and inspect the accepted output format. Android's system USB path is not a userspace USB driver. Native DSD support cannot be inferred from the product name.");
}

@Serializable
data class ListeningEq(val enabled: Boolean = false, val manual: Boolean = false, val toneX: Int = 0,
    val toneY: Int = 0, val focused: Boolean = false, val balance: Float = 0f,
    val bands: List<Float> = List(7) { 0f }, val fullHeadroom: Boolean = false) {
    fun checked(): ListeningEq {
        require(toneX in -EqLayout.TONE_STEPS..EqLayout.TONE_STEPS && toneY in -EqLayout.TONE_STEPS..EqLayout.TONE_STEPS)
        require(balance.isFinite() && balance in -1f..1f && bands.size == EqLayout.MANUAL_COUNT)
        require(bands.all { it.isFinite() && it in -EqLayout.MANUAL_RANGE_DB..EqLayout.MANUAL_RANGE_DB })
        return this
    }
}

@Serializable
data class ListeningDeviceProfile(val routeKey: String, val routeLabel: String, val model: String,
    val eq: ListeningEq, val restoreOnConnect: Boolean = false, val bypass: Boolean = false,
    val measurement: DeviceEqMeasurement? = null) {
    val knownModel get() = ListeningDeviceModel.entries.firstOrNull { it.name == model } ?: ListeningDeviceModel.CUSTOM
    fun appliedEq() = eq.copy(enabled = eq.enabled && !bypass)
}

/** Bounded, device-local settings. Construct once per platform and supply its private persistence. */
class ListeningDeviceProfiles(private val read: () -> String, private val write: (String) -> Unit) {
    @Serializable private data class Saved(val version: Int = 1, val profiles: List<ListeningDeviceProfile> = emptyList())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow(load())
    val profiles: StateFlow<List<ListeningDeviceProfile>> = mutable
    @Synchronized fun save(profile: ListeningDeviceProfile) {
        require(profile.routeKey.matches(Regex("[a-f0-9]{64}")) && profile.routeLabel.length in 1..120)
        require(ListeningDeviceModel.entries.any { it.name == profile.model })
        profile.eq.checked()
        profile.measurement?.checked(profile.knownModel)
        val next = (mutable.value.filterNot { it.routeKey == profile.routeKey } + profile).takeLast(12)
        val encoded = json.encodeToString(Saved(profiles = next))
        require(encoded.length <= 8_000)
        write(encoded) // Do not publish a remembered setting if persistence failed.
        mutable.value = next
    }
    @Synchronized fun remove(key: String) {
        val next = mutable.value.filterNot { it.routeKey == key }
        write(json.encodeToString(Saved(profiles = next))); mutable.value = next
    }
    fun forRoute(key: String?) = mutable.value.firstOrNull { it.routeKey == key }
    private fun load(): List<ListeningDeviceProfile> = runCatching {
        val raw = read(); require(raw.length <= 8_000)
        val saved = json.decodeFromString<Saved>(raw); require(saved.version == 1 && saved.profiles.size <= 12)
        saved.profiles.onEach {
            require(it.routeKey.matches(Regex("[a-f0-9]{64}")) && it.routeLabel.length in 1..120)
            require(ListeningDeviceModel.entries.any { model -> model.name == it.model }); it.eq.checked(); it.measurement?.checked(it.knownModel)
        }.distinctBy { it.routeKey }
    }.getOrDefault(emptyList())
}

@Serializable
data class DeviceEqMeasurement(val sourceUrl: String, val measurement: String, val approximation: Boolean = true) {
    fun checked(model: ListeningDeviceModel): DeviceEqMeasurement {
        require(model in setOf(ListeningDeviceModel.SONY_WH1000XM5, ListeningDeviceModel.SAMSUNG_BUDS4_PRO)) { "Choose the exact headphone model first" }
        require(measurement.isNotBlank() && measurement.length <= 160 && sourceUrl.length <= 1024)
        val uri = URI(sourceUrl)
        require(uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.query == null)
        val path = URLDecoder.decode(uri.rawPath.orEmpty(), "UTF-8")
        require((uri.host == "github.com" && path.startsWith("/jaakkopasanen/AutoEq/blob/") ||
            uri.host == "raw.githubusercontent.com" && path.startsWith("/jaakkopasanen/AutoEq/")) &&
            "/results/" in path && "/${model.label}/" in path && path.endsWith("GraphicEQ.txt")) {
            "Use the matching model's AutoEq results GraphicEQ.txt source URL"
        }
        require(approximation) // This engine has fixed centres/Q; it cannot apply arbitrary PEQ exactly.
        return this
    }
}

/** Explicit, previewable log-frequency approximation; no downloads and no claim of exact PEQ. */
fun importAutoEqGraphic(model: ListeningDeviceModel, sourceUrl: String, measurement: String, text: String,
    current: ListeningEq = ListeningEq()): Pair<ListeningEq, DeviceEqMeasurement> {
    val provenance = DeviceEqMeasurement(sourceUrl.trim(), measurement.trim()).checked(model)
    require(text.length <= 16_384 && text.trim().startsWith("GraphicEQ:")) { "Paste a single AutoEq GraphicEQ line" }
    val points = text.trim().removePrefix("GraphicEQ:").split(';').map { pair ->
        val values = pair.trim().split(Regex("\\s+")); require(values.size == 2) { "Invalid GraphicEQ point" }
        val hz = values[0].toFloatOrNull(); val db = values[1].toFloatOrNull()
        require(hz != null && hz.isFinite() && hz in 10f..24_000f && db != null && db.isFinite() && db in -30f..30f)
        hz to db
    }
    require(points.size in 2..256 && points.zipWithNext().all { (a, b) -> a.first < b.first }) { "Frequencies must increase without duplicates" }
    require(points.first().first <= EqLayout.MANUAL_BANDS_HZ.first() && points.last().first >= EqLayout.MANUAL_BANDS_HZ.last()) { "The curve does not cover every app band" }
    val bands = EqLayout.MANUAL_BANDS_HZ.map { hz ->
        val right = points.indexOfFirst { it.first >= hz }.coerceAtLeast(1)
        val a = points[right - 1]; val b = points[right]
        val mix = ln(hz / a.first) / ln(b.first / a.first)
        (a.second + (b.second - a.second) * mix).also {
            require(it in -EqLayout.MANUAL_RANGE_DB..EqLayout.MANUAL_RANGE_DB) { "The curve exceeds this equalizer's supported gain range" }
        }
    }
    return current.copy(enabled = true, manual = true, bands = bands, fullHeadroom = true).checked() to provenance
}

/** A reconnect never uses Android's ephemeral device ID, nor guesses a product model from its name. */
fun listeningRouteKey(platform: String, kind: String, identity: String): String = MessageDigest.getInstance("SHA-256")
    .digest("$platform\u0000$kind\u0000$identity".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** Trigger restoration once per actual route change, only for an explicitly saved opt-in. */
class ListeningReconnect {
    private var previous: String? = null
    fun changed(key: String?, profiles: ListeningDeviceProfiles): ListeningEq? {
        if (previous == key) return null
        previous = key
        return profiles.forRoute(key)?.takeIf { it.restoreOnConnect }?.appliedEq()
    }
}

data class ListeningDeviceFacts(val routeKey: String? = null, val name: String = "No active output",
    val identityDetail: String = "Device identity unavailable", val measured: List<Pair<String, String>> = emptyList(),
    val unavailable: String = "Headset internal DAC format, ANC and transport bitrate are not exposed",
    val spatial: String = "Platform spatial audio state unavailable", val tuningAvailable: Boolean = true)

/** System spatialization is a separate processor; do not stack stereo widening over eligible content. */
data class PlatformSpatialState(val supported: Boolean = false, val available: Boolean = false,
    val enabled: Boolean = false, val eligible: Boolean? = null, val headTracker: Boolean? = null,
    val reported: Boolean = false) {
    val blocksWidening get() = enabled && available && eligible != false
}
