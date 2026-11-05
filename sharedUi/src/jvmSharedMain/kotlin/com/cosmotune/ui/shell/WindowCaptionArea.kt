package com.cosmotune.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Desktop supplies native window dragging behind header controls. Phones keep ordinary layout. */
val LocalWindowCaptionArea = staticCompositionLocalOf<@Composable (Modifier, @Composable () -> Unit) -> Unit> {
    { modifier, content -> Box(modifier) { content() } }
}

@Composable
fun WindowCaptionArea(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    LocalWindowCaptionArea.current(modifier, content)
}
