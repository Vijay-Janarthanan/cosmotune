package com.cosmotune.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/**
 * The setting a search result pointed at: the page scrolls it into view and lights it up for a moment. [flash] again for the same
 * row lights it again.
 */
@Stable
class SettingsHighlight {
    var target by mutableStateOf<String?>(null)
        private set
    var pulse by mutableIntStateOf(0)
        private set

    fun flash(id: String) {
        target = id
        pulse++
    }

    fun done(id: String) {
        if (target == id) target = null
    }
}

/** The highlight of the settings page on screen, for rows that find it themselves (the desktop's, by their title). */
val LocalSettingsHighlight = compositionLocalOf<SettingsHighlight?> { null }

/** How long the page is given to settle (a new page composed, a list scrolled) before the row is brought into view. */
private const val SETTLE_MS = 260L
private const val HOLD_MS = 1_100L
private const val HIGHLIGHT_ALPHA = 0.22f

/**
 * Brings the row [id] into view and washes it with the theme's primary colour while [state] points at it, then lets the wash
 * fade. A row with no id, or a page with no highlight, is left as it is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.settingsHighlight(id: String?, state: SettingsHighlight? = LocalSettingsHighlight.current): Modifier {
    if (state == null || id == null) return this
    val active = state.target == id
    val pulse = state.pulse
    val requester = remember { BringIntoViewRequester() }
    val glow = remember { Animatable(0f) }
    val reduced = LocalReducedMotion.current
    val color = MaterialTheme.colorScheme.primary
    LaunchedEffect(active, pulse) {
        if (!active) {
            glow.snapTo(0f)
            return@LaunchedEffect
        }
        delay(SETTLE_MS)
        requester.bringIntoView()
        if (reduced) glow.snapTo(1f) else glow.animateTo(1f, tween(240))
        delay(HOLD_MS)
        if (reduced) glow.snapTo(0f) else glow.animateTo(0f, tween(900))
        state.done(id)
    }
    return this
        .bringIntoViewRequester(requester)
        .drawBehind {
            val alpha = glow.value
            if (alpha > 0.001f) drawRoundRect(color.copy(alpha = HIGHLIGHT_ALPHA * alpha), cornerRadius = CornerRadius(16.dp.toPx()))
        }
}
