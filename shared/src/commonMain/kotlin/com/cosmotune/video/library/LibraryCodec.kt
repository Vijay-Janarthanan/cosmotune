@file:OptIn(ExperimentalSerializationApi::class)

package com.cosmotune.video.library

import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.settings.CodecPreference
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.TitleCase
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothPreset
import com.cosmotune.video.settings.SmoothnessSettings
import com.cosmotune.video.settings.VideoSettings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The files of spec 8.1 that this task keeps (the caches `feed-cache.json` and `downloads.json` belong to other tasks). */
internal enum class LibraryFile(val fileName: String) {
    SUBSCRIPTIONS("subscriptions.json"),
    HISTORY("history.json"),
    WATCH_LATER("watch-later.json"),
    LIKED("liked.json"),
    PLAYLISTS("playlists.json"),
    SAVED_PLAYLISTS("saved-playlists.json"),
    SEARCHES("searches.json"),
    SETTINGS("settings.json"),
}

/** A library file or export that cannot be read: not JSON, not the expected shape, not Cosmotune's, or from a newer version. */
class LibraryFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What `history.json` holds: the entries, most recent first, and whether history is paused (spec 5.8). */
data class HistoryDocument(val entries: List<HistoryEntry>, val paused: Boolean)

/**
 * The documents of spec 8.1, each `{ "version": 1, ... }` (plan task Y-7, step 2). Writing is always the current version;
 * reading goes through a reader for the version in the file (only version 1 exists: the next version adds a branch in
 * [readDocument]'s callers and keeps the old one). Unknown keys are ignored, so an older app reads what a newer one added
 * to version 1. A version newer than this app reads is refused ([LibraryFormatException]) and the store keeps that file aside
 * rather than overwrite it. Nulls are left out of the text.
 */
object LibraryCodec {
    /** The version every document is written at. */
    const val VERSION = 1

    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ------------------------------------------------------------------ the documents

    /** `subscriptions.json`. */
    fun encodeSubscriptions(list: List<Subscription>): String =
        json.encodeToString(SubscriptionsDocument.serializer(), SubscriptionsDocument(VERSION, list.map { it.toDto() }))

