package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.SearchResult
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.sources.TrackMatcher
import com.cosmotune.music.data.sources.JioSaavnMatcher
import com.cosmotune.music.data.sources.RecordingMetadata
import com.cosmotune.music.data.model.ArtistRef
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly opt-in: personal history stays in ignored build output, never in test fixtures. */
class JioSaavnHistoryAuditTest {
    private val json = Json { prettyPrint = true }
    private val directory = File("../build/jiosaavn-history-audit").canonicalFile

    @Test(timeout = 600_000) fun recoveredRecordingsUseTheActualAdapterAndDecoder() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_JIOSAAVN_HISTORY_PLAYBACK_PROBE") == "1")
        check(com.cosmotune.music.data.innertube.Innertube.cookie == null)
        val audited = json.parseToJsonElement(File(directory, "results.json").readText()).jsonArray
        val reports = mutableListOf<JsonObject>()
        for (row in audited.map { it.jsonObject }.filter {
            it.getValue("desktop").jsonArray.isEmpty() && it.getValue("jiosaavn").jsonArray.isNotEmpty()
        }) {
            val target = song(row.getValue("target"))
            if (target.isVideo) continue
            reports += buildJsonObject {
                put("song", target.title)
                try {
                    val matched = withTimeout(20_000) { DesktopJioSaavn.matches(target).firstOrNull() }
                    checkNotNull(matched) { "No recording via production adapter" }
                    put("matchedTitle", matched.title); put("matchedAlbum", matched.albumName)
                    val stream = withTimeout(15_000) { DesktopJioSaavn.stream(matched.videoId).getOrThrow() }
                    checkNotNull(stream)
                    val decoder = DesktopAudioDecoder()
                    try {
                        decoder.open(stream.url, requested = DesktopPcmFormat(48_000, 2, 4, true)).getOrThrow()
                        check(decoder.read()?.isNotEmpty() == true)
                        put("status", "DECODED_AUDIO"); put("codec", decoder.measuredFormat?.codec)
                    } finally { decoder.close() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (failure: Exception) { put("status", "FAILED"); put("type", failure.javaClass.simpleName) }
            }
            File(directory, "playback-probes.json").writeText(json.encodeToString(JsonArray.serializer(), JsonArray(reports)))
        }
        check(reports.isNotEmpty())
        check(reports.all { it["status"]?.jsonPrimitive?.content == "DECODED_AUDIO" })
    }

    @Test(timeout = 900_000) fun auditSavedHistoryWithoutChangingIt() = runBlocking {
        assumeTrue(System.getenv("COSMOTUNE_JIOSAAVN_HISTORY_AUDIT") == "1")
        directory.mkdirs()
        val snapshot = File(directory, "history.json")
        val history = if (snapshot.exists()) json.parseToJsonElement(snapshot.readText()).jsonArray.map(::song)
            else DesktopPersistence().history().distinctBy { it.videoId }.also {
                snapshot.writeText(json.encodeToString(JsonArray.serializer(), JsonArray(it.map(::metadata))))
            }
        if (System.getenv("COSMOTUNE_JIOSAAVN_HISTORY_EXPORT_ONLY") == "1") return@runBlocking
        val logged = File(directory, "logged-songs.json").takeIf { it.exists() }?.let {
            json.parseToJsonElement(it.readText().removePrefix("\uFEFF")).jsonArray.mapIndexed { index, row ->
                Song(row.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "log:$index",
                    row.jsonObject.getValue("title").jsonPrimitive.content,
                    row.jsonObject.getValue("artist").jsonPrimitive.content, null,
                    isVideo = row.jsonObject["isVideo"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
        }.orEmpty().filter { log -> history.none { it.title == log.title && it.artist == log.artist } }
        val rows = mutableListOf<JsonObject>()
        check(com.cosmotune.music.data.innertube.Innertube.cookie == null) { "History metadata probe must remain anonymous" }
        for ((index, target) in (history + logged).withIndex()) {
            if (target.videoId.startsWith("podio:") || target.localUri != null || target.localPath != null) continue
            val cache = File(directory, "search-$index.json")
            val detailsFile = File(directory, "metadata-$index.json")
            val savedDetails = detailsFile.takeIf { it.exists() }?.let { json.parseToJsonElement(it.readText()).jsonObject }
                ?.takeIf { it["id"]?.jsonPrimitive?.content == target.videoId && it["title"]?.jsonPrimitive?.content == target.title }
            val details = if (savedDetails != null) savedDetails.let {
                TrackMatcher.targetOf(target).copy(album = it["album"]?.jsonPrimitive?.contentOrNull,
                    durationSec = it["durationSec"]?.jsonPrimitive?.intOrNull)
            } else RecordingMetadata.enrich(TrackMatcher.targetOf(target)).also { enriched ->
                detailsFile.writeText(buildJsonObject {
                    put("id", target.videoId); put("title", target.title)
                    put("album", enriched.album); put("durationSec", enriched.durationSec)
                }.toString())
            }
            val queries = (DesktopTrackMatcher.queries(target) + TrackMatcher.queries(TrackMatcher.targetOf(target)) + JioSaavnMatcher.queries(details)).distinct()
            val saved = if (cache.exists()) json.parseToJsonElement(cache.readText()).jsonArray else JsonArray(emptyList())
            val missing = queries.filter { query -> saved.none { it.jsonObject["query"]?.jsonPrimitive?.content == query } }
            val searches = JsonArray(saved.filter { it.jsonObject["query"]?.jsonPrimitive?.content in queries } + missing.map { query -> buildJsonObject {
                    put("query", query)
                    try {
                        val songs = withTimeout(15_000) { DesktopJioSaavn.search(query, 50).getOrThrow() }
                            .mapNotNull { (it as? SearchResult.Track)?.song }
                        put("results", JsonArray(songs.map(::metadata)))
                    } catch (error: Exception) {
                        if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                        put("error", error.javaClass.simpleName)
                        put("results", JsonArray(emptyList()))
                    }
                } }).also { cache.writeText(json.encodeToString(JsonArray.serializer(), it)) }
            val candidates = searches.flatMap { it.jsonObject.getValue("results").jsonArray }.map(::song)
                .distinctBy { it.videoId }
            val desktop = DesktopTrackMatcher.ranked(candidates, target)
            val shared = TrackMatcher.ranked(candidates, TrackMatcher.targetOf(target))
            val matched = JioSaavnMatcher.ranked(candidates, details)
            rows += buildJsonObject {
                put("index", index); put("origin", if (index >= history.size) "log" else "saved-history")
                put("target", metadata(target)); put("candidates", candidates.size)
                put("resolvedAlbum", details.album); put("resolvedDurationSec", details.durationSec)
                put("jiosaavn", JsonArray(matched.map(::metadata)))
                put("desktop", JsonArray(desktop.map(::metadata))); put("shared", JsonArray(shared.map(::metadata)))
                put("sameTitle", JsonArray(candidates.filter {
                    DesktopTrackMatcher.parseTitle(it.title, it.artist).core == DesktopTrackMatcher.parseTitle(target.title, target.artist).core
                }.map(::metadata)))
                put("searchErrors", searches.count { "error" in it.jsonObject })
            }
            File(directory, "results.json").writeText(json.encodeToString(JsonArray.serializer(), JsonArray(rows)))
        }
    }

    private fun metadata(song: Song) = buildJsonObject {
        put("id", song.videoId); put("title", song.title); put("artist", song.artist)
        put("album", song.albumName); put("duration", song.durationText)
        put("isVideo", song.isVideo); put("explicit", song.isExplicit)
        put("artists", JsonArray(song.artists.map { JsonPrimitive(it.name) }))
    }

    private fun song(value: JsonElement): Song = value.jsonObject.let {
        Song(it.getValue("id").jsonPrimitive.content, it.getValue("title").jsonPrimitive.content,
            it.getValue("artist").jsonPrimitive.content, null,
            albumName = it["album"]?.jsonPrimitive?.contentOrNull,
            durationText = it["duration"]?.jsonPrimitive?.contentOrNull,
            isVideo = it["isVideo"]?.jsonPrimitive?.booleanOrNull ?: false,
            isExplicit = it["explicit"]?.jsonPrimitive?.booleanOrNull,
            artists = it["artists"]?.jsonArray?.map { name -> ArtistRef(name.jsonPrimitive.content) }.orEmpty())
    }
}
