package com.cosmotune.podio

import kotlinx.serialization.Serializable

/**
 * A list that fills itself from the followed shows' latest episodes (Pocket Casts' filters, AntennaPod's episode filters): the
 * rules are all kept at once; a rule left at its default lets everything through.
 */
@Serializable data class PodioSmartList(val id: String, val name: String,
    /** Only episodes not yet played to their end. */
    val unplayed: Boolean = true,
    /** Only episodes begun and not finished. */
    val started: Boolean = false,
    /** Only episodes downloaded to this device. */
    val downloaded: Boolean = false,
    /** Only these shows (ids); empty for every followed show. */
    val shows: Set<String> = emptySet(),
    /** Only episodes this long or shorter, minutes; 0 for any length. */
    val maxMinutes: Int = 0,
    /** Only episodes out in the last this many days; 0 for any age. */
    val withinDays: Int = 0,
    /**
     * Words the episode's title or notes must have, gPodder's episode query in plain words: "interview" keeps the episodes that say
     * it, "-trailer" leaves out those that do, a phrase in quotes is kept as one. Empty for any.
     */
    val words: String = "")

object PodioSmartLists {
    const val MAX = 20
    val LENGTHS = listOf(0, 15, 30, 60)
    val AGES = listOf(0, 1, 7, 30)

    /** The episodes of [candidates] (an episode and its show) that [list] keeps, the newest first. */
    fun match(list: PodioSmartList, candidates: List<Pair<PodcastEpisode, PodcastShow>>, saved: PodioSaved, downloaded: Set<String>, nowMs: Long = System.currentTimeMillis()):
        List<Pair<PodcastEpisode, PodcastShow>> = candidates.filter { (episode, show) ->
            val id = episode.item().id
            val heard = id in saved.completedEpisodes
            val begun = (saved.progress[id] ?: 0) > 0 && !heard
            val published = PodioRefresh.publishedAt(episode.published)
            (!list.unplayed || !heard) && (!list.started || begun) && (!list.downloaded || id in downloaded) &&
                (list.shows.isEmpty() || show.id in list.shows) &&
                (list.maxMinutes <= 0 || episode.durationSeconds in 1..list.maxMinutes * 60L) &&
                (list.withinDays <= 0 || published != null && nowMs - published <= list.withinDays * 86_400_000L) &&
                matchesWords(list.words, episode.title + " " + episode.description)
        }.distinctBy { it.first.id }.sortedByDescending { PodioRefresh.publishedAt(it.first.published) ?: Long.MIN_VALUE }

    /** Whether [text] has every wanted word of [query] and none of its "-" words (case and accents aside). */
    fun matchesWords(query: String, text: String): Boolean {
        if (query.isBlank()) return true
        val haystack = fold(text)
        return terms(query).all { (word, wanted) -> (fold(word) in haystack) == wanted }
    }

    /** The words of a query, each with whether it is wanted ("-" leaves it out); a quoted phrase is one word. */
    fun terms(query: String): List<Pair<String, Boolean>> = Regex("(-?)(?:\"([^\"]+)\"|(\\S+))").findAll(query.take(200)).mapNotNull { match ->
        val word = (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
        if (word.isEmpty() || word == "-") null else word to (match.groupValues[1] != "-")
    }.take(12).toList()

    private fun fold(text: String): String = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** [saved] with [list] added, or put in place of the one with its id. */
    fun put(saved: PodioSaved, list: PodioSmartList): PodioSaved {
        val clean = list.copy(name = list.name.trim().take(60).ifBlank { "My list" }, maxMinutes = list.maxMinutes.coerceIn(0, 600), withinDays = list.withinDays.coerceIn(0, 365),
            words = list.words.trim().take(200))
        val at = saved.smartLists.indexOfFirst { it.id == clean.id }
        val next = if (at >= 0) saved.smartLists.toMutableList().also { it[at] = clean } else saved.smartLists + clean
        return saved.copy(smartLists = next.take(MAX))
    }

    fun remove(saved: PodioSaved, id: String): PodioSaved = saved.copy(smartLists = saved.smartLists.filterNot { it.id == id })

    /** What the list keeps, in words: "Unplayed · 30 min or less · This week". */
    fun describe(list: PodioSmartList, showCount: Int = list.shows.size): String = listOfNotNull(
        "Unplayed".takeIf { list.unplayed }, "Started".takeIf { list.started }, "Downloaded".takeIf { list.downloaded },
        list.maxMinutes.takeIf { it > 0 }?.let { "$it min or less" },
        when (list.withinDays) { 0 -> null; 1 -> "Today"; 7 -> "This week"; 30 -> "This month"; else -> "Last ${list.withinDays} days" },
        showCount.takeIf { it > 0 }?.let { if (it == 1) "1 show" else "$it shows" },
        list.words.trim().takeIf { it.isNotEmpty() }?.let { "“${it.take(40)}”" },
    ).joinToString(" · ").ifBlank { "Every episode" }
}
