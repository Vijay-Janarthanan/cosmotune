package com.cosmotune.ui.states

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.cache.CachedLoad
import com.cosmotune.cache.CachedState
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook

/**
 * A page's [CachedLoad]: the content it holds at once (memory, or the disk after a restart), taken on the UI thread, and a
 * background refresh when the page appears and the content is not fresh. Coming back to the page within the freshness window
 * fetches nothing.
 */
@Composable
fun <T> CachedLoad<T>.collectOnPage(): State<CachedState<T>> {
    LaunchedEffect(this) { load() }
    return state.collectAsUiState()
}

/** The words of the note a page shows over cached content when its refresh failed. */
const val REFRESH_FAILED_TEXT = "Couldn't refresh"

/**
 * The small note over cached content whose refresh failed: "Couldn't refresh · Try again". The content stays; [onRetry] fetches
 * again. Tagged `cache:refresh-failed` for tests.
 */
@Composable
fun RefreshFailedNote(onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = orbitLook()
    val fonts = orbitFonts()
    Row(
        modifier
            .testTag("cache:refresh-failed")
            .clip(RoundedCornerShape(50))
            .background(look.chip)
            .let { if (onRetry != null) it.clickable(role = Role.Button, onClick = onRetry) else it }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(REFRESH_FAILED_TEXT, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim)
        if (onRetry != null) Text("· Try again", style = fonts.body.copy(fontSize = 12.5.sp), color = look.ink)
    }
}

/**
 * [content] (a page that keeps its cached content when a refresh fails) with the [RefreshFailedNote] over its top when [failed]:
 * the note sits [top] below the page's top edge (the page's own top padding), centred, and [onRetry] fetches again.
 */
@Composable
fun WithRefreshNote(failed: Boolean, onRetry: () -> Unit, top: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(Modifier) {
        content()
        if (failed) RefreshFailedNote(onRetry, Modifier.align(Alignment.TopCenter).padding(top = top + 8.dp))
    }
}