    /** Reads `subscriptions.json`; entries without a channel id are dropped. */
    fun decodeSubscriptions(text: String): List<Subscription> = readDocument(text, "subscriptions") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(SubscriptionsDocument.serializer(), root).subscriptions.mapNotNull { it.toDomain() }
            else -> throw unsupported(version)
        }
    }

    /** `history.json`. */
    fun encodeHistory(entries: List<HistoryEntry>, paused: Boolean): String =
        json.encodeToString(HistoryFileDocument.serializer(), HistoryFileDocument(VERSION, paused, entries.map { it.toDto() }))

    /** Reads `history.json`; entries without a video id are dropped. */
    fun decodeHistory(text: String): HistoryDocument = readDocument(text, "history") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(HistoryFileDocument.serializer(), root).let { d ->
                HistoryDocument(d.entries.mapNotNull { it.toDomain() }, d.paused)
            }
            else -> throw unsupported(version)
        }
    }

    /** `watch-later.json` and `liked.json`. */
    fun encodeItems(list: List<VideoItem>): String =
        json.encodeToString(ItemsDocument.serializer(), ItemsDocument(VERSION, list.map { it.toDto() }))

    /** Reads `watch-later.json` or `liked.json`; items without an id are dropped. */
    fun decodeItems(text: String): List<VideoItem> = readDocument(text, "item list") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(ItemsDocument.serializer(), root).items.mapNotNull { it.toDomain() }
            else -> throw unsupported(version)
        }
    }

    /** `playlists.json`. */
    fun encodePlaylists(list: List<LocalPlaylist>): String =
        json.encodeToString(PlaylistsDocument.serializer(), PlaylistsDocument(VERSION, list.map { it.toDto() }))

    /** Reads `playlists.json`; playlists without an id are dropped. */
    fun decodePlaylists(text: String): List<LocalPlaylist> = readDocument(text, "playlists") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(PlaylistsDocument.serializer(), root).playlists.mapNotNull { it.toDomain() }
            else -> throw unsupported(version)
        }
    }

    /** `saved-playlists.json`. */
    fun encodeSavedPlaylists(list: List<PlaylistItem>): String =
        json.encodeToString(SavedPlaylistsDocument.serializer(), SavedPlaylistsDocument(VERSION, list.map { it.toDto() }))

    /** Reads `saved-playlists.json`; playlists without an id are dropped. */
    fun decodeSavedPlaylists(text: String): List<PlaylistItem> = readDocument(text, "saved playlists") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(SavedPlaylistsDocument.serializer(), root).playlists.mapNotNull { it.toDomain() }
            else -> throw unsupported(version)
        }
    }

    /** `searches.json`. */
    fun encodeSearches(list: List<String>): String =
        json.encodeToString(SearchesDocument.serializer(), SearchesDocument(VERSION, list))

    /** Reads `searches.json`. */
    fun decodeSearches(text: String): List<String> = readDocument(text, "searches") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(SearchesDocument.serializer(), root).searches
            else -> throw unsupported(version)
        }
    }

    /** `settings.json`: every field of [VideoSettings]; the segment behaviours as a map of API name to behaviour name. */
    fun encodeSettings(settings: VideoSettings): String =
        json.encodeToString(SettingsDocument.serializer(), settings.toDocument())

    /**
     * Reads `settings.json`. A missing key is the default; an unknown category is ignored; an unknown behaviour, codec or
     * title-case name falls back to the default; a value that cannot be used (a speed of 0, a double-tap of 7 s) does too.
     */
    fun decodeSettings(text: String): VideoSettings = readDocument(text, "settings") { root, version ->
        when (version) {
            1 -> json.decodeFromJsonElement(SettingsDocument.serializer(), root).toSettings()
            else -> throw unsupported(version)
        }
    }

    // ------------------------------------------------------------------ reading

    /** Parses [text] as a JSON object and hands it, with its version, to [reader]; every failure is a [LibraryFormatException]. */
    internal fun <T> readDocument(text: String, what: String, reader: (JsonObject, Int) -> T): T {
        val root = parseObject(text, what)
        val version = versionOf(root, what)
        try {
            return reader(root, version)
        } catch (e: LibraryFormatException) {
            throw e
        } catch (e: Exception) {
            throw LibraryFormatException("the $what document does not have the expected shape: ${e.message}", e)
        }
    }

    internal fun parseObject(text: String, what: String): JsonObject {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw LibraryFormatException("the $what document is not JSON: ${e.message}", e)
        }
        return element as? JsonObject ?: throw LibraryFormatException("the $what document is not a JSON object")
    }

    /** The document's `version`; a document without one is version 1. */
    internal fun versionOf(root: JsonObject, what: String): Int {
        val element = root["version"] ?: return 1
        val primitive = element as? JsonPrimitive
        val version = if (primitive == null || primitive.isString) null else primitive.intOrNull
        if (version == null || version < 1) throw LibraryFormatException("the $what document has a version that is not a number from 1: $element")
        return version
    }

    internal fun unsupported(version: Int): LibraryFormatException =
        LibraryFormatException("version $version is newer than this app reads (the newest it reads is $VERSION)")
}

// ---------------------------------------------------------------------- the shapes of the files

