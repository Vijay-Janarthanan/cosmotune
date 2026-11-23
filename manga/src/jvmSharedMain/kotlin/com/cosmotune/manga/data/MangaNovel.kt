package com.cosmotune.manga.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** One block of a text chapter: a paragraph (its lines kept with `\n`), a heading, or a scene break. */
@Serializable
data class MangaNovelBlock(val kind: Kind, val text: String = "") {
    @Serializable enum class Kind { PARAGRAPH, HEADING, BREAK }
}

/** A chapter of a novel as the reader lays it out: its title and its blocks, in reading order. */
@Serializable
data class MangaNovelChapter(val title: String, val blocks: List<MangaNovelBlock>)

/** A source whose chapters are text (web novels, public-domain novels) rather than page images. */
interface MangaTextSource {
    suspend fun chapterText(chapter: MangaChapter): MangaNovelChapter
}

/**
 * Turns a chapter's HTML into plain blocks: paragraphs, headings and scene breaks, without scripts, styles, the site's own
 * chrome ([parse]'s `remove`), or lines the page's own style sheet hides (a class set to `display: none`, or an inline style).
 */
object MangaNovelText {
    const val MAX_BLOCKS = 20_000
    private const val MAX_CHARS = 2_000_000
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val skipped = setOf("script", "style", "noscript", "iframe", "form", "button", "nav", "svg", "select", "input", "textarea", "template", "img", "figure")
    private val headings = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val blocks = setOf("p", "div", "section", "article", "blockquote", "li", "ul", "ol", "dl", "dt", "dd", "pre", "table", "tr", "td", "th",
        "center", "header", "footer", "aside", "main", "body", "tbody", "thead", "caption", "address", "details", "summary")
    private val sceneBreak = Regex("^[\\s*•·~#=—–\\-_⁂◇◆○●]{1,40}$")
    private val hiddenRule = Regex("([^{}]+)\\{([^{}]*)}")
    private val hiddenValue = Regex("display\\s*:\\s*none", RegexOption.IGNORE_CASE)
    private val singleClass = Regex("^\\.([A-Za-z0-9_-]+)$")

    fun parse(html: String, title: String, remove: String = ""): MangaNovelChapter {
        val document = Jsoup.parse(html)
        val hidden = document.select("style").flatMap { style -> hiddenClasses(style.data()) }.toSet()
        document.select("style, script").remove()
        if (remove.isNotBlank()) runCatching { document.select(remove).remove() }
        hidden.forEach { name -> document.getElementsByClass(name).remove() }
        document.select("[style]").filter { hiddenValue.containsMatchIn(it.attr("style")) }.forEach { it.remove() }
        val out = ArrayList<MangaNovelBlock>()
        val buffer = StringBuilder()
        var chars = 0
        fun add(block: MangaNovelBlock) {
            if (out.size >= MAX_BLOCKS || chars >= MAX_CHARS) return
            if (block.kind == MangaNovelBlock.Kind.BREAK && (out.isEmpty() || out.last().kind == MangaNovelBlock.Kind.BREAK)) return
            out += block; chars += block.text.length
        }
        fun flush() {
            val text = tidy(buffer.toString())
            buffer.setLength(0)
            if (text.isEmpty()) return
            add(if (sceneBreak.matches(text) && text.any { !it.isWhitespace() }) MangaNovelBlock(MangaNovelBlock.Kind.BREAK) else MangaNovelBlock(MangaNovelBlock.Kind.PARAGRAPH, text))
        }
        fun walk(node: Node) {
            when (node) {
                is TextNode -> buffer.append(node.wholeText)
                is Element -> when (val name = node.normalName()) {
                    in skipped -> Unit
                    "br" -> buffer.append(LINE)
                    "hr" -> { flush(); add(MangaNovelBlock(MangaNovelBlock.Kind.BREAK)) }
                    in headings -> { flush(); tidy(node.text()).replace('\n', ' ').takeIf { it.isNotEmpty() }?.let { add(MangaNovelBlock(MangaNovelBlock.Kind.HEADING, it)) } }
                    in blocks -> { flush(); node.childNodes().forEach(::walk); flush() }
                    else -> node.childNodes().forEach(::walk)
                }
            }
        }
        walk(document.body())
        flush()
        while (out.lastOrNull()?.kind == MangaNovelBlock.Kind.BREAK) out.removeAt(out.lastIndex)
        return MangaNovelChapter(title.trim().take(500), out)
    }

