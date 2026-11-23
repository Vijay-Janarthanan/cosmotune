package com.cosmotune.podio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Podio's part of the account sync (gPodder's subscription and episode-action sync, done with the Cosmotune account instead):
 * the followed shows, the saved stations, and where each episode was left and whether it was finished.
 *
 * Downloads, the queue, show settings and listening history stay on the device. A feed or a station address that carries a sign-in
 * (`user:password@`) never travels.
 */
object SyncPodio {
    const val KIND = "podio"

    /** Where episodes were left: only the latest ones travel, so the cloud copy stays small. */
    const val MAX_PROGRESS = 300

    private val json = Json { ignoreUnknownKeys = true }

    fun snapshot(saved: PodioSaved): Map<String, JsonObject> = buildMap {
        // A private feed stays on the device that has its login.
        saved.shows.filter { portable(it.feed) && it.feed !in saved.feedLogins }.forEach { show ->
            put("show:${show.id}", json.encodeToJsonElement(show.copy(people = emptyList(), funding = emptyList())).jsonObject)
        }
        saved.stations.filter { portable(it.url) }.forEach { station ->
            put("station:${station.id}", json.encodeToJsonElement(station.copy(offline = false, removed = false)).jsonObject)
        }
        saved.progress.entries.toList().takeLast(MAX_PROGRESS).forEach { (id, position) ->
            put("progress:$id", buildJsonObject { put("positionMs", position); put("done", id in saved.completedEpisodes) })
        }
    }

    /** What starts a sync: positions only count each whole minute, so playing an episode is not a sync every few seconds. */
    fun changeSignal(saved: PodioSaved): Map<String, JsonObject> = snapshot(saved).mapValues { (id, data) ->
        if (!id.startsWith("progress:")) data
        else buildJsonObject {
            put("minute", ((data["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0) / 60_000)
            put("done", (data["done"] as? JsonPrimitive)?.booleanOrNull ?: false)
        }
    }

    /**
     * [saved] with another device's changes laid over it: shows and stations followed or let go there, and positions moved there.
     * An entry that cannot be read is left out; a full library (500 shows or stations) takes no more.
     */
    fun apply(saved: PodioSaved, upserts: Map<String, JsonObject>, deletes: Set<String>): PodioSaved {
        val gone = deletes - upserts.keys
        val goneShows = gone.filter { it.startsWith("show:") }.map { it.removePrefix("show:") }.toSet()
        val goneStations = gone.filter { it.startsWith("station:") }.map { it.removePrefix("station:") }.toSet()
        val goneProgress = gone.filter { it.startsWith("progress:") }.map { it.removePrefix("progress:") }.toSet()
        val shows = saved.shows.filterNot { it.id in goneShows }.toMutableList()
        val stations = saved.stations.filterNot { it.id in goneStations }.toMutableList()
        val progress = (saved.progress - goneProgress).toMutableMap()
        val completed = (saved.completedEpisodes - goneProgress).toMutableSet()
        for ((id, data) in upserts) {
            when {
                id.startsWith("show:") -> {
                    val show = runCatching { json.decodeFromJsonElement<PodcastShow>(data) }.getOrNull() ?: continue
                    if (id != "show:${show.id}" || show.title.isBlank() || !portable(show.feed)) continue
                    val at = shows.indexOfFirst { it.id == show.id || it.feed.trimEnd('/') == show.feed.trimEnd('/') }
                    // A show this device follows keeps what its own feed said (hosts, support links); only a new one is added.
                    if (at < 0 && shows.size < 500) shows += show
                }
                id.startsWith("station:") -> {
                    val station = runCatching { json.decodeFromJsonElement<RadioStation>(data) }.getOrNull() ?: continue
                    if (id != "station:${station.id}" || station.name.isBlank() || !portable(station.url)) continue
                    val at = stations.indexOfFirst { it.id == station.id }
                    if (at >= 0) stations[at] = station.copy(offline = stations[at].offline, removed = stations[at].removed)
                    else if (stations.size < 500) stations += station
                }
                id.startsWith("progress:") -> {
                    val episode = id.removePrefix("progress:").takeIf { it.startsWith("podio:episode:") } ?: continue
                    val position = (data["positionMs"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: continue
                    progress.remove(episode); progress[episode] = position
                    if ((data["done"] as? JsonPrimitive)?.booleanOrNull == true) completed += episode else completed -= episode
                }
            }
        }
        return saved.copy(shows = shows, stations = stations, progress = progress, completedEpisodes = completed)
    }

    /** An http(s) address with no sign-in in it. */
    fun portable(url: String): Boolean = runCatching {
        val uri = java.net.URI(url.trim())
        (uri.scheme == "https" || uri.scheme == "http") && uri.host != null && uri.userInfo == null
    }.getOrDefault(false)
}
