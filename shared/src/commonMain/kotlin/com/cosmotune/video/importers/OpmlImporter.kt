package com.cosmotune.video.importers

/**
 * OPML subscriptions (spec 2.7, 8.2), the form FreeTube, Invidious and Piped write:
 * `<opml version="1.1"><body><outline text="..."><outline text="Name" title="Name" type="rss" xmlUrl="...?channel_id=UC..."/>`.
 *
 * This is a tolerant scan, not an XML parser (common code has none): it walks the text for every `<outline` tag at any depth,
 * reads its attributes (quoted with either quote, names in any case, entities decoded), and takes the entries that have an
 * `xmlUrl`. The channel id is the first `UC[a-zA-Z0-9_-]{22}` in the `xmlUrl`; the name is `title`, else `text`, else the id.
 * A feed with no channel id (a playlist feed) is skipped. Folders (outlines with no `xmlUrl`) are not entries. Comments and
 * CDATA sections are passed over.
 */
object OpmlImporter {
    /** Reads the OPML text in [xml]. [nowMs] is the time the channels are followed at (the file has none). */
    fun parse(xml: String, nowMs: Long): ImportPreview {
        val text = xml.removePrefix("\uFEFF")
        val outlines = outlineAttributes(text)
        if (outlines == null) return ImportPreview.failed(ImportReason.UNREADABLE)
        val collector = SubscriptionCollector(nowMs)
        for (attributes in outlines) {
            val feed = attributes["xmlurl"] ?: continue
            val id = ImportSupport.channelIdIn(feed)
            if (id == null) {
                collector.skip()
                continue
            }
            val name = attributes["title"]?.takeIf { it.isNotBlank() } ?: attributes["text"]
            collector.addChannel(id, name)
        }
        return collector.preview()
    }

    /** The attributes (lower-case names) of each `<outline` tag, in order; null when the text has no `<opml` at all. */
    internal fun outlineAttributes(text: String): List<Map<String, String>>? {
        if (!text.contains("<opml", ignoreCase = true)) return null
        val found = ArrayList<Map<String, String>>()
        val n = text.length
        var i = 0
        while (i < n) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            when {
                text.startsWith("<!--", lt) -> i = skipPast(text, "-->", lt + 4)
                text.startsWith("<![CDATA[", lt) -> i = skipPast(text, "]]>", lt + 9)
                isOutlineStart(text, lt) -> i = readTag(text, lt + 8, found)
                else -> i = lt + 1
            }
        }
        return found
    }

    private fun skipPast(text: String, end: String, from: Int): Int {
        val at = text.indexOf(end, from)
        return if (at < 0) text.length else at + end.length
    }

    private fun isOutlineStart(text: String, lt: Int): Boolean {
        if (lt + 8 > text.length || !text.substring(lt + 1, lt + 8).equals("outline", ignoreCase = true)) return false
        val after = text.getOrNull(lt + 8) ?: return true
        return after.isWhitespace() || after == '/' || after == '>'
    }

    /** Reads the attributes of the tag whose name ends before [from]; adds them to [into]; returns the index after the tag. */
    private fun readTag(text: String, from: Int, into: MutableList<Map<String, String>>): Int {
        val attributes = LinkedHashMap<String, String>()
        val n = text.length
        var i = from
        while (i < n) {
            while (i < n && (text[i].isWhitespace() || text[i] == '/')) i += 1
            if (i >= n) break
            if (text[i] == '>') {
                i += 1
                break
            }
            val nameStart = i
            while (i < n && !text[i].isWhitespace() && text[i] != '=' && text[i] != '>' && text[i] != '/') i += 1
            val name = text.substring(nameStart, i).lowercase()
            while (i < n && text[i].isWhitespace()) i += 1
            var value = ""
            if (i < n && text[i] == '=') {
                i += 1
                while (i < n && text[i].isWhitespace()) i += 1
                if (i < n && (text[i] == '"' || text[i] == '\'')) {
                    val quote = text[i]
                    val close = text.indexOf(quote, i + 1)
                    if (close < 0) {
                        value = text.substring(i + 1)
                        i = n
                    } else {
                        value = text.substring(i + 1, close)
                        i = close + 1
                    }
                } else {
                    val valueStart = i
                    while (i < n && !text[i].isWhitespace() && text[i] != '>') i += 1
                    value = text.substring(valueStart, i)
                }
            }
            if (name.isNotEmpty() && name !in attributes) attributes[name] = ImportSupport.decodeXmlEntities(value)
        }
        into.add(attributes)
        return i
    }
}
