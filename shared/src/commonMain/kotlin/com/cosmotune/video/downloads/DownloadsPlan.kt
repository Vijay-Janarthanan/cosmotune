package com.cosmotune.video.downloads

/** The filter chips of the Downloads page (spec 5.10): All, Videos, Shorts, Audio, by [DownloadKind]. */
enum class DownloadsFilter { ALL, VIDEOS, SHORTS, AUDIO }

/** The sort of the Downloads page (spec 5.10, the sort menu): Newest, Oldest, Title, Size. */
enum class DownloadsSort { NEWEST, OLDEST, TITLE, SIZE }

/** What a tap or a row of a download's sheet does (spec 5.10): Play, Pause, Resume, Try again, Cancel, Delete, Save a copy to Movies. */
enum class DownloadItemAction { PLAY, PAUSE, RESUME, RETRY, CANCEL, DELETE, SAVE_COPY }

/** One row of the Downloads page (spec 5.10): a batch's header, or one download. [key] is the row's lazy-list key. */
sealed interface DownloadsRow {
    val key: String

    /**
     * A batch's header row: its [info] (the title), "[done] of [total]" over the whole batch (whatever the filter), the [progress]
     * line (what is done, and the part of what runs, over [total]), whether it is [paused] (Resume instead of Pause), how many of
     * its downloads are [unfinished] (neither done nor failed: none, no Pause, Resume or Cancel), and whether the page [collapsed]
     * it (its items are then left out).
     */
    data class Batch(
        val info: DownloadBatchInfo,
        val done: Int,
        val total: Int,
        val progress: Float,
        val paused: Boolean,
        val unfinished: Int,
        val collapsed: Boolean,
    ) : DownloadsRow {
        override val key: String get() = "batch:${info.id}"
    }

    /** One download; [batchId] is the batch whose header stands above it, null for a loose one. */
    data class Item(val entry: DownloadEntry, val batchId: String?) : DownloadsRow {
        override val key: String get() = "item:${entry.id}"
    }
}

/**
 * The parts of a finished download's line (spec 5.10: "720p · 312 MB · Subtitles: English"): the [quality] label (null for audio
 * only, which the page says in its own words), the [size] of the file, the [subtitles] language it got, and [missedSubtitles] when
 * subtitles were asked for and none were found.
 */
data class DoneParts(val quality: String?, val size: String, val subtitles: String?, val missedSubtitles: Boolean)

/**
 * What the Downloads page shows (plan task Y-33, step 1; spec 5.10), worked out from the engine's entries and batches. Pure: the
 * page keeps the filter, the sort and which batches are collapsed, and draws the rows [build] gives.
 */
object DownloadsPlan {
    /**
     * The page's rows for [filter] and [sort]: each batch that has a download the filter keeps, as its header with those downloads
     * under it (none while its id is in [collapsed]), then the loose downloads. Downloads are sorted by [sort]: Newest and Oldest by
     * when they were added, Title by the video's title ignoring case, Size the largest first (the file's size once done, the estimate
     * before); two that tie keep the order they were added in, so a batch keeps its own order. Batches are sorted the same way: by
     * their newest or oldest download, their title, their total size. A batch's counters and progress cover all of it; its title
     * and paused state come from [batches] when the engine lists it, else from its downloads (the engine's rule: paused when every
     * unfinished download is paused by the user).
     */
    fun build(
        entries: List<DownloadEntry>,
        batches: List<BatchState>,
        filter: DownloadsFilter,
        sort: DownloadsSort,
        collapsed: Set<String> = emptySet(),
    ): List<DownloadsRow> {
        if (entries.isEmpty()) return emptyList()
        val indexed = entries.withIndex().toList()
        val groups = LinkedHashMap<String, MutableList<IndexedValue<DownloadEntry>>>()
        val loose = ArrayList<IndexedValue<DownloadEntry>>()
        for (each in indexed) {
            val batch = each.value.batch
            if (batch == null) loose += each else groups.getOrPut(batch.id) { ArrayList() } += each
        }
        val order = itemOrder(sort)
        val rows = ArrayList<DownloadsRow>()
        for ((batchId, members) in groups.entries.sortedWith(batchOrder(sort))) {
            val shown = members.filter { matches(it.value, filter) }
            if (shown.isEmpty()) continue
            val known = batches.firstOrNull { it.info.id == batchId }
            val header = header(known?.info ?: members.first().value.batch!!, members.map { it.value }, known, batchId in collapsed)
            rows += header
            if (!header.collapsed) shown.sortedWith(order).mapTo(rows) { DownloadsRow.Item(it.value, batchId) }
        }
        loose.filter { matches(it.value, filter) }.sortedWith(order).mapTo(rows) { DownloadsRow.Item(it.value, null) }
        return rows
    }

