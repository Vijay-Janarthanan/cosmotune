package com.cosmotune.music.data.recommendations

import com.cosmotune.listening.WatchedArtist
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.ArtistIdentity
import com.cosmotune.music.data.sources.TrackMatcher
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

enum class MusicCandidateOrigin { LIBRARY, RECENT, PROVIDER, REGION_CHART, LANGUAGE_CATALOGUE, RADIO, FOLLOWED_ARTIST }
enum class MusicPlaylistKind(val title: String) {
    DAILY_MIX("Daily mix"), REDISCOVER("Rediscover"), FOLLOWED_RELEASES("From followed artists"), REGION("Regional discovery"), SEED("Based on a song")
}
data class MusicCandidate(val song: Song, val origin: MusicCandidateOrigin, val rank: Int = 0,
    /** Null means the source did not state it; a search query is not language metadata. */
    val language: String? = null, val releasedOn: LocalDate? = null, val isrc: String? = null, val provider: String? = null,
    val explanation: String = "")
data class MusicDiscoveryContext(val library: List<Song> = emptyList(), val history: List<Song> = emptyList(),
    val likedIds: Set<String> = emptySet(), val followedArtists: List<WatchedArtist> = emptyList())
data class RecordingChoice(val song: Song, val alternatives: Set<String>, val origins: Set<MusicCandidateOrigin>,
    val language: String?, val releasedOn: LocalDate?, val explanation: String)
data class MusicPlaylistPreview(val title: String, val songs: List<Song>, val recordings: List<RecordingChoice>,
    val explanation: String, val unavailablePools: Set<String> = emptySet())

/** Conservative cross-catalogue identity: unknown evidence never becomes a guessed match. */
object MusicRecordingIdentity {
    private val validIsrc = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
    private val remaster = Regex("(?i)\\b(?:re-?master(?:ed)?|re-?record(?:ed|ing)?)\\b")
    private val year = Regex("\\b(?:19|20)\\d{2}\\b")
    private data class Metadata(val title: String, val artist: String, val album: String?, val duration: String?)
    internal data class Facts(val title: TrackMatcher.TitleParts, val versions: Set<String>, val key: String, val primaryArtist: String, val unknownArtist: Boolean, val seconds: Int?, val album: String)
    private val cache = LinkedHashMap<Metadata, Facts>(2_048, .75f, true)
    private var exceptions = emptyList<String>()
    @Synchronized internal fun facts(song: Song): Facts {
        if (exceptions != ArtistIdentity.userExceptions) { cache.clear(); exceptions = ArtistIdentity.userExceptions.toList() }
        val metadata = Metadata(song.title, song.artist, song.albumName, song.durationText)
        return cache.getOrPut(metadata) {
            val title = TrackMatcher.parseTitle(song.title, song.artist)
            val versions = title.versions + TrackMatcher.albumVersionMarkers(song.albumName) + buildSet {
            val packaging = song.title + " " + song.albumName.orEmpty()
            if (remaster.containsMatchIn(packaging)) {
                add("master-edition")
                year.findAll(packaging).forEach { add("edition:${it.value}") }
            }
            }
            val artist = TrackMatcher.artistNames(song.artist).map { it.joinToString("") }.sorted().joinToString("&")
            Facts(title, versions, "${title.core}\u001f$artist\u001f${versions.sorted().joinToString(",")}",
                TrackMatcher.primaryArtist(song.artist).ifBlank { song.artist.lowercase(Locale.ROOT) }, artist.isBlank(),
                TrackMatcher.secondsOf(song.durationText), TrackMatcher.normalize(song.albumName.orEmpty()))
        }.also { while (cache.size > 2_048) cache.remove(cache.keys.first()) }
    }
    fun versions(song: Song): Set<String> = facts(song).versions
    fun key(song: Song): String = facts(song).let { (if (it.unknownArtist) it.key + "\u001f" + song.videoId else it.key) + "\u001f${song.isExplicit}" }
    fun same(left: MusicCandidate, right: MusicCandidate): Boolean {
        val a = left.song; val b = right.song
        if (a.videoId == b.videoId) return true
        val fa = facts(a); val fb = facts(b)
        if (fa.versions != fb.versions) return false
        if (a.isExplicit != null && b.isExplicit != null && a.isExplicit != b.isExplicit) return false
        val ia = left.isrc?.uppercase(Locale.ROOT)?.takeIf { validIsrc.matches(it) }
        val ib = right.isrc?.uppercase(Locale.ROOT)?.takeIf { validIsrc.matches(it) }
        if (ia != null && ib != null) return ia == ib
        val titleA = fa.title
        val titleB = fb.title
        if (titleA.core != titleB.core && !TrackMatcher.isTranslationMatch(titleA, titleB)) return false
        if (titleA.core.isBlank() || !TrackMatcher.sharesArtist(a.artist, b.artist)) return false
        val da = fa.seconds; val db = fb.seconds
        if (da != null && db != null) return abs(da - db) <= maxOf(3, minOf(da, db) / 100)
        return !a.albumName.isNullOrBlank() && !b.albumName.isNullOrBlank() &&
            fa.album == fb.album
    }
}

