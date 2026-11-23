package com.cosmotune.watch.plugins.stremio

import com.cosmotune.watch.http.WatchHttp
import com.cosmotune.watch.http.arr
import com.cosmotune.watch.http.bool
import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.obj
import com.cosmotune.watch.http.prim
import com.cosmotune.watch.http.str
import com.cosmotune.watch.http.strings
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/** One resource of a manifest (`stream`, `meta`, `catalog`, `subtitles`), optionally narrowed to types and id prefixes. */
data class StremioResource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

/** A manifest catalogue: the extras it takes (`search`, `genre`, `skip`), those it needs, and each extra's allowed values when listed. */
data class StremioCatalog(val type: String, val id: String, val name: String, val extras: List<String>, val requiredExtras: List<String>,
    val options: Map<String, List<String>> = emptyMap())

data class StremioManifest(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val logo: String?,
    val types: List<String>,
    val idPrefixes: List<String>?,
    val resources: List<StremioResource>,
    val catalogs: List<StremioCatalog>,
    val configurationRequired: Boolean,
    val p2p: Boolean,
    val adult: Boolean,
) {
    fun supports(resource: String, type: String, id: String? = null): Boolean {
        return resources.filter { it.name == resource }.any { r ->
            val types = r.types ?: this.types
            val prefixes = r.idPrefixes ?: idPrefixes
            type in types && (id == null || prefixes.isNullOrEmpty() || prefixes.any { id.startsWith(it) })
        }
    }

    /** "Films", "Series", "Live TV", "Subtitles", "Catalogues", for the Install sheet. */
    val offers: List<String> get() = buildList {
        val streams = resources.any { it.name == "stream" }
        if (streams && "movie" in types) add("Films")
        if (streams && "series" in types) add("Series")
        if (streams && ("tv" in types || "channel" in types)) add("Live TV")
        if (resources.any { it.name == "subtitles" }) add("Subtitles")
        if (catalogs.isNotEmpty()) add("Catalogues")
        if (resources.any { it.name == "meta" } && !streams) add("Details")
    }

    companion object {
        fun parse(e: JsonElement): StremioManifest {
            val id = requireNotNull(e.str("id")) { "This is not an add-on manifest" }
            val resources = e.list("resources").mapNotNull { r ->
                when (r) {
                    is JsonPrimitive -> r.contentOrNull?.let { StremioResource(it, null, null) }
                    else -> r.str("name")?.let { StremioResource(it, r.child("types")?.let { _ -> r.strings("types") }, r.child("idPrefixes")?.let { _ -> r.strings("idPrefixes") }) }
                }
            }
            val catalogs = e.list("catalogs").mapNotNull { c ->
                val type = c.str("type") ?: return@mapNotNull null
                val cid = c.str("id") ?: return@mapNotNull null
                val extras = c.list("extra").mapNotNull { it.str("name") } + c.strings("extraSupported")
                val required = c.list("extra").filter { it.bool("isRequired") == true }.mapNotNull { it.str("name") } + c.strings("extraRequired")
                val options = c.list("extra").mapNotNull { x -> x.str("name")?.let { it to x.strings("options") } }.filter { it.second.isNotEmpty() }.toMap()
                StremioCatalog(type, cid, c.str("name") ?: cid, extras.distinct(), required.distinct(), options)
            }
            val hints = e.child("behaviorHints")
            return StremioManifest(id, e.str("name") ?: id, e.str("version"), e.str("description"), e.str("logo"), e.strings("types"),
                e.child("idPrefixes")?.let { e.strings("idPrefixes") }, resources, catalogs,
                hints.bool("configurationRequired") == true, hints.bool("p2p") == true, hints.bool("adult") == true)
        }
    }
}

/**
 * A Stremio- or Nuvio-protocol add-on: a base URL answering GETs with JSON (`/manifest.json`, `/catalog/...`, `/meta/...`,
 * `/stream/...`, `/subtitles/...`). Nothing is downloaded or run.
 */
class StremioClient(private val http: WatchHttp, manifestUrl: String) {
    val manifestUrl: String = normalise(manifestUrl)
    private val manifestAddress = this.manifestUrl.toHttpUrl()
    val base: String = manifestAddress.newBuilder().query(null).fragment(null).build().toString().removeSuffix("/manifest.json")
    private fun resource(path: String): String = (base + path).toHttpUrl().newBuilder().encodedQuery(manifestAddress.encodedQuery).build().toString()

    suspend fun manifest(ttlMs: Long = 6 * WatchHttp.HOUR): StremioManifest = StremioManifest.parse(http.getJson(manifestUrl, ttlMs = ttlMs))

    suspend fun catalog(type: String, id: String, extra: Map<String, String> = emptyMap()): List<JsonElement> =
        http.getJson(resource("/catalog/${seg(type)}/${seg(id)}${extraPath(extra)}.json"), ttlMs = WatchHttp.HOUR).list("metas")

    suspend fun meta(type: String, id: String): JsonElement? = http.getJson(resource("/meta/${seg(type)}/${seg(id)}.json"), ttlMs = 6 * WatchHttp.HOUR).child("meta")

    suspend fun streams(type: String, id: String): List<JsonElement> =
        http.getJson(resource("/stream/${seg(type)}/${seg(id)}.json"), ttlMs = 10 * 60_000L).list("streams")

    suspend fun subtitles(type: String, id: String, extra: Map<String, String> = emptyMap()): List<JsonElement> =
        http.getJson(resource("/subtitles/${seg(type)}/${seg(id)}${extraPath(extra)}.json"), ttlMs = WatchHttp.HOUR).list("subtitles")

    private fun seg(id: String) = id.urlSegment()
    private fun extraPath(extra: Map<String, String>) =
        if (extra.isEmpty()) "" else "/" + extra.entries.joinToString("&") { (k, v) -> "${k.urlSegment()}=${v.urlSegment()}" }

    companion object {
        /** `stremio://host/x/manifest.json` and plain https links, with or without `/manifest.json`, to one https form. */
        fun normalise(link: String): String {
            var url = link.trim()
            if (url.startsWith("stremio://", true)) url = "https://" + url.substringAfter("://")
            if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*:/").containsMatchIn(url) && !url.startsWith("//")) url = "https://$url"
            val normalized = requireNotNull(com.cosmotune.watch.plugins.SourceAddress.parse(url)) { "Use a valid HTTP or HTTPS add-on address." }
            require(normalized.headers.isEmpty() && '|' !in url) { "Add-on installation links cannot contain inline request headers." }
            val parsed = normalized.url.toHttpUrl()
            val path = parsed.encodedPath.trimEnd('/')
            return parsed.newBuilder().encodedPath(if (path.endsWith("/manifest.json")) path else "$path/manifest.json").fragment(null).build().toString()
        }

        private fun String.urlSegment(): String = java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20").replace("%3A", ":")
    }
}

internal fun JsonElement?.textList(name: String): List<String> = obj?.get(name).arr?.mapNotNull { it.prim?.contentOrNull }.orEmpty()
