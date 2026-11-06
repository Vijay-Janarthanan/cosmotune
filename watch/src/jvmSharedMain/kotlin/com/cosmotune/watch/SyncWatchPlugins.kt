package com.cosmotune.watch

import com.cosmotune.watch.plugin.UserSourceSpec
import com.cosmotune.watch.store.StoredSource
import com.cosmotune.watch.store.WatchState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The user's add-ons as cloud sync carries them (kind `watchplugins`, beside playlists, likes and artists): each add-on's
 * manifest URL, name and switch. Servers, playlists, folders, sign-ins and passwords never sync; a sync never removes them.
 * The hosts' sync source calls [snapshot] and [apply] on the device's [com.cosmotune.watch.store.WatchStore].
 */
object SyncWatchPlugins {
    const val KIND = "watchplugins"
    private const val MAX = 100

    fun snapshot(state: WatchState): Map<String, JsonObject> = state.sources.mapNotNull { s ->
        val spec = s.spec as? UserSourceSpec.StremioAddon ?: return@mapNotNull null
        s.id to buildJsonObject { put("url", spec.manifestUrl); put("name", s.name ?: spec.label); put("enabled", s.enabled) }
    }.toMap()

    fun apply(state: WatchState, upserts: Map<String, JsonObject>, deletes: Set<String>, nowMs: Long = System.currentTimeMillis()): WatchState {
        val addOn = { id: String -> state.sources.firstOrNull { it.id == id }?.spec is UserSourceSpec.StremioAddon }
        val gone = (deletes - upserts.keys).filter { addOn(it) }.toSet()
        val sources = state.sources.filterNot { it.id in gone }.map { s ->
            val data = upserts[s.id] ?: return@map s
            if (s.spec !is UserSourceSpec.StremioAddon) return@map s
            s.copy(enabled = (data["enabled"] as? JsonPrimitive)?.booleanOrNull ?: s.enabled, name = text(data, "name") ?: s.name, updatedAtMs = nowMs)
        }.toMutableList()
        for ((id, data) in upserts) {
            if (sources.any { it.id == id } || sources.size >= MAX || !id.startsWith("st-")) continue
            val url = text(data, "url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
            if (url.length > 500) continue
            sources += StoredSource(id, UserSourceSpec.StremioAddon(url, text(data, "name").orEmpty()), enabled = (data["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                addedAtMs = nowMs, name = text(data, "name"))
        }
        return state.copy(sources = sources, removed = state.removed + gone.associateWith { nowMs })
    }

    private fun text(data: JsonObject, name: String) = (data[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
}
