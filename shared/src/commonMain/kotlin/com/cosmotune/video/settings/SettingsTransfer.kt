package com.cosmotune.video.settings

import com.cosmotune.video.library.LibraryCodec

/**
 * Video's settings as a file of their own (Import and export, Settings): the same text as `settings.json`. Loading one keeps what
 * belongs to this device: its download folder, whether the first-run notice was seen, and the marks of the background work.
 */
object SettingsTransfer {
    fun write(settings: VideoSettings): String = LibraryCodec.encodeSettings(settings)

    /** The settings in [text], or null when it is no settings file (a library export, anything else). */
    fun read(text: String): VideoSettings? {
        if (!text.trimStart().startsWith("{") || "\"subscriptions\"" in text || "\"version\"" !in text) return null
        return runCatching { LibraryCodec.decodeSettings(text) }.getOrNull()
    }

    fun apply(current: VideoSettings, loaded: VideoSettings): VideoSettings = loaded.copy(
        downloadFolder = current.downloadFolder,
        firstRunSeen = current.firstRunSeen,
        uploadAlertsSeenMs = current.uploadAlertsSeenMs,
        autoBackupLastMs = current.autoBackupLastMs,
    )
}
