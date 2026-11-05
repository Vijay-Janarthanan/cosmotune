package com.cosmotune.video.ui.downloads

import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadOptions
import com.cosmotune.video.downloads.DownloadPlanner
import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.RungOption
import com.cosmotune.video.downloads.SubtitleFiles
import com.cosmotune.video.downloads.SubtitleLanguage
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.actions.DownloadAsk

/** What the download host does with an ask (spec 5.16). */
sealed interface DownloadDecision {
    /** Show the sheet (phone) or the dialog (desktop). */
    data object Ask : DownloadDecision

    /** A choice is remembered: queue at once with [request] and say "Downloading in 720p · Change". */
    data class Start(val request: DownloadRequest) : DownloadDecision

    /** Everything asked for is downloaded already: the toast "Already downloaded", no sheet. */
    data object AlreadyDownloaded : DownloadDecision

    /** Everything asked for is in the queue already (waiting, running or paused): the toast "Already in Downloads · View", no sheet. */
    data object AlreadyInDownloads : DownloadDecision
}

/** The channel's "Download the latest" counts (spec 5.9: 5, 10, 25 or 50; plan task Y-32: 10 by default). */
object LatestCount {
    val CHOICES: List<Int> = listOf(5, 10, 25, 50)
    const val DEFAULT = 10
}

/**
 * The download sheet's pure rules (spec 5.16; plan task Y-32): whether an ask shows the sheet or starts at once, what a batch
 * queues, what the sheet starts with and what "Remember my choice" stores. No Compose, no engine call: the host and the sheet call
 * these, and the tests run them in memory.
 */
object DownloadAskResolver {

    /**
     * The decision for [ask], given the download [prefs] and the engine's [entries]:
     * 1. nothing left to download (one video downloaded, or every item of a batch without the latest picker downloaded or queued):
     *    [DownloadDecision.AlreadyDownloaded] when all of it is finished, else [DownloadDecision.AlreadyInDownloads];
     * 2. [DownloadDecision.Ask] when [forceAsk] ("Change" on the toast), when "Ask before each download" is on, or for a batch with
     *    the latest picker (its count is always asked);
     * 3. else [DownloadDecision.Start] with the remembered request.
     *
     * A failed download counts as not downloaded: asking again retries it.
     */
    fun resolve(prefs: DownloadPrefs, ask: DownloadAsk, entries: List<DownloadEntry> = emptyList(), forceAsk: Boolean = false): DownloadDecision {
        val picker = ask is DownloadAsk.Batch && ask.latestPicker
        if (!picker) {
            val items = itemsOf(ask)
            if (pending(items, entries).isEmpty()) {
                return if (items.all { isDone(it.id, entries) }) DownloadDecision.AlreadyDownloaded else DownloadDecision.AlreadyInDownloads
            }
        }
        if (forceAsk || prefs.askBeforeEach || picker) return DownloadDecision.Ask
        return DownloadDecision.Start(prefs.remembered)
    }

    /** The items of [items] still to download: not finished and not in the queue (a failed one is pending again). */
    fun pending(items: List<VideoItem>, entries: List<DownloadEntry>): List<VideoItem> {
        if (entries.isEmpty()) return items
        val taken = entries.filter { it.state !is DownloadState.Failed }.mapTo(HashSet()) { it.item.id }
        return items.filterNot { it.id in taken }
    }

    /** What a batch queues: the newest [count] of its items with the latest picker (all of them when fewer are loaded), else the whole list. */
    fun batchItems(ask: DownloadAsk.Batch, count: Int): List<VideoItem> = if (ask.latestPicker) ask.items.take(count) else ask.items

    /**
     * The settings after the sheet's "Download" with [request]: "Remember my choice" on stores it and stops asking
     * (`DownloadPrefs(askBeforeEach = false, remembered = request)`); off leaves the settings as they were.
     */
    fun confirm(settings: VideoSettings, request: DownloadRequest, remember: Boolean): VideoSettings =
        if (remember) settings.copy(downloads = DownloadPrefs(askBeforeEach = false, remembered = request)) else settings

    /** The quality the sheet starts on: the [remembered] one when [options] offer it (Auto and audio only always are), else Auto. */
    fun initialQuality(options: DownloadOptions, remembered: DownloadQuality): DownloadQuality = when (remembered) {
        DownloadQuality.Auto, DownloadQuality.AudioOnly -> remembered
        is DownloadQuality.Rung -> if (options.rungs.any { it.quality == remembered }) remembered else DownloadQuality.Auto
    }

    /**
     * The subtitle language the sheet starts on: the [remembered] language when [options] list it (a person's track before an
     * automatic one), else the app's language when the video has it, else the original language (spec 5.16; for a batch the app's
     * language), else the first; null when there is none.
     */
    fun initialLanguage(options: DownloadOptions, remembered: SubtitlePick, appLanguage: String): SubtitleLanguage? {
        val languages = options.subtitleLanguages
        if (languages.isEmpty()) return null
        val tag = remembered.language
        if (tag != null) {
            val match = if (tag.isEmpty()) languages.firstOrNull { it.tag.isEmpty() }
            else languages.firstOrNull { SubtitleFiles.sameLanguage(it.tag, tag) && !it.autoGenerated } ?: languages.firstOrNull { SubtitleFiles.sameLanguage(it.tag, tag) }
            if (match != null) return match
        }
        return DownloadPlanner.defaultSubtitle(options, appLanguage)
    }

    /** What the sheet asks for: [quality], and the subtitles in [language] when [subtitles] is on and there is a language to take. */
    fun request(quality: DownloadQuality, subtitles: Boolean, language: SubtitleLanguage?): DownloadRequest =
        DownloadRequest(quality, if (subtitles && language != null) SubtitlePick(true, language.tag) else SubtitlePick(false, null))

    /** The row Auto means (its size is Auto's): the rung labelled [DownloadOptions.autoMeans], else the top rung, else audio only. */
    fun autoOption(options: DownloadOptions): RungOption =
        options.rungs.firstOrNull { it.label == options.autoMeans } ?: options.rungs.firstOrNull() ?: options.audioOnly

    /** A rung's label for "Downloading in 720p" ("1080p60"); null for Auto and audio only, which the sheet names with its strings. */
    fun rungLabel(quality: DownloadQuality): String? =
        (quality as? DownloadQuality.Rung)?.let { QualityOption.labelOf(it.height, it.fps, hdr = false) }

    private fun itemsOf(ask: DownloadAsk): List<VideoItem> = when (ask) {
        is DownloadAsk.One -> listOf(ask.item)
        is DownloadAsk.Batch -> ask.items
    }

    private fun isDone(videoId: String, entries: List<DownloadEntry>): Boolean =
        entries.any { it.item.id == videoId && it.state is DownloadState.Done }
}
