package com.cosmotune.watch

import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.TitleSummary
import com.cosmotune.watch.model.TitleType
import kotlinx.serialization.Serializable

/** The kind chips of Watch search. */
enum class WatchKind(val label: String) {
    ALL("All"), FILMS("Films"), SERIES("Series"), ANIME("Anime"), LIVE("Live TV");

    /** The title kind sources are asked for; null asks for both. */
    val titleType: TitleType? get() = when (this) { FILMS -> TitleType.FILM; SERIES -> TitleType.SERIES; else -> null }
}

/**
 * The fixed genre list of Watch search. [tmdbFilm] and [tmdbSeries] are TMDB's genre ids (TMDB has no horror, romance or thriller
 * for series: null). [aliases] are the other names sources give the same genre ("Science Fiction", "Sci-Fi & Fantasy").
 */
enum class WatchGenre(val label: String, val tmdbFilm: Int, val tmdbSeries: Int?, private val aliases: List<String> = emptyList()) {
    ACTION("Action", 28, 10759, listOf("Action & Adventure", "Action and Adventure", "Martial Arts")),
    ADVENTURE("Adventure", 12, 10759, listOf("Action & Adventure", "Action and Adventure")),
    ANIMATION("Animation", 16, 16, listOf("Animated", "Anime", "Cartoon", "Cartoons")),
    COMEDY("Comedy", 35, 35, listOf("Comedies", "Sitcom")),
    CRIME("Crime", 80, 80),
    DOCUMENTARY("Documentary", 99, 99, listOf("Documentaries", "Docuseries")),
    DRAMA("Drama", 18, 18, listOf("Dramas")),
    FAMILY("Family", 10751, 10751, listOf("Kids", "Children")),
    FANTASY("Fantasy", 14, 10765, listOf("Sci-Fi & Fantasy", "Sci-Fi and Fantasy")),
    HORROR("Horror", 27, null),
    MYSTERY("Mystery", 9648, 9648),
    ROMANCE("Romance", 10749, null, listOf("Romantic")),
    SCI_FI("Sci-fi", 878, 10765, listOf("Science Fiction", "Sci-Fi & Fantasy", "Sci-Fi and Fantasy", "SciFi")),
    THRILLER("Thriller", 53, null, listOf("Suspense")),
    WAR("War", 10752, 10768, listOf("War & Politics", "War and Politics")),
    WESTERN("Western", 37, 37, listOf("Westerns"));

    /** Every name of this genre, its own first. */
    val spellings: List<String> get() = listOf(label) + aliases
    private val names: Set<String> get() = spellings.map(::normaliseGenre).toSet()

    /** Whether a title of [genres] (as any source names them) is of this genre. */
    fun matches(genres: List<String>): Boolean = genres.any { normaliseGenre(it) in names }

    /** The value an add-on uses for this genre among its [options] (its own spelling), or null when it has none. */
    fun optionIn(options: List<String>): String? =
        options.firstOrNull { normaliseGenre(it) == normaliseGenre(label) } ?: options.firstOrNull { normaliseGenre(it) in names }

    companion object {
        internal fun normaliseGenre(name: String): String = name.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }
    }
}

/** The year chips. Each is a range of release years against [currentYear]. */
enum class WatchYears(val label: String) {
    THIS_YEAR("This year"), LAST_YEAR("Last year"), TEENS("2010s"), NOUGHTIES("2000s"), NINETIES("1990s"), OLDER("Older");

    fun range(currentYear: Int): IntRange = when (this) {
        THIS_YEAR -> currentYear..currentYear
        LAST_YEAR -> (currentYear - 1)..(currentYear - 1)
        TEENS -> 2010..2019
        NOUGHTIES -> 2000..2009
        NINETIES -> 1990..1999
        OLDER -> 1870..1989
    }

    /** The chip's name: This year says which ("2026"), as the Browse row does. */
    fun name(currentYear: Int): String = when (this) { THIS_YEAR -> currentYear.toString(); LAST_YEAR -> (currentYear - 1).toString(); else -> label }
}

/** Original languages offered by the Language chip ([code] ISO 639-1, as TMDB gives a title's original language). */
enum class WatchLanguage(val label: String, val code: String) {
    ENGLISH("English", "en"), TAMIL("Tamil", "ta"), HINDI("Hindi", "hi"), TELUGU("Telugu", "te"), MALAYALAM("Malayalam", "ml"),
    KANNADA("Kannada", "kn"), BENGALI("Bengali", "bn"), MARATHI("Marathi", "mr"), KOREAN("Korean", "ko"), JAPANESE("Japanese", "ja"),
    CHINESE("Chinese", "zh"), SPANISH("Spanish", "es"), FRENCH("French", "fr"), GERMAN("German", "de"), ITALIAN("Italian", "it"),
    PORTUGUESE("Portuguese", "pt"), TURKISH("Turkish", "tr"), ARABIC("Arabic", "ar"), RUSSIAN("Russian", "ru");

