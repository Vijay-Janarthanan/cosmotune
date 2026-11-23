package com.cosmotune.video.ui.search

import androidx.compose.runtime.Composable

/** The desktop has no speech service Cosmotune can use without an account. */
@Composable
internal actual fun rememberVoiceInput(): VoiceInput? = null
