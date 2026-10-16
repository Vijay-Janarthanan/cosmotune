package com.cosmotune.video.downloads

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.model.VideoStream
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.QualityOption

/**
 * What a device can play back from a download (plan task Y-24; spec 5.16: "each rung of the ladder the device can play"):
 * the tallest picture and highest frame rate it decodes, the codecs it decodes, the height Auto stops at ([autoCeilingHeight],
 * the desktop's 1440, spec 6.3; null = no ceiling below [maxHeight]) and whether its joiner writes WebM for VP9 ([joinsToWebm]:
 * the desktop's FFmpeg does, Android's Transformer writes MP4 only). Heights are the short side of the picture, so a vertical
 * 1080 x 1920 Short is "1080p".
 */
data class DeviceCaps(
    val maxHeight: Int,
    val maxFps: Int,
    val codecs: Set<VideoCodec>,
    val autoCeilingHeight: Int? = null,
    val joinsToWebm: Boolean = false,
) {
    companion object {
        /** The caps of a player engine (spec 6.3, 6.4: the decoders, Auto's ceiling); [joinsToWebm] as the platform's joiner writes. */
        fun from(engine: EngineCapabilities, joinsToWebm: Boolean): DeviceCaps = DeviceCaps(
            maxHeight = engine.video.maxOfOrNull { it.maxHeight } ?: 0,
            maxFps = engine.video.maxOfOrNull { it.maxFps } ?: 0,
            codecs = engine.video.map { it.codec }.toSet(),
            autoCeilingHeight = engine.autoCeilingHeight,
            joinsToWebm = joinsToWebm,
        )
    }
}

/**
 * A download engine that can copy a finished video into the shared Movies folder (spec 5.10: "Save a copy to Movies", Android
 * only). The Downloads page offers the row when `graph.downloads` is one; the desktop's controller is not.
 */
interface SavesCopyToMovies {
    /** Copies the finished download [id] into `Movies/Cosmotune` for other players; false when it cannot (not finished, audio only, a pair). */
    suspend fun saveCopyToMovies(id: String): Boolean
}

/**
 * The download planner (plan task Y-24, step 1; spec 5.16): what the sheet offers for one video or a batch, the concrete
 * streams of a request, the size estimates, the row labels and the file names. Pure: no file, no network.
 *
 * - The ladder: every rung (short side, frame rate) of the video's video-only streams the device decodes, top first; at each
 *   rung H.264 first (other players read it), then VP9, then AV1; HDR streams are left out (a download is also for other players).
 * - The audio follows the container: MP4 takes AAC, WebM (the desktop's VP9) takes Opus; the original track, not a dubbed one.
 * - Sizes: the streams' content lengths, else bitrate x duration (an estimate); a batch: the typical bitrates of [TYPICAL_KBPS].
 */
