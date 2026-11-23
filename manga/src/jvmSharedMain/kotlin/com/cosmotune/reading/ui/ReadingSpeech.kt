package com.cosmotune.reading.ui

import androidx.compose.runtime.Composable

/** A voice the device can read with: its id for [ReadingSpeech.speak], a name to show and its language. */
data class ReadingVoiceInfo(val id: String, val name: String, val language: String)

/** The device's own text-to-speech (Android's engine, the desktop's system voices). Nothing is sent anywhere. */
internal interface ReadingSpeech {
    suspend fun voices(): List<ReadingVoiceInfo>
    /** Speaks [text] with [voice] (blank: the default) at [rate] (1 is normal) and returns when it is done; cancelling stops it. */
    suspend fun speak(text: String, voice: String, rate: Float)
    fun stop()
    fun close()
}

/** This device's speech, or null when it has none. */
@Composable internal expect fun rememberReadingSpeech(): ReadingSpeech?
