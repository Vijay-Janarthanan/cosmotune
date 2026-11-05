package com.cosmotune.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberSystemAccentSeed(): Int? {
    val context = LocalContext.current
    // A new wallpaper changes the configuration's ui mode bits or recreates the activity; either way this is read again.
    val configuration = LocalConfiguration.current
    return remember(context, configuration.uiMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { context.getColor(android.R.color.system_accent1_500) }.getOrNull() else null
    }
}
