package com.cosmotune.music.data.innertube

import kotlinx.serialization.json.*

/** Session-local ownership hints from the catalogue, not a bypass of stream validation. */
internal object UploadedTracks {
    private val ids = LinkedHashSet<String>()
    private const val LIMIT = 20_000

    @Synchronized fun contains(id: String): Boolean = id in ids
    @Synchronized fun clear() = ids.clear()

    @Synchronized fun observe(id: String, renderer: JsonObject) {
        if (!id.matches(Regex("[A-Za-z0-9_-]{11}")) || !privateMarker(renderer)) return
        if (id !in ids && ids.size >= LIMIT) ids.remove(ids.first())
        ids += id
    }

    private fun privateMarker(root: JsonElement): Boolean {
        val pending = ArrayDeque<JsonElement>().apply { add(root) }
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 5_000) {
            when (val node = pending.removeFirst()) {
                is JsonObject -> {
                    if (node["musicDeletePrivatelyOwnedEntityCommand"] is JsonObject ||
                        (node["musicVideoType"] as? JsonPrimitive)?.contentOrNull?.contains("PRIVATELY_OWNED") == true) return true
                    pending.addAll(node.values)
                }
                is JsonArray -> pending.addAll(node)
                else -> Unit
            }
        }
        return false
    }
}
