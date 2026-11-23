package com.cosmotune.podio

import kotlinx.serialization.Serializable

/**
 * One followed show's own choices, from its settings sheet (Pocket Casts' per-podcast settings, AntennaPod's feed settings). A
 * null choice follows the app's own in Settings › Podio.
 */
@Serializable data class PodioShowSettings(
    /** Download the show's new episodes as the background check finds them; null follows "Download new episodes". */
    val autoDownload: Boolean? = null,
    /** Put the show's new episodes at the end of Up next as they are found. */
    val autoQueue: Boolean = false,
    /** Say when the show has new episodes. */
    val notify: Boolean = true,
    /** Start each episode this far in (an intro or a jingle), seconds. */
    val skipIntroSeconds: Int = 0,
    /** Move on this long before each episode ends (an outro or ads), seconds. */
    val skipOutroSeconds: Int = 0,
    /** Remove a download once it is played; null follows "Remove played downloads". */
    val deletePlayed: Boolean? = null,
    /** Keep only this many of the show's newest downloads (started ones always stay); 0 keeps all, null follows the app's. */
    val keepDownloads: Int? = null,
    /** Louder, clearer speech for a quiet show; null follows "Voice boost". */
    val voiceBoost: Boolean? = null,
) {
    val isDefault: Boolean get() = this == PodioShowSettings()
}

/** What a show's own settings mean once the app's defaults are taken into account. */
object PodioShowRules {
    const val MAX_SKIP_SECONDS = 600
    /** The skip lengths the sheet offers, seconds. */
    val SKIP_CHOICES = listOf(0, 10, 15, 30, 45, 60, 90, 120)
    /** The "keep newest" counts the sheet and Settings offer; 0 keeps every download. */
    val KEEP_CHOICES = listOf(0, 1, 2, 3, 5, 10)

    fun settings(saved: PodioSaved, showId: String?): PodioShowSettings = showId?.let { saved.showSettings[it] } ?: PodioShowSettings()

    fun autoDownload(saved: PodioSaved, showId: String): Boolean = settings(saved, showId).autoDownload ?: saved.autoDownloadNew
    fun deletePlayed(saved: PodioSaved, showId: String?): Boolean = settings(saved, showId).deletePlayed ?: saved.deletePlayedDownloads
    /** How much louder the voice boost plays a show: about 4 dB, enough for quiet speech without pushing music into clipping. */
    const val VOICE_BOOST_GAIN = 1.6f

    fun voiceBoost(saved: PodioSaved, showId: String?): Boolean = settings(saved, showId).voiceBoost ?: saved.voiceBoost

    /** The gain for the playing episode [episodeId] (1 when no boost applies). */
    fun gainFor(saved: PodioSaved, episodeId: String?): Float {
        if (episodeId == null || !episodeId.startsWith("podio:episode:")) return 1f
        if (!saved.voiceBoost && saved.showSettings.values.none { it.voiceBoost == true }) return 1f
        return if (voiceBoost(saved, PodioShowRules.showOf(episodeId, saved))) VOICE_BOOST_GAIN else 1f
    }

    fun keepDownloads(saved: PodioSaved, showId: String?): Int = (settings(saved, showId).keepDownloads ?: saved.keepDownloadsPerShow).coerceAtLeast(0)

    /** [saved] with [showId]'s settings changed; a show back at every default keeps no entry. */
    fun change(saved: PodioSaved, showId: String, change: (PodioShowSettings) -> PodioShowSettings): PodioSaved {
        val next = change(settings(saved, showId)).let {
            it.copy(skipIntroSeconds = it.skipIntroSeconds.coerceIn(0, MAX_SKIP_SECONDS), skipOutroSeconds = it.skipOutroSeconds.coerceIn(0, MAX_SKIP_SECONDS),
                keepDownloads = it.keepDownloads?.coerceIn(0, 100))
        }
        return saved.copy(showSettings = if (next.isDefault) saved.showSettings - showId else saved.showSettings + (showId to next))
    }

    /**
     * The followed show an episode belongs to: the queue's own record of it, else the show whose last refresh listed it, else the
     * followed show of that title ([showTitle], the item's author).
     */
    fun showOf(episodeId: String, saved: PodioSaved, queue: PodioQueueState? = null, showTitle: String? = null): String? {
        queue?.current?.takeIf { it.id == episodeId }?.let { return it.show.id }
        queue?.entries?.firstOrNull { it.id == episodeId }?.let { return it.show.id }
        saved.knownEpisodes.entries.firstOrNull { episodeId in it.value }?.let { return it.key }
        val title = showTitle?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return saved.shows.firstOrNull { it.title.trim().equals(title, ignoreCase = true) }?.id
    }

    /** What the app does with what a refresh found: which to download, which to put in Up next, which to tell about. */
    data class AfterRefresh(val download: List<PodioRefresh.Found>, val queue: List<PodioRefresh.Found>, val notify: List<PodioRefresh.Found>)

    fun afterRefresh(found: List<PodioRefresh.Found>, saved: PodioSaved, maxDownloads: Int = 5): AfterRefresh = AfterRefresh(
        download = found.filter { autoDownload(saved, it.show.id) }.take(maxDownloads),
        queue = found.filter { settings(saved, it.show.id).autoQueue }.sortedBy { PodioRefresh.publishedAt(it.episode.published) ?: Long.MAX_VALUE },
        notify = found.filter { settings(saved, it.show.id).notify },
    )

