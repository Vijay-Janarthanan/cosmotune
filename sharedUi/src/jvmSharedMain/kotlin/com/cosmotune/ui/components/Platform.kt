package com.cosmotune.ui.components

import androidx.compose.runtime.Composable

/** The Android API level, or null on the desktop (where Skia blurs everything, so [com.cosmotune.design.GlassPolicy] lets glass blur). */
internal expect val platformSdkInt: Int?

/** True on a phone that Android itself calls low-RAM (`ActivityManager.isLowRamDevice`); always false on the desktop. */
@Composable
internal expect fun rememberLowRamDevice(): Boolean
