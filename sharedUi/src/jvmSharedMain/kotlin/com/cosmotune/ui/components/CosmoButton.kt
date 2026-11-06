package com.cosmotune.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.design.ControlMetrics
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/** PRIMARY is the one peach action, TONAL and QUIET the quiet chip, TEXT the words alone. */
enum class CosmoButtonKind { PRIMARY, TONAL, QUIET, TEXT }

/** 40, 48 and 56 dp tall (`ControlMetrics.BUTTON_*`). */
enum class CosmoButtonSize(val heightDp: Float) {
    SMALL(ControlMetrics.BUTTON_SMALL_DP),
    MEDIUM(ControlMetrics.BUTTON_MEDIUM_DP),
    LARGE(ControlMetrics.BUTTON_LARGE_DP),
}

/**
 * A button in Orbit: the pill a dialog's actions are ([OrbitPillButton]), [size] tall. PRIMARY is the one peach action of a page, TONAL
 * and QUIET the quiet chip (the ink at 8 %), TEXT the words alone in the ink on no fill. Disabled at 40 %. [floating] is kept for its
 * callers: Orbit has no glass, so a button over a layer is the same button.
 *
 * A SMALL button is 40 dp tall but reserves a 48 dp touch target (`minimumInteractiveComponentSize`). The label is also the
 * content description. The colours follow the page's Orbit look.
 */
@Composable
fun CosmoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: CosmoButtonKind = CosmoButtonKind.PRIMARY,
    size: CosmoButtonSize = CosmoButtonSize.MEDIUM,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    floating: Boolean = false,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val haptics = rememberHaptics()
    val tap = { haptics.play(Haptic.Tap); onClick() }
    val height = size.heightDp.dp
    if (kind == CosmoButtonKind.TEXT) {
        // The words alone: no fill, the ink, the pill's shape for the press.
        val ink = if (enabled) look.ink else look.ink.copy(alpha = 0.4f)
        Row(
            modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = height)
                .clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = tap)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = fonts.chip.copy(fontWeight = FontWeight(800)), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        return
    }
    OrbitPillButton(
        label = text,
        onClick = tap,
        fonts = fonts,
        look = look,
        modifier = modifier.minimumInteractiveComponentSize(),
        primary = kind == CosmoButtonKind.PRIMARY,
        enabled = enabled,
        icon = icon,
        height = height,
    )
}
