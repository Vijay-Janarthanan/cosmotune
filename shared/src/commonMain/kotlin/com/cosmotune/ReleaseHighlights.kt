package com.cosmotune

/**
 * The picture at the start of a highlight, named in the notes by one plain keyword (no emoji): `look`, `watch`, `live` and so on.
 * The apps draw each as a round tinted disc with its icon; a keyword they do not know is drawn as [NEW].
 */
enum class HighlightIcon(val keyword: String) {
    LOOK("look"),
    WATCH("watch"),
    LIVE("live"),
    CHANNELS("channels"),
    TIMER("timer"),
    PLAYBACK("playback"),
    LYRICS("lyrics"),
    AUDIO("audio"),
    PLAYLIST("playlist"),
    SYNC("sync"),
    CHECK("check"),
    READ("read"),
    PODCAST("podcast"),
    TV("tv"),
    WEAR("wear"),
    DOWNLOAD("download"),
    SEARCH("search"),
    TOGETHER("together"),
    SETTINGS("settings"),
    NEW("new"),
    ;

    companion object {
        /** The icon named [keyword], or null when there is none of that name. */
        fun of(keyword: String?): HighlightIcon? = keyword?.trim()?.lowercase()?.let { k -> entries.firstOrNull { it.keyword == k } }
    }
}

/**
 * One line of what a release brings, as a listener reads it: an [icon], a short [title] ("Reliable sleep timer") and one plain
 * sentence ([text]). A line taken from an older release's notes, which had no highlights, has no [title].
 */
data class ReleaseHighlight(val icon: HighlightIcon, val title: String?, val text: String)

/**
 * What the update and "what's new" cards show of a release: its highlights, never the whole notes.
 *
 * A release's notes (`docs/releases/<version>.md`, which the Release workflow publishes whole as the GitHub release body) start
 * with a `## Highlights` section of at most [MAX] items, each one line:
 *
 * ```
 * ## Highlights
 *
 * - <!-- icon: timer --> **Reliable sleep timer** — Stops on time, even with the screen off.
 * ```
 *
 * The HTML comment names the [HighlightIcon] (GitHub does not show it on the release page), the bold words are the title (two to
 * four words) and what follows the dash is the sentence. Notes without that section, the releases before it existed, give their
 * first [FALLBACK] top-level bullets instead (or, with no bullets, the first sentences of their paragraphs), stripped of their
 * Markdown and links and cut to one line each. Pure Kotlin, so `ReleaseHighlightsTest` runs it on the JVM.
 */
object ReleaseHighlights {

    /** The most highlights a release shows. */
    const val MAX = 5

    /** How many lines an older release's notes give when they have no highlights. */
    const val FALLBACK = 3

    /** The longest a fallback line runs before it is cut at a word. */
    const val FALLBACK_LENGTH = 96

