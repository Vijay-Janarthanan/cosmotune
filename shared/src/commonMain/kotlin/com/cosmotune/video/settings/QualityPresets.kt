package com.cosmotune.video.settings

/**
 * Named quality presets (Settings, Playback, Quality preset): one tap sets the Wi-Fi and mobile-data qualities and the codec together.
 * The settings keep the three values; the preset shown is the one they match, or Custom.
 */
enum class QualityPreset(val wifi: Int?, val mobile: Int?, val codec: CodecPreference) {
    DATA_SAVER(480, 360, CodecPreference.AUTO),
    BALANCED(720, 480, CodecPreference.AUTO),
    SHARP(1080, 720, CodecPreference.AUTO),
    BEST(null, null, CodecPreference.AUTO);

    fun applyTo(settings: VideoSettings): VideoSettings = settings.copy(wifiQuality = wifi, meteredQuality = mobile, codec = codec)

    companion object {
        /** The preset [settings] match, or null (Custom). */
        fun of(settings: VideoSettings): QualityPreset? =
            entries.firstOrNull { it.wifi == settings.wifiQuality && it.mobile == settings.meteredQuality && it.codec == settings.codec }
    }
}
