package com.cosmotune.video.importers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Subscriptions from Google Takeout (spec 2.7, 8.2): `subscriptions.csv`, the older JSON array, or the whole Takeout ZIP.
 *
 * - CSV: read by position (channel id, channel address, channel title), through [Csv], because titles hold commas and quotes.
 *   The header is whatever the first row is when its first field is not a channel id (24 characters starting with `UC`):
 *   its language does not matter (`Channel Id`, `Channel ID`, `ID del canal`).
 * - JSON (older Takeout): an array of `youtube#subscription` objects; the channel followed is `snippet.resourceId.channelId`
 *   (`snippet.channelId` is the account's own channel), its name `snippet.title`, the follow date `snippet.publishedAt`.
 * - ZIP: the first `.csv` that yields channels, trying those under a folder named like subscriptions (in any of several
 *   languages) first, then any other CSV; then the same for `.json` entries.
 */
object TakeoutSubscriptionsImporter {
    /** Reads `subscriptions.csv` text. [nowMs] is the follow time (the CSV has none). */
    fun parseCsv(text: String, nowMs: Long): ImportPreview {
        val collector = SubscriptionCollector(nowMs)
        val records = Csv.parse(text).filterNot { it.isBlankRecord() }
        if (records.isEmpty()) return ImportPreview.failed(ImportReason.UNREADABLE)
        records.forEachIndexed { index, record ->
            val id = record.firstOrNull()?.trim().orEmpty()
            when {
                ImportSupport.isChannelId(id) -> collector.addChannel(id, record.getOrNull(2))
                index == 0 -> Unit // the header, in whatever language
                else -> collector.skip()
            }
        }
        return collector.preview()
    }

    /** Reads the older JSON array. */
    fun parseJson(text: String, nowMs: Long): ImportPreview {
        val root = try {
            Json.parseToJsonElement(text.removePrefix("\uFEFF"))
        } catch (e: Exception) {
            return ImportPreview.failed(ImportReason.UNREADABLE)
        }
        if (root !is JsonArray) return ImportPreview.failed(ImportReason.UNREADABLE)
        val collector = SubscriptionCollector(nowMs)
        for (element in root) {
            val snippet = element.asObject()?.get("snippet").asObject()
            val id = snippet?.get("resourceId").asObject()?.get("channelId").asText()?.trim()
            if (snippet == null || id == null || !ImportSupport.isChannelId(id)) {
                collector.skip()
                continue
            }
            val thumbnails = snippet["thumbnails"].asObject()
            val avatar = listOf("high", "medium", "default").firstNotNullOfOrNull { thumbnails?.get(it).asObject()?.get("url").asText() }
            val followed = snippet["publishedAt"].asText()?.let(ImportSupport::parseTimeMs)
            collector.addChannel(id, snippet["title"].asText(), avatar, followed)
        }
        return collector.preview()
    }

    /** Reads the channels of a whole Takeout ZIP (only the entries it needs are read). */
    fun parseZip(zip: ZipEntries, nowMs: Long): ImportPreview {
        val entries = zip.names().filterNot { it.startsWith("__MACOSX/") }
        fun ordered(extension: String) = entries.filter { it.lowercase().endsWith(extension) }
            .sortedBy { if (looksLikeSubscriptionsPath(it)) 0 else 1 }
        for (name in ordered(".csv")) {
            val bytes = zip.read(name) ?: continue
            val preview = parseCsv(bytes.decodeToString(), nowMs)
            if (preview.subscriptions.isNotEmpty()) return preview
        }
        for (name in ordered(".json")) {
            if (!looksLikeSubscriptionsPath(name)) continue
            val bytes = zip.read(name) ?: continue
            val preview = parseJson(bytes.decodeToString(), nowMs)
            if (preview.subscriptions.isNotEmpty()) return preview
        }
        return ImportPreview.failed(ImportReason.NOTHING_FOUND)
    }

    /**
     * Whether a path in a Takeout ZIP looks like the subscriptions folder or file in some language (`subscriptions`,
     * `suscripciones`, `abonnements`, `abos`, `iscrizioni`, `inscrições`, ...). Only an ordering hint: a CSV is chosen
     * by what it holds.
     */
    internal fun looksLikeSubscriptionsPath(path: String): Boolean {
        val lower = path.lowercase()
        return SUBSCRIPTION_HINTS.any { lower.contains(it) }
    }

    private val SUBSCRIPTION_HINTS = listOf("subscri", "suscri", "abonn", "abos", "iscri", "abbon", "inscri", "prenum", "abonel", "订阅", "訂閱", "登録", "구독", "подпис")
}
