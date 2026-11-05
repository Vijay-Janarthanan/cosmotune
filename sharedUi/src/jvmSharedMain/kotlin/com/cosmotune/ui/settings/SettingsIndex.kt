package com.cosmotune.ui.settings

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import java.text.Normalizer
import java.util.Locale

/**
 * One setting as the settings search knows it: where it lives ([category], and the [group] it stands in on that page), what it is
 * called ([title]), what its row says under the title ([summary]) and the other words somebody might type looking for it
 * ([keywords]: "eq" for the equaliser, the names of the services behind a row). [id] is stable within one screen; the page uses it
 * to find the row again when a result is picked, and to light it up.
 */
@Immutable
data class SettingsItem(
    val id: String,
    val title: String,
    val category: SettingsCategory,
    val summary: String? = null,
    val keywords: List<String> = emptyList(),
    val group: String? = null,
)

/**
 * The settings search: every word of the query must be found in the item's title, summary, keywords or group (accents and case do
 * not count), and the results come best first: the title that is the query, then a title that starts with it, then titles whose
 * words start with the query's words, then the other fields. Ties keep the order the items were given in, which is the order of
 * the pages. A blank query finds nothing (the settings home shows its categories instead).
 */
object SettingsIndex {
    private val SPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val WORD_BREAK = Regex("[^\\p{L}\\p{N}]+")

    /** [text] in the form the search compares: lower case, without accents, trimmed. */
    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).trim()

    /** The words of [query], normalized. */
    fun tokens(query: String): List<String> = normalize(query).split(SPACE).filter { it.isNotEmpty() }

    /** Whether every word of [query] is found somewhere in [item]; a blank query matches nothing. */
    fun matches(item: SettingsItem, query: String): Boolean = score(item, tokens(query), normalize(query)) != null

    /**
     * The rank of [item] for these [tokens] (lower is better), or null when a token is found nowhere. [phrase] is the whole query,
     * normalized, for the bonus of a title that is it or starts with it.
     */
    internal fun score(item: SettingsItem, tokens: List<String>, phrase: String): Int? {
        if (tokens.isEmpty()) return null
        val title = normalize(item.title)
        val titleWords = title.split(WORD_BREAK).filter { it.isNotEmpty() }
        val keywords = item.keywords.map(::normalize)
        val summary = item.summary?.let(::normalize).orEmpty()
        val group = item.group?.let(::normalize).orEmpty()
        var total = 0
        for (token in tokens) {
            total += when {
                titleWords.any { it.startsWith(token) } -> 0
                title.contains(token) -> 1
                keywords.any { k -> k.split(WORD_BREAK).any { it.startsWith(token) } } -> 2
                keywords.any { it.contains(token) } -> 3
                summary.contains(token) -> 4
                group.contains(token) -> 5
                else -> return null
            }
        }
        val bonus = when {
            title == phrase -> -20
            title.startsWith(phrase) -> -10
            title.contains(phrase) -> -5
            else -> 0
        }
        return total * 4 + bonus
    }

    /** The items [query] finds, best first, each id once, at most [limit] of them. */
    fun search(items: List<SettingsItem>, query: String, limit: Int = 80): List<SettingsItem> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return emptyList()
        val phrase = normalize(query)
        val seen = HashSet<String>()
        return items
            .mapIndexedNotNull { index, item -> score(item, tokens, phrase)?.let { Triple(it, index, item) } }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third }
            .filter { seen.add(it.id) }
            .take(limit)
    }
}

/**
 * Where the rows of a settings screen register themselves while the search is open: the screen composes its pages in a pass that
 * draws nothing, each row adds itself here instead of drawing, and the results are read from [items] after the pass. A row that
 * registers again (the pass ran once more) replaces its earlier entry and keeps its place.
 */
class SettingsCollector {
    private val byId = LinkedHashMap<String, SettingsItem>()

    fun add(item: SettingsItem) {
        byId[item.id] = item
    }

    val items: List<SettingsItem> get() = byId.values.toList()
}

/** The collector of the pass that is running, or null while rows are drawn as usual. */
val LocalSettingsCollector = compositionLocalOf<SettingsCollector?> { null }
