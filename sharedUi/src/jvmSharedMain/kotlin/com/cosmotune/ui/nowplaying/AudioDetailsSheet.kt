package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.audioFormatLabel
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.AudioSourceComparisons
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.music.ui.player.PlayerDrawer
import com.cosmotune.providers.ProviderHub
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
    PlayerDrawer(hazeState, "Audio details", onDismiss) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(song.title, style = MaterialTheme.typography.titleMedium)
            DetailSection("Playing now")
            DetailFact("Source", name)
            DetailFact("Decoder input", stats?.audioFormatLabel() ?: "Waiting for decoder measurements")
            stats?.channels?.let { DetailFact("Input channels", if (it == 2) "Stereo" else "$it channels") }
            stats?.pcmDataRateKbps?.let { DetailFact("Decoded PCM data rate", "$it kbps (not encoded bitrate)") }
            stats?.claimed?.let { DetailFact("Source offer", it.summary + " · declared by provider") }
            if (stats?.downgraded == true) Text("Received sample rate or bit depth is below the source offer.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onPipeline, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Decoder, processing and output device") }

            HorizontalDivider()
            DetailSection("Source comparison")
            val checking = report?.offers?.count { it.status == AudioSourceComparisons.Status.SEARCHING } ?: 0
            if (checking > 0) Text("Still checking $checking source(s); the current stream keeps playing.", style = MaterialTheme.typography.bodySmall)
            Text("The first usable stream can start immediately while enabled sources look for the same recording. Lossless is preferred; known depth and rate break lossless ties. Bitrates are compared within the same lossy codec. A different master or codec is not automatically better. An enabled Atmos mix preference can take priority without proving immersive output.", style = MaterialTheme.typography.bodySmall)
            if (report?.offers.isNullOrEmpty()) {
                Text("No automatic comparison recorded for this item. Local files, video uploads and explicitly selected versions can keep their original source.", style = MaterialTheme.typography.bodySmall)
            }
            report?.offers.orEmpty().forEach { offer ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(offer.name + if (offer == current) " · Playing" else "", fontWeight = FontWeight.SemiBold)
                    Text(offer.status.label + (offer.httpStatus?.let { " (HTTP $it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    offer.formats.forEach { Text("Offered: ${it.summary}", style = MaterialTheme.typography.bodySmall) }
                    if (offer.status == AudioSourceComparisons.Status.AVAILABLE && offer != current) {
                        Text("Alternative found; the playing stream is shown above. An offer does not confirm successful decoding or justify an audible switch. Compare its values with the decoder input above.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            HorizontalDivider()
            DetailSection("Provider packages")
            if (installed.isEmpty()) Text("No provider packages installed. Add them in Sources → Provider packages to include more catalogues.", style = MaterialTheme.typography.bodySmall)
            installed.forEach { item ->
                val attempted = report?.offers?.any { it.id == "sflx-${item.manifest.name}" } == true
                val status = when {
                    !item.enabled -> "Disabled · not queried"
                    "download_provider" !in item.manifest.type -> "Metadata/lyrics provider · no audio resolution"
                    attempted -> "Included in comparison above"
                    else -> "Enabled · not queried in this lookup; source policy or item type may exclude it"
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(item.manifest.title, fontWeight = FontWeight.SemiBold)
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Text("Declared tiers: " + item.manifest.qualityOptions.joinToString { it.label.ifBlank { it.id } }.ifBlank { "Not stated" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Catalogue availability depends on each provider and its configured account. Declared tiers are not measurements of this song. Atmos/JOC source audio and actual Atmos rendering are separate; output rendering is not verified by a source badge.", style = MaterialTheme.typography.bodySmall)
        }
    }
    }
}

@Composable
private fun DetailSection(title: String) = Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

@Composable
private fun DetailFact(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
}
