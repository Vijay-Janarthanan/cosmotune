package com.cosmotune.video.downloads

import com.cosmotune.video.model.VideoItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The list of downloads as a file of its own (Import and export, Downloads): each video with the quality and subtitles asked for,
 * finished or not. Loading it on another device (or after the files were lost) downloads again every video that is not already
 * there or in the queue.
 */
object DownloadListFile {
    const val FORMAT = "cosmotune-video-downloads"

    @Serializable
    internal data class Doc(val format: String = FORMAT, val version: Int = 1, val downloads: List<Row> = emptyList())

    @Serializable
    internal data class Row(
        val id: String,
        val title: String = "",
        val channelId: String? = null,
        val channelName: String? = null,
        val thumbnailUrl: String? = null,
        val durationSec: Long? = null,
        val quality: String = "auto",
        val height: Int? = null,
        val fps: Int? = null,
        val subtitles: Boolean = false,
        val subtitleLanguage: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun write(entries: List<DownloadEntry>): String = json.encodeToString(
        Doc.serializer(),
        Doc(downloads = entries.distinctBy { it.item.id }.map { e ->
            val q = e.request.quality
            Row(
                id = e.item.id, title = e.item.title, channelId = e.item.channelId, channelName = e.item.channelName,
                thumbnailUrl = e.item.thumbnailUrl, durationSec = e.item.durationSec,
                quality = when (q) { DownloadQuality.Auto -> "auto"; DownloadQuality.AudioOnly -> "audio"; is DownloadQuality.Rung -> "rung" },
                height = (q as? DownloadQuality.Rung)?.height, fps = (q as? DownloadQuality.Rung)?.fps,
                subtitles = e.request.subtitles.enabled, subtitleLanguage = e.request.subtitles.language,
            )
        }),
    )

    /** The videos and what was asked for each, or null when [text] is no download list. */
    fun read(text: String): List<Pair<VideoItem, DownloadRequest>>? {
        if ("\"$FORMAT\"" !in text) return null
        val doc = runCatching { json.decodeFromString(Doc.serializer(), text) }.getOrNull() ?: return null
        if (doc.format != FORMAT) return null
        return doc.downloads.filter { it.id.length == 11 }.map { r ->
            val quality = when (r.quality) {
                "audio" -> DownloadQuality.AudioOnly
                "rung" -> if (r.height != null && r.height > 0) DownloadQuality.Rung(r.height, r.fps ?: 30) else DownloadQuality.Auto
                else -> DownloadQuality.Auto
            }
            VideoItem(r.id, r.title.ifBlank { r.id }, r.channelId, r.channelName, null, r.thumbnailUrl, r.durationSec, null, null, null) to
                DownloadRequest(quality, SubtitlePick(r.subtitles, r.subtitleLanguage))
        }
    }

    /** The videos of [list] to download here: those with no download yet among [existing] (a failed one is tried again instead). */
    fun missing(list: List<Pair<VideoItem, DownloadRequest>>, existing: List<DownloadEntry>): List<Pair<VideoItem, DownloadRequest>> {
        val have = existing.filter { it.state !is DownloadState.Failed }.mapTo(HashSet()) { it.item.id }
        return list.filter { it.first.id !in have }.distinctBy { it.first.id }
    }
}
