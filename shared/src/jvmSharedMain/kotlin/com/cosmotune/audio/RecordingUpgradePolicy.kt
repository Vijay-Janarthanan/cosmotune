package com.cosmotune.audio

/** Decoder durations are mandatory evidence before replacing audible audio, independently of tier. */
object RecordingUpgradePolicy {
    fun compatibleDuration(playingMs: Long?, candidateMs: Long?, slackMs: Long = 3_000): Boolean =
        playingMs != null && candidateMs != null && playingMs > 0 && candidateMs > 0 && slackMs >= 0 &&
            kotlin.math.abs(playingMs - candidateMs) <= slackMs
}
