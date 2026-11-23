package com.cosmotune.music.data.credits

import com.cosmotune.music.data.innertube.Innertube
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One line of a song's credits: what was done ("Written by") and by whom. */
data class SongCredit(val role: String, val names: List<String>) {
    val text: String get() = names.joinToString(", ")
}

/**
 * A song's credits as YouTube Music lists them under "View song credits" (Song info's credits, from upstream 1.10, GPL-3.0): the
 * credits page `MPTC<videoId>` first, else the credits entry in the song's own menu. Only catalogue tracks have them; anything
 * else answers an empty list.
 */
object SongCredits {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<SongCredit>>()

    /** [videoId]'s credits, or empty (none, not a YouTube track, or offline). Kept for the run once found. */
    suspend fun forSong(videoId: String): List<SongCredit> {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return emptyList()
        cache[videoId]?.let { return it }
        val found = attempt { parseCreditsPage(Innertube.browse("MPTC$videoId")) }.ifEmpty {
            attempt {
                val endpoint = findCreditsEndpoint(Innertube.next(videoId), videoId) ?: return@attempt emptyList()
                val browseId = (endpoint["browseId"] as? JsonPrimitive)?.contentOrNull ?: return@attempt emptyList()
                parseCreditsPage(Innertube.browse(browseId, (endpoint["params"] as? JsonPrimitive)?.contentOrNull))
            }
        }
        if (found.isNotEmpty()) cache[videoId] = found
        return found
    }

    private suspend fun attempt(block: suspend () -> List<SongCredit>): List<SongCredit> =
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyList() }

    /** The credits browse endpoint in a `next` answer: a menu entry whose page type says track credits, else one named for credits. */
    fun findCreditsEndpoint(root: JsonElement, videoId: String): JsonObject? {
        fun JsonObject.pageType(): String = ((this["browseEndpointContextSupportedConfigs"] as? JsonObject)
            ?.get("browseEndpointContextMusicConfig") as? JsonObject)?.get("pageType")?.let { it as? JsonPrimitive }?.contentOrNull.orEmpty()
        fun isCredits(browse: JsonObject): Boolean =
            "CREDIT" in browse.pageType() || (browse["browseId"] as? JsonPrimitive)?.contentOrNull.orEmpty().startsWith("MPTC")
        fun browseOf(nav: JsonObject) = (nav["navigationEndpoint"] as? JsonObject)?.get("browseEndpoint") as? JsonObject
        val rows = objectsNamed(root, "playlistPanelVideoRenderer").filter { (it["videoId"] as? JsonPrimitive)?.contentOrNull == videoId }
            .toList().ifEmpty { objectsNamed(root, "playlistPanelVideoRenderer").toList() }
        for (scope in rows + listOfNotNull(root as? JsonObject)) {
            val items = objectsNamed(scope, "menuNavigationItemRenderer").toList()
            val endpoint = items.firstNotNullOfOrNull { nav -> browseOf(nav)?.takeIf(::isCredits) }
                ?: items.firstNotNullOfOrNull { nav -> browseOf(nav)?.takeIf { nav.runsText().contains("credit", ignoreCase = true) } }
            if (endpoint != null) return endpoint
        }
        return objectsNamed(root, "browseEndpoint").firstOrNull(::isCredits)
    }

    /** The credits on a credits page: dialog sections first (role, then the names a line each), else list rows, else shelves. */
    fun parseCreditsPage(root: JsonElement): List<SongCredit> {
        val credits = mutableListOf<SongCredit>()
        for (section in objectsNamed(root, "dismissableDialogContentSectionRenderer")) {
            val role = section["title"]?.runsText()?.trim().orEmpty()
            val names = splitNames(section["subtitle"]?.runsText().orEmpty())
            if (role.isNotBlank() && names.isNotEmpty()) credits += SongCredit(role, names)
        }
        if (credits.isEmpty()) {
            for (item in objectsNamed(root, "musicResponsiveListItemRenderer")) {
                val flex = (item["flexColumns"] as? JsonArray).orEmpty()
                val role = flex.getOrNull(0)?.rendererText()?.trim().orEmpty()
                val names = if (flex.size > 1) flex.drop(1).flatMap { splitNames(it.rendererText()) } else splitNames(item["subtitle"]?.runsText().orEmpty())
                if (role.isNotBlank() && names.isNotEmpty()) credits += SongCredit(role, names)
            }
        }
        return credits.distinctBy { it.role.lowercase() to it.text.lowercase() }
    }

    private fun JsonElement.rendererText(): String =
        objectsNamed(this, "text").firstNotNullOfOrNull { it.runsText().takeIf(String::isNotBlank) } ?: runsText()

    private fun JsonElement.runsText(): String = when (this) {
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> joinToString("") { it.runsText() }
        is JsonObject -> (this["simpleText"] as? JsonPrimitive)?.contentOrNull
            ?: (this["runs"] as? JsonArray)?.joinToString("") { run -> ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
            ?: this["text"]?.runsText()
            ?: ""
    }

    /** Names a line each, or split on a bullet, or on a comma before a capitalised name ("Jeff Bass, Luis Resto & Marshall Mathers"). */
    private fun splitNames(joined: String): List<String> {
        if (joined.isBlank()) return emptyList()
        return joined.split(Regex("\\r?\\n+|\\s*[•·]\\s*|\\s*,\\s+(?=[\\p{Lu}\"'])"))
            .map { it.trim().trimEnd(',', '•', '·') }.filter { it.isNotBlank() }.ifEmpty { listOf(joined.trim()) }
    }

    private fun objectsNamed(root: JsonElement, name: String): Sequence<JsonObject> = sequence {
        when (root) {
            is JsonObject -> for ((key, value) in root) {
                if (key == name && value is JsonObject) yield(value)
                yieldAll(objectsNamed(value, name))
            }
            is JsonArray -> for (value in root) yieldAll(objectsNamed(value, name))
            else -> Unit
        }
    }
}
