package com.cosmotune.listening

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The followed artists' release check, as Listening tools > New releases and the phone's daily background check run it: each due
 * (or, with force, every) artist's page is read, what was not known before lands in the inbox, and [Outcome.found] says which
 * releases are new this time, for a notification. The first check of an artist only learns its catalogue.
 */
object ReleaseChecks {

    data class Outcome(val failures: Int, val found: List<ReleaseNotice>)

    suspend fun run(
        store: ListeningStore,
        lookup: suspend (WatchedArtist) -> List<ReleaseNotice>,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Outcome {
        val artists = store.state.value.artists.let { if (force) it else dueForReleaseCheck(it, now) }
        val before = store.state.value.inbox.mapTo(HashSet()) { it.artistId to it.id }
        var failures = 0
        for (artist in artists) {
            try {
                val releases = lookup(artist)
                withContext(Dispatchers.IO) { store.acceptReleases(artist.id, releases, now) }
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) failures++ else throw cancelled
            } catch (_: Exception) {
                failures++
            }
        }
        val found = store.state.value.inbox.filter { !it.read && (it.artistId to it.id) !in before }
        return Outcome(failures, found)
    }

    /** The notification's title and its lines: one release by its artist and title, several counted, each on its own line. */
    fun notificationText(found: List<ReleaseNotice>): Pair<String, List<String>> =
        if (found.size == 1) "New from ${found[0].artist}" to listOf(found[0].title)
        else "${found.size} new releases" to found.take(MAX_LINES).map { "${it.artist} · ${it.title}" }

    private const val MAX_LINES = 6
}
