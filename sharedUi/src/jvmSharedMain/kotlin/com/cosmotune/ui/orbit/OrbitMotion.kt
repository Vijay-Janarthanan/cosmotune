package com.cosmotune.ui.orbit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.ui.player.CanvasArtworkPlayer
import com.cosmotune.music.ui.player.CanvasContentMode

/**
 * A big disc that can move: the still cover ([still], and [overStill] over it once a better picture is known), and, when [clip] is
 * given and [plays] (the caller's reading of the animated-artwork switch, reduced motion and mobile data), the silent loop inside the
 * same circle. The still stays under the loop until its first frame and whenever the clip fails, so the disc is never empty.
 * [onMoving] says whether the loop is on screen now.
 */
@Composable
internal fun OrbitMotionDisc(
    still: String?,
    size: Dp,
    clip: CanvasArtwork?,
    plays: Boolean,
    modifier: Modifier = Modifier,
    overStill: String? = null,
    onMoving: (Boolean) -> Unit = {},
) {
    val moving by rememberUpdatedState(onMoving)
    var rendered by remember(clip) { mutableStateOf(false) }
    val showing = clip != null && plays
    LaunchedEffect(showing, rendered) { moving(showing && rendered) }
    Box(modifier.size(size).clip(CircleShape)) {
        Disc(still, size)
        overStill?.takeIf { it.isNotBlank() && it != still }?.let { hero ->
            AsyncImage(hero, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (showing && clip != null) {
            CanvasArtworkPlayer(
                clip, isPlaying = true, modifier = Modifier.fillMaxSize(), contentMode = CanvasContentMode.CROP,
                onRenderedChanged = { rendered = it }, presentationAlpha = { if (rendered) 1f else 0f },
            )
        }
    }
}
