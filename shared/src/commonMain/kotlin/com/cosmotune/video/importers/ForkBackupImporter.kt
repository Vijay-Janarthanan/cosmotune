package com.cosmotune.video.importers

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LocalPlaylist
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The backups of two other YouTube apps (PipePipe writes NewPipe's own backup, read by [NewPipeDatabaseImporter]):
 *
 * - FreeTube's export files, one JSON object per line (its NeDB files): `freetube-subscriptions-*.db` (profiles, each with
 *   `subscriptions: [{id, name, thumbnail}]`), `freetube-history-*.db` (`{videoId, title, author, authorId, lengthSeconds,
 *   watchProgress, timeWatched}`) and `freetube-playlists-*.db` (`{playlistName, videos: [{videoId, title, author, authorId,
 *   lengthSeconds, timeAdded}]}`).
 * - LibreTube's backup, one JSON object: `subscriptions: [{channelId, name, avatar}]`, `watchHistory: [{videoId, title,
 *   uploader, uploaderUrl, thumbnailUrl, duration, accessDate}]`, `watchPositions: [{videoId, position}]`, `searchHistory:
 *   [{query}]`, `localPlaylists: [{playlist: {id, name}, videos: [{videoId, title, uploader, thumbnailUrl, duration}]}]` and
 *   `playlistBookmarks: [{playlistId, playlistName, thumbnailUrl, uploader, uploaderUrl, videos}]`.
 *
 * Times are kept when the file has them; an entry without a usable video or channel id is skipped.
 */
object ForkBackupImporter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Whether [head] (the start of a file) is one of FreeTube's export files. */
    fun isFreeTube(head: String): Boolean {
        val first = head.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
        if (!first.startsWith("{")) return false
        val profile = "\"subscriptions\"" in first && "\"bgColor\"" in first
        val history = "\"videoId\"" in first && "\"timeWatched\"" in first
        val playlist = "\"playlistName\"" in first && "\"videos\"" in first
        return profile || history || playlist
    }

    /** Whether [head] is a LibreTube backup. */
    fun isLibreTube(head: String): Boolean = "\"watchHistory\"" in head || "\"localPlaylists\"" in head || "\"playlistBookmarks\"" in head

    // ---- FreeTube ------------------------------------------------------------------------------------------------------

    fun parseFreeTube(text: String, nowMs: Long): ImportPreview {
        val objects = text.removePrefix("﻿").lineSequence().map { it.trim() }.filter { it.startsWith("{") }
            .mapNotNull { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }.toList()
        if (objects.isEmpty()) return ImportPreview.failed(ImportReason.UNREADABLE)
        val channels = SubscriptionCollector(nowMs)
        val history = LinkedHashMap<String, HistoryEntry>()
        val playlists = ArrayList<LocalPlaylist>()
        var skipped = 0
        for (o in objects) {
            when {
                o["subscriptions"] is kotlinx.serialization.json.JsonArray -> for (s in o["subscriptions"].asArray().orEmpty()) {
                    val sub = s.asObject()
                    val id = sub?.get("id").asText()
                    if (id != null && ImportSupport.isChannelId(id)) channels.addChannel(id, sub?.get("name").asText(), sub?.get("thumbnail").asText()) else channels.skip()
                }
                o["playlistName"] != null -> {
                    val name = o["playlistName"].asText()?.takeIf { it.isNotBlank() } ?: "FreeTube"
                    val items = o["videos"].asArray().orEmpty().mapNotNull { v ->
                        freeTubeVideo(v.asObject()).also { if (it == null) skipped++ }
                    }.distinctBy { it.id }
                    val id = o["_id"].asText()?.takeIf { it.isNotBlank() } ?: name
                    val created = o["createdAt"].asLong() ?: nowMs
                    playlists.add(LocalPlaylist("freetube-$id", name, items, created, o["lastUpdatedAt"].asLong() ?: created))
                }
                o["videoId"] != null -> {
                    val item = freeTubeVideo(o)
                    if (item == null) {
                        skipped++
                        continue
                    }
                    val durationMs = (o["lengthSeconds"].asLong() ?: 0L) * 1000
                    val positionMs = ((o["watchProgress"].asDouble() ?: 0.0) * 1000).toLong()
                    val at = o["timeWatched"].asLong() ?: nowMs
                    val before = history[item.id]
                    if (before == null || before.lastWatchedMs < at) history[item.id] = HistoryEntry(item, at, positionMs, durationMs, 1)
                    else skipped++
                }
                else -> skipped++
            }
        }
        val subs = channels.preview()
        return subs.copy(
            history = history.values.sortedByDescending { it.lastWatchedMs },
            playlists = playlists,
            skipped = subs.skipped + skipped,
        ).nothingFoundIfEmpty()
    }

    private fun freeTubeVideo(o: JsonObject?): VideoItem? {
        if (o == null) return null
        val id = o["videoId"].asText()?.takeIf(ImportSupport::isVideoId) ?: return null
        return VideoItem(
            id = id,
            title = o["title"].asText().orEmpty().ifBlank { id },
            channelId = o["authorId"].asText()?.takeIf(ImportSupport::isChannelId),
            channelName = o["author"].asText()?.takeIf { it.isNotBlank() },
            channelAvatarUrl = null,
            thumbnailUrl = ImportSupport.thumbnailOf(id),
            durationSec = o["lengthSeconds"].asLong()?.takeIf { it > 0 },
            viewCount = null,
            uploadedAtMs = o["published"].asLong()?.takeIf { it > 0 },
            uploadedText = null,
            isLive = o["isLive"].asBool() == true,
        )
    }

    // ---- LibreTube -----------------------------------------------------------------------------------------------------

    fun parseLibreTube(text: String, nowMs: Long): ImportPreview {
        val root = runCatching { json.parseToJsonElement(text.removePrefix("﻿")) as? JsonObject }.getOrNull()
            ?: return ImportPreview.failed(ImportReason.UNREADABLE)
        var skipped = 0
        val channels = SubscriptionCollector(nowMs)
        for (s in root["subscriptions"].asArray().orEmpty()) {
            val sub = s.asObject()
            val id = sub?.get("channelId").asText() ?: sub?.get("url").asText()?.let(ImportSupport::channelIdIn)
            if (id != null && ImportSupport.isChannelId(id)) channels.addChannel(id, sub?.get("name").asText(), sub?.get("avatar").asText()) else channels.skip()
        }
        val positions = root["watchPositions"].asArray().orEmpty().mapNotNull { p ->
            val o = p.asObject() ?: return@mapNotNull null
            val id = o["videoId"].asText() ?: return@mapNotNull null
            id to (o["position"].asLong() ?: 0L)
        }.toMap()
        val history = LinkedHashMap<String, HistoryEntry>()
        for (h in root["watchHistory"].asArray().orEmpty()) {
            val o = h.asObject()
            val item = libreVideo(o)
            if (o == null || item == null) {
                skipped++
                continue
            }
            val at = o["accessDate"].asLong() ?: o["currentTime"].asLong() ?: nowMs
            history[item.id] = HistoryEntry(item, at, positions[item.id] ?: 0L, (item.durationSec ?: 0L) * 1000, 1)
        }
        val playlists = root["localPlaylists"].asArray().orEmpty().mapNotNull { p ->
            val o = p.asObject() ?: return@mapNotNull null
            val head = o["playlist"].asObject()
            val name = head?.get("name").asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null.also { skipped++ }
            val id = head?.get("id").asLong()?.toString() ?: name
            val items = o["videos"].asArray().orEmpty().mapNotNull { v -> libreVideo(v.asObject()).also { if (it == null) skipped++ } }.distinctBy { it.id }
            LocalPlaylist("libretube-$id", name, items, nowMs, nowMs)
        }
        val saved = root["playlistBookmarks"].asArray().orEmpty().mapNotNull { b ->
            val o = b.asObject() ?: return@mapNotNull null
            val id = o["playlistId"].asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null.also { skipped++ }
            PlaylistItem(
                id = id,
                title = o["playlistName"].asText().orEmpty().ifBlank { id },
                thumbnailUrl = o["thumbnailUrl"].asText(),
                ownerName = o["uploader"].asText(),
                ownerId = o["uploaderUrl"].asText()?.let(ImportSupport::channelIdIn),
                videoCount = o["videos"].asLong(),
            )
        }
        val searches = root["searchHistory"].asArray().orEmpty().mapNotNull { it.asObject()?.get("query").asText()?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        val subs = channels.preview()
        return subs.copy(
            history = history.values.sortedByDescending { it.lastWatchedMs },
            playlists = playlists,
            savedPlaylists = saved,
            searches = searches,
            skipped = subs.skipped + skipped,
        ).nothingFoundIfEmpty()
    }

    private fun libreVideo(o: JsonObject?): VideoItem? {
        if (o == null) return null
        val id = o["videoId"].asText()?.takeIf(ImportSupport::isVideoId) ?: return null
        return VideoItem(
            id = id,
            title = o["title"].asText().orEmpty().ifBlank { id },
            channelId = o["uploaderUrl"].asText()?.let(ImportSupport::channelIdIn),
            channelName = o["uploader"].asText()?.takeIf { it.isNotBlank() },
            channelAvatarUrl = o["uploaderAvatar"].asText(),
            thumbnailUrl = o["thumbnailUrl"].asText() ?: ImportSupport.thumbnailOf(id),
            durationSec = o["duration"].asLong()?.takeIf { it > 0 },
            viewCount = null,
            uploadedAtMs = null,
            uploadedText = null,
        )
    }

    private fun ImportPreview.nothingFoundIfEmpty(): ImportPreview = if (isEmpty) copy(reason = ImportReason.NOTHING_FOUND) else this

    private fun JsonElement?.asDouble(): Double? = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

    private fun JsonElement?.asBool(): Boolean? = (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull()
}
