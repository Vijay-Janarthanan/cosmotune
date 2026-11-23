package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** The font family of an imported font file, or null when it cannot be read. */
internal expect fun subtitleFontFamily(path: String): FontFamily?

/** Opens the device's file picker for a font file and hands its name and bytes (at most 20 MB) to [onPicked]. */
@Composable internal expect fun rememberFontFilePicker(onPicked: (String, ByteArray) -> Unit): () -> Unit
