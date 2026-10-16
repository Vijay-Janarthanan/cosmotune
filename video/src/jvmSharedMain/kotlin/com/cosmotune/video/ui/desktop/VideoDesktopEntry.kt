package com.cosmotune.video.ui.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_placeholder
import com.cosmotune.video.ui.VideoHostLinks
import org.jetbrains.compose.resources.stringResource

/*
 * The desktop's entry points into Video mode (spec section 5.1): the page that stands in the window's content area, the
 * sidebar's Video part, the profile drawer's list and the Back key. The desktop host (`com.cosmotune.video.host`, in
 * `desktopApp`) calls these four and nothing else of `:video`.
 *
 * Stubs until the shell (Y-14), which replaces the bodies and keeps every signature.
 */

/** Video's page in the content area: [contentPadding] is what the desktop frame leaves clear for its bars. Stub: one empty state. */
@Composable
fun VideoDesktopPage(contentPadding: PaddingValues, host: VideoHostLinks) {
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        CosmoEmptyState(message = stringResource(Res.string.video_placeholder))
    }
}

/**
 * The sidebar's Video section. [focusSearch] is true when the search shortcut asked for the search field; [onSearchFocused]
 * tells the host the request was served. Stub: nothing.
 */
@Composable
fun VideoDesktopSidebar(focusSearch: Boolean, onSearchFocused: () -> Unit) {
    Column {}
}

/** The rows the profile drawer lists below the modes while the mode is Video. Stub: none. */
@Composable
fun videoDesktopDrawerEntries(): List<CosmoDrawerEntry> = emptyList()

/** The desktop's Back (mouse button 4, Alt and Left) inside Video: [canGoBack] is whether there is a page to go back to. Stub: no. */
object VideoDesktopBack {
    val canGoBack: Boolean get() = false

    fun goBack() {}
}
