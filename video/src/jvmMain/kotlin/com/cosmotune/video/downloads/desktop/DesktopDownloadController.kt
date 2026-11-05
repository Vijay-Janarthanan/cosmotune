package com.cosmotune.video.downloads.desktop

import com.cosmotune.CosmoConfig
import com.cosmotune.video.downloads.DeviceCaps
import com.cosmotune.video.downloads.DownloadContainer
import com.cosmotune.video.downloads.DownloadController
import com.cosmotune.video.downloads.DownloadKind
import com.cosmotune.video.downloads.DownloadPlatform
import com.cosmotune.video.downloads.DownloadRunner
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The download engine on the desktop (plan task Y-24, step 6): [DownloadRunner] with the desktop's platform. The video and
 * audio go into `<cache>/cosmotune/video-downloads/` (`$XDG_CACHE_HOME` or `~/.cache`), are joined by [FfmpegRemux] (both kept
 * when that fails; audio only is written as it is), and the result goes into the folder chosen in Settings
 * (`settings.downloadFolder`) or `~/Videos/Cosmotune/`, with the subtitles and the sidecar beside it. The list is
 * `~/.local/share/cosmotune/video/downloads.json` (`$XDG_DATA_HOME` first, spec 8.1). "Only on Wi-Fi" does not apply (no
 * metered signal: the network counts as unmetered) and there is no battery hold. [segments] (the graph's SponsorBlock client)
 * fills the sidecar's segments when online.
 */
class DesktopDownloadController(
    source: VideoSource,
    settings: VideoSettingsRepository,
    scope: CoroutineScope,
    segments: SegmentProvider? = null,
) : DownloadController by DownloadRunner(source, settings, scope, DesktopDownloadPlatform(), segments)

/** The desktop's side of the engine: folders under the user's home, FFmpeg, no holds, the JVM's language. */
internal class DesktopDownloadPlatform(
    private val home: String = System.getProperty("user.home"),
    private val environment: (String) -> String? = System::getenv,
) : DownloadPlatform {
    override val caps: DeviceCaps = DeviceCapsDesktop.of()

    override val cacheDir: File = File(folder("XDG_CACHE_HOME", ".cache"), "${CosmoConfig.STORAGE_NAME}/video-downloads")

    override val stateDir: File = File(folder("XDG_DATA_HOME", ".local/share"), "${CosmoConfig.STORAGE_NAME}/video")

    override fun outputDir(kind: DownloadKind, settings: VideoSettings): File =
        settings.downloadFolder?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, "Videos/Cosmotune")

    override suspend fun join(video: File, audio: File, output: File, container: DownloadContainer): Boolean =
        withContext(Dispatchers.IO) { FfmpegRemux.remux(video, audio, output, container) }

    override fun isMetered(): Boolean = false

    override fun isBatteryLow(): Boolean = false

    override val changes: Flow<Unit> = emptyFlow()

    override fun freeBytes(settings: VideoSettings): Long =
        generateSequence(outputDir(DownloadKind.VIDEO, settings)) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

    override val appLanguage: String get() = Locale.getDefault().language

    override fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.ifEmpty { tag }

    private fun folder(variable: String, fallback: String): String = environment(variable)?.takeIf { it.isNotBlank() } ?: "$home/$fallback"
}