    /** Whether [language] (a code of any length or a name) is this language. */
    fun matches(language: String?): Boolean = language != null && LanguageCodes.same(language, code)
}

/** How results are ordered; kept per viewer profile ([WatchPreferences.searchSort]). */
@Serializable
enum class WatchSort(val label: String) { BEST("Best match"), NEWEST("Newest"), RATED("Highest rated"), NAME("A to Z") }

/** A filter a source may be unable to apply. [phrase] completes "2 sources can't filter …". */
enum class WatchFilter(val phrase: String) { ANIME("anime"), GENRE("by genre"), LANGUAGE("by language"), YEAR("by year") }

/**
 * What Watch search asks for: the box's [text] and the chips. A blank [text] browses: popular titles that match the filters.
 * [sources] narrows to those source ids (empty means every source that is on); the metadata source's id is [META_SOURCE].
 */
data class WatchQuery(
    val text: String = "",
    val kind: WatchKind = WatchKind.ALL,
    val genre: WatchGenre? = null,
    val years: WatchYears? = null,
    val language: WatchLanguage? = null,
    val sources: Set<String> = emptySet(),
    val sort: WatchSort = WatchSort.BEST,
) {
    val trimmed: String get() = text.trim()
    val browsing: Boolean get() = trimmed.isEmpty()
    val hasFilters: Boolean get() = genre != null || years != null || language != null || sources.isNotEmpty()
    /** The filters that narrow titles, in the order a source is checked against them. */
    val filters: List<WatchFilter> get() = listOfNotNull(
        WatchFilter.ANIME.takeIf { kind == WatchKind.ANIME }, WatchFilter.GENRE.takeIf { genre != null },
        WatchFilter.LANGUAGE.takeIf { language != null }, WatchFilter.YEAR.takeIf { years != null },
    )
    fun withoutFilters(): WatchQuery = copy(genre = null, years = null, language = null, sources = emptySet())
    fun asks(sourceId: String): Boolean = sources.isEmpty() || sourceId in sources

    companion object { const val META_SOURCE = "meta" }
}

/** How a source handles one filter: it cannot, its results are checked here (they carry the field), or it applies it itself. */
enum class FilterSupport { NONE, CHECKED, SENT }

/**
 * What one source can do with one [WatchQuery]. [answers] false: it has nothing of this kind to search or browse, and is not asked
 * (nor counted as left out). A filter it cannot apply ([FilterSupport.NONE]) leaves it out of the results.
 */
data class QueryAbility(
    val answers: Boolean,
    val genre: FilterSupport = FilterSupport.NONE,
    val year: FilterSupport = FilterSupport.CHECKED,
    val language: FilterSupport = FilterSupport.NONE,
    val anime: FilterSupport = FilterSupport.NONE,
) {
    fun support(filter: WatchFilter): FilterSupport = when (filter) {
        WatchFilter.ANIME -> anime; WatchFilter.GENRE -> genre; WatchFilter.LANGUAGE -> language; WatchFilter.YEAR -> year
    }

    companion object { val SILENT = QueryAbility(answers = false) }
}

/** One source of the Source chip and the "N sources on" line. */
data class SearchSource(val id: String, val name: String)

/** A search's answer: the titles (checked, merged and sorted), channels, and how many sources answered or were left out. */
data class WatchFound(
    val query: WatchQuery,
    val titles: List<TitleSummary>,
    val channels: List<Channel> = emptyList(),
    /** Sources that answered. */
    val answered: Int = 0,
    /** Sources asked whose answer failed. */
    val failed: Int = 0,
    /** Sources left out because they cannot apply a filter, by the first filter they cannot apply. */
    val leftOut: Map<WatchFilter, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = titles.isEmpty() && channels.isEmpty()
    /** Every source asked failed: the page offers Try again. */
    val error: Boolean get() = isEmpty && failed > 0 && answered == 0

    /** "2 sources can't filter by genre", or with several filters "2 sources can't filter by genre, 1 by language". */
    val leftOutNote: String? get() {
        val parts = WatchFilter.entries.mapNotNull { f -> leftOut[f]?.takeIf { it > 0 }?.let { f to it } }
        if (parts.isEmpty()) return null
        val (first, n) = parts.first()
        val head = "$n ${if (n == 1) "source" else "sources"} can't filter ${first.phrase}"
        return (listOf(head) + parts.drop(1).map { (f, m) -> "$m ${f.phrase}" }).joinToString(", ")
    }

    /** "14 films", "3 series", "20 titles", "6 channels". */
    val countLabel: String get() {
        val films = titles.count { it.type == TitleType.FILM }
        val series = titles.size - films
        return when {
            titles.isEmpty() -> "${channels.size} ${if (channels.size == 1) "channel" else "channels"}"
            series == 0 && channels.isEmpty() -> "$films ${if (films == 1) "film" else "films"}"
            films == 0 && channels.isEmpty() -> "$series series"
            else -> (titles.size + channels.size).let { "$it ${if (it == 1) "result" else "results"}" }
        }
    }
}

