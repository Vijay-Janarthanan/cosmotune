package com.cosmotune.video.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * Video mode's whole screen on the phone (spec section 5.1), drawn by the app's `PhoneModeHost` in place of Music's root while
 * the mode is Video. [windowWidth] and [windowHeight] are the window's, so the shell can pick its layout without measuring;
 * [host] is the way back to Music and the links with it.
 *
 * Stub until the shell (Y-14): one empty state that says the page is not built yet.
 */
@Composable
fun CosmoVideoApp(windowWidth: Dp, windowHeight: Dp, host: VideoHostLinks, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        CosmoEmptyState(message = stringResource(Res.string.video_placeholder))
    }
}
