package com.cosmotune.video.ui.downloads

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion

/*
 * Part two's toast (`CosmoToast`: a pill of the solid glass at least 52 dp high, padding 8 8 8 18, 14.5 sp weight 500 in `textPrimary`,
 * 16 dp in from the sides and 8 dp above what is under it, rising 16 dp into place on the move spring) with the C text action at its
 * end, which `CosmoToast` does not take: "Downloading in 720p · Change", "Added to Downloads · View" (spec 5.16).
 */
private val TOAST_MIN_HEIGHT = 52.dp
private val TOAST_PADDING_START = 18.dp
private val TOAST_PADDING_OTHER = 8.dp
private val TOAST_RISE = 16.dp
private val TOAST_LIFT = 8.dp
private const val TOAST_SP = 14.5f
private const val TOAST_WEIGHT = 500
private val TOAST_SHAPE = RoundedCornerShape(percent = 50)

/**
 * A toast of the download host with its action (spec 5.16): the [message] and the text button [action] ("Change", "View"), which runs
 * [onAction]. Read out politely as soon as it appears; its life (4 s, the next toast replacing it) is the action host's.
 */
@Composable
internal fun DownloadToast(message: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val style = remember(type, message) {
        type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(TOAST_WEIGHT), fontSize = TOAST_SP.sp, lineHeight = TOAST_SP.sp * 1.3f), message)
    }
    // 0 while it waits below, 1 in place; read in the layer block, so the rise recomposes nothing.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, CosmoMotion.move(reduced)) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin)
            .padding(bottom = TOAST_LIFT)
            .graphicsLayer {
                val progress = arrival.value
                alpha = progress.coerceIn(0f, 1f)
                translationY = if (reduced) 0f else (1f - progress) * TOAST_RISE.toPx()
            }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .cosmoGlass(hazeState = null, shape = TOAST_SHAPE)
            .heightIn(min = TOAST_MIN_HEIGHT)
            .padding(start = TOAST_PADDING_START, top = TOAST_PADDING_OTHER, end = TOAST_PADDING_OTHER, bottom = TOAST_PADDING_OTHER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = message, style = style, color = Color(palette.textPrimary), modifier = Modifier.weight(1f))
        CosmoButton(text = action, onClick = onAction, kind = CosmoButtonKind.TEXT, size = CosmoButtonSize.SMALL, floating = true)
    }
}
