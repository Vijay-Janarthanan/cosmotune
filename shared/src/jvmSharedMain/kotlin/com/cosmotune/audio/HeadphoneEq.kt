package com.cosmotune.audio

import com.cosmotune.music.playback.*
import kotlinx.serialization.json.*
import kotlin.math.ln

/** Catalogue entries retain the measurement author. Choosing a preset never guesses the connected model. */
object HeadphoneEq {
    data class Preset(val brand: String, val name: String, val author: String, val text: String) {
        val label get() = "$brand $name"
    }
    val presets: List<Preset> by lazy {
        val text = requireNotNull(javaClass.getResourceAsStream("/headphone-eq.json")).bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val data = element.jsonObject
            Preset(data.getValue("b").jsonPrimitive.content, data.getValue("n").jsonPrimitive.content, data.getValue("by").jsonPrimitive.content,
                data.getValue("f").jsonArray.mapIndexed { index, row ->
                    val filter = row.jsonArray
                    "Filter ${index + 1}: ON ${filter[0].jsonPrimitive.content} Fc ${filter[1].jsonPrimitive.content} Hz Gain ${filter[2].jsonPrimitive.content} dB Q ${filter[3].jsonPrimitive.content}"
                }.joinToString("\n"))
        }
    }
    const val CATALOGUE_SOURCE = "https://github.com/GHOSTxASR/AETHER/blob/fd019594548e52be06f6339bb758ff5661f15f3d/core/data/src/main/assets/eq_headphones.json"

    /** Wavelet/AutoEq GraphicEQ and Equalizer APO/squig.link parametric text, previewed at our fixed centres. */
    fun importCurve(text: String, headphone: String, source: String, measurement: String,
        current: ListeningEq = ListeningEq()): Pair<ListeningEq, DeviceEqMeasurement> {
        require(text.length in 1..16_384)
        val provenance = DeviceEqMeasurement(source.trim(), measurement.trim(), format = "import", headphone = headphone.trim()).checked(ListeningDeviceModel.CUSTOM)
        val lines = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
        val graphic = lines.singleOrNull { it.startsWith("GraphicEQ:", true) }
        val bands = if (graphic != null) {
            require(lines.all { it == graphic || it.startsWith("Preamp:", true) }) { "Mixed GraphicEQ and parametric filters are not supported" }
            val points = graphic.substringAfter(':').split(';').map { pair ->
                val values = pair.trim().split(Regex("\\s+"))
                require(values.size == 2) { "Invalid GraphicEQ point" }
                checkedNumber(values[0], 10f..24_000f) to checkedNumber(values[1], -30f..30f)
            }
            require(points.size in 2..256 && points.zipWithNext().all { (a, b) -> a.first < b.first }) { "Frequencies must increase without duplicates" }
            require(points.first().first <= EqLayout.MANUAL_BANDS_HZ.first() && points.last().first >= EqLayout.MANUAL_BANDS_HZ.last()) { "The curve must cover all seven bands" }
            EqLayout.MANUAL_BANDS_HZ.map { hz ->
                val right = points.indexOfFirst { it.first >= hz }.coerceAtLeast(1)
                val a = points[right - 1]; val b = points[right]
                a.second + (b.second - a.second) * ln(hz / a.first) / ln(b.first / a.first)
            }
        } else {
            val filters = lines.filterNot { it.startsWith("Preamp:", true) }.mapNotNull { line ->
                if (Regex("(?i)^Filter\\s+\\d+:\\s+OFF\\b").containsMatchIn(line)) return@mapNotNull null
                val match = requireNotNull(FILTER.matchEntire(line)) { "Unsupported filter. Use ON PK, LSC or HSC with Fc, Gain and Q." }
                val (kind, hz, db, q) = match.destructured
                val slot = FilterSlot(when (kind.uppercase()) {
                    "PK" -> FilterKind.BELL
                    "LS", "LSC" -> FilterKind.LOW_SHELF
                    else -> FilterKind.HIGH_SHELF
                }, checkedNumber(hz, 10f..24_000f))
                Triple(slot, checkedNumber(db, -30f..30f), checkedNumber(q, .1f..20f))
            }
            require(filters.size in 1..32) { "Supply between one and 32 parametric filters" }
            EqLayout.MANUAL_BANDS_HZ.map { hz -> filters.sumOf { (slot, db, q) -> sectionGainDb(slot, db, q, hz).toDouble() }.toFloat() }
        }
        val preamps = lines.filter { it.startsWith("Preamp:", true) }
        require(preamps.size <= 1) { "Supply one preamp value" }
        val preamp = preamps.singleOrNull()?.let { checkedNumber(it.substringAfter(':').trim().removeSuffix("dB").trim(), -30f..0f) } ?: 0f
        val shifted = bands.map { it + preamp }
        require(shifted.all { it.isFinite() && it in -EqLayout.MANUAL_RANGE_DB..EqLayout.MANUAL_RANGE_DB }) { "This curve exceeds the seven-band equalizer's gain range" }
        return current.copy(enabled = true, manual = true, bands = shifted, fullHeadroom = true).checked() to provenance
    }
    private fun checkedNumber(value: String, range: ClosedFloatingPointRange<Float>): Float =
        requireNotNull(value.toFloatOrNull()?.takeIf { it.isFinite() && it in range }) { "EQ value is out of range" }
    private val FILTER = Regex("(?i)^Filter\\s+\\d+:\\s+ON\\s+(PK|LSC?|HSC?)\\s+Fc\\s+([0-9.eE+-]+)\\s+Hz\\s+Gain\\s+([0-9.eE+-]+)\\s+dB\\s+Q\\s+([0-9.eE+-]+)\\s*$")
}