    /** Whether [filter] keeps [entry]: All keeps everything, the others one [DownloadKind]. */
    fun matches(entry: DownloadEntry, filter: DownloadsFilter): Boolean = when (filter) {
        DownloadsFilter.ALL -> true
        DownloadsFilter.VIDEOS -> entry.kind == DownloadKind.VIDEO
        DownloadsFilter.SHORTS -> entry.kind == DownloadKind.SHORT
        DownloadsFilter.AUDIO -> entry.kind == DownloadKind.AUDIO
    }

    /** The size the Size sort uses: the file's once done, the estimate of the chosen streams before. */
    fun sizeOf(entry: DownloadEntry): Long = (entry.state as? DownloadState.Done)?.bytes ?: entry.choice.estimatedBytes

    /** How far [entry] has come, 0 to 1: done (and joining, whose bytes are all fetched) 1, running its fraction, the rest 0. */
    fun progressOf(entry: DownloadEntry): Float = when (val s = entry.state) {
        is DownloadState.Done, DownloadState.Joining -> 1f
        is DownloadState.Running -> s.fraction.coerceIn(0f, 1f)
        else -> 0f
    }

    /** A running download's percentage, whole, 0 to 100 (spec 5.10: the ring's percentage). */
    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).toInt()

    /** The storage line's two sizes (spec 5.10: "Downloads use 1.4 GB · 21 GB free") from the engine's `storage()`, written as the sheet writes sizes. */
    fun storage(storage: Pair<Long, Long>): Pair<String, String> =
        DownloadPlanner.sizeText(storage.first) to DownloadPlanner.sizeText(storage.second)

    /** The parts of a finished download's line, or null until it is done. */
    fun doneParts(entry: DownloadEntry): DoneParts? {
        val done = entry.state as? DownloadState.Done ?: return null
        val quality = if (entry.kind == DownloadKind.AUDIO || entry.choice.video == null) null else entry.choice.label
        val got = entry.subtitleGot?.takeIf { it.isNotBlank() }
        return DoneParts(quality, DownloadPlanner.sizeText(done.bytes), got, missedSubtitles = got == null && entry.request.subtitles.enabled)
    }

    /** What a tap on [entry]'s row does: a finished download plays (offline), a failed one tries again, any other opens its sheet (null). */
    fun tapAction(entry: DownloadEntry): DownloadItemAction? = when (entry.state) {
        is DownloadState.Done -> DownloadItemAction.PLAY
        is DownloadState.Failed -> DownloadItemAction.RETRY
        else -> null
    }

    /**
     * The rows of [entry]'s sheet (spec 5.10), the delete last: finished, Play, Save a copy to Movies (when [canSaveCopy], the
     * platform has it, and [canSaveCopy] of the entry holds) and Delete; failed, Try again and Delete; paused by the user, Resume and
     * Cancel; waiting, held for Wi-Fi or a low battery, or running, Pause and Cancel; joining, Cancel.
     */
    fun actionsFor(entry: DownloadEntry, canSaveCopy: Boolean): List<DownloadItemAction> = when (val s = entry.state) {
        is DownloadState.Done -> listOfNotNull(
            DownloadItemAction.PLAY,
            DownloadItemAction.SAVE_COPY.takeIf { canSaveCopy && canSaveCopy(entry) },
            DownloadItemAction.DELETE,
        )
        is DownloadState.Failed -> listOf(DownloadItemAction.RETRY, DownloadItemAction.DELETE)
        is DownloadState.Paused ->
            if (s.reason == PauseReason.USER) listOf(DownloadItemAction.RESUME, DownloadItemAction.CANCEL)
            else listOf(DownloadItemAction.PAUSE, DownloadItemAction.CANCEL)
        DownloadState.Waiting, DownloadState.WaitingForWifi, is DownloadState.Running -> listOf(DownloadItemAction.PAUSE, DownloadItemAction.CANCEL)
        DownloadState.Joining -> listOf(DownloadItemAction.CANCEL)
    }

    /** Whether [entry] can be copied to Movies (spec 5.10, Q11): finished, a video, and one playable file (not the pair a failed join keeps). */
    fun canSaveCopy(entry: DownloadEntry): Boolean =
        entry.state is DownloadState.Done && entry.kind != DownloadKind.AUDIO && entry.localFiles()?.muxedPath != null

    /** Whether deleting [entry] is the engine's `remove` (finished or failed) rather than its `cancel` (anything still to do). */
    fun removes(entry: DownloadEntry): Boolean = entry.state is DownloadState.Done || entry.state is DownloadState.Failed

    /**
     * What a batch's Cancel takes away: null when nothing of batch [batchId] is finished (the engine's `cancelBatch`, which deletes
     * the whole batch), else the ids of its downloads that are not (cancelled one by one), so a Cancel never deletes a video that
     * is already downloaded.
     */
    fun batchCancelIds(entries: List<DownloadEntry>, batchId: String): List<String>? {
        val members = entries.filter { it.batch?.id == batchId }
        if (members.none { it.state is DownloadState.Done }) return null
        return members.filterNot { it.state is DownloadState.Done }.map { it.id }
    }

    private fun header(info: DownloadBatchInfo, members: List<DownloadEntry>, known: BatchState?, collapsed: Boolean): DownloadsRow.Batch {
        val unfinished = members.filterNot { it.state is DownloadState.Done || it.state is DownloadState.Failed }
        val paused = known?.paused ?: (unfinished.isNotEmpty() && unfinished.all { it.state == DownloadState.Paused(PauseReason.USER) })
        val total = members.size
        return DownloadsRow.Batch(
            info = info,
            done = members.count { it.state is DownloadState.Done },
            total = total,
            progress = if (total == 0) 0f else (members.sumOf { progressOf(it).toDouble() } / total).toFloat(),
            paused = paused,
            unfinished = unfinished.size,
            collapsed = collapsed,
        )
    }

    /** Two downloads in [sort]'s order; a tie keeps the order they were added in. */
    private fun itemOrder(sort: DownloadsSort): Comparator<IndexedValue<DownloadEntry>> {
        val first: Comparator<IndexedValue<DownloadEntry>> = when (sort) {
            DownloadsSort.NEWEST -> compareByDescending { it.value.createdMs }
            DownloadsSort.OLDEST -> compareBy { it.value.createdMs }
            DownloadsSort.TITLE -> compareBy { it.value.item.title.lowercase() }
            DownloadsSort.SIZE -> compareByDescending { sizeOf(it.value) }
        }
        return first.thenBy { it.index }
    }

    /** Two batches in [sort]'s order, by their newest or oldest download, their title, their total size; a tie keeps the order added. */
    private fun batchOrder(sort: DownloadsSort): Comparator<Map.Entry<String, List<IndexedValue<DownloadEntry>>>> {
        val first: Comparator<Map.Entry<String, List<IndexedValue<DownloadEntry>>>> = when (sort) {
            DownloadsSort.NEWEST -> compareByDescending { group -> group.value.maxOf { it.value.createdMs } }
            DownloadsSort.OLDEST -> compareBy { group -> group.value.minOf { it.value.createdMs } }
            DownloadsSort.TITLE -> compareBy { group -> group.value.first().value.batch?.title.orEmpty().lowercase() }
            DownloadsSort.SIZE -> compareByDescending { group -> group.value.sumOf { sizeOf(it.value) } }
        }
        return first.thenBy { group -> group.value.minOf { it.index } }
    }
}