@Serializable
internal data class VideoItemDto(
    val id: String = "",
    val title: String = "",
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

internal fun VideoItem.toDto() = VideoItemDto(
    id, title, channelId, channelName, channelAvatarUrl, thumbnailUrl, durationSec, viewCount, uploadedAtMs, uploadedText,
    isLive, isUpcoming, isShort,
)

internal fun VideoItemDto.toDomain(): VideoItem? =
    if (id.isBlank()) null
    else VideoItem(id, title, channelId, channelName, channelAvatarUrl, thumbnailUrl, durationSec, viewCount, uploadedAtMs, uploadedText, isLive, isUpcoming, isShort)

@Serializable
internal data class SubscriptionDto(
    val channelId: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val subscribedAtMs: Long = 0,
    val lastUploadMs: Long? = null,
)

internal fun Subscription.toDto() = SubscriptionDto(channelId, name, avatarUrl, subscribedAtMs, lastUploadMs)

internal fun SubscriptionDto.toDomain(): Subscription? =
    if (channelId.isBlank()) null else Subscription(channelId, name, avatarUrl, subscribedAtMs, lastUploadMs)

@Serializable
internal data class HistoryEntryDto(
    val item: VideoItemDto = VideoItemDto(),
    val lastWatchedMs: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val watchCount: Int = 1,
)

internal fun HistoryEntry.toDto() = HistoryEntryDto(item.toDto(), lastWatchedMs, positionMs, durationMs, watchCount)

internal fun HistoryEntryDto.toDomain(): HistoryEntry? =
    item.toDomain()?.let { HistoryEntry(it, lastWatchedMs, positionMs, durationMs, watchCount) }

@Serializable
internal data class LocalPlaylistDto(
    val id: String = "",
    val name: String = "",
    val items: List<VideoItemDto> = emptyList(),
    val createdMs: Long = 0,
    val updatedMs: Long = 0,
)

internal fun LocalPlaylist.toDto() = LocalPlaylistDto(id, name, items.map { it.toDto() }, createdMs, updatedMs)

internal fun LocalPlaylistDto.toDomain(): LocalPlaylist? =
    if (id.isBlank()) null else LocalPlaylist(id, name, items.mapNotNull { it.toDomain() }, createdMs, updatedMs)

@Serializable
internal data class PlaylistItemDto(
    val id: String = "",
    val title: String = "",
    val thumbnailUrl: String? = null,
    val ownerName: String? = null,
    val ownerId: String? = null,
    val videoCount: Long? = null,
)

internal fun PlaylistItem.toDto() = PlaylistItemDto(id, title, thumbnailUrl, ownerName, ownerId, videoCount)

internal fun PlaylistItemDto.toDomain(): PlaylistItem? =
    if (id.isBlank()) null else PlaylistItem(id, title, thumbnailUrl, ownerName, ownerId, videoCount)

@Serializable
internal data class SubscriptionsDocument(val version: Int = LibraryCodec.VERSION, val subscriptions: List<SubscriptionDto> = emptyList())

@Serializable
internal data class HistoryFileDocument(
    val version: Int = LibraryCodec.VERSION,
    val paused: Boolean = false,
    val entries: List<HistoryEntryDto> = emptyList(),
)

@Serializable
internal data class ItemsDocument(val version: Int = LibraryCodec.VERSION, val items: List<VideoItemDto> = emptyList())

@Serializable
internal data class PlaylistsDocument(val version: Int = LibraryCodec.VERSION, val playlists: List<LocalPlaylistDto> = emptyList())

@Serializable
internal data class SavedPlaylistsDocument(val version: Int = LibraryCodec.VERSION, val playlists: List<PlaylistItemDto> = emptyList())

@Serializable
internal data class SearchesDocument(val version: Int = LibraryCodec.VERSION, val searches: List<String> = emptyList())

// ---------------------------------------------------------------------- settings.json

/** The defaults of [VideoSettings], the one source for every missing key. */
internal val DEFAULTS = VideoSettings()

@Serializable
internal data class QualityDto(val kind: String = "auto", val height: Int = 0, val fps: Int = 0)

@Serializable
internal data class SubtitleDto(val enabled: Boolean = false, val language: String? = null)

@Serializable
internal data class DownloadPrefsDto(
    val askBeforeEach: Boolean = DEFAULTS.downloads.askBeforeEach,
    val quality: QualityDto = QualityDto(),
    val subtitles: SubtitleDto = SubtitleDto(),
)

/**
 * Every field of [VideoSettings], flat beside `version`; a missing key is the default. A nullable field must default to null here:
 * nulls are left out of the text, so an omitted key has to read back as null.
 */
@Serializable
internal data class SettingsDocument(
    val version: Int = LibraryCodec.VERSION,
    val wifiQuality: Int? = DEFAULTS.wifiQuality,
    val meteredQuality: Int? = DEFAULTS.meteredQuality,
    val codec: String = DEFAULTS.codec.name,
    val defaultSpeed: Float = DEFAULTS.defaultSpeed,
    val autoplay: Boolean = DEFAULTS.autoplay,
    val resume: Boolean = DEFAULTS.resume,
    val background: Boolean = DEFAULTS.background,
    val autoPip: Boolean = DEFAULTS.autoPip,
    val doubleTapSeconds: Int = DEFAULTS.doubleTapSeconds,
    val captionsLanguage: String? = DEFAULTS.captionsLanguage,
    val captionsScale: Float = DEFAULTS.captionsScale,
    val captionsBackground: Boolean = DEFAULTS.captionsBackground,
    val sponsorBlock: Boolean = DEFAULTS.sponsorBlock,
    val segmentBehaviours: Map<String, String> = emptyMap(),
    val segmentsOnBar: Boolean = DEFAULTS.segmentsOnBar,
    val countSkips: Boolean = DEFAULTS.countSkips,
    val deArrowTitles: Boolean = DEFAULTS.deArrowTitles,
    val deArrowThumbnails: Boolean = DEFAULTS.deArrowThumbnails,
    val titleCase: String = DEFAULTS.titleCase.name,
    val dislikes: Boolean = DEFAULTS.dislikes,
    val hideShorts: Boolean = DEFAULTS.hideShorts,
    val region: String? = DEFAULTS.region,
    val contentLanguage: String? = DEFAULTS.contentLanguage,
    val downloads: DownloadPrefsDto = DownloadPrefsDto(),
    val pauseOnLowBattery: Boolean = DEFAULTS.pauseOnLowBattery,
    val downloadWifiOnly: Boolean = DEFAULTS.downloadWifiOnly,
    val downloadFolder: String? = DEFAULTS.downloadFolder,
    val firstRunSeen: Boolean = DEFAULTS.firstRunSeen,
    val homeKiosk: String = DEFAULTS.homeKiosk,
    val smoothness: SmoothnessDto = SmoothnessDto(),
)

/** Frame smoothing inside `settings.json`; a file from before it has none of these keys and reads as the defaults (off). */
@Serializable
internal data class SmoothnessDto(
    val mode: String = DEFAULTS.smoothness.mode.name,
    val targetFps: Int = DEFAULTS.smoothness.targetFps,
    val strength: Int = DEFAULTS.smoothness.strength,
    val onlyBelow30: Boolean = DEFAULTS.smoothness.onlyBelow30,
    val preset: String = DEFAULTS.smoothness.preset.name,
    val matchDisplayRate: Boolean = DEFAULTS.smoothness.matchDisplayRate,
)

internal fun SmoothnessSettings.toDto() = SmoothnessDto(mode.name, targetFps, strength, onlyBelow30, preset.name, matchDisplayRate)

internal fun SmoothnessDto.toDomain() = SmoothnessSettings(
    mode = SmoothMode.entries.firstOrNull { it.name == mode } ?: DEFAULTS.smoothness.mode,
    targetFps = if (targetFps in SmoothnessSettings.TARGET_CHOICES) targetFps else DEFAULTS.smoothness.targetFps,
    strength = strength.coerceIn(0, 100),
    onlyBelow30 = onlyBelow30,
    preset = SmoothPreset.entries.firstOrNull { it.name == preset } ?: DEFAULTS.smoothness.preset,
    matchDisplayRate = matchDisplayRate,
)

internal fun VideoSettings.toDocument() = SettingsDocument(
    version = LibraryCodec.VERSION,
    wifiQuality = wifiQuality,
    meteredQuality = meteredQuality,
    codec = codec.name,
    defaultSpeed = defaultSpeed,
    autoplay = autoplay,
    resume = resume,
    background = background,
    autoPip = autoPip,
    doubleTapSeconds = doubleTapSeconds,
    captionsLanguage = captionsLanguage,
    captionsScale = captionsScale,
    captionsBackground = captionsBackground,
    sponsorBlock = sponsorBlock,
    segmentBehaviours = segmentBehaviours.entries.associate { (category, behaviour) -> category.apiName to behaviour.name },
    segmentsOnBar = segmentsOnBar,
    countSkips = countSkips,
    deArrowTitles = deArrowTitles,
    deArrowThumbnails = deArrowThumbnails,
    titleCase = titleCase.name,
    dislikes = dislikes,
    hideShorts = hideShorts,
    region = region,
    contentLanguage = contentLanguage,
    downloads = downloads.toDto(),
    pauseOnLowBattery = pauseOnLowBattery,
    downloadWifiOnly = downloadWifiOnly,
    downloadFolder = downloadFolder,
    firstRunSeen = firstRunSeen,
    homeKiosk = homeKiosk,
    smoothness = smoothness.toDto(),
)

internal fun SettingsDocument.toSettings(): VideoSettings {
    val behaviours = SegmentCategory.defaults().toMutableMap()
    for ((apiName, behaviourName) in segmentBehaviours) {
        val category = SegmentCategory.of(apiName) ?: continue
        val behaviour = SegmentBehaviour.entries.firstOrNull { it.name == behaviourName } ?: continue
        behaviours[category] = behaviour
    }
    return VideoSettings(
        wifiQuality = wifiQuality?.takeIf { it > 0 },
        meteredQuality = meteredQuality?.takeIf { it > 0 },
        codec = CodecPreference.entries.firstOrNull { it.name == codec } ?: DEFAULTS.codec,
        defaultSpeed = if (defaultSpeed.isFinite() && defaultSpeed in 0.25f..4f) defaultSpeed else DEFAULTS.defaultSpeed,
        autoplay = autoplay,
        resume = resume,
        background = background,
        autoPip = autoPip,
        doubleTapSeconds = if (doubleTapSeconds in VideoSettings.DOUBLE_TAP_CHOICES) doubleTapSeconds else DEFAULTS.doubleTapSeconds,
        captionsLanguage = captionsLanguage?.takeIf { it.isNotBlank() },
        captionsScale = if (captionsScale in VideoSettings.CAPTION_SCALES) captionsScale else DEFAULTS.captionsScale,
        captionsBackground = captionsBackground,
        sponsorBlock = sponsorBlock,
        segmentBehaviours = behaviours,
        segmentsOnBar = segmentsOnBar,
        countSkips = countSkips,
        deArrowTitles = deArrowTitles,
        deArrowThumbnails = deArrowThumbnails,
        titleCase = TitleCase.entries.firstOrNull { it.name == titleCase } ?: DEFAULTS.titleCase,
        dislikes = dislikes,
        hideShorts = hideShorts,
        region = region?.takeIf { it.isNotBlank() },
        contentLanguage = contentLanguage?.takeIf { it.isNotBlank() },
        downloads = downloads.toDomain(),
        pauseOnLowBattery = pauseOnLowBattery,
        downloadWifiOnly = downloadWifiOnly,
        downloadFolder = downloadFolder?.takeIf { it.isNotBlank() },
        firstRunSeen = firstRunSeen,
        homeKiosk = homeKiosk.ifBlank { DEFAULTS.homeKiosk },
        smoothness = smoothness.toDomain(),
    )
}

private fun DownloadPrefs.toDto() = DownloadPrefsDto(
    askBeforeEach = askBeforeEach,
    quality = when (val q = remembered.quality) {
        DownloadQuality.Auto -> QualityDto("auto")
        DownloadQuality.AudioOnly -> QualityDto("audio")
        is DownloadQuality.Rung -> QualityDto("rung", q.height, q.fps)
    },
    subtitles = SubtitleDto(remembered.subtitles.enabled, remembered.subtitles.language),
)

private fun DownloadPrefsDto.toDomain() = DownloadPrefs(
    askBeforeEach = askBeforeEach,
    remembered = DownloadRequest(
        quality = when (quality.kind) {
            "audio" -> DownloadQuality.AudioOnly
            "rung" -> if (quality.height > 0) DownloadQuality.Rung(quality.height, quality.fps.coerceAtLeast(0)) else DownloadQuality.Auto
            else -> DownloadQuality.Auto
        },
        // "" (each video's original language) and null (the app's language first) are different choices: kept as written (Y-34)
        subtitles = SubtitlePick(subtitles.enabled, subtitles.language),
    ),
)
