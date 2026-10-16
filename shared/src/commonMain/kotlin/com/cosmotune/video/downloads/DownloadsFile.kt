package com.cosmotune.video.downloads

import com.cosmotune.video.model.AudioCodec
import com.cosmotune.video.model.AudioStream
import com.cosmotune.video.model.AudioTrackKind
import com.cosmotune.video.model.ByteRange
import com.cosmotune.video.model.Delivery
import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.model.VideoStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `downloads.json` (spec 8.1: "the download entries and batches (state, files, quality, subtitles)", no cap; plan task Y-24):
 * the queue's snapshot with a `version` field, written to `downloads.json.tmp` and then renamed over `downloads.json`, so a
 * crash mid-write never leaves half a file. Running entries come back waiting ([DownloadQueueState.restored]). The file
 * operations go through [Io]: common code has no file system; the platforms give a real one (`FileDownloadsIo`).
 */
object DownloadsFile {
    /** The file's name in the app's Video folder (Android `filesDir/video/`, desktop `~/.local/share/cosmotune/video/`). */
    const val NAME = "downloads.json"

    /** Where a write goes before the rename. */
    const val TEMP_NAME = "$NAME.tmp"

    /** Where an unreadable file is set aside, so its entries can still be recovered by hand. */
    const val BAD_NAME = "$NAME.bad"

    /** The format's version; a newer file is not read. */
    const val VERSION = 1

    /** The few file operations the atomic write needs, by name inside one folder. */
    interface Io {
        /** The text of [name], or null when there is no such file. */
        fun read(name: String): String?

        /** Writes [text] to [name] completely (flushed to the device) before returning. */
        fun write(name: String, text: String)

        /** Renames [from] over [to], atomically where the file system can. */
        fun rename(from: String, to: String)
    }

    /** Writes [state] to [TEMP_NAME], then renames it over [NAME]. */
    fun save(io: Io, state: DownloadQueueState) {
        io.write(TEMP_NAME, encode(state))
        io.rename(TEMP_NAME, NAME)
    }

    /** The queue in [NAME], restored; an empty queue when there is none; an unreadable file is renamed to [BAD_NAME] first. */
    fun load(io: Io): DownloadQueueState {
        val text = io.read(NAME) ?: return DownloadQueueState()
        val state = decode(text)
        if (state == null) runCatching { io.rename(NAME, BAD_NAME) }
        return state ?: DownloadQueueState()
    }

    /** [state] as the file's JSON. */
    fun encode(state: DownloadQueueState): String = json.encodeToString(SnapshotDto.serializer(), SnapshotDto(
        version = VERSION,
        nextNumber = state.nextNumber,
        batches = state.batches.map { BatchDto(it.id, it.kind.name, it.title) },
        entries = state.entries.map { it.toDto() },
    ))

    /** The queue in [text], restored ([DownloadQueueState.restored]); null when the text is not this format or is newer. Unreadable entries are skipped. */
    fun decode(text: String): DownloadQueueState? {
        val dto = runCatching { json.decodeFromString(SnapshotDto.serializer(), text) }.getOrNull() ?: return null
        if (dto.version > VERSION) return null
        val batches = dto.batches.mapNotNull { b -> enumOrNull<BatchKind>(b.kind)?.let { DownloadBatchInfo(b.id, it, b.title) } }
        val entries = dto.entries.mapNotNull { e -> runCatching { e.toEntry(batches) }.getOrNull() }
        return DownloadQueueState(entries = entries, batches = batches, nextNumber = dto.nextNumber).restored()
    }

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private fun DownloadEntry.toDto() = EntryDto(
        id = id, item = item.toDto(), kind = kind.name, choice = choice.toDto(), request = request.toDto(), state = state.toDto(),
        batch = batch?.id, subtitleFiles = subtitleFiles, subtitleGot = subtitleGot, createdMs = createdMs,
    )

    private fun EntryDto.toEntry(batches: List<DownloadBatchInfo>) = DownloadEntry(
        id = id, item = item.toItem(), kind = enumOrNull<DownloadKind>(kind) ?: DownloadKind.VIDEO, choice = choice.toChoice(),
        request = request.toRequest(), state = state.toState(), batch = batch?.let { b -> batches.firstOrNull { it.id == b } },
        subtitleFiles = subtitleFiles, subtitleGot = subtitleGot, createdMs = createdMs,
    )

    private fun DownloadChoice.toDto() = ChoiceDto(video?.toDto(), audio.toDto(), container.name, estimatedBytes, label)

    private fun ChoiceDto.toChoice() = DownloadChoice(video?.toStream(), audio.toStream(), enumOrNull<DownloadContainer>(container) ?: DownloadContainer.MP4, estimatedBytes, label)

    private fun DownloadRequest.toDto(): RequestDto = when (val q = quality) {
        DownloadQuality.Auto -> RequestDto("auto", null, null, subtitles.enabled, subtitles.language)
        DownloadQuality.AudioOnly -> RequestDto("audio", null, null, subtitles.enabled, subtitles.language)
        is DownloadQuality.Rung -> RequestDto("rung", q.height, q.fps, subtitles.enabled, subtitles.language)
    }

    private fun RequestDto.toRequest(): DownloadRequest {
        val quality = when (quality) {
            "audio" -> DownloadQuality.AudioOnly
            "rung" -> DownloadQuality.Rung(height ?: 720, fps ?: 30)
            else -> DownloadQuality.Auto
        }
        return DownloadRequest(quality, SubtitlePick(subtitles, language))
    }

