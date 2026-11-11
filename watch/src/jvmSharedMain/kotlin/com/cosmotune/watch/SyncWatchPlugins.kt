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
import kotlinx.serialization.json.JsonArray

/**
 * The user's add-ons as cloud sync carries them (kind `watchplugins`, beside playlists, likes and artists): each add-on's
 * manifest URL, name and switch. Servers, playlists, folders, sign-ins and passwords never sync; a sync never removes them.
 * The hosts' sync source calls [snapshot] and [apply] on the device's [com.cosmotune.watch.store.WatchStore].
 */
object SyncWatchPlugins {
    const val KIND = "watchplugins"
    private const val MAX = 100

    fun snapshot(state: WatchState): Map<String, JsonObject> = state.sources.mapNotNull { s ->
        s.id to when (val spec = s.spec) {
            is UserSourceSpec.StremioAddon -> buildJsonObject { put("url", spec.manifestUrl); put("name", s.name ?: spec.label); put("enabled", s.enabled) }
            is UserSourceSpec.NuvioScript -> buildJsonObject {
                put("kind", "script"); put("url", spec.scriptUrl); put("name", s.name ?: spec.label); put("enabled", s.enabled)
                put("sha256", spec.sha256); put("domains", JsonArray(spec.domains.map(::JsonPrimitive)))
            }
            else -> return@mapNotNull null
        }
    }.toMap()

    fun apply(state: WatchState, upserts: Map<String, JsonObject>, deletes: Set<String>, nowMs: Long = System.currentTimeMillis()): WatchState {
        val addOn = { id: String -> state.sources.firstOrNull { it.id == id }?.spec.let { it is UserSourceSpec.StremioAddon || it is UserSourceSpec.NuvioScript } }
        val gone = (deletes - upserts.keys).filter { addOn(it) }.toSet()
        val sources = state.sources.filterNot { it.id in gone }.map { s ->
            val data = upserts[s.id] ?: return@map s
            if (s.spec !is UserSourceSpec.StremioAddon && s.spec !is UserSourceSpec.NuvioScript) return@map s
            s.copy(enabled = (data["enabled"] as? JsonPrimitive)?.booleanOrNull ?: s.enabled, name = text(data, "name") ?: s.name, updatedAtMs = nowMs)
        }.toMutableList()
        for ((id, data) in upserts) {
            if (sources.any { it.id == id } || sources.size >= MAX) continue
            if (text(data, "kind") == "script") {
                val url = text(data, "url", 2048) ?: continue
                val sha = text(data, "sha256")?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: continue
                val domains = (data["domains"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: continue
                val valid = runCatching {
                    val uri = java.net.URI(url)
                    require(id.matches(Regex("js-[a-f0-9]{10}")) && uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null)
                    com.cosmotune.providers.ProviderManifest("watch-script", "1.0.0", type = listOf("metadata_provider"),
                        permissions = com.cosmotune.providers.ProviderPermissions(network = domains)).validate()
                }.isSuccess
                if (valid) sources += StoredSource(id, UserSourceSpec.NuvioScript(url, domains, text(data, "name").orEmpty(), sha),
                    enabled = (data["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true, addedAtMs = nowMs, name = text(data, "name"))
                continue
            }
            if (!id.startsWith("st-")) continue
            val url = text(data, "url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
            if (url.length > 500) continue
            sources += StoredSource(id, UserSourceSpec.StremioAddon(url, text(data, "name").orEmpty()), enabled = (data["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                addedAtMs = nowMs, name = text(data, "name"))
        }
        return state.copy(sources = sources, removed = state.removed + gone.associateWith { nowMs })
    }

    private fun text(data: JsonObject, name: String, limit: Int = 200) = (data[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it.length <= limit }
}
