package com.cosmotune.music.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/** One filter line of an AutoEQ "ParametricEQ.txt" file. */
data class EqFilter(val kind: FilterKind, val frequencyHz: Float, val gainDb: Float, val q: Float)

/** A line that was not used, with why, so the listener can be told in plain words. */
data class EqLineProblem(val lineNumber: Int, val text: String, val reason: String)

/** What a pasted or opened preset text held: the filters that were readable and the lines that were not. */
data class EqParseResult(val preampDb: Float?, val filters: List<EqFilter>, val rejected: List<EqLineProblem>)

/** Seven slider values that stand in for a set of filters; [clipped] when one hit the end of its travel. */
data class BandMapping(val bandsDb: List<Float>, val clipped: Boolean)

/**
 * The AutoEQ / Equalizer APO text format that preset sites hand out:
 *
 *     Preamp: -6.2 dB
 *     Filter 1: ON PK Fc 105 Hz Gain 2.3 dB Q 0.70
 *
 * plus LSC / HSC (and LS / HS) shelves. Cosmotune's equaliser is seven fixed
 * bands, so an import is mapped onto them ([mapToBands]) rather than followed
 * exactly. Reading never throws: lines it cannot use are listed in
 * [EqParseResult.rejected] and the rest still count.
 *
 * The idea of importing and exporting this format with typed-in values comes
 * from LastWave Native (GPL-3.0, commit 1398814bc7); this code is written for
 * Cosmotune's own filter layout.
 */
object EqPresetText {
    const val MAX_FILTERS = 32
    const val MAX_TEXT_CHARS = 32_768
    private const val GAIN_LIMIT = 30f