    private fun DownloadState.toDto(): StateDto = when (this) {
        DownloadState.Waiting -> StateDto("waiting")
        DownloadState.WaitingForWifi -> StateDto("wifi")
        is DownloadState.Paused -> StateDto(if (reason == PauseReason.USER) "paused" else "battery")
        is DownloadState.Running -> StateDto("running", fraction = fraction)
        DownloadState.Joining -> StateDto("joining")
        is DownloadState.Done -> StateDto("done", path = path, bytes = bytes)
        is DownloadState.Failed -> StateDto("failed", reason = reason)
    }

    private fun StateDto.toState(): DownloadState = when (type) {
        "wifi" -> DownloadState.WaitingForWifi
        "paused" -> DownloadState.Paused(PauseReason.USER)
        "battery" -> DownloadState.Paused(PauseReason.BATTERY_LOW)
        "running" -> DownloadState.Running(fraction ?: 0f)
        "joining" -> DownloadState.Joining
        "done" -> DownloadState.Done(path ?: error("a finished entry without a file"), bytes ?: 0L)
        "failed" -> DownloadState.Failed(reason.orEmpty())
        else -> DownloadState.Waiting
    }

    internal inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }
}

// ---- The JSON shapes (the models of :shared are not serializable; these mirror them field by field) ---------------------

@Serializable
internal data class SnapshotDto(val version: Int, val nextNumber: Long = 1, val batches: List<BatchDto> = emptyList(), val entries: List<EntryDto> = emptyList())

@Serializable
internal data class BatchDto(val id: String, val kind: String, val title: String)

@Serializable
internal data class EntryDto(
    val id: String,
    val item: ItemDto,
    val kind: String,
    val choice: ChoiceDto,
    val request: RequestDto,
    val state: StateDto,
    val batch: String? = null,
    val subtitleFiles: List<String> = emptyList(),
    val subtitleGot: String? = null,
    val createdMs: Long = 0,
)

@Serializable
internal data class ChoiceDto(val video: VideoStreamDto? = null, val audio: AudioStreamDto, val container: String, val estimatedBytes: Long, val label: String)

@Serializable
internal data class RequestDto(val quality: String, val height: Int? = null, val fps: Int? = null, val subtitles: Boolean = false, val language: String? = null)

@Serializable
internal data class StateDto(val type: String, val fraction: Float? = null, val reason: String? = null, val path: String? = null, val bytes: Long? = null)

@Serializable
internal data class ItemDto(
    val id: String,
    val title: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val channelAvatarUrl: String? = null,
    val thumbnailUrl: String? = null,
    val durationSec: Long? = null,
    val viewCount: Long? = null,
    val uploadedAtMs: Long? = null,
    val uploadedText: String? = null,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val isShort: Boolean = false,
)

@Serializable
internal data class RangeDto(val start: Long, val endInclusive: Long)

@Serializable
internal data class VideoStreamDto(
    val url: String, val itag: Int, val mimeType: String, val codec: String, val codecs: String, val width: Int, val height: Int,
    val fps: Int, val bitrate: Int, val hdr: Boolean = false, val contentLength: Long? = null, val init: RangeDto? = null,
    val index: RangeDto? = null, val delivery: String, val hasAudio: Boolean = false,
)

@Serializable
internal data class AudioStreamDto(
    val url: String, val itag: Int, val mimeType: String, val codec: String, val codecs: String, val bitrate: Int,
    val sampleRate: Int? = null, val channels: Int? = null, val contentLength: Long? = null, val init: RangeDto? = null,
    val index: RangeDto? = null, val delivery: String, val trackId: String? = null, val trackName: String? = null,
    val language: String? = null, val trackKind: String,
)

internal fun VideoItem.toDto() = ItemDto(id, title, channelId, channelName, channelAvatarUrl, thumbnailUrl, durationSec, viewCount, uploadedAtMs, uploadedText, isLive, isUpcoming, isShort)

internal fun ItemDto.toItem() = VideoItem(id, title, channelId, channelName, channelAvatarUrl, thumbnailUrl, durationSec, viewCount, uploadedAtMs, uploadedText, isLive, isUpcoming, isShort)

private fun ByteRange.toDto() = RangeDto(start, endInclusive)

private fun RangeDto.toRange() = ByteRange(start, endInclusive)

private fun VideoStream.toDto() = VideoStreamDto(url, itag, mimeType, codec.name, codecs, width, height, fps, bitrate, hdr, contentLength, init?.toDto(), index?.toDto(), delivery.name, hasAudio)

private fun VideoStreamDto.toStream() = VideoStream(
    url, itag, mimeType, DownloadsFile.enumOrNull<VideoCodec>(codec) ?: VideoCodec.OTHER, codecs, width, height, fps, bitrate, hdr, contentLength,
    init?.toRange(), index?.toRange(), DownloadsFile.enumOrNull<Delivery>(delivery) ?: Delivery.DASH, hasAudio,
)

private fun AudioStream.toDto() = AudioStreamDto(
    url, itag, mimeType, codec.name, codecs, bitrate, sampleRate, channels, contentLength, init?.toDto(), index?.toDto(), delivery.name,
    trackId, trackName, language, trackKind.name,
)

private fun AudioStreamDto.toStream() = AudioStream(
    url, itag, mimeType, DownloadsFile.enumOrNull<AudioCodec>(codec) ?: AudioCodec.OTHER, codecs, bitrate, sampleRate, channels, contentLength,
    init?.toRange(), index?.toRange(), DownloadsFile.enumOrNull<Delivery>(delivery) ?: Delivery.DASH, trackId, trackName, language,
    DownloadsFile.enumOrNull<AudioTrackKind>(trackKind) ?: AudioTrackKind.UNKNOWN,
)
