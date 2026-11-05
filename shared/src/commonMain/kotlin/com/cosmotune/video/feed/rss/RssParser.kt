package com.cosmotune.video.feed.rss

/**
 * One entry of a channel's Atom feed (spec 2.7): the video, its channel, the title, the page it links to, when it was
 * published and last updated, the thumbnail, the views, and whether the link is a Shorts address (`/shorts/<id>`: the only
 * sign of a Short in the feed, spec 8.3). Every part but the video id and the title may be missing.
 */
data class RssEntry(
    val videoId: String,
    val channelId: String?,
    val title: String,
    val link: String?,
    val publishedMs: Long?,
    val updatedMs: Long?,
    val thumbnailUrl: String?,
    val views: Long?,
    val isShortLink: Boolean,
)

/**
 * A tolerant scan of a channel's or a playlist's Atom feed (plan task Y-9, step 1; spec 2.7). It is not an XML parser: it cuts
 * out the `<entry>` blocks and reads the handful of elements Video mode needs, so a feed with another prefix order, extra
 * elements or a truncated tail still gives the entries that are whole. Anything that is not a feed (the server's 404 page)
 * gives an empty list; nothing here throws. Pure Kotlin, so it is common code.
 */
object RssParser {
    /** The entries of [xml] in the order of the feed. */
    fun parse(xml: String): List<RssEntry> {
        val entries = ArrayList<RssEntry>()
        var from = 0
        while (true) {
            val open = findOpening(xml, "entry", from) ?: break
            val bodyStart = xml.indexOf('>', open)
            if (bodyStart < 0) break
            val close = xml.indexOf("</entry>", bodyStart)
            if (close < 0) break
            // `<entry/>` has no body to read.
            if (xml[bodyStart - 1] != '/') {
                readEntry(xml.substring(bodyStart + 1, close))?.let { entries += it }
            }
            from = close + "</entry>".length
        }
        return entries
    }

    /** Whether [xml] has the root element of a feed (so an HTML error page that arrived with status 200 is not taken for an empty feed). */
    fun looksLikeFeed(xml: String): Boolean = findOpening(xml, "feed", 0) != null

    private fun readEntry(block: String): RssEntry? {
        val videoId = (text(block, "yt:videoId") ?: text(block, "id")?.trim()?.takeIf { it.startsWith(VIDEO_ID_PREFIX) }?.removePrefix(VIDEO_ID_PREFIX))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val link = alternateLink(block)
        return RssEntry(
            videoId = videoId,
            channelId = text(block, "yt:channelId")?.trim()?.takeIf { it.isNotEmpty() },
            title = (text(block, "title") ?: "").replace(LINE_BREAKS, " ").trim(),
            link = link,
            publishedMs = parseIsoMs(text(block, "published")),
            updatedMs = parseIsoMs(text(block, "updated")),
            thumbnailUrl = tagAttribute(block, "media:thumbnail", "url")?.takeIf { it.isNotEmpty() },
            views = tagAttribute(block, "media:statistics", "views")?.trim()?.toLongOrNull(),
            isShortLink = link?.contains("/shorts/") == true,
        )
    }

    // ---- elements and attributes ----

    private val LINE_BREAKS = Regex("[\r\n\t]+")

    /** The `<id>` of an entry reads `yt:video:<id>`; it stands in for a missing `yt:videoId`. */
    private const val VIDEO_ID_PREFIX = "yt:video:"

    /** The index of `<name` where it opens an element (followed by `>`, `/` or white space), at or after [from]. */
    private fun findOpening(xml: String, name: String, from: Int): Int? {
        val needle = "<$name"
        var at = xml.indexOf(needle, from)
        while (at >= 0) {
            val next = xml.getOrNull(at + needle.length)
            if (next == '>' || next == '/' || next == ' ' || next == '\n' || next == '\r' || next == '\t') return at
            at = xml.indexOf(needle, at + needle.length)
        }
        return null
    }

    /** The decoded text of the first `<name>...</name>` in [block], or null when there is none (or it is empty-element). */
    private fun text(block: String, name: String): String? {
        val open = findOpening(block, name, 0) ?: return null
        val end = block.indexOf('>', open)
        if (end < 0 || block[end - 1] == '/') return null
        val close = block.indexOf("</$name>", end)
        if (close < 0) return null
        return decodeText(block.substring(end + 1, close))
    }

