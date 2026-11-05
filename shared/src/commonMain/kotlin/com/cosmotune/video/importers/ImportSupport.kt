package com.cosmotune.video.importers

import com.cosmotune.video.library.Subscription
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What a channel address names: the channel itself, or a name the source has to look up. */
internal sealed interface ChannelRef {
    /** A `/channel/UC...` address: the id. */
    data class Id(val id: String) : ChannelRef

    /** A `/user/<name>`, `/c/<name>` or `/@handle` address, normalised to `https://<host>/...`. */
    data class Unresolved(val address: String) : ChannelRef
}

/** An address cut into its host, its path segments and its query. */
internal data class UrlParts(val host: String, val segments: List<String>, val query: String) {
    /** The value of query parameter [name], or null. */
    fun param(name: String): String? {
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0 && pair.substring(0, eq) == name) return pair.substring(eq + 1)
        }
        return null
    }
}

/** Small helpers the importers share: ids, addresses, times, XML text and JSON access. Common code only (no `java.*`). */
internal object ImportSupport {
    /** Real channel ids are `UC` and 22 more characters (the fake ids of `FakeVideoSource` are shorter on purpose). */
    private const val CHANNEL_ID_LENGTH = 24
    private const val VIDEO_ID_LENGTH = 11

    /** The host used for an address that has none. */
    private const val DEFAULT_HOST = "www.youtube.com"

    fun isIdChar(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    /** Whether [s] is a channel id: `UC` and 22 characters of `[a-zA-Z0-9_-]`. */
    fun isChannelId(s: String): Boolean = s.length == CHANNEL_ID_LENGTH && s.startsWith("UC") && s.all(::isIdChar)

    /** Whether [s] is a video id: 11 characters of `[a-zA-Z0-9_-]`. */
    fun isVideoId(s: String): Boolean = s.length == VIDEO_ID_LENGTH && s.all(::isIdChar)

    /** The first channel id (`UC[a-zA-Z0-9_-]{22}`, not part of a longer run of id characters) in [text], or null. */
    fun channelIdIn(text: String): String? {
        var i = text.indexOf("UC")
        while (i >= 0) {
            val end = i + CHANNEL_ID_LENGTH
            if (end <= text.length &&
                (i == 0 || !isIdChar(text[i - 1])) &&
                (end == text.length || !isIdChar(text[end])) &&
                isChannelId(text.substring(i, end))
            ) {
                return text.substring(i, end)
            }
            i = text.indexOf("UC", i + 1)
        }
        return null
    }

    /**
     * A video's picture from its id, for rows that have only an id (history from Takeout, playlists from Takeout), so they have a
     * picture before the video is opened. The real thumbnail replaces it when the video is watched.
     */
    fun thumbnailOf(videoId: String): String = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    /** Cuts [url] into host, path segments and query; a relative address has an empty host. */
    fun splitUrl(url: String): UrlParts {
        var rest = url.trim()
        val scheme = rest.indexOf("://")
        rest = when {
            scheme in 1..10 -> rest.substring(scheme + 3)
            rest.startsWith("//") -> rest.substring(2)
            else -> rest
        }
        val fragment = rest.indexOf('#')
        if (fragment >= 0) rest = rest.substring(0, fragment)
        val q = rest.indexOf('?')
        val query = if (q >= 0) rest.substring(q + 1) else ""
        if (q >= 0) rest = rest.substring(0, q)
        val slash = rest.indexOf('/')
        val host = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash + 1)
        return UrlParts(host.lowercase(), path.split('/').filter { it.isNotEmpty() }, query)
    }

    /** What the channel address [url] names, or null when it is not a channel address. */
    fun channelRefOf(url: String): ChannelRef? {
        val parts = splitUrl(url)
        val first = parts.segments.getOrNull(0) ?: return null
        val second = parts.segments.getOrNull(1)
        val host = parts.host.ifEmpty { DEFAULT_HOST }
        return when {
            first == "channel" && second != null -> if (isChannelId(second)) ChannelRef.Id(second) else null
            (first == "user" || first == "c") && second != null -> ChannelRef.Unresolved("https://$host/$first/$second")
            first.startsWith("@") && first.length > 1 -> ChannelRef.Unresolved("https://$host/$first")
            else -> null
        }
    }

    /** The video id in a watch, short, embed, live or short-link address, or null. Music's address is not a video here. */
    fun videoIdOf(url: String): String? {
        val parts = splitUrl(url)
        if (parts.host.startsWith("music.")) return null
        val first = parts.segments.getOrNull(0)
        val candidate = when {
            parts.host == "youtu.be" -> first
            first == "watch" -> parts.param("v")
            first == "shorts" || first == "embed" || first == "live" || first == "v" -> parts.segments.getOrNull(1)
            else -> null
        }
        return candidate?.takeIf(::isVideoId)
    }

    /** The playlist id of a playlist address (`...playlist?list=PL...`), or null. */
    fun playlistIdOf(url: String): String? = splitUrl(url).param("list")?.takeIf { it.isNotBlank() }

