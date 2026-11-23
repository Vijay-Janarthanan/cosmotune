package com.cosmotune.video.ui.search

import androidx.compose.runtime.Composable

/** Asks the device's speech service for words; null where there is none (the desktop, a phone without one). */
fun interface VoiceInput {
    /** Listens once; [onWords] hears what was said (never empty). */
    fun listen(onWords: (String) -> Unit)
}

@Composable
internal expect fun rememberVoiceInput(): VoiceInput?