    /** The text of an element: CDATA taken as it is, anything else with its entities decoded. */
    private fun decodeText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("<![CDATA[") && trimmed.endsWith("]]>")) return trimmed.substring("<![CDATA[".length, trimmed.length - "]]>".length)
        return decodeEntities(raw)
    }

    /** The opening tag `<name ...>` of the first [name] element, without the angle brackets, or null. */
    private fun openingTag(block: String, name: String, from: Int = 0): Pair<String, Int>? {
        val open = findOpening(block, name, from) ?: return null
        val end = block.indexOf('>', open)
        if (end < 0) return null
        return block.substring(open + 1 + name.length, end) to end
    }

    private fun tagAttribute(block: String, name: String, attribute: String): String? {
        val (tag, _) = openingTag(block, name) ?: return null
        return attribute(tag, attribute)
    }

    /** The `href` of the first `<link>` that is the alternate (or says nothing about `rel`, which Atom reads as alternate). */
    private fun alternateLink(block: String): String? {
        var from = 0
        while (true) {
            val (tag, end) = openingTag(block, "link", from) ?: return null
            val rel = attribute(tag, "rel")
            if (rel == null || rel == "alternate") {
                val href = attribute(tag, "href")
                if (!href.isNullOrEmpty()) return href
            }
            from = end
        }
    }

    /** The value of attribute [name] in the text of a tag (double or single quotes), entities decoded; null when absent. */
    private fun attribute(tag: String, name: String): String? {
        var at = tag.indexOf(name)
        while (at >= 0) {
            val before = if (at == 0) ' ' else tag[at - 1]
            var i = at + name.length
            if (before.isWhitespace()) {
                while (i < tag.length && tag[i].isWhitespace()) i++
                if (i < tag.length && tag[i] == '=') {
                    i++
                    while (i < tag.length && tag[i].isWhitespace()) i++
                    val quote = tag.getOrNull(i)
                    if (quote == '"' || quote == '\'') {
                        val end = tag.indexOf(quote, i + 1)
                        if (end > 0) return decodeEntities(tag.substring(i + 1, end))
                    }
                }
            }
            at = tag.indexOf(name, at + name.length)
        }
        return null
    }

    // ---- entities ----

    /** `&amp; &lt; &gt; &quot; &apos;` and `&#N;` or `&#xH;`; anything else that looks like an entity is left as it is. */
    internal fun decodeEntities(text: String): String {
        if (text.indexOf('&') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '&') {
                out.append(c)
                i++
                continue
            }
            val semicolon = text.indexOf(';', i + 1)
            val decoded = if (semicolon in (i + 2)..(i + 12)) entity(text.substring(i + 1, semicolon)) else null
            if (decoded == null) {
                out.append(c)
                i++
            } else {
                out.append(decoded)
                i = semicolon + 1
            }
        }
        return out.toString()
    }

    private fun entity(name: String): String? = when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        else -> if (name.startsWith("#")) codePointText(name) else null
    }

    private fun codePointText(name: String): String? {
        val hex = name.startsWith("#x") || name.startsWith("#X")
        val digits = name.substring(if (hex) 2 else 1)
        if (digits.isEmpty() || digits.length > 8) return null
        val value = digits.toIntOrNull(if (hex) 16 else 10) ?: return null
        return when {
            value <= 0 || value > 0x10FFFF || value in 0xD800..0xDFFF -> null
            value < 0x10000 -> value.toChar().toString()
            else -> {
                val v = value - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }
        }
    }

    // ---- dates ----

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d+))?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?)?$""")

    /** An ISO-8601 date or date and time with an optional zone (no zone is UTC) as epoch milliseconds, or null when it does not read. */
    internal fun parseIsoMs(text: String?): Long? {
        val match = ISO.matchEntire(text?.trim() ?: return null) ?: return null
        val g = match.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        val hour = g[4].ifEmpty { "0" }.toInt()
        val minute = g[5].ifEmpty { "0" }.toInt()
        val second = g[6].ifEmpty { "0" }.toInt()
        if (month !in 1..12 || day !in 1..daysInMonth(year, month) || hour > 23 || minute > 59 || second > 60) return null
        val millis = g[7].take(3).padEnd(3, '0').toInt()
        val offsetMinutes = when (val zone = g[8]) {
            "", "Z", "z" -> 0
            else -> {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val h = digits.substring(0, 2).toInt()
                val m = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
                if (h > 23 || m > 59) return null
                sign * (h * 60 + m)
            }
        }
        val seconds = daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L + second - offsetMinutes * 60L
        return seconds * 1_000L + millis
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Days from 1970-01-01 to the given civil date (proleptic Gregorian; Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