    /**
     * An instant from the date texts the files use, as milliseconds since 1970 (UTC): ISO-8601 (`2026-10-15T08:30:00.123Z`,
     * `...+05:30`) and Takeout's CSV form (`2018-07-06 20:16:24 UTC`); a date alone is midnight UTC. Null when it is neither.
     */
    fun parseTimeMs(raw: String): Long? {
        val s = raw.trim()
        if (s.length < 10) return null
        fun digits(from: Int, count: Int): Int? {
            if (from + count > s.length) return null
            var value = 0
            for (k in from until from + count) {
                val d = s[k] - '0'
                if (d !in 0..9) return null
                value = value * 10 + d
            }
            return value
        }
        val year = digits(0, 4) ?: return null
        val month = digits(5, 2) ?: return null
        val day = digits(8, 2) ?: return null
        if (s[4] != '-' || s[7] != '-' || month !in 1..12 || day !in 1..31) return null

        var hour = 0
        var minute = 0
        var second = 0
        var millis = 0
        var i = 10
        if (i < s.length && (s[i] == 'T' || s[i] == ' ')) {
            hour = digits(i + 1, 2) ?: return null
            if (s.getOrNull(i + 3) != ':') return null
            minute = digits(i + 4, 2) ?: return null
            i += 6
            if (s.getOrNull(i) == ':') {
                second = digits(i + 1, 2) ?: return null
                i += 3
                if (s.getOrNull(i) == '.' || s.getOrNull(i) == ',') {
                    var scale = 100
                    i += 1
                    while (i < s.length && s[i] in '0'..'9') {
                        millis += (s[i] - '0') * scale
                        scale /= 10
                        i += 1
                    }
                }
            }
            if (hour > 23 || minute > 59 || second > 60) return null
        }
        val zone = s.substring(i).trim()
        val offsetMinutes = when {
            zone.isEmpty() || zone == "Z" || zone.equals("UTC", ignoreCase = true) || zone == "GMT" -> 0
            zone[0] == '+' || zone[0] == '-' -> {
                val sign = if (zone[0] == '-') -1 else 1
                val body = zone.substring(1).replace(":", "")
                val hours = body.take(2).toIntOrNull() ?: return null
                val minutes = if (body.length >= 4) (body.substring(2, 4).toIntOrNull() ?: return null) else 0
                sign * (hours * 60 + minutes)
            }
            else -> return null
        }
        val days = daysFromCivil(year, month, day)
        val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1000L + millis
    }

    /** Days from 1970-01-01 to the given date (proleptic Gregorian). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }

    /** [text] with the XML entities decoded: the five named ones and numeric references (`&#38;`, `&#x26;`). Unknown ones stay. */
    fun decodeXmlEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i += 1
                continue
            }
            val semi = text.indexOf(';', i + 1)
            val decoded = if (semi in (i + 2)..(i + 10)) decodeEntity(text.substring(i + 1, semi)) else null
            if (decoded == null) {
                out.append(c)
                i += 1
            } else {
                out.append(decoded)
                i = semi + 1
            }
        }
        return out.toString()
    }

    private fun decodeEntity(name: String): String? = when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> if (name.startsWith("#")) numericEntity(name.substring(1)) else null
    }

    private fun numericEntity(body: String): String? {
        val code = if (body.startsWith("x") || body.startsWith("X")) body.substring(1).toIntOrNull(16) else body.toIntOrNull()
        return when {
            code == null || code <= 0 || code > 0x10FFFF || code in 0xD800..0xDFFF -> null
            code < 0x10000 -> code.toChar().toString()
            else -> {
                val offset = code - 0x10000
                buildString {
                    append((0xD800 + (offset shr 10)).toChar())
                    append((0xDC00 + (offset and 0x3FF)).toChar())
                }
            }
        }
    }
}

/** This element as an object, or null. */
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

/** This element as an array, or null. */
internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

/** This element as text when it is a JSON string, else null. */
internal fun JsonElement?.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** This element as a whole number when it is a JSON number (or a string of digits), else null. */
internal fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.let { it.longOrNull ?: if (it.isString) it.content.trim().toLongOrNull() else null }

/**
 * Gathers the channels an importer finds: one [Subscription] per channel id (the first one in the file wins), the addresses
 * that still need a lookup, and the count of entries that gave neither. A repeat of a channel or an address counts as skipped.
 */
internal class SubscriptionCollector(private val nowMs: Long) {
    private val seen = HashSet<String>()
    private val seenAddresses = HashSet<String>()
    private val found = ArrayList<Subscription>()
    private val addresses = ArrayList<String>()

    /** How many entries gave nothing new. */
    var skipped: Int = 0
        private set

    /** Adds channel [id] (its name falls back to the id when the file gives none). */
    fun addChannel(id: String, name: String?, avatarUrl: String? = null, subscribedAtMs: Long? = null) {
        if (!seen.add(id)) {
            skipped += 1
            return
        }
        val shown = name?.trim().orEmpty().ifEmpty { id }
        found.add(Subscription(id, shown, avatarUrl?.takeIf { it.isNotBlank() }, subscribedAtMs ?: nowMs, null))
    }

    /** Adds a channel address that needs a lookup. */
    fun addAddress(address: String) {
        if (seenAddresses.add(address)) addresses.add(address) else skipped += 1
    }

    /** Counts one entry that gave nothing. */
    fun skip() {
        skipped += 1
    }

    /** Files the channel address [url] under an id or a lookup, or counts it as skipped. */
    fun addByAddress(url: String, name: String?, avatarUrl: String? = null) {
        when (val ref = ImportSupport.channelRefOf(url)) {
            is ChannelRef.Id -> addChannel(ref.id, name, avatarUrl)
            is ChannelRef.Unresolved -> addAddress(ref.address)
            null -> skip()
        }
    }

    /** What was gathered, with a reason when it is nothing. */
    fun preview(): ImportPreview =
        ImportPreview(subscriptions = found.toList(), unresolvedChannels = addresses.toList(), skipped = skipped).explainedIfEmpty()
}