    private val heading = Regex("""^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val topBullet = Regex("""^[-*+]\s+(.*)$""")
    private val anyBullet = Regex("""^\s*([-*+]|\d+[.)])\s+""")
    private val comment = Regex("""<!--(.*?)-->""", RegexOption.DOT_MATCHES_ALL)
    private val iconComment = Regex("""^<!--\s*(?:icon\s*:\s*)?([A-Za-z-]+)\s*-->\s*""", RegexOption.IGNORE_CASE)
    private val boldLead = Regex("""^(\*\*|__)(.+?)\1\s*(.*)$""")
    private val separator = Regex("""^\s*(?:—|–|-|:)\s*""")
    private val dashSplit = Regex("""\s+[—–]\s+""")
    private val linkOnly = Regex("""^\s*(?:[-*+]\s+)?\[[^\]]*]\([^)]*\)\s*$""")

    /** The highlights of [markdown] when it has them, else its fallback lines; nothing for no notes. */
    fun of(markdown: String?): List<ReleaseHighlight> {
        if (markdown.isNullOrBlank()) return emptyList()
        return highlights(markdown) ?: fallback(markdown)
    }

    /**
     * The items of the `## Highlights` section of [markdown], at most [MAX]; null when the notes have no such section (an older
     * release). An item with no sentence is left out.
     */
    fun highlights(markdown: String): List<ReleaseHighlight>? {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { line ->
            heading.matchEntire(line)?.let { it.groupValues[1].length == 2 && it.groupValues[2].equals("Highlights", ignoreCase = true) } == true
        }
        if (start < 0) return null
        val items = mutableListOf<ReleaseHighlight>()
        for (line in lines.drop(start + 1)) {
            if (heading.matches(line)) break
            if (line.isBlank() || comment.matches(line.trim())) continue
            // The section is its list: a paragraph after the items is where the rest of the notes begin.
            val item = topBullet.matchEntire(line.trimEnd())?.groupValues?.get(1) ?: if (items.isEmpty()) continue else break
            parseItem(item)?.let(items::add)
            if (items.size == MAX) break
        }
        return items
    }

    private fun parseItem(raw: String): ReleaseHighlight? {
        var rest = raw.trim()
        var icon: HighlightIcon? = null
        iconComment.find(rest)?.let { match ->
            icon = HighlightIcon.of(match.groupValues[1])
            rest = rest.substring(match.range.last + 1)
        }
        val title: String?
        val text: String
        val bold = boldLead.matchEntire(rest)
        if (bold != null) {
            title = plain(bold.groupValues[2])
            text = plain(bold.groupValues[3].replaceFirst(separator, ""))
        } else {
            val parts = rest.split(dashSplit, limit = 2)
            title = if (parts.size == 2) plain(parts[0]) else null
            text = plain(parts.last())
        }
        if (text.isEmpty()) return null
        return ReleaseHighlight(icon ?: HighlightIcon.NEW, title?.takeIf { it.isNotEmpty() }, text)
    }

    /**
     * An older release's lines: its first [FALLBACK] top-level bullets, or, when it has fewer, the first sentences of its
     * paragraphs after them, each as plain text on one line. A line that is only a link ("Download and installation guide") is
     * not one of them.
     */
    fun fallback(markdown: String): List<ReleaseHighlight> {
        val bullets = mutableListOf<String>()
        val paragraphs = mutableListOf<String>()
        var fence = false
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence || line.isBlank() || heading.matches(line) || linkOnly.matches(line)) continue
            val bullet = topBullet.matchEntire(line.trimEnd())
            when {
                bullet != null -> bullets += bullet.groupValues[1]
                anyBullet.containsMatchIn(line) || line.startsWith(" ") || line.startsWith("\t") -> Unit
                line.trimStart().startsWith(">") || line.trimStart().startsWith("|") -> Unit
                else -> paragraphs += line
            }
        }
        return (bullets + paragraphs).asSequence()
            .map(::oneLine)
            .filter { it.isNotEmpty() }
            .take(FALLBACK)
            .map { ReleaseHighlight(HighlightIcon.NEW, null, it) }
            .toList()
    }

    /** [markdown] as one plain line: its first sentence when it has more, cut at a word with an ellipsis past [FALLBACK_LENGTH]. */
    fun oneLine(markdown: String): String {
        val text = plain(markdown)
        if (text.isEmpty()) return text
        val sentence = Regex("""^(.+?[.!?])\s+\p{Lu}""").find(text)?.groupValues?.get(1) ?: text
        if (sentence.length <= FALLBACK_LENGTH) return sentence
        val cut = sentence.substring(0, FALLBACK_LENGTH).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ', '—', '–', '-')
        return "$cut…"
    }

    /**
     * [markdown] with its Markdown taken away: comments and pictures dropped, links to their words, bare addresses dropped,
     * emphasis, code marks and heading marks removed, and the spaces closed up.
     */
    fun plain(markdown: String): String = markdown
        .replace(comment, "")
        .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
        .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""\[([^\]]*)]\[[^\]]*]"""), "$1")
        .replace(Regex("""<(?:https?://|mailto:)[^>]*>"""), "")
        .replace(Regex("""(?<![\w(])https?://\S+"""), "")
        .replace(Regex("""<[^>]+>"""), "")
        .replace(Regex("""^\s*#+\s*"""), "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replace(Regex("""(?<![\w*])\*(?!\s)([^*]+?)(?<!\s)\*(?![\w*])"""), "$1")
        .replace(Regex("""(?<![\w_])_(?!\s)([^_]+?)(?<!\s)_(?![\w_])"""), "$1")
        .replace(Regex("""\\([\\`*_{}\[\]()#+\-.!>])"""), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .let { if (it.matches(Regex("""^[\p{P}\s]*$"""))) "" else it }
}
