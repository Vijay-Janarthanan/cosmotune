package com.cosmotune.watch.live

import com.cosmotune.watch.http.child
import com.cosmotune.watch.http.list
import com.cosmotune.watch.http.str
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** One headline of one outlet's feed. */
data class NewsItem(val title: String, val link: String, val outlet: String, val publishedMs: Long, val summary: String? = null, val imageUrl: String? = null)

/** A story Wikipedia's editors list "In the news": its text, the page it links to first, and a picture. */
data class ItnStory(val text: String, val link: String?, val imageUrl: String?)

/**
 * The world's news for the Spotlight: the public feeds of BBC World, The Guardian World, Al Jazeera, France 24 and DW, read
 * defensively (RSS 2.0 or RDF; an item without a title, an https link or a date is left out), the same story across outlets put
 * together by its words, and ranked by how many outlets carry it. Wikipedia "In the news" marks the major ones.
 */
object NewsFeeds {
    /** An outlet's world feed. */
    data class Feed(val outlet: String, val url: String)

    val WORLD = listOf(
        Feed("BBC", "https://feeds.bbci.co.uk/news/world/rss.xml"),
        Feed("Guardian", "https://www.theguardian.com/world/rss"),
        Feed("Al Jazeera", "https://www.aljazeera.com/xml/rss/all.xml"),
        Feed("France 24", "https://www.france24.com/en/rss"),
        Feed("DW", "https://rss.dw.com/rdf/rss-en-world"),
    )

    private const val DAY = 86_400_000L

    /** A feed's items of the last [maxAgeMs]; never throws (a broken feed is no items). */
    fun parseRss(xml: String, outlet: String, nowMs: Long, maxAgeMs: Long = DAY): List<NewsItem> = runCatching {
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) return emptyList()
        val factory = DocumentBuilderFactory.newInstance().apply {
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val parser = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        val items = parser.parse(InputSource(StringReader(xml))).getElementsByTagName("item")
        (0 until items.length).mapNotNull { index ->
            val item = items.item(index) as? Element ?: return@mapNotNull null
            fun field(name: String) = item.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
            val link = field("link").toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString() ?: return@mapNotNull null
            val date = dateOf(field("pubDate").ifEmpty { field("dc:date") })?.takeIf { nowMs - it in -3_600_000L..maxAgeMs } ?: return@mapNotNull null
            val title = clean(field("title")).take(240).ifBlank { return@mapNotNull null }
            NewsItem(title, link, outlet, date, clean(field("description")).take(400).ifBlank { null }, image(item))
        }.distinctBy { it.link }
    }.getOrDefault(emptyList())

    private fun dateOf(text: String): Long? {
        if (text.isBlank()) return null
        return runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: utcMs(text)
    }

    /** The biggest picture an item names: `media:content` or `media:thumbnail` (the widest), else an image enclosure. */
    private fun image(item: Element): String? {
        val candidates = listOf("media:content", "media:thumbnail").flatMap { tag ->
            val nodes = item.getElementsByTagName(tag)
            (0 until nodes.length).mapNotNull { i -> (nodes.item(i) as? Element)?.let { e -> e.getAttribute("url").takeIf { it.isNotBlank() }?.let { it to (e.getAttribute("width").toIntOrNull() ?: 0) } } }
        }
        val best = candidates.maxByOrNull { it.second }?.first
            ?: (item.getElementsByTagName("enclosure").item(0) as? Element)?.takeIf { it.getAttribute("type").startsWith("image") }?.getAttribute("url")
        // BBC's thumbnails are 240 px; the same picture comes at 800.
        return best?.takeIf { it.startsWith("https://") }?.replace("/ace/standard/240/", "/ace/standard/800/")
    }

    /** Text without its HTML: tags gone, entities read, spaces made single. */
    fun clean(html: String): String = html.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace(Regex("\\s+"), " ").trim()

    private val STOP = setOf(
        "the", "and", "for", "with", "from", "that", "this", "after", "over", "into", "amid", "says", "said", "say", "will", "would", "could",
        "has", "have", "had", "are", "was", "were", "its", "his", "her", "their", "they", "them", "who", "what", "why", "how", "when", "where",
        "new", "not", "but", "out", "off", "about", "than", "more", "most", "been", "being", "can", "may", "now", "live", "video", "watch",
        "news", "world", "latest", "update", "updates", "first", "last", "one", "two", "three", "year", "years", "day", "days", "week",
    )

