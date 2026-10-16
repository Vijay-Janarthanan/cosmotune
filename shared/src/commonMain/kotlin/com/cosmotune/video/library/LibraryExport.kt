package com.cosmotune.video.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** The shape of `cosmotune-video-<date>.json` (spec 8.1): the library's lists, in the elements the files use. */
@Serializable
internal data class ExportDocument(
    val format: String = "",
    val version: Int = LibraryCodec.VERSION,
    val exportedAt: Long = 0,
    val subscriptions: List<SubscriptionDto> = emptyList(),
    val history: List<HistoryEntryDto> = emptyList(),
    val watchLater: List<VideoItemDto> = emptyList(),
    val liked: List<VideoItemDto> = emptyList(),
    val playlists: List<LocalPlaylistDto> = emptyList(),
    val savedPlaylists: List<PlaylistItemDto> = emptyList(),
    val searches: List<String> = emptyList(),
)

/**
 * Cosmotune's own export file (plan task Y-7, step 3; spec 8.1): `{ "format": "cosmotune-video", "version": 1, "exportedAt",
 * "subscriptions", "history", "watchLater", "liked", "playlists", "savedPlaylists", "searches" }`. It holds the library but not
 * the settings, whether history is paused, or the caches. [exportedAt] is the time in milliseconds since 1970. The store's
 * `exportJson()` writes it; [parse] reads it back as a [LibraryImport] for `importDocument` (the settings screen reads the file
 * the person picked, parses it, shows [ImportReport]-style numbers, and merges).
 */
object LibraryExport {
    /** The `format` of the file. */
    const val FORMAT = "cosmotune-video"

    /** The version the file is written at. */
    const val VERSION = 1

    /** The document of [library] as of [exportedAtMs]. */
    fun encode(library: LibraryImport, exportedAtMs: Long): String = LibraryCodec.json.encodeToString(
        ExportDocument.serializer(),
        ExportDocument(
            format = FORMAT,
            version = VERSION,
            exportedAt = exportedAtMs,
            subscriptions = library.subscriptions.map { it.toDto() },
            history = library.history.map { it.toDto() },
            watchLater = library.watchLater.map { it.toDto() },
            liked = library.liked.map { it.toDto() },
            playlists = library.playlists.map { it.toDto() },
            savedPlaylists = library.savedPlaylists.map { it.toDto() },
            searches = library.searches,
        ),
    )

    /**
     * Reads an export file. Throws [LibraryFormatException] when [text] is not JSON, is not a Cosmotune Video export (another
     * `format`), or is of a newer version than this app reads. Records without an id are dropped.
     */
    fun parse(text: String): LibraryImport {
        val root = LibraryCodec.parseObject(text, "export")
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) throw LibraryFormatException("this is not a Cosmotune Video export file")
        val version = LibraryCodec.versionOf(root, "export")
        return try {
            when (version) {
                1 -> LibraryCodec.json.decodeFromJsonElement(ExportDocument.serializer(), root).let { d ->
                    LibraryImport(
                        subscriptions = d.subscriptions.mapNotNull { it.toDomain() },
                        history = d.history.mapNotNull { it.toDomain() },
                        playlists = d.playlists.mapNotNull { it.toDomain() },
                        savedPlaylists = d.savedPlaylists.mapNotNull { it.toDomain() },
                        watchLater = d.watchLater.mapNotNull { it.toDomain() },
                        liked = d.liked.mapNotNull { it.toDomain() },
                        searches = d.searches,
                    )
                }
                else -> throw LibraryCodec.unsupported(version)
            }
        } catch (e: LibraryFormatException) {
            throw e
        } catch (e: Exception) {
            throw LibraryFormatException("the export file does not have the expected shape: ${e.message}", e)
        }
    }

    /** [parse], or null when the file cannot be read. */
    fun parseOrNull(text: String): LibraryImport? = try {
        parse(text)
    } catch (e: LibraryFormatException) {
        null
    }
}
