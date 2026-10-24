package com.cosmotune.music.data

import java.util.Locale

/** A compact readout of known decoder input, keeping encoded bitrate separate from PCM throughput. */
fun NerdStats.Snapshot.audioFormatLabel(): String? = buildList {
    NerdStats.codecLabel(mimeType)?.let(::add)
    if (bitrateProvenance == TelemetryProvenance.MEASURED || bitrateProvenance == TelemetryProvenance.AUTHORITATIVE) {
        bitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
    }
    bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
    sampleRateHz?.takeIf { it > 0 }?.let { add(String.format(Locale.ROOT, "%.1f kHz", it / 1000f)) }
}.joinToString(" · ").takeIf { it.isNotEmpty() }
