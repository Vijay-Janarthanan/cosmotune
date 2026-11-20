package com.cosmotune.podio

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The refresh of followed shows that runs in the background (Android's scheduled work, the desktop while it is open) and from
 * Following: each followed feed is read again, and an episode is new when the show's feed did not have it at the last refresh.
 * A show refreshed for the first time (just followed, or the first refresh ever) reports nothing: it only learns its episodes.
 */
object PodioRefresh {
    /** How many of a show's newest episode ids are remembered between refreshes. */
    const val KNOWN_PER_SHOW = 60

    data class Found(val episode: PodcastEpisode, val show: PodcastShow)

    /** What one refresh found, and what the library should remember for the next. */
    data class Result(val found: List<Found>, val known: Map<String, List<String>>)

    /** The newest episodes of a feed first; those without a readable date keep the feed's order after them. */
    fun newestFirst(episodes: List<PodcastEpisode>): List<PodcastEpisode> =
        episodes.withIndex().sortedWith(compareByDescending<IndexedValue<PodcastEpisode>> { publishedAt(it.value.published) ?: Long.MIN_VALUE }.thenBy { it.index })
            .map { it.value }

    /**
     * Compares [feeds] (a followed show and its episodes, as read now) with [known] (each show's episode ids at the last refresh).
     * Shows that failed to load are simply absent from [feeds] and keep what was known of them.
     */
    fun diff(known: Map<String, List<String>>, feeds: List<Pair<PodcastShow, List<PodcastEpisode>>>): Result {
        val found = ArrayList<Found>()
        val next = known.toMutableMap()
        for ((show, episodes) in feeds) {
            val newest = newestFirst(episodes).take(KNOWN_PER_SHOW)
            val ids = newest.map { it.item().id }
            val before = known[show.id]
            if (before != null) {
                val old = before.toSet()
                newest.filter { it.item().id !in old }.forEach { found += Found(it, show) }
            }
            // What was known stays known (a feed that shows fewer episodes this time must not bring old ones back as new).
            next[show.id] = (ids + before.orEmpty()).distinct().take(KNOWN_PER_SHOW)
        }
        return Result(found, next)
    }

    /**
     * What the page saw (Following's list, a part of each feed) added to what is known, so the background refresh does not
     * announce what the person has already seen. A show the refresh has never read is left for it to learn in full.
     */
    fun learn(known: Map<String, List<String>>, seen: List<Pair<PodcastShow, List<PodcastEpisode>>>): Map<String, List<String>> =
        known + seen.filter { (show, _) -> show.id in known }.associate { (show, episodes) ->
            show.id to (newestFirst(episodes).map { it.item().id } + known.getValue(show.id)).distinct().take(KNOWN_PER_SHOW)
        }

    /**
     * Reads every followed show of [library] (up to 60, four at a time), records what it found, and returns the new episodes
     * (newest first). [force] refreshes even when [PodioSaved.refreshInBackground] is off.
     */
    suspend fun run(library: PodioLibrary, catalogue: PodioCatalogue, nowMs: Long = System.currentTimeMillis(), force: Boolean = false): List<Found> {
        val saved = library.state.value
        if (saved.shows.isEmpty() || !force && !saved.refreshInBackground) return emptyList()
        val permits = Semaphore(4)
        val feeds = coroutineScope {
            saved.shows.take(60).map { show -> async { permits.withPermit {
                try { show to catalogue.feed(show.feed).episodes }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            } } }.awaitAll().filterNotNull()
        }
        if (feeds.isEmpty()) return emptyList()
        var result = Result(emptyList(), emptyMap())
        library.update { old ->
            result = diff(old.knownEpisodes, feeds)
            old.copy(knownEpisodes = result.known, lastRefreshMs = nowMs)
        }
        return result.found.sortedByDescending { publishedAt(it.episode.published) ?: Long.MIN_VALUE }
    }

    /** "3 new episodes" and the lines under it ("Show · Episode"), for the notification. */
    fun notificationText(found: List<Found>): Pair<String, List<String>> {
        val title = if (found.size == 1) "1 new episode" else "${found.size} new episodes"
        val lines = found.take(5).map { "${it.show.title.ifBlank { "Podcast" }} · ${it.episode.title}" }
        return title to lines
    }

    internal fun publishedAt(published: String): Long? {
        val text = published.trim().takeIf(String::isNotEmpty) ?: return null
        val instant = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text.replace(Regex("\\s+[A-Z]{3,4}$"), " +0000"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        return instant?.toEpochMilli()
    }
}