object DownloadPlanner {
    /** The rungs a batch is offered (spec 5.11's quality list without Auto), tallest first. */
    val LADDER: List<Int> = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)

    /** Typical total bitrates with audio, kbit/s, for a batch's "about" sizes (plan task Y-24). */
    val TYPICAL_KBPS: Map<Int, Int> = mapOf(144 to 120, 240 to 250, 360 to 500, 480 to 900, 720 to 2_000, 1080 to 3_800, 1440 to 9_000, 2160 to 18_000)

    /** Audio only in a batch, kbit/s. */
    const val AUDIO_ONLY_KBPS = 128

    /** An item without a duration (feed items: RSS has none, spec 8.3) counts as ten minutes. */
    const val UNKNOWN_DURATION_SEC = 600L

    /** The longest title a file name keeps. */
    const val MAX_TITLE_CHARS = 120

    /** The characters no file name may hold (Windows' list, which covers the others). */
    const val UNSAFE_CHARS = "\\/:*?\"<>|"

    /** The label of the audio-only row and choice (the sheet shows `video_dl_audio_only`). */
    const val AUDIO_ONLY_LABEL = "Audio only"

    /** The name of a batch's "each video's original language" row (tag ""; the sheet shows `video_dl_each_original`). */
    const val EACH_ORIGINAL_NAME = "Each video’s original language"

    private const val AUTO_GENERATED_SUFFIX = " (auto-generated)"

    /** Typical kbit/s of a rung of [height] (the nearest rung at or below it). */
    fun typicalKbps(height: Int): Int = TYPICAL_KBPS.entries.filter { it.key <= height }.maxByOrNull { it.key }?.value ?: TYPICAL_KBPS.getValue(144)

    // ---- One video ------------------------------------------------------------------------------------------------------

    /**
     * What the sheet offers for one video (spec 5.16): the rung Auto means, every rung the device plays with its size, audio only,
     * the subtitle languages (manual tracks first, auto-generated ones marked) and the original language. [languageName] names a
     * tag when a track has no name. Throws [IllegalArgumentException] when the video has no audio stream (live, upcoming).
     */
    fun options(details: VideoDetails, caps: DeviceCaps, languageName: (String) -> String = { it }): DownloadOptions {
        val rungs = rungStreams(details.streams, caps)
        val options = rungs.map { video ->
            val choice = videoChoice(details, video, caps)
            RungOption(
                quality = DownloadQuality.Rung(sideOf(video), video.fps), label = choice.label, codec = codecName(video.codec),
                estimatedBytes = choice.estimatedBytes, isEstimate = isEstimated(video, choice.audio),
            )
        }
        val audio = audioOnlyChoice(details)
        return DownloadOptions(
            autoMeans = autoRung(rungs, caps)?.let { labelOf(it) } ?: AUDIO_ONLY_LABEL,
            rungs = options,
            audioOnly = RungOption(DownloadQuality.AudioOnly, AUDIO_ONLY_LABEL, audioCodecName(audio.audio.codec), audio.estimatedBytes, audio.audio.contentLength == null),
            subtitleLanguages = subtitleLanguages(details, languageName),
            originalLanguage = originalLanguage(details),
        )
    }

    /** The subtitle row's default (spec 5.16): the app's language when the video has it, else the original language, else the first. */
    fun defaultSubtitle(options: DownloadOptions, appLanguage: String): SubtitleLanguage? {
        val languages = options.subtitleLanguages
        return languages.firstOrNull { SubtitleFiles.sameLanguage(it.tag, appLanguage) && !it.autoGenerated }
            ?: languages.firstOrNull { SubtitleFiles.sameLanguage(it.tag, appLanguage) }
            ?: options.originalLanguage?.let { original -> languages.firstOrNull { SubtitleFiles.sameLanguage(it.tag, original) } }
            ?: languages.firstOrNull()
    }

    /**
     * The concrete streams for [request] (spec 5.16): Auto is the highest rung within the caps (below Auto's ceiling); a rung this
     * video lacks takes the nearest lower one (the same height at a lower frame rate first), or the lowest when none is lower;
     * audio only is the best AAC as M4A, else Opus as it is. A video with no rung the device plays is downloaded as audio only.
     */
    fun choiceFor(request: DownloadRequest, details: VideoDetails, caps: DeviceCaps): DownloadChoice {
        val rungs = rungStreams(details.streams, caps)
        val video = when (val q = request.quality) {
            DownloadQuality.AudioOnly -> null
            DownloadQuality.Auto -> autoRung(rungs, caps)
            is DownloadQuality.Rung -> rungs.firstOrNull { compareRung(sideOf(it), it.fps, q.height, q.fps) <= 0 } ?: rungs.lastOrNull()
        } ?: return audioOnlyChoice(details)
        return videoChoice(details, video, caps)
    }

    /**
     * A stand-in choice for an entry whose streams are not known yet (a batch is queued without asking the source for every
     * item; the streams are resolved when the item starts): the rung asked for (Auto: the caps' top rung), a typical size, no
     * address. [isPlanned] tells it apart.
     */
    fun plannedChoice(item: VideoItem, request: DownloadRequest, caps: DeviceCaps): DownloadChoice {
        val seconds = item.durationSec?.takeIf { it > 0 } ?: UNKNOWN_DURATION_SEC
        val audio = AudioStream(
            url = "", itag = 0, mimeType = "audio/mp4", codec = AudioCodec.AAC, codecs = "", bitrate = AUDIO_ONLY_KBPS * 1000,
            sampleRate = null, channels = null, contentLength = null, init = null, index = null, delivery = Delivery.DASH,
            trackId = null, trackName = null, language = null, trackKind = AudioTrackKind.ORIGINAL,
        )
        val (height, fps) = when (val q = request.quality) {
            DownloadQuality.AudioOnly -> return DownloadChoice(null, audio, DownloadContainer.M4A, AUDIO_ONLY_KBPS * 125L * seconds, AUDIO_ONLY_LABEL)
            DownloadQuality.Auto -> topRung(caps, ceiling = true)
            is DownloadQuality.Rung -> q.height to q.fps
        }
        val video = VideoStream(
            url = "", itag = 0, mimeType = "video/mp4", codec = VideoCodec.H264, codecs = "", width = height * 16 / 9, height = height,
            fps = fps, bitrate = typicalKbps(height) * 1000, hdr = false, contentLength = null, init = null, index = null,
            delivery = Delivery.DASH, hasAudio = false,
        )
        return DownloadChoice(video, audio, DownloadContainer.MP4, typicalKbps(height) * 125L * seconds, QualityOption.labelOf(height, fps, hdr = false))
    }

    /** Whether [choice] is a [plannedChoice] (no stream address yet). */
    fun isPlanned(choice: DownloadChoice): Boolean = choice.audio.itag == 0 && choice.audio.url.isEmpty()

    // ---- A batch --------------------------------------------------------------------------------------------------------

    /**
     * What the sheet offers for a batch (spec 5.16): the ladder from the caps, "about" sizes from each item's duration (live and
     * upcoming items are left out: they are not downloaded) and [TYPICAL_KBPS]; audio only at [AUDIO_ONLY_KBPS]; the subtitle list
     * is the app's language and each video's original language (tag ""). No network.
     */
    fun batchOptions(items: List<VideoItem>, caps: DeviceCaps, appLanguage: String, appLanguageName: String): DownloadOptions {
        val seconds = items.filterNot { it.isLive || it.isUpcoming }.sumOf { it.durationSec?.takeIf { s -> s > 0 } ?: UNKNOWN_DURATION_SEC }
        val rungs = batchRungs(caps).map { (h, fps) ->
            RungOption(DownloadQuality.Rung(h, fps), QualityOption.labelOf(h, fps, hdr = false), codec = null, typicalKbps(h) * 125L * seconds, isEstimate = true)
        }
        val top = topRung(caps, ceiling = true)
        return DownloadOptions(
            autoMeans = QualityOption.labelOf(top.first, top.second, hdr = false),
            rungs = rungs,
            audioOnly = RungOption(DownloadQuality.AudioOnly, AUDIO_ONLY_LABEL, codec = null, AUDIO_ONLY_KBPS * 125L * seconds, isEstimate = true),
            subtitleLanguages = listOf(SubtitleLanguage(appLanguage, appLanguageName, false), SubtitleLanguage("", EACH_ORIGINAL_NAME, false)),
            originalLanguage = null,
        )
    }

    // ---- Names ----------------------------------------------------------------------------------------------------------

    /** What a download of [item] holds (spec 5.10's filter chips): audio for audio only, else a Short or a video. */
    fun kindOf(item: VideoItem, request: DownloadRequest): DownloadKind = when {
        request.quality == DownloadQuality.AudioOnly -> DownloadKind.AUDIO
        item.isShort -> DownloadKind.SHORT
        else -> DownloadKind.VIDEO
    }

    /**
     * The file of a finished download: [baseName] and the container's extension ("Night drive [fakevideo04].mp4"). [request] is
     * the one [choice] was made for (an audio-only choice has no video stream and an audio container).
     */
    fun fileName(item: VideoItem, request: DownloadRequest, choice: DownloadChoice): String {
        check(request.quality != DownloadQuality.AudioOnly || choice.video == null) { "an audio-only request has no video stream" }
        return "${baseName(item)}.${extensionOf(choice.container)}"
    }

    /** The name every file of a download starts with: "Short " for a Short, the [safeTitle], then " [<id>]". */
    fun baseName(item: VideoItem): String {
        val title = safeTitle(item.title).ifEmpty { "Video" }
        return (if (item.isShort) "Short " else "") + "$title [${item.id}]"
    }

    /** [title] made safe for every file system: no [UNSAFE_CHARS] or control characters, white space collapsed, no leading dot, at most [MAX_TITLE_CHARS]. */
    fun safeTitle(title: String): String {
        val cleaned = buildString {
            for (c in title) append(if (c in UNSAFE_CHARS || c.code < 0x20 || c.code == 0x7F) ' ' else c)
        }.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").trimStart('.', ' ')
        if (cleaned.length <= MAX_TITLE_CHARS) return cleaned
        var end = MAX_TITLE_CHARS
        if (cleaned[end - 1].isHighSurrogate()) end -= 1
        return cleaned.substring(0, end).trimEnd()
    }

    /** The extension of [container]: mp4, webm, m4a; Opus audio is the WebM stream as served, so webm. */
    fun extensionOf(container: DownloadContainer): String = when (container) {
        DownloadContainer.MP4 -> "mp4"
        DownloadContainer.WEBM -> "webm"
        DownloadContainer.M4A -> "m4a"
        DownloadContainer.OPUS -> "webm"
    }

    /** The two files kept when the join fails (spec 2.5, plan task Y-24): "<base>.video.<ext>" and "<base>.audio.<ext>". */
    fun pairNames(item: VideoItem, choice: DownloadChoice): Pair<String, String> {
        val base = baseName(item)
        return "$base.video.${videoExtension(choice.video)}" to "$base.audio.${audioExtension(choice.audio)}"
    }

    // ---- Labels ---------------------------------------------------------------------------------------------------------

    /** A row's text: "1080p · H.264 · 312 MB" ("about 312 MB" when estimated from durations). */
    fun rowLabel(option: RungOption): String =
        listOfNotNull(option.label, option.codec, (if (option.isEstimate) "about " else "") + sizeText(option.estimatedBytes)).joinToString(" · ")

    /** A size as the sheet and the storage line write it: "820 KB", "312 MB", "1.4 GB", "21 GB" (decimal units). */
    fun sizeText(bytes: Long): String {
        if (bytes < 1_000_000) return "${(bytes.coerceAtLeast(0) + 999) / 1_000} KB"
        val mb = (bytes + 500_000) / 1_000_000
        if (mb < 1_000) return "$mb MB"
        val tenths = (bytes + 50_000_000) / 100_000_000
        return if (tenths < 100) "${tenths / 10}.${tenths % 10} GB" else "${(bytes + 500_000_000) / 1_000_000_000} GB"
    }

    /** A codec as the sheet names it: "H.264", "VP9", "AV1". */
    fun codecName(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "H.264"
        VideoCodec.VP9 -> "VP9"
        VideoCodec.AV1 -> "AV1"
        VideoCodec.OTHER -> "Other"
    }

    // ---- Streams --------------------------------------------------------------------------------------------------------

    /**
     * The video's original language: the language of its original audio track, else of its auto-generated subtitles (the source
     * transcribes the spoken language), else null.
     */
    fun originalLanguage(details: VideoDetails): String? =
        details.streams.audio.firstOrNull { it.trackKind == AudioTrackKind.ORIGINAL && !it.language.isNullOrBlank() }?.language
            ?: details.subtitles.firstOrNull { it.autoGenerated && it.language.isNotBlank() }?.language

    /** The stream of a fresh [streams] with the same itag as [video] (a partial file resumes only from the same stream). */
    fun matching(streams: StreamSet, video: VideoStream): VideoStream? = streams.videoOnly.firstOrNull { it.itag == video.itag }

    /** The stream of a fresh [streams] with the same itag and track as [audio]. */
    fun matching(streams: StreamSet, audio: AudioStream): AudioStream? = streams.audio.firstOrNull { it.itag == audio.itag && it.trackId == audio.trackId }

    /** Every rung's best stream, top first: one per (short side, frame rate), H.264 then VP9 then AV1, within the caps, no HDR. */
    private fun rungStreams(streams: StreamSet, caps: DeviceCaps): List<VideoStream> =
        streams.videoOnly
            .filter { !it.hdr && it.codec != VideoCodec.OTHER && it.codec in caps.codecs && sideOf(it) in 1..caps.maxHeight && it.fps <= caps.maxFps }
            .groupBy { sideOf(it) to it.fps }
            .map { (_, same) -> same.sortedWith(compareBy<VideoStream> { CODEC_ORDER.indexOf(it.codec) }.thenByDescending { it.bitrate }).first() }
            .sortedWith(compareByDescending<VideoStream> { sideOf(it) }.thenByDescending { it.fps })

    /** The rung Auto takes: the top one at or below the caps' ceiling, else the lowest. */
    private fun autoRung(rungs: List<VideoStream>, caps: DeviceCaps): VideoStream? {
        val ceiling = caps.autoCeilingHeight ?: return rungs.firstOrNull()
        return rungs.firstOrNull { sideOf(it) <= ceiling } ?: rungs.lastOrNull()
    }

    private fun videoChoice(details: VideoDetails, video: VideoStream, caps: DeviceCaps): DownloadChoice {
        val container = if (video.codec == VideoCodec.VP9 && caps.joinsToWebm) DownloadContainer.WEBM else DownloadContainer.MP4
        val audio = bestAudio(details.streams, if (container == DownloadContainer.WEBM) AudioCodec.OPUS else AudioCodec.AAC)
            ?: throw IllegalArgumentException("The video ${details.item.id} has no audio stream")
        val ms = durationMs(details)
        return DownloadChoice(video, audio, container, sizeOf(video.contentLength, video.bitrate, ms) + sizeOf(audio.contentLength, audio.bitrate, ms), labelOf(video))
    }

    private fun audioOnlyChoice(details: VideoDetails): DownloadChoice {
        val audio = bestAudio(details.streams, AudioCodec.AAC) ?: throw IllegalArgumentException("The video ${details.item.id} has no audio stream")
        val container = if (audio.codec == AudioCodec.AAC) DownloadContainer.M4A else DownloadContainer.OPUS
        return DownloadChoice(null, audio, container, sizeOf(audio.contentLength, audio.bitrate, durationMs(details)), AUDIO_ONLY_LABEL)
    }

    /** The original track (or every track when none is marked original), [prefer]red codec first, highest bitrate first. */
    private fun bestAudio(streams: StreamSet, prefer: AudioCodec): AudioStream? {
        val usable = streams.audio.filter { it.codec != AudioCodec.OTHER && it.url.isNotEmpty() }
        val original = usable.filter { it.trackKind == AudioTrackKind.ORIGINAL }.ifEmpty {
            usable.filter { it.trackKind != AudioTrackKind.DUBBED && it.trackKind != AudioTrackKind.DESCRIPTIVE }.ifEmpty { usable }
        }
        return original.filter { it.codec == prefer }.maxByOrNull { it.bitrate } ?: original.maxByOrNull { it.bitrate }
    }

    private fun subtitleLanguages(details: VideoDetails, languageName: (String) -> String): List<SubtitleLanguage> =
        (details.subtitles.filterNot { it.autoGenerated } + details.subtitles.filter { it.autoGenerated })
            .map { track ->
                val name = track.name.removeSuffix(AUTO_GENERATED_SUFFIX).trim().ifEmpty { languageName(track.language) }
                SubtitleLanguage(track.language, name, track.autoGenerated)
            }
            .distinct()

    private fun durationMs(details: VideoDetails): Long =
        details.streams.durationMs?.takeIf { it > 0 } ?: details.item.durationSec?.takeIf { it > 0 }?.times(1000) ?: UNKNOWN_DURATION_SEC * 1000

    private fun sizeOf(contentLength: Long?, bitrate: Int, durationMs: Long): Long = contentLength ?: (bitrate.toLong() * durationMs / 8_000)

    private fun isEstimated(video: VideoStream, audio: AudioStream) = video.contentLength == null || audio.contentLength == null

    /** The rung's height: the short side of the picture (a vertical Short is named like a landscape video). */
    private fun sideOf(stream: VideoStream): Int = if (stream.width > 0 && stream.height > 0) minOf(stream.width, stream.height) else stream.height

    private fun labelOf(video: VideoStream) = QualityOption.labelOf(sideOf(video), video.fps, hdr = false)

    /** Negative when rung (h1, f1) is below rung (h2, f2). */
    private fun compareRung(h1: Int, f1: Int, h2: Int, f2: Int): Int = if (h1 != h2) h1.compareTo(h2) else f1.compareTo(f2)

    /** The ladder a batch is offered: [LADDER] up to the caps; 60 fps from 720p up when the device plays it. */
    private fun batchRungs(caps: DeviceCaps): List<Pair<Int, Int>> =
        LADDER.filter { it <= caps.maxHeight }.map { h -> h to (if (h >= 720 && caps.maxFps >= 60) 60 else 30) }

    /** The caps' top rung ([ceiling]: below Auto's ceiling), as height and frame rate. */
    private fun topRung(caps: DeviceCaps, ceiling: Boolean): Pair<Int, Int> {
        val limit = if (ceiling) minOf(caps.maxHeight, caps.autoCeilingHeight ?: caps.maxHeight) else caps.maxHeight
        return batchRungs(caps.copy(maxHeight = limit)).firstOrNull() ?: (LADDER.last() to 30)
    }

    private fun audioCodecName(codec: AudioCodec): String = when (codec) {
        AudioCodec.AAC -> "AAC"
        AudioCodec.OPUS -> "Opus"
        AudioCodec.OTHER -> "Other"
    }

    internal fun videoExtension(video: VideoStream?): String = if (video?.mimeType?.contains("webm") == true) "webm" else "mp4"

    internal fun audioExtension(audio: AudioStream): String = if (audio.mimeType.contains("webm")) "webm" else "m4a"

    private val CODEC_ORDER = listOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.AV1)
}

/**
 * The files a finished download plays from (spec 5.10: "plays inside the app through `playLocal`"): the joined file, or the
 * pair kept when the join failed ("<base>.video.<ext>" and "<base>.audio.<ext>"); null until the entry is done. An audio-only
 * download is a single file too.
 */
fun DownloadEntry.localFiles(): MediaInput.LocalFiles? {
    val done = state as? DownloadState.Done ?: return null
    val pairVideo = "[${item.id}].video.${DownloadPlanner.videoExtension(choice.video)}"
    if (choice.video != null && done.path.endsWith(pairVideo)) {
        val audio = done.path.removeSuffix(".video.${DownloadPlanner.videoExtension(choice.video)}") + ".audio.${DownloadPlanner.audioExtension(choice.audio)}"
        return MediaInput.LocalFiles(muxedPath = null, videoPath = done.path, audioPath = audio)
    }
    return MediaInput.LocalFiles(muxedPath = done.path, videoPath = null, audioPath = null)
}