    private const val NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)"
    private val PREAMP = Regex("^preamp\\s*:\\s*($NUMBER)\\s*(?:db)?$", RegexOption.IGNORE_CASE)
    private val FILTER = Regex(
        "^filter\\s*\\d*\\s*:\\s*(on|off)\\s+(\\w+)\\s+fc\\s+($NUMBER)\\s*hz\\s+gain\\s+($NUMBER)\\s*db\\s+q\\s+($NUMBER)$",
        RegexOption.IGNORE_CASE,
    )
    private val FILTER_OFF = Regex("^filter\\s*\\d*\\s*:\\s*off\\b", RegexOption.IGNORE_CASE)

    fun parse(text: String): EqParseResult {
        var preamp: Float? = null
        val filters = ArrayList<EqFilter>()
        val rejected = ArrayList<EqLineProblem>()
        fun reject(number: Int, line: String, reason: String) {
            rejected += EqLineProblem(number, line.take(120), reason)
        }
        text.take(MAX_TEXT_CHARS).split('\n').forEachIndexed { index, raw ->
            val number = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val preampMatch = PREAMP.matchEntire(line)
            if (preampMatch != null) {
                val value = preampMatch.groupValues[1].toFloatOrNull()
                when {
                    value == null || !value.isFinite() || value > 0f || value < -GAIN_LIMIT ->
                        reject(number, line, "Preamp must be between -30 and 0 dB")
                    preamp != null -> reject(number, line, "More than one preamp line")
                    else -> preamp = value
                }
                return@forEachIndexed
            }
            if (FILTER_OFF.containsMatchIn(line)) return@forEachIndexed
            if (line.startsWith("preamp", true)) {
                reject(number, line, "Preamp must be between -30 and 0 dB")
                return@forEachIndexed
            }
            val match = FILTER.matchEntire(line)
            if (match == null) {
                reject(number, line, "Not a filter line")
                return@forEachIndexed
            }
            val kind = when (match.groupValues[2].uppercase()) {
                "PK" -> FilterKind.BELL
                "LS", "LSC" -> FilterKind.LOW_SHELF
                "HS", "HSC" -> FilterKind.HIGH_SHELF
                else -> {
                    reject(number, line, "Only peak and shelf filters are supported")
                    return@forEachIndexed
                }
            }
            val hz = match.groupValues[3].toFloatOrNull()
            val gain = match.groupValues[4].toFloatOrNull()
            val q = match.groupValues[5].toFloatOrNull()
            val reason = when {
                hz == null || !hz.isFinite() || hz < 20f || hz > 20_000f -> "Frequency must be 20 to 20000 Hz"
                gain == null || !gain.isFinite() || abs(gain) > GAIN_LIMIT -> "Gain must be within 30 dB"
                q == null || !q.isFinite() || q < 0.1f || q > 20f -> "Q must be 0.1 to 20"
                filters.size >= MAX_FILTERS -> "More than $MAX_FILTERS filters"
                else -> null
            }
            if (reason != null) reject(number, line, reason) else filters += EqFilter(kind, hz!!, gain!!, q!!)
        }
        return EqParseResult(preamp, filters, rejected)
    }

    /**
     * Writes the seven bands as filters in the same format. The two ends are
     * shelves, the five between are peaks, all at the equaliser's own Q, so
     * the file describes the very curve the app plays.
     */
    fun export(bandsDb: List<Float>, preampDb: Float): String = buildString {
        append("Preamp: ").append(tenths(preampDb.coerceAtMost(0f))).append(" dB\n")
        EqLayout.MANUAL_BANDS_HZ.forEachIndexed { index, hz ->
            val code = when (EqLayout.slots[index].kind) {
                FilterKind.LOW_SHELF -> "LSC"
                FilterKind.HIGH_SHELF -> "HSC"
                FilterKind.BELL -> "PK"
            }
            val gain = bandsDb.getOrElse(index) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
            append("Filter ${index + 1}: ON $code Fc ${hz.roundToInt()} Hz Gain ${tenths(gain)} dB Q ${hundredths(EqLayout.MANUAL_Q)}\n")
        }
    }

    /**
     * Finds the seven slider values whose response, at the seven centres, is
     * the sum of [filters] there. Each pass nudges every band by what is still
     * missing at its centre, which takes out the overlap between neighbouring
     * bands, so a file this app exported maps back onto the same sliders.
     */
    fun mapToBands(filters: List<EqFilter>): BandMapping {
        val centres = EqLayout.MANUAL_BANDS_HZ
        val target = FloatArray(centres.size) { i ->
            filters.sumOf { sectionGainDb(FilterSlot(it.kind, it.frequencyHz), it.gainDb, it.q, centres[i]).toDouble() }.toFloat()
        }
        val range = EqLayout.MANUAL_RANGE_DB
        val gains = FloatArray(centres.size)
        var clipped = false
        repeat(24) {
            val next = FloatArray(centres.size)
            for (i in centres.indices) {
                var response = 0f
                for (j in centres.indices) response += sectionGainDb(EqLayout.slots[j], gains[j], EqLayout.MANUAL_Q, centres[i])
                next[i] = gains[i] + (target[i] - response)
            }
            clipped = false
            for (i in centres.indices) {
                if (abs(next[i]) > range) clipped = true
                gains[i] = next[i].coerceIn(-range, range)
            }
        }
        return BandMapping(gains.map { (it * 10f).roundToInt() / 10f + 0f }, clipped)
    }

    /** A typed band value: "2.5", "-3 dB", "+1,5". Past the slider's travel it stops at the end; unreadable gives null. */
    fun parseGain(text: String): Float? {
        val cleaned = text.trim().removeSuffix("dB").removeSuffix("db").removeSuffix("DB").trim()
            .replace(',', '.').removePrefix("+")
        val value = cleaned.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return value.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
    }

    private fun tenths(value: Float): String {
        val scaled = (value * 10f).roundToInt()
        val sign = if (scaled < 0) "-" else ""
        return "$sign${abs(scaled) / 10}.${abs(scaled) % 10}"
    }

    private fun hundredths(value: Float): String {
        val scaled = (value * 100f).roundToInt()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }
}
