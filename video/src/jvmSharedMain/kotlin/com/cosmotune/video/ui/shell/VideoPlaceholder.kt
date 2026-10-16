package com.cosmotune.video.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * What a route shows until the task that owns its page replaces its branch in [VideoNavHost]: the empty state that says the page
 * is not built yet. [route] is the route being drawn (the page that takes its place gets it too).
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun VideoPlaceholder(route: VideoRoute) {
    Box(Modifier.fillMaxSize()) {
        CosmoEmptyState(message = stringResource(Res.string.video_placeholder))
    }
}
