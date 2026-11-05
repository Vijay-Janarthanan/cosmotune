package com.cosmotune.music.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Ink shared by player labels and direction-aware lyric highlights. */
internal val LocalPlayerTint = staticCompositionLocalOf { Color.White }
