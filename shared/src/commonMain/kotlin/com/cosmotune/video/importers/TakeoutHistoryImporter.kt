package com.cosmotune.video.importers

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.VideoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull

/**
 * Watch history from Google Takeout's `watch-history.json` (spec 2.7, 8.2; the HTML form is not read in this phase).
 *
 * The file is an array of `{header, title ("Watched <title>", localised), titleUrl, subtitles [{name, url}] (the channel), time
 * (ISO-8601), products, activityControls, details}`, newest first, with no duration or position. An entry counts when:
 * - its `titleUrl` is a video address (removed videos have none),
 * - it has no `details` (that marks an ad),
 * - its `products`, when there are any, hold `YouTube` (an entry from YouTube Music's address is left to Music), and
 * - its `time` reads.
 * Everything else is skipped and counted.
 *
 * Mapping: the title with the localised "Watched " prefix removed; the channel from `subtitles[0]`; position 0 and duration
 * unknown (0), so no watched bar is drawn. A video that appears several times is one entry (the library keeps one entry per
 * video): its `lastWatchedMs` is the newest `time` and its `watchCount` the number of times it appears. The entries come out
 * newest first.
 */
object TakeoutHistoryImporter {
    /** A prefix inferred from fewer titles than this is not trusted; only the English "Watched " is removed then. */
    private const val MIN_TITLES_FOR_PREFIX = 5

    /** A common start longer than this is a shared series name, not the localised verb. */
    private const val MAX_PREFIX_LENGTH = 40

    private const val ENGLISH_PREFIX = "Watched "

    private class Watch(
        val videoId: String,
        val title: String,
        val channelName: String?,
        val channelId: String?,
        val timeMs: Long,
    )

    /** Reads the history JSON in [json]. */
    fun parse(json: String): ImportPreview {
        val root = try {
            Json.parseToJsonElement(json.removePrefix("\uFEFF"))
        } catch (e: Exception) {
            return ImportPreview.failed(ImportReason.UNREADABLE)
        }
        if (root !is JsonArray) return ImportPreview.failed(ImportReason.UNREADABLE)

        var skipped = 0
        val watches = ArrayList<Watch>()
        for (element in root) {
            val entry = element.asObject()
            val titleUrl = entry?.get("titleUrl").asText()
            val videoId = titleUrl?.let(ImportSupport::videoIdOf)
            val timeMs = entry?.get("time").asText()?.let(ImportSupport::parseTimeMs)
            val details = entry?.get("details")
            val isAd = details != null && details !is JsonNull
            val products = entry?.get("products").asArray()?.mapNotNull { it.asText() }
            val fromVideo = products == null || products.isEmpty() || products.any { it.equals("YouTube", ignoreCase = true) }
            if (entry == null || videoId == null || timeMs == null || isAd || !fromVideo) {
                skipped += 1
                continue
            }
            val channel = entry["subtitles"].asArray()?.firstOrNull().asObject()
            val channelAddress = channel?.get("url").asText()
            watches.add(
                Watch(
                    videoId = videoId,
                    title = entry["title"].asText().orEmpty(),
                    channelName = channel?.get("name").asText()?.trim()?.takeIf { it.isNotEmpty() },
                    channelId = (channelAddress?.let(ImportSupport::channelRefOf) as? ChannelRef.Id)?.id,
                    timeMs = timeMs,
                ),
            )
        }

        val prefix = localisedPrefix(watches.map { it.title })
        val entries = LinkedHashMap<String, MutableList<Watch>>()
        for (watch in watches) entries.getOrPut(watch.videoId) { ArrayList() }.add(watch)
        val history = entries.map { (id, views) ->
            val newest = views.maxByOrNull { it.timeMs }!!
            val title = newest.title.removePrefix(prefix).trim().ifEmpty { id }
            HistoryEntry(
                item = VideoItem(
                    id = id,
                    title = title,
                    channelId = newest.channelId,
                    channelName = newest.channelName,
                    channelAvatarUrl = null,
                    thumbnailUrl = ImportSupport.thumbnailOf(id),
                    durationSec = null,
                    viewCount = null,
                    uploadedAtMs = null,
                    uploadedText = null,
                ),
                lastWatchedMs = newest.timeMs,
                positionMs = 0,
                durationMs = 0,
                watchCount = views.size,
            )
        }.sortedByDescending { it.lastWatchedMs }
        return ImportPreview(history = history, skipped = skipped).explainedIfEmpty()
    }

    /**
     * The localised "Watched " that starts every title. When every title starts with the English "Watched " that is it. Else,
     * with enough titles to infer it from, the longest start all titles share, cut back to end in a space (as FreeTube does).
     * With too few titles, or a start too long to be one verb, nothing is cut but an English "Watched " (a title without it is
     * left as it is).
     */
    internal fun localisedPrefix(titles: List<String>): String {
        if (titles.isNotEmpty() && titles.all { it.startsWith(ENGLISH_PREFIX) }) return ENGLISH_PREFIX
        if (titles.size >= MIN_TITLES_FOR_PREFIX) {
            var common = titles[0]
            for (title in titles) {
                while (!title.startsWith(common)) common = common.dropLast(1)
                if (common.isEmpty()) break
            }
            val cut = common.substring(0, common.lastIndexOf(' ') + 1)
            if (cut.isNotEmpty() && cut.length <= MAX_PREFIX_LENGTH) return cut
        }
        return ENGLISH_PREFIX
    }
}