    /** The words that say what a headline is about: lower case, no punctuation, no small words, a plural's "s" dropped. */
    fun words(title: String): Set<String> = title.lowercase(Locale.ROOT).replace('’', '\'').split(Regex("[^\\p{L}\\p{N}]+"))
        .map { it.removeSuffix("'s") }
        .filter { (it.length >= 3 || it.any(Char::isDigit)) && it !in STOP }
        .map { w -> if (w.length > 4 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w }
        .map { w -> if (w.length > 6 && w.endsWith("ed")) w.dropLast(2) else w }
        .toSet()

    /** Words every other story has ("attack", "Russian", "deal"): they count half when two headlines are compared. */
    private val WEAK = setOf(
        "attack", "strike", "kill", "killed", "dead", "death", "missing", "drone", "war", "deal", "talk", "election", "vote", "russian", "russia",
        "ukraine", "ukrainian", "israel", "israeli", "gaza", "trump", "president", "minister", "government", "police", "court", "fire",
        "china", "chinese", "leader", "official", "people", "city", "state", "country", "report",
    )

    /**
     * Whether two headlines tell the same story: three words in common making two fifths of the shorter, and not only the words every
     * story has ("Russian drone attack" alone is not one story); or two long distinctive words making half.
     */
    fun sameStory(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a intersect b
        val overlap = shared.size.toDouble() / minOf(a.size, b.size)
        val weight = shared.sumOf { if (it in WEAK) 0.5 else 1.0 }
        return (shared.size >= 3 && overlap >= 0.4 && weight >= 2.5) || (shared.size == 2 && overlap >= 0.5 && shared.all { it.length >= 5 && it !in WEAK })
    }

    /** One story told by one or more outlets. [items] newest first. */
    class Story(val items: List<NewsItem>) {
        val outlets: List<String> get() = items.map { it.outlet }.distinct()
        val newestMs: Long get() = items.maxOf { it.publishedMs }
    }

    /** The items put together into stories (an item joins the first story one of whose headlines tells the same story). */
    fun group(items: List<NewsItem>): List<Story> {
        val groups = mutableListOf<MutableList<Pair<NewsItem, Set<String>>>>()
        items.sortedByDescending { it.publishedMs }.forEach { item ->
            val w = words(item.title)
            val home = groups.firstOrNull { g -> g.any { sameStory(it.second, w) } }
            if (home != null) home += item to w else groups += mutableListOf(item to w)
        }
        return groups.map { g -> Story(g.map { it.first }) }
    }

    /** Most outlets first, then a story in the news on Wikipedia, then the newest. */
    fun rank(stories: List<Story>, itn: List<ItnStory>): List<Story> {
        val itnWords = itn.map { words(it.text) }
        return stories.sortedWith(compareByDescending<Story> { it.outlets.size }.thenByDescending { s -> major(s, itnWords) }.thenByDescending { it.newestMs })
    }

    private fun major(story: Story, itnWords: List<Set<String>>): Boolean =
        story.items.any { item -> val w = words(item.title); itnWords.any { (it intersect w).size >= 2 } }

    /** The ranked stories as Spotlight entries: the first outlet's headline (one with a picture first), all its outlets, never live. */
    fun toEvents(stories: List<Story>, itn: List<ItnStory>, nowMs: Long, limit: Int = 25): List<LiveEvent> {
        val itnWords = itn.map { words(it.text) }
        return rank(stories, itn).take(limit).map { s ->
            val order = WORLD.map { it.outlet }
            val lead = s.items.sortedWith(compareBy<NewsItem>({ if (it.imageUrl == null) 1 else 0 }, { order.indexOf(it.outlet).let { i -> if (i < 0) 99 else i } })).first()
            LiveEvent(
                id = "news:" + lead.link.substringAfter("://").take(160), kind = LiveEventKind.NEWS, title = lead.title, statusText = lead.summary,
                status = LiveEventStatus.FEATURED, startMs = s.newestMs, imageUrl = lead.imageUrl ?: s.items.firstNotNullOfOrNull { it.imageUrl },
                source = lead.outlet, updatedAtMs = nowMs, link = lead.link, outlets = listOf(lead.outlet) + (s.outlets - lead.outlet),
                major = s.outlets.size >= 2 || major(s, itnWords),
            )
        }
    }

    /** Wikipedia's featured feed: its "In the news" stories (CC BY-SA; credited where shown). */
    fun parseItn(root: JsonElement): List<ItnStory> = root.list("news").mapNotNull { n ->
        val text = clean(n.str("story")?.replace(Regex("<!--.*?-->"), "") ?: return@mapNotNull null).ifBlank { return@mapNotNull null }
        val first = n.list("links").firstOrNull()
        ItnStory(text, first.child("content_urls").child("desktop").str("page")?.takeIf { it.startsWith("https://") },
            first.child("thumbnail").str("source")?.takeIf { it.startsWith("https://") })
    }

    /** Wikipedia's stories as Spotlight entries ("Wikipedia", never live). */
    fun itnEvents(itn: List<ItnStory>, nowMs: Long): List<LiveEvent> = itn.mapIndexed { i, s ->
        LiveEvent("news:itn:$i:${s.text.hashCode()}", LiveEventKind.NEWS, s.text, status = LiveEventStatus.FEATURED, imageUrl = s.imageUrl,
            source = "Wikipedia", updatedAtMs = nowMs, link = s.link, outlets = listOf("Wikipedia"), major = true, competition = "In the news")
    }

    /**
     * Google News for the viewer's country (their own tab, never the strip): its top stories of the last day, the publisher taken
     * off the headline. Links must be Google News' own.
     */
    fun parseCountry(xml: String, countryName: String, nowMs: Long, limit: Int = 12): List<LiveEvent> =
        parseRss(xml, "Google News", nowMs).asSequence()
            .filter { it.link.toHttpUrlOrNull()?.host == "news.google.com" }
            .map { item ->
                // "Headline - Publisher": the publisher is the outlet.
                val dash = item.title.lastIndexOf(" - ")
                val publisher = if (dash > 0) item.title.substring(dash + 3).trim() else "Google News"
                val title = if (dash > 0) item.title.substring(0, dash).trim() else item.title
                LiveEvent("news:local:" + (item.link.toHttpUrlOrNull()?.encodedPath ?: item.link), LiveEventKind.NEWS, title, competition = countryName,
                    status = LiveEventStatus.FEATURED, startMs = item.publishedMs, source = publisher, updatedAtMs = nowMs, link = item.link,
                    outlets = listOf(publisher), local = true)
            }.distinctBy { it.id }.take(limit).toList()
}

/**
 * Major events coming up, from Wikidata's public query service (keyless): national elections (instances of election, Q40231, for a
 * sovereign state, with a day set) and summit meetings (Q2761147, Q1072326), in the next 60 days, each with an English Wikipedia page. A date
 * known only to the year (Wikidata keeps it as the 1st of January) is left out.
 */
object MajorEvents {
    const val ENDPOINT = "https://query.wikidata.org/sparql"