    /**
     * Applies a refresh's findings that need no platform: new episodes of the shows set to "Add to Up next" join the end of the
     * queue, oldest first. Returns what the host should download and announce.
     */
    fun apply(found: List<PodioRefresh.Found>, library: PodioLibrary, maxDownloads: Int = 5): AfterRefresh {
        val plan = afterRefresh(found, library.state.value, maxDownloads)
        if (plan.queue.isNotEmpty()) runCatching {
            library.queue().update { state -> plan.queue.fold(state) { acc, it -> PodioQueueOrder.add(acc, PodioQueueEntry(it.episode, it.show)) } }
        }
        return plan
    }
}

/**
 * Skips a show's intro and outro while it plays (Pocket Casts' "Skip first / last"): held by the playback owner, it is told the
 * position every second and answers with where to seek, once per episode for each end.
 */
class PodioSkipper {
    private var id: String? = null
    private var introDone = false
    private var outroDone = false

    /** Where to seek now, or null. [introMs] and [outroMs] are the show's lengths to skip; 0 skips nothing. */
    fun check(episodeId: String?, positionMs: Long, durationMs: Long, playing: Boolean, introMs: Long, outroMs: Long): Long? {
        if (episodeId == null || !episodeId.startsWith("podio:episode:")) { id = null; return null }
        if (episodeId != id) { id = episodeId; introDone = false; outroDone = false }
        if (!playing) return null
        if (!introDone) {
            introDone = true
            // Only an episode starting from its beginning skips its intro; one resumed later, or sought back into, plays as it is.
            if (introMs > 0 && positionMs < minOf(introMs, START_WINDOW_MS) && (durationMs <= 0 || introMs < durationMs - outroMs - MIN_LEFT_MS)) return introMs
        }
        if (!outroDone && outroMs > 0 && durationMs > outroMs + introMs + MIN_LEFT_MS && positionMs >= durationMs - outroMs && positionMs < durationMs - END_MS) {
            outroDone = true
            // A moment before the end, so the episode finishes in the player's own way (and the next one follows).
            return durationMs - END_MS
        }
        return null
    }

    /** [check] with the lengths of the show [episodeId] belongs to, read from [library] (and its queue). */
    fun check(library: PodioLibrary, episodeId: String?, positionMs: Long, durationMs: Long, playing: Boolean): Long? {
        val saved = library.state.value
        val settings = episodeId?.takeIf { it.startsWith("podio:episode:") && saved.showSettings.isNotEmpty() }?.let { id ->
            PodioShowRules.settings(saved, PodioShowRules.showOf(id, saved, runCatching { queue(library).state.value }.getOrNull()))
        } ?: PodioShowSettings()
        return check(episodeId, positionMs, durationMs, playing, settings.skipIntroSeconds * 1000L, settings.skipOutroSeconds * 1000L)
    }

    private var queue: Pair<PodioLibrary, PodioQueue>? = null
    private fun queue(library: PodioLibrary): PodioQueue = queue?.takeIf { it.first === library }?.second ?: library.queue().also { queue = library to it }

    private companion object {
        /** The intro is skipped only in the episode's first seconds. */
        const val START_WINDOW_MS = 8_000L
        /** An episode shorter than its skips and this much plays whole. */
        const val MIN_LEFT_MS = 30_000L
        const val END_MS = 250L
    }
}

/**
 * Which downloads to remove by themselves (AntennaPod's auto-delete, Escapepod's retention): played ones where "Remove played
 * downloads" is on, and beyond a show's "Keep newest" count the older ones. The playing episode and started ones always stay.
 */
object PodioRetention {
    fun toRemove(downloads: List<PodioItem>, saved: PodioSaved, playingId: String?, queue: PodioQueueState? = null): List<PodioItem> {
        val started = { id: String -> (saved.progress[id] ?: 0) > 0 && id !in saved.completedEpisodes }
        val shown = downloads.filter { it.id != playingId && it.id.startsWith("podio:episode:") }
        val showOf = shown.associate { it.id to PodioShowRules.showOf(it.id, saved, queue, it.author) }
        val played = shown.filter { it.id in saved.completedEpisodes && PodioShowRules.deletePlayed(saved, showOf[it.id]) }
        val beyond = shown.filter { showOf[it.id] != null && !started(it.id) }.groupBy { showOf.getValue(it.id)!! }.flatMap { (show, items) ->
            val keep = PodioShowRules.keepDownloads(saved, show)
            if (keep <= 0 || items.size <= keep) return@flatMap emptyList()
            val order = saved.knownEpisodes[show].orEmpty()
            // Newest first by the show's last refresh; what it no longer lists is older than all of it.
            items.sortedBy { order.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }.drop(keep)
        }
        return (played + beyond).distinctBy { it.id }
    }

    /** The downloads that are played to their end (Downloads' "Remove played"). */
    fun played(downloads: List<PodioItem>, saved: PodioSaved, playingId: String?): List<PodioItem> =
        downloads.filter { it.id != playingId && it.id in saved.completedEpisodes }
}
