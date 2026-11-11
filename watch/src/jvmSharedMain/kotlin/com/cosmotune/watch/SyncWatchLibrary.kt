package com.cosmotune.watch

import com.cosmotune.watch.model.MyListEntry
import com.cosmotune.watch.model.WatchProgress
import com.cosmotune.watch.store.WatchState
import com.cosmotune.watch.store.allProfiles
import com.cosmotune.watch.model.WatchProfile
import kotlinx.serialization.json.*

/** Account sync for Watch library data. Source credentials and playback URLs never travel. */
object SyncWatchLibrary {
    const val KIND = "watchlibrary"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun snapshot(state: WatchState): Map<String, JsonObject> = buildMap {
        state.allProfiles().values.forEach { profile ->
            put("profile:${profile.id}", buildJsonObject { put("name", profile.name); profile.preferences?.let { put("preferences", json.encodeToJsonElement(it)) } })
            profile.progress.values.filter { it.key.namespace in setOf("tmdb", "imdb") }.forEach { p ->
                put("progress:${profile.id}:${p.key.value}", json.encodeToJsonElement(p.copy(lastHint = null, summary = p.summary?.portable())).jsonObject)
            }
            profile.myList.filter { it.key.namespace in setOf("tmdb", "imdb") }.forEach { put("list:${profile.id}:${it.key.value}", json.encodeToJsonElement(it.copy(summary = it.summary.portable())).jsonObject) }
            profile.playlists.forEach { playlist -> put("playlist:${profile.id}:${playlist.id}", json.encodeToJsonElement(playlist.copy(
                items = playlist.items.filter { it.key.namespace in setOf("tmdb", "imdb") }.map { it.portable() })).jsonObject) }
        }
        state.settings.filterKeys { it in SYNC_SETTINGS }.forEach { (key, value) ->
            put("setting:$key", buildJsonObject { put("value", value) })
        }
    }

    /** Trigger policy only. Sync itself always takes the latest exact position. */
    fun changeSignal(state: WatchState): Map<String, JsonObject> = snapshot(state).mapValues { (id, data) ->
        if (!id.startsWith("progress:")) data else JsonObject(data + mapOf(
            "updatedAtMs" to JsonPrimitive(0),
            "positionMs" to JsonPrimitive(((data["positionMs"] as? JsonPrimitive)?.longOrNull ?: 0) / 300_000),
        ))
    }

    private fun com.cosmotune.watch.model.TitleSummary.portable() = copy(
        posterUrl = publicImage(posterUrl), backdropUrl = publicImage(backdropUrl), logoUrl = publicImage(logoUrl),
    )
    private fun publicImage(value: String?): String? = value?.takeIf {
        runCatching { java.net.URI(it).let { uri -> uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null } }.getOrDefault(false)
    }

    fun apply(state: WatchState, upserts: Map<String, JsonObject>, deletes: Set<String>): WatchState {
        val profiles = state.allProfiles().toMutableMap()
        val settings = state.settings.toMutableMap()
        for ((id, data) in upserts.filterKeys { it.startsWith("profile:") }) {
            val key = id.removePrefix("profile:")
            val name = (data["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(40)
            if (validProfile(key) && !name.isNullOrBlank() && (key in profiles || profiles.size < 8))
                profiles[key] = (profiles[key] ?: WatchProfile(key, name)).copy(name = name,
                    preferences = data["preferences"]?.let { runCatching { json.decodeFromJsonElement<WatchPreferences>(it) }.getOrNull() } ?: profiles[key]?.preferences)
        }
        for (id in deletes - upserts.keys) {
            val kind = id.substringBefore(':')
            val profileId = id.substringAfter(':').substringBefore(':')
            val key = id.substringAfter(':').substringAfter(':', "")
            val profile = profiles[profileId]
            when (kind) {
                "profile" -> if (profileId != "default") profiles.remove(profileId)
                "progress" -> if (profile != null) profiles[profileId] = profile.copy(progress = profile.progress - key)
                "list" -> if (profile != null) profiles[profileId] = profile.copy(myList = profile.myList.filterNot { it.key.value == key })
                "playlist" -> if (profile != null) profiles[profileId] = profile.copy(playlists = profile.playlists.filterNot { it.id == key })
                "setting" -> if (profileId in SYNC_SETTINGS) settings.remove(profileId)
            }
        }
        for ((id, data) in upserts) runCatching {
            val profileId = id.substringAfter(':').substringBefore(':')
            val profile = profiles[profileId]
            when {
                id.startsWith("playlist:") && profile != null -> {
                    val playlist = json.decodeFromJsonElement<com.cosmotune.watch.model.WatchPlaylist>(data)
                    if (playlist.id.matches(Regex("[a-f0-9-]{36}")) && id == "playlist:$profileId:${playlist.id}" && playlist.name.isNotBlank() && playlist.name.length <= 100 && playlist.items.size <= 1000)
                        profiles[profileId] = profile.copy(playlists = profile.playlists.filterNot { it.id == playlist.id } + playlist.copy(items = playlist.items.map { it.portable() }.distinctBy { it.key }))
                }
                id.startsWith("progress:") && profile != null -> {
                    val decoded = json.decodeFromJsonElement<WatchProgress>(data)
                    val p = decoded.copy(lastHint = null, summary = decoded.summary?.portable())
                    if (id == "progress:$profileId:${p.key.value}" && p.positionMs >= 0 && p.durationMs >= 0 && p.updatedAtMs >= 0)
                        profiles[profileId] = profile.copy(progress = profile.progress + (p.key.value to p))
                }
                id.startsWith("list:") && profile != null -> {
                    val decoded = json.decodeFromJsonElement<MyListEntry>(data)
                    val item = decoded.copy(summary = decoded.summary.portable())
                    if (id == "list:$profileId:${item.key.value}" && item.key == item.summary.key)
                        profiles[profileId] = profile.copy(myList = (profile.myList.filterNot { it.key == item.key } + item).sortedByDescending { it.addedAtMs })
                }
                id.startsWith("setting:") -> {
                    val key = id.removePrefix("setting:")
                    val value = data["value"]?.jsonPrimitive?.contentOrNull
                    if (key in SYNC_SETTINGS && value != null && value.length <= 500) settings[key] = value
                }
            }
        }
        val active = profiles[state.activeProfile] ?: profiles.getValue("default")
        return state.copy(activeProfile = active.id, profiles = profiles, progress = active.progress, myList = active.myList, settings = settings)
    }

    private val SYNC_SETTINGS = setOf("watch.density", "watch.aniskip", "watch.subtitleLanguages", "regions")
    private fun validProfile(value: String) = value == "default" || value.matches(Regex("[a-f0-9-]{36}"))
}