    fun query(fromMs: Long, toMs: Long): String {
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() + "T00:00:00Z"
        return """
            SELECT DISTINCT ?e ?eLabel ?date ?countryLabel ?kind ?article WHERE {
              { ?e wdt:P31/wdt:P279* wd:Q40231 ; wdt:P585 ?date ; wdt:P1001 ?country . ?country wdt:P31 wd:Q3624078 . BIND("election" AS ?kind) }
              UNION
              { VALUES ?t { wd:Q2761147 wd:Q1072326 } ?e wdt:P31/wdt:P279* ?t ; wdt:P580 ?date . OPTIONAL { ?e wdt:P17 ?country . } BIND("summit" AS ?kind) }
              FILTER(?date >= "${day(fromMs)}"^^xsd:dateTime && ?date < "${day(toMs)}"^^xsd:dateTime)
              OPTIONAL { ?article schema:about ?e ; schema:isPartOf <https://en.wikipedia.org/> . }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } ORDER BY ?date LIMIT 100
        """.trimIndent()
    }

    /** The answer's events, soonest first: each once, with a page, a real name and a day; nothing outside [nowMs] to 60 days on. */
    fun parse(root: JsonElement, nowMs: Long): List<LiveEvent> = root.child("results").list("bindings").mapNotNull { b ->
        fun value(name: String) = b.child(name).str("value")
        val entity = value("e")?.substringAfterLast('/') ?: return@mapNotNull null
        val label = value("eLabel")?.takeUnless { it.matches(Regex("Q\\d+")) } ?: return@mapNotNull null
        val article = value("article")?.takeIf { it.startsWith("https://en.wikipedia.org/") } ?: return@mapNotNull null
        val dateText = value("date") ?: return@mapNotNull null
        if (dateText.substring(5).startsWith("01-01T")) return@mapNotNull null
        val date = utcMs(dateText) ?: return@mapNotNull null
        if (date < nowMs - DAY || date > nowMs + SpotlightWindows.EVENTS_AHEAD_MS) return@mapNotNull null
        if (label.startsWith("next ", ignoreCase = true)) return@mapNotNull null
        val kind = value("kind")
        LiveEvent("event:wd:$entity", LiveEventKind.EVENT, label, competition = value("countryLabel"), status = LiveEventStatus.UPCOMING,
            startMs = date, source = "Wikidata", updatedAtMs = nowMs, link = article, statusText = if (kind == "summit") "Summit" else "Election", major = true)
    }.distinctBy { it.id }.sortedBy { it.startMs }

    private const val DAY = 86_400_000L
}