/** The pure parts of Watch search: who is asked, what is left out, what is kept and in what order. */
object WatchSearchRules {
    /** Which of [abilities] (source id to its ability for [query]) are asked, and how many are left out by which filter. */
    fun plan(query: WatchQuery, abilities: Map<String, QueryAbility>): Pair<List<String>, Map<WatchFilter, Int>> {
        val asked = mutableListOf<String>()
        val leftOut = mutableMapOf<WatchFilter, Int>()
        if (query.kind == WatchKind.LIVE) return asked to leftOut
        for ((id, ability) in abilities) {
            if (!query.asks(id) || !ability.answers) continue
            val missing = query.filters.firstOrNull { ability.support(it) == FilterSupport.NONE }
            if (missing == null) asked += id else leftOut[missing] = (leftOut[missing] ?: 0) + 1
        }
        return asked to leftOut
    }

    /**
     * Whether [item] stays in the results of [query]. A field the title carries is always checked; a title without it stays only
     * when its source applied that filter itself ([ability] says [FilterSupport.SENT]).
     */
    fun keeps(item: TitleSummary, query: WatchQuery, ability: QueryAbility, currentYear: Int): Boolean {
        query.kind.titleType?.let { if (item.type != it) return false }
        // A source's own anime lists are trusted; elsewhere an anime is an animation first made in Japanese.
        if (query.kind == WatchKind.ANIME && ability.anime != FilterSupport.SENT) {
            if (!WatchGenre.ANIMATION.matches(item.genres)) return false
            if (item.genres.none { it.equals("anime", true) } && !WatchLanguage.JAPANESE.matches(item.originalLanguage)) return false
        }
        query.genre?.let { g ->
            if (item.genres.isNotEmpty()) { if (!g.matches(item.genres)) return false } else if (ability.genre != FilterSupport.SENT) return false
        }
        query.language?.let { l ->
            if (item.originalLanguage != null) { if (!l.matches(item.originalLanguage)) return false } else if (ability.language != FilterSupport.SENT) return false
        }
        query.years?.let { y ->
            val year = item.year
            if (year != null) { if (year !in y.range(currentYear)) return false } else if (ability.year != FilterSupport.SENT) return false
        }
        return true
    }

    /** [items] (each source's own order, merged) in [sort] order; Best match puts names that match [text] best first. */
    fun sort(items: List<TitleSummary>, sort: WatchSort, text: String): List<TitleSummary> = when (sort) {
        WatchSort.BEST -> if (text.isBlank()) items else {
            val q = normaliseName(text)
            items.sortedBy { matchRank(normaliseName(it.name), q) }
        }
        WatchSort.NEWEST -> items.sortedWith(compareBy<TitleSummary> { it.year == null }.thenByDescending { it.year ?: 0 })
        WatchSort.RATED -> items.sortedWith(compareBy<TitleSummary> { it.rating == null }.thenByDescending { it.rating ?: 0.0 })
        WatchSort.NAME -> items.sortedBy { normaliseName(it.name) }
    }

    /**
     * The titles of several sources' answers as one list: every source's first title, then every second, and so on (so one long
     * answer does not push the rest down), each title once (the first source to give it keeps it).
     */
    fun merge(answers: List<List<TitleSummary>>): List<TitleSummary> {
        val seen = HashSet<String>()
        val out = ArrayList<TitleSummary>()
        val longest = answers.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (list in answers) list.getOrNull(i)?.let { if (seen.add(it.key.value)) out += it }
        return out
    }

    /**
     * The channels a search keeps: only with All or Live TV and no genre or year (channels have neither), in the chosen language
     * and from the chosen sources.
     */
    fun channelsFor(query: WatchQuery, channels: List<Channel>): List<Channel> {
        if (query.kind != WatchKind.ALL && query.kind != WatchKind.LIVE || query.genre != null || query.years != null) return emptyList()
        return channels.filter { c -> query.asks(c.pluginId) && (query.language == null || c.languages.any { query.language.matches(it) }) }
    }

    /** [recent] with [text] put first (once, whatever its case), at most [max] long. */
    fun remember(recent: List<String>, text: String, max: Int = 8): List<String> {
        val t = text.trim()
        if (t.length < 2) return recent
        return (listOf(t) + recent.filterNot { it.equals(t, true) }).take(max)
    }

    private fun matchRank(name: String, q: String): Int = when {
        name == q -> 0
        name.startsWith(q) -> 1
        name.split(' ').any { it.startsWith(q) } || name.contains(q) -> 2
        else -> 3
    }

    internal fun normaliseName(name: String): String =
        name.lowercase().replace("&", "and").map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
            .replace(Regex("^(the|a|an) "), "").replace(Regex("\\s+"), " ").trim()
}
