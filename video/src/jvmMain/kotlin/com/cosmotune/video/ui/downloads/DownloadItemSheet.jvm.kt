package com.cosmotune.video.ui.downloads

import androidx.compose.runtime.Composable

/** The desktop keeps its downloads in a folder of its own (`~/Videos/Cosmotune/`): there is no shared Movies folder to copy to. */
@Composable
internal actual fun rememberSaveCopy(): SaveCopy? = null
