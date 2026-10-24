package com.cosmotune.ui.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.cosmotune.music.data.canvas.AppleArtistArt
import com.cosmotune.music.data.canvas.AppleArtistArtRepository
import com.cosmotune.music.data.canvas.ArtistSignature
import com.cosmotune.music.data.canvas.ArtistSignatureRepository
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.ui.player.CanvasArtworkPlayer
import com.cosmotune.music.ui.player.CanvasContentMode
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.signed_by
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Platform stores supply their existing settings flows before any artist page is composed. */
object ArtistVisualSettings {
    var enabled: StateFlow<Boolean> = MutableStateFlow(false)
        private set
    var baseUrl: StateFlow<String> = MutableStateFlow("")
        private set
    fun install(enabled: StateFlow<Boolean>, baseUrl: StateFlow<String>) {
        this.enabled = enabled
        this.baseUrl = baseUrl
    }
}

data class ArtistVisuals(val art: AppleArtistArt?, val signature: ArtistSignature?)

@Composable
fun rememberArtistVisuals(name: String): ArtistVisuals {
    val enabled by ArtistVisualSettings.enabled.collectAsStateWithLifecycle()
    val address by ArtistVisualSettings.baseUrl.collectAsStateWithLifecycle()
    val base = ArtistSignatureRepository.effectiveBaseUrl(enabled, address)
    var art by remember(name) { mutableStateOf(AppleArtistArtRepository.cached(name)) }
    var signature by remember(name, base) { mutableStateOf(ArtistSignatureRepository.cached(name, base)) }
    LaunchedEffect(name, base) {
        if (name.isBlank()) return@LaunchedEffect
        coroutineScope {
            launch { art = AppleArtistArtRepository.artFor(name) ?: art }
            if (base != null) launch { signature = ArtistSignatureRepository.signatureFor(name, base) ?: signature }
        }
    }
    return ArtistVisuals(art, signature)
}

/** The still remains behind the muted loop until frames arrive or if video fails. */
@Composable
fun ArtistMotionArtwork(art: AppleArtistArt?, fallbackUrl: String?, modifier: Modifier = Modifier) {
    val settings = PlayerPlatform.host.settings
    val animated by settings.animatedCanvas.collectAsStateWithLifecycle()
    val cellular by settings.meteredConnection.collectAsStateWithLifecycle()
    val overCellular by settings.canvasOverCellular.collectAsStateWithLifecycle()
    val reduced = LocalReducedMotion.current
    val clip = remember(art?.videoUrl) { art?.videoUrl?.let { CanvasArtwork(it) } }
    var rendered by remember(clip) { mutableStateOf(false) }
    Box(modifier) {
        AsyncImage(art?.heroUrl ?: fallbackUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (clip != null && artistMotionPlays(animated, reduced, cellular, overCellular)) {
            CanvasArtworkPlayer(clip, isPlaying = true, modifier = Modifier.fillMaxSize(), contentMode = CanvasContentMode.CROP,
                onRenderedChanged = { rendered = it }, presentationAlpha = { if (rendered) 1f else 0f })
        }
    }
}

/**
 * Whether the artist's muted header loop may replace the still: the listener's animated-artwork setting is on, the system
 * has not asked for reduced motion, and a metered connection is used only when canvas over mobile data is allowed. An
 * unknown network ([metered] null) is not treated as metered, as the player's own canvas does not treat it so.
 */
fun artistMotionPlays(animated: Boolean, reducedMotion: Boolean, metered: Boolean?, overCellular: Boolean): Boolean =
    animated && !reducedMotion && (metered != true || overCellular)

/** SVG ink follows the palette; raster scans retain a light backing so their ink stays legible. */
@Composable
fun ArtistSignatureMark(signature: ArtistSignature?, name: String, modifier: Modifier = Modifier) {
    if (signature == null) return
    val ink = Color(LocalCosmoColors.current.palette.textPrimary)
    val caption = stringResource(Res.string.signed_by, name)
    Column(modifier.widthIn(max = 240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(caption, color = ink.copy(alpha = 0.7f), fontSize = 11.sp)
        val svg = signature.format == "svg"
        AsyncImage(signature.imageUrl, contentDescription = caption,
            modifier = Modifier.height(72.dp).widthIn(max = 220.dp)
                .then(if (svg) Modifier else Modifier.background(Color.White).padding(6.dp)),
            contentScale = ContentScale.Fit, colorFilter = if (svg) ColorFilter.tint(ink) else null)
    }
}
