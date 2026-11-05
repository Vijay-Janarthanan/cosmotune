package com.cosmotune.ui.nowplaying

import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.TelemetryProvenance
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.components.SourceQuality
import com.cosmotune.music.ui.components.sourceQualityBadge
import java.util.Locale

/*
 * The quality pills (the tiers, colours and labels follow Lokal's `src/quality.js` and `src/resolutionLabel.mjs`, MIT, adapted;
 * see NOTICE.md): one pill model for Now Playing, the mini player, the player bar and the song rows.
 *
 * Two kinds of evidence are kept apart, in the words and in the look:
 *  - MEASURED: what the decoder is being fed now ([NerdStats.Snapshot]), the only thing that may say "lossless" or "Dolby Atmos"
 *    about what is playing. Drawn filled.
 *  - CATALOGUE: what a source or catalogue says a row can be streamed at ([Song.sourceQuality]). Drawn outlined, and read out as
 *    "reported by the catalogue"; an Atmos catalogue tag reads "Atmos available", never "Dolby Atmos" as if it were playing.
 *
 * A lossy stream never shows a bit depth (a decoder's PCM width is not the source's), and nothing is claimed before the decoder has
 * measured something.
 */

/** The tier of a pill. It decides the wording and whether the pill is drawn as the highest tier (primary ink, outlined); pills are neutral, not coloured. */
enum class QualityTone { ATMOS, HI_RES, LOSSLESS, HIGH, STANDARD, LOW }

/** Where the pill's claim comes from. */
enum class QualityEvidence { MEASURED, CATALOGUE }

/** One pill: its [tone], its short [text] ("FLAC · 24/96", "AAC 256", "Dolby Atmos"), and where the claim comes from. */
data class QualityBadge(val tone: QualityTone, val text: String, val evidence: QualityEvidence) {
    /** What a screen reader says: the text and where it comes from. */
    val spoken: String
        get() = when (evidence) {
            QualityEvidence.MEASURED -> "$text, measured from the playing stream"
            QualityEvidence.CATALOGUE -> "$text, reported by the catalogue"
        }
}

/** "96", "44.1", "2.8224 MHz" (DSD's rates keep their digits), from a rate in Hz. */
internal fun compactRate(hz: Int): String =
    NerdStats.megahertzLabel(hz) ?: (hz / 1000.0).let { khz ->
        if (khz == Math.floor(khz)) khz.toInt().toString() else String.format(Locale.ROOT, "%.1f", khz).trimEnd('0').trimEnd('.')
    }

/**
 * The pill of what is playing, from the decoder's own figures, or null when there is nothing measured to say. Lossless: the codec
 * and "depth/rate" ("FLAC · 24/96", "ALAC · 16/44.1"; only the half that is known otherwise: "FLAC · 96 kHz", "FLAC · 24-bit").
 * Lossy: the codec and the measured bitrate ("AAC 256", "Opus 160"), never a bit depth. Dolby Atmos (E-AC-3 JOC) is its own pill
 * and never lossless.
 */
fun NerdStats.Snapshot.measuredQualityBadge(): QualityBadge? {
    val codec = NerdStats.codecLabel(mimeType) ?: return null
    if (isDolbyAtmos) return QualityBadge(QualityTone.ATMOS, "Dolby Atmos", QualityEvidence.MEASURED)
    if (isLossless) {
        // DSD reaches the decoder as plain PCM; its container ("DSF", "DFF") is the honest name then.
        val name = if (codec == "PCM" && !container.isNullOrBlank()) container!!.uppercase() else codec
        val bits = bitDepth?.takeIf { it > 0 }
        val rate = sampleRateHz?.takeIf { it > 0 }
        val detail = when {
            bits != null && rate != null && NerdStats.megahertzLabel(rate) == null -> "$bits/${compactRate(rate)}"
            rate != null -> if (NerdStats.megahertzLabel(rate) != null) compactRate(rate) else "${compactRate(rate)} kHz"
            bits != null -> "$bits-bit"
            else -> null
        }
        return QualityBadge(if (isHiRes) QualityTone.HI_RES else QualityTone.LOSSLESS, listOfNotNull(name, detail).joinToString(" · "), QualityEvidence.MEASURED)
    }
    val measured = bitrateProvenance == TelemetryProvenance.MEASURED || bitrateProvenance == TelemetryProvenance.AUTHORITATIVE
    val kbps = bitrateKbps?.takeIf { it > 0 && measured }
    val tone = when {
        isHiQuality -> QualityTone.HIGH
        isLowQuality -> QualityTone.LOW
        else -> QualityTone.STANDARD
    }
    return QualityBadge(tone, if (kbps != null) "$codec $kbps" else codec, QualityEvidence.MEASURED)
}

/**
 * The pill a source or catalogue reports for a row ([Song.sourceQualityBadge]), or null when it reports nothing worth a label.
 * Worded as an offer: "Hi-Res", "Lossless", "High", and "Atmos available" for a Dolby Atmos edition.
 */
fun Song.catalogueQualityBadge(): QualityBadge? = when (sourceQualityBadge()) {
    SourceQuality.DOLBY_ATMOS -> QualityBadge(QualityTone.ATMOS, "Atmos available", QualityEvidence.CATALOGUE)
    SourceQuality.HI_RES_LOSSLESS -> QualityBadge(QualityTone.HI_RES, "Hi-Res", QualityEvidence.CATALOGUE)
    SourceQuality.LOSSLESS -> QualityBadge(QualityTone.LOSSLESS, "Lossless", QualityEvidence.CATALOGUE)
    SourceQuality.HIGH_QUALITY -> QualityBadge(QualityTone.HIGH, "High", QualityEvidence.CATALOGUE)
    null -> null
}

/**
 * The colours of a pill as ARGB: the [ink] of its text, the [fill] behind it (translucent: it sits on whatever ground is under it)
 * and its [edge]. The inks keep 4.5:1 over the fill on every surface tier of their theme.
 */
data class QualityColours(val ink: Int, val fill: Int, val edge: Int)

/**
 * The tone's colours in the dark or the light theme (DESIGN_SYSTEM.md section 4.11: quality pills are neutral, never coloured).
 * A measured pill is a quiet fill with secondary ink; the highest tiers (Hi-Res, Atmos) take the primary ink and a 1 dp outline. A
 * catalogue pill has no fill, only its outline, so an offer cannot be taken for a measurement.
 */
fun QualityTone.colours(dark: Boolean, evidence: QualityEvidence = QualityEvidence.MEASURED): QualityColours {
    val primary = if (dark) 0xFFF2F2F3.toInt() else 0xFF121214.toInt()
    val secondary = if (dark) 0xFFB4B4BB.toInt() else 0xFF55555D.toInt()
    val outline = if (dark) 0x29FFFFFF else 0x29000000
    val top = this == QualityTone.HI_RES || this == QualityTone.ATMOS
    return when (evidence) {
        QualityEvidence.MEASURED -> QualityColours(
            ink = if (top) primary else secondary,
            fill = if (dark) 0x14FFFFFF else 0x0F000000,
            edge = if (top) outline else 0,
        )
        QualityEvidence.CATALOGUE -> QualityColours(ink = secondary, fill = 0, edge = outline)
    }
}
