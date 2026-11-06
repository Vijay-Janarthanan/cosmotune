package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.audioFormatLabel
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.AudioSourceComparisons
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.music.ui.player.PlayerDrawer
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.providers.ProviderHub
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import dev.chrisbanes.haze.HazeState

internal val LocalOpenAudioDetails = staticCompositionLocalOf<(() -> Unit)?> { null }

/** A tap reads existing resolver evidence. Opening this sheet never starts provider requests. */
@Composable
fun AudioDetailsSheet(song: Song, hazeState: HazeState, onDismiss: () -> Unit, onPipeline: () -> Unit) {
    val stats by NerdStats.current.collectAsStateWithLifecycle()
    val reports by AudioSourceComparisons.state.collectAsStateWithLifecycle()
    val hub by ProviderHub.state.collectAsStateWithLifecycle()
    val installed = hub.manager?.store?.state?.collectAsStateWithLifecycle()?.value?.installed.orEmpty()
    val report = reports[AudioSourceComparisons.key(TrackMatcher.targetOf(song))]
    val source = stats?.sourceName
    val current = AudioSourceComparisons.playingOffer(report, stats?.sourceId, source)
    val name = current?.name ?: ProviderHub.sourceName(source) ?: source ?: "Not reported yet"
    val fonts = orbitFonts()
    val look = orbitLook()
    val small = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp)
    val strong = fonts.title.copy(fontSize = 14.sp)
    PlayerDrawer(hazeState, "Audio details", onDismiss, titleGap = 4.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(song.title, style = fonts.body, color = look.dim)
            DetailCard(look) { SignalPath(stats, name, rememberOutputSummary(), fonts, look) }
            DetailCard(look) {
                DetailSection("Playing now", fonts, look)
                DetailFact("Source", name, small, look)
                DetailFact("Decoder input", stats?.audioFormatLabel() ?: "Waiting for decoder measurements", small, look)
                stats?.channels?.let { DetailFact("Input channels", if (it == 2) "Stereo" else "$it channels", small, look) }
                stats?.pcmDataRateKbps?.let { DetailFact("Decoded PCM data rate", "$it kbps (not encoded bitrate)", small, look) }
                stats?.claimed?.let { DetailFact("Source offer", it.summary + " · declared by provider", small, look) }
                if (stats?.downgraded == true) Text("Received sample rate or bit depth is below the source offer.", style = small, color = MaterialTheme.colorScheme.error)
                OrbitSheetButton("Decoder, processing and output device", onPipeline, Modifier.padding(top = 6.dp), icon = CosmoIcons.Equaliser)
            }

            DetailCard(look) {
                DetailSection("Source comparison", fonts, look)
                val checking = report?.offers?.count { it.status == AudioSourceComparisons.Status.SEARCHING } ?: 0
                if (checking > 0) Text("Still checking $checking source(s); the current stream keeps playing.", style = small, color = look.ink)
                Text("The first usable stream can start immediately while enabled sources look for the same recording. Lossless is preferred; known depth and rate break lossless ties. Bitrates are compared within the same lossy codec. A different master or codec is not automatically better. An enabled Atmos mix preference can take priority without proving immersive output.", style = small, color = look.dim)
                if (report?.offers.isNullOrEmpty()) {
                    Text("No automatic comparison recorded for this item. Local files, video uploads and explicitly selected versions can keep their original source.", style = small, color = look.dim)
                }
                report?.offers.orEmpty().forEach { offer ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(offer.name + if (offer == current) " · Playing" else "", style = strong, color = if (offer == current) look.accent else look.ink)
                        Text(offer.status.label + (offer.httpStatus?.let { " (HTTP $it)" } ?: ""), style = small, color = look.dim)
                        offer.formats.forEach { Text("Offered: ${it.summary}", style = small, color = look.ink) }
                        if (offer.status == AudioSourceComparisons.Status.AVAILABLE && offer != current) {
                            Text("Alternative found; the playing stream is shown above. An offer does not confirm successful decoding or justify an audible switch. Compare its values with the decoder input above.", style = small, color = look.dim)
                        }
                    }
                }
            }

            DetailCard(look) {
                DetailSection("Provider packages", fonts, look)
                if (installed.isEmpty()) Text("No provider packages installed. Add them in Sources → Provider packages to include more catalogues.", style = small, color = look.dim)
                installed.forEach { item ->
                    val attempted = report?.offers?.any { it.id == "sflx-${item.manifest.name}" } == true
                    val status = when {
                        !item.enabled -> "Disabled · not queried"
                        "download_provider" !in item.manifest.type -> "Metadata/lyrics provider · no audio resolution"
                        attempted -> "Included in comparison above"
                        else -> "Enabled · not queried in this lookup; source policy or item type may exclude it"
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.manifest.title, style = strong, color = look.ink)
                        Text(status, style = small, color = look.dim)
                        Text("Declared tiers: " + item.manifest.qualityOptions.joinToString { it.label.ifBlank { it.id } }.ifBlank { "Not stated" }, style = small, color = look.dim)
                    }
                }
                Text("Catalogue availability depends on each provider and its configured account. Declared tiers are not measurements of this song. Atmos/JOC source audio and actual Atmos rendering are separate; output rendering is not verified by a source badge.", style = small, color = look.dim)
            }
        }
    }
}

