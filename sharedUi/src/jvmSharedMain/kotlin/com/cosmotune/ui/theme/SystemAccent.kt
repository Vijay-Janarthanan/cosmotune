package com.cosmotune.ui.theme

import androidx.compose.runtime.Composable

/**
 * The system's accent colour as an ARGB seed, or null where there is none: Android 12 and later (Material You's
 * `system_accent1_500`, taken from the wallpaper); null on older Android and on the desktop.
 */
@Composable
internal expect fun rememberSystemAccentSeed(): Int?