    /** Classes a style sheet hides on their own (`.name{display:none}`); a rule that needs more than the class is left alone. */
    private fun hiddenClasses(css: String): List<String> = hiddenRule.findAll(css.take(200_000)).filter { hiddenValue.containsMatchIn(it.groupValues[2]) }
        .flatMap { rule -> rule.groupValues[1].split(',').mapNotNull { singleClass.find(it.trim())?.groupValues?.get(1) } }.toList()

    /** A line break the page asked for (`<br>`); a newline in the HTML source is only a space. */
    private const val LINE = '\u2028'

    /** Spaces collapsed, each line trimmed, empty lines dropped. */
    private fun tidy(text: String): String = text.replace('\u00a0', ' ').split(LINE)
        .map { it.replace(Regex("[\\s\\u200b]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    fun encode(chapter: MangaNovelChapter): String = json.encodeToString(MangaNovelChapter.serializer(), chapter)
    fun decode(text: String): MangaNovelChapter = json.decodeFromString(MangaNovelChapter.serializer(), text)
}

/** A place in a text chapter: the block and the character in it. It holds at any text size, font or page shape. */
data class MangaNovelPosition(val block: Int, val char: Int) : Comparable<MangaNovelPosition> {
    override fun compareTo(other: MangaNovelPosition) = compareValuesBy(this, other, { it.block }, { it.char })
}

/** One laid-out line of a block: its characters and where it stands, in pixels from the block's top. */
data class MangaNovelLine(val start: Int, val end: Int, val top: Int, val bottom: Int)

/** The part of a block a page shows, characters [start] to [end]. */
data class MangaNovelSlice(val block: Int, val start: Int, val end: Int)

data class MangaNovelPage(val slices: List<MangaNovelSlice>) {
    val start: MangaNovelPosition get() = slices.first().let { MangaNovelPosition(it.block, it.start) }
    fun contains(place: MangaNovelPosition) = slices.any { it.block == place.block && place.char >= it.start && (place.char < it.end || it.start == it.end) }
}

/** How text chapters look: the font, its size, the space between lines, the side margins, the colours, and paged or scrolling. */
@Serializable
data class MangaNovelStyle(
    val font: Font = Font.SERIF,
    val size: Int = 18,
    val lineSpacing: Float = 1.5f,
    /** Side margin in dp, past the reader's own edge. */
    val margin: Int = 16,
    val theme: Theme = Theme.AUTO,
    val paged: Boolean = false,
) {
    @Serializable enum class Font { SERIF, SANS, MONO }
    /** AUTO follows Cosmotune's light or dark look. */
    @Serializable enum class Theme { AUTO, LIGHT, SEPIA, DARK, BLACK }

    fun clamped() = copy(size = size.coerceIn(SIZES), lineSpacing = lineSpacing.coerceIn(1.2f, 2.2f), margin = margin.coerceIn(0, 64))

    companion object { val SIZES = 12..32 }
}

/** Pages and places in a text chapter, kept apart from Compose so they can be checked on their own. */
object MangaNovelLayout {
    /**
     * Fills pages [pageHeight] px tall with the [lines] of each block (as the text measured them), [blockGap] px between blocks.
     * A paragraph that does not fit goes on on the next page from the line it stopped at; a line taller than a page gets one.
     */
    fun paginate(lines: List<List<MangaNovelLine>>, pageHeight: Int, blockGap: Int): List<MangaNovelPage> {
        val pages = ArrayList<MangaNovelPage>()
        var slices = ArrayList<MangaNovelSlice>()
        var used = 0
        fun close() { if (slices.isNotEmpty()) { pages += MangaNovelPage(slices); slices = ArrayList(); used = 0 } }
        lines.forEachIndexed { block, blockLines ->
            if (blockLines.isEmpty()) return@forEachIndexed
            var sliceStart = -1
            var sliceEnd = 0
            if (slices.isNotEmpty()) used += blockGap
            blockLines.forEach { line ->
                val height = (line.bottom - line.top).coerceAtLeast(1)
                if (used + height > pageHeight && (slices.isNotEmpty() || sliceStart >= 0)) {
                    if (sliceStart >= 0) slices += MangaNovelSlice(block, sliceStart, sliceEnd)
                    close(); sliceStart = -1
                }
                if (sliceStart < 0) sliceStart = line.start
                sliceEnd = line.end
                used += height
            }
            if (sliceStart >= 0) slices += MangaNovelSlice(block, sliceStart, sliceEnd)
        }
        close()
        return pages
    }

    /** The page that holds [place]; the last page for a place past the end. */
    fun pageOf(pages: List<MangaNovelPage>, place: MangaNovelPosition): Int {
        if (pages.isEmpty()) return 0
        pages.indexOfFirst { it.contains(place) }.takeIf { it >= 0 }?.let { return it }
        return (pages.indexOfFirst { it.start > place } - 1).let { if (it < -1) pages.lastIndex else it.coerceAtLeast(0) }
    }

    /** The character [offsetPx] down a block [heightPx] tall that has [length] characters. */
    fun charAt(length: Int, offsetPx: Int, heightPx: Int): Int =
        if (heightPx <= 0 || length <= 0) 0 else (length.toLong() * offsetPx / heightPx).toInt().coerceIn(0, length - 1)

    /** How far down a block [heightPx] tall its character [char] stands. */
    fun offsetFor(char: Int, length: Int, heightPx: Int): Int = if (length <= 0) 0 else (heightPx.toLong() * char / length).toInt().coerceIn(0, heightPx)

    /** The place kept in the library's reading progress: the block as the page, its character as the offset. */
    fun progress(bookId: String, chapterId: String, chapter: MangaNovelChapter, place: MangaNovelPosition, finished: Boolean,
        nowMs: Long = System.currentTimeMillis()): MangaProgress =
        MangaProgress(bookId, chapterId, place.block.coerceIn(0, (chapter.blocks.size - 1).coerceAtLeast(0)), chapter.blocks.size,
            MangaReaderMode.PAGES_LTR, nowMs, reachedEnd = finished, pageOffsetPx = place.char.coerceIn(0, 10_000_000))

    fun position(progress: MangaProgress?, chapter: MangaNovelChapter): MangaNovelPosition {
        if (progress == null || chapter.blocks.isEmpty()) return MangaNovelPosition(0, 0)
        if (progress.page > chapter.blocks.lastIndex) return MangaNovelPosition(chapter.blocks.lastIndex, 0)
        val block = progress.page.coerceAtLeast(0)
        return MangaNovelPosition(block, progress.pageOffsetPx.coerceIn(0, chapter.blocks[block].text.length))
    }

    /** How far through the chapter [place] is, by characters (a scene break counts as one). */
    fun fraction(chapter: MangaNovelChapter, place: MangaNovelPosition): Float {
        val lengths = chapter.blocks.map { it.text.length.coerceAtLeast(1) }
        val total = lengths.sum()
        if (total == 0) return 0f
        val before = lengths.take(place.block.coerceIn(0, lengths.size)).sum() + place.char.coerceIn(0, lengths.getOrElse(place.block) { 0 })
        return (before.toFloat() / total).coerceIn(0f, 1f)
    }

    /** The place [fraction] of the way through the chapter. */
    fun at(chapter: MangaNovelChapter, fraction: Float): MangaNovelPosition {
        val lengths = chapter.blocks.map { it.text.length.coerceAtLeast(1) }
        var left = (lengths.sum() * fraction.coerceIn(0f, 1f)).toInt()
        lengths.forEachIndexed { index, length -> if (left < length) return MangaNovelPosition(index, left); left -= length }
        return MangaNovelPosition((lengths.size - 1).coerceAtLeast(0), 0)
    }
}