/** One group of the sheet: the quiet Orbit card (ink at 5 %, radius 22) its facts sit on, in place of a rule between groups. */
@Composable
private fun DetailCard(look: OrbitLook, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(look.row).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** What the output says it negotiated ("24-bit PCM · 48 kHz"), or null where the host reports nothing (or has no output to ask). */
@Composable
private fun rememberOutputSummary(): String? {
    val flow = remember { runCatching { PlayerPlatform.host.outputFormat }.getOrNull() } ?: return null
    return flow.collectAsStateWithLifecycle().value.summary.takeIf { it.isNotBlank() }
}

/** One step of the signal path: what it is, what it says, and how that is known. */
private class PathStep(val title: String, val value: String, val evidence: String?, val badge: QualityBadge? = null)

/**
 * The signal path (Lokal's audio pipeline sheet, adapted; see NOTICE.md): the source and what it offers (as the catalogue reports
 * it), what the decoder is fed (measured, with its pill), the processing, and what the output negotiated. Each step says how it is
 * known, so an offer is never read as a measurement.
 */
@Composable
private fun SignalPath(stats: NerdStats.Snapshot?, source: String, output: String?, fonts: OrbitFonts, look: OrbitLook) {
    val offer = stats?.claimed?.summary
    val steps = listOf(
        PathStep("Source", if (offer != null) "$source · offers $offer" else source, if (offer != null) "Catalogue-reported" else null),
        PathStep("Decoder input", stats?.audioFormatLabel() ?: "Waiting for the first measurement", if (stats != null) "Measured" else null, stats?.measuredQualityBadge()),
        PathStep("Processing", "Equaliser, normalisation and effects: see the full pipeline below", null),
        PathStep("Output", output ?: "Not reported by this device", if (output != null) "Negotiated with the output" else null),
    )
    DetailSection("Signal path", fonts, look)
    Column(Modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                // The rail: a dot per step and a line down to the next one.
                Box(Modifier.width(20.dp).fillMaxHeight()) {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).clip(CircleShape).background(look.ink).align(Alignment.TopCenter))
                    if (index < steps.lastIndex) {
                        Box(Modifier.padding(top = 15.dp).width(1.dp).fillMaxHeight().background(look.ink.copy(alpha = 0.22f)).align(Alignment.TopCenter))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 8.dp, bottom = if (index < steps.lastIndex) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(step.title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink)
                        step.evidence?.let { OrbitLabel(it, fonts, look.dim) }
                    }
                    step.badge?.let { QualityPill(it) }
                    Text(step.value, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 17.sp), color = look.dim)
                }
            }
        }
    }
}

/** A group's mono overline ("PLAYING NOW"). */
@Composable
private fun DetailSection(title: String, fonts: OrbitFonts, look: OrbitLook) = OrbitLabel(title, fonts, look.dim, Modifier.padding(bottom = 2.dp))

@Composable
private fun DetailFact(label: String, value: String, style: TextStyle, look: OrbitLook) {
    Text("$label: $value", style = style, color = look.ink)
}