/** At most 2,000 rows, title buckets and bounded comparisons; no whole-catalogue scan or network call. */
object MusicDiscoveryRanker {
    fun deduplicate(candidates: List<MusicCandidate>): List<RecordingChoice> {
        data class Group(var primary: MusicCandidate, val rows: MutableList<MusicCandidate>)
        val groups = ArrayList<Group>()
        val buckets = HashMap<String, MutableList<Group>>()
        val ids = HashMap<String, Group>()
        val isrcs = HashMap<String, MutableList<Group>>()
        for (candidate in candidates.take(2_000).filter { !it.song.isVideo && !it.song.videoId.startsWith("podio:") && it.song.videoId.isNotBlank() }) {
            val parts = MusicRecordingIdentity.facts(candidate.song).title
            val version = "\u001f" + MusicRecordingIdentity.versions(candidate.song).sorted().joinToString(",")
            val keys = (listOf(parts.core) + parts.alternateTitles.keys.take(3)).map { it + version }.distinct()
            val options = ids[candidate.song.videoId]?.let(::listOf)
                ?: (candidate.isrc?.uppercase(Locale.ROOT)?.let { isrcs[it] }.orEmpty() + keys.flatMap { buckets[it].orEmpty() }).distinct().take(16)
            val group = options.firstOrNull { MusicRecordingIdentity.same(it.primary, candidate) }
            if (group == null) {
                val created = Group(candidate, mutableListOf(candidate))
                groups += created; keys.forEach { buckets.getOrPut(it) { ArrayList() } += created }; ids[candidate.song.videoId] = created
                candidate.isrc?.uppercase(Locale.ROOT)?.let { isrcs.getOrPut(it) { ArrayList() } += created }
            } else {
                group.rows += candidate; ids[candidate.song.videoId] = group
                // Existing local recordings remain the playable representative; provider offers stay alternatives.
                if (candidate.song.localPath != null || candidate.song.localUri != null) group.primary = candidate
            }
        }
        return groups.map { group ->
            val language = group.rows.mapNotNull { it.language }.distinct().singleOrNull()
            RecordingChoice(group.primary.song, group.rows.mapTo(LinkedHashSet()) { it.song.videoId },
                group.rows.mapTo(LinkedHashSet()) { it.origin }, language, group.rows.mapNotNull { it.releasedOn }.minOrNull(),
                group.rows.map { it.explanation }.firstOrNull(String::isNotBlank).orEmpty())
        }
    }

    fun select(candidates: List<MusicCandidate>, context: MusicDiscoveryContext, kind: MusicPlaylistKind,
        seed: Song? = null, limit: Int = 30, today: LocalDate = LocalDate.now(),
        adjustment: (Song) -> Float = { 0f }, hidden: (Song) -> Boolean = { false }): List<RecordingChoice> {
        val groups = deduplicate(candidates)
        val recent = context.history.take(80).mapTo(HashSet()) { it.videoId }
        val library = context.library.mapTo(HashSet()) { it.videoId }
        val ranks = candidates.groupBy { it.song.videoId }.mapValues { (_, rows) -> rows.minOf { it.rank.coerceAtLeast(0) } }
        val count = limit.coerceIn(1, 50)
        val eligible = groups.filter { item ->
            !hidden(item.song) && item.releasedOn?.isAfter(today) != true && when (kind) {
                MusicPlaylistKind.REDISCOVER -> item.alternatives.any { it in library } && item.alternatives.none { it in recent }
                MusicPlaylistKind.FOLLOWED_RELEASES -> MusicCandidateOrigin.FOLLOWED_ARTIST in item.origins
                MusicPlaylistKind.REGION -> item.origins.any { it in setOf(MusicCandidateOrigin.REGION_CHART, MusicCandidateOrigin.LANGUAGE_CATALOGUE, MusicCandidateOrigin.PROVIDER) }
                else -> true
            }
        }
        val artistKeys = eligible.associate { it.song.videoId to MusicRecordingIdentity.facts(it.song).primaryArtist }
        fun artist(song: Song) = artistKeys.getValue(song.videoId)
        val ranked = eligible.map { item ->
            val age = item.releasedOn?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) }
            val freshness = age?.takeIf { it in 0..180 }?.let { .3 * (1 - it / 180.0) } ?: 0.0
            val liked = if (item.alternatives.any { it in context.likedIds }) .6 else 0.0
            val affinity = if (seed != null && TrackMatcher.sharesArtist(seed.artist, item.song.artist)) .12 else 0.0
            val daily = if (kind == MusicPlaylistKind.DAILY_MIX) ((MusicRecordingIdentity.key(item.song) + today).hashCode().toUInt().toLong() % 1000) / 10_000.0 else 0.0
            val rank = item.alternatives.mapNotNull { ranks[it] }.minOrNull() ?: 2_000
            item to (1.0 / (2 + rank) + freshness + liked + affinity + daily + adjustment(item.song).coerceIn(-1f, 1f))
        }.sortedByDescending { it.second }
        val chosen = ArrayList<RecordingChoice>()
        val artists = HashMap<String, Int>()
        val pools = HashMap<MusicCandidateOrigin, Int>()
        val remaining = ranked.toMutableList()
        while (chosen.size < count && remaining.isNotEmpty()) {
            val best = remaining.filter { artists.getOrDefault(artist(it.first.song), 0) < 3 }.maxByOrNull { (row, score) ->
                score - .15 * artists.getOrDefault(artist(row.song), 0) - .03 * (row.origins.minOfOrNull { pools.getOrDefault(it, 0) } ?: 0)
            } ?: break
            remaining.remove(best)
            chosen += best.first
            val who = artist(best.first.song); artists[who] = artists.getOrDefault(who, 0) + 1
            best.first.origins.forEach { pools[it] = pools.getOrDefault(it, 0) + 1 }
        }
        return chosen
    }
}
