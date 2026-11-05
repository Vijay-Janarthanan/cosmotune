package com.cosmotune.video.downloads

import com.cosmotune.video.model.VideoItem

/**
 * What holds the queue (spec 5.16: "Only on Wi-Fi" and "Pause when the battery is low" hold the queue with their own states):
 * the two settings and the two facts the platform reports. The desktop reports neither (no metered signal, no battery hold).
 */
data class QueueConditions(
    val wifiOnly: Boolean = false,
    val metered: Boolean = false,
    val pauseOnLowBattery: Boolean = false,
    val batteryLow: Boolean = false,
) {
    /** Only on Wi-Fi, and on mobile data. */
    val wifiHold: Boolean get() = wifiOnly && metered

    /** Pause when the battery is low, and it is. */
    val batteryHold: Boolean get() = pauseOnLowBattery && batteryLow

    companion object {
        /** Below this charge, and not charging, the battery is low (plan task Y-24). */
        const val LOW_BATTERY_PERCENT = 15

        /** Whether [percent] (negative when unknown) while [charging] counts as low. */
        fun isBatteryLow(percent: Int, charging: Boolean): Boolean = !charging && percent in 0 until LOW_BATTERY_PERCENT
    }
}

/** What happens to the queue (plan task Y-24, step 3). The engine sends them; [DownloadQueueState.reduce] applies them. */
sealed interface QueueEvent {
    /** One video to add and its choice (a [DownloadPlanner.plannedChoice] until its streams are known). */
    data class Add(val item: VideoItem, val choice: DownloadChoice)

    /** Adds downloads, alone ([batch] null) or as a batch; skips videos already downloaded or queued, and live or upcoming ones. */
    data class Enqueue(val adds: List<Add>, val request: DownloadRequest, val batch: DownloadBatchInfo?, val nowMs: Long) : QueueEvent

    /** A waiting entry starts with its resolved streams; refused when it is no longer waiting. */
    data class Start(val id: String, val choice: DownloadChoice) : QueueEvent

    /** A running entry has fetched [fraction] of its bytes. */
    data class Progress(val id: String, val fraction: Float) : QueueEvent

    /** A running entry is joining its two files. */
    data class Join(val id: String) : QueueEvent

    /** An entry finished: its file (or the video half of a pair), its size, its subtitle files and the language it got. */
    data class Finish(val id: String, val path: String, val bytes: Long, val subtitleFiles: List<String>, val subtitleGot: String?) : QueueEvent

    /** An entry failed. */
    data class Fail(val id: String, val reason: String) : QueueEvent

    /** The user pauses an entry. */
    data class Pause(val id: String) : QueueEvent

    /** The user resumes an entry. */
    data class Resume(val id: String) : QueueEvent

    /** The user pauses every unfinished entry of a batch. */
    data class PauseBatch(val batchId: String) : QueueEvent

    /** The user resumes a batch. */
    data class ResumeBatch(val batchId: String) : QueueEvent

    /** The user cancels an entry: it goes, with its files. */
    data class Cancel(val id: String) : QueueEvent

    /** The user cancels a batch: every entry of it goes, with its files. */
    data class CancelBatch(val batchId: String) : QueueEvent

    /** The user tries a failed entry again. */
    data class Retry(val id: String) : QueueEvent

    /** The user removes an entry and its files. */
    data class Remove(val id: String) : QueueEvent

    /** The holds changed. */
    data class Conditions(val conditions: QueueConditions) : QueueEvent
}

/** The queue after an event, the entries that went (their files are to be deleted, [DownloadQueueState.filesOf]) and the running work to stop. */
data class QueueStep(val state: DownloadQueueState, val removed: List<DownloadEntry> = emptyList(), val stop: List<String> = emptyList())

/**
 * The download queue as a pure state machine (plan task Y-24, step 3; spec 5.10, 5.16): entries in the order added, the
 * batches, the next id, and the holds. One entry runs at a time, first in first out across batches; an entry goes
 * Waiting, then WaitingForWifi while Wi-Fi only holds on mobile data, then Paused(BATTERY_LOW) while the battery holds, then
 * Running, Joining (not for audio only), and Done or Failed; the user pauses (Paused(USER)) and resumes entries and batches.
 * A batch is paused when every unfinished entry of it is paused by the user. Nothing here touches a file: [reduce] says
 * what to delete and what to stop.
 */
data class DownloadQueueState(
    val entries: List<DownloadEntry> = emptyList(),
    val batches: List<DownloadBatchInfo> = emptyList(),
    val nextNumber: Long = 1,
    val conditions: QueueConditions = QueueConditions(),
) {
    /** The entry running or joining, or null. */
    val active: DownloadEntry? get() = entries.firstOrNull { it.state is DownloadState.Running || it.state == DownloadState.Joining }

    /** The entry to start now: the first waiting one when nothing runs, else null. */
    fun nextToStart(): DownloadEntry? = if (active != null) null else entries.firstOrNull { it.state == DownloadState.Waiting }

    /** The entry [id], or null. */
    fun entry(id: String): DownloadEntry? = entries.firstOrNull { it.id == id }

    /** Each batch's progress (spec 5.10: "12 of 24", Pause or Resume). */
    fun batchStates(): List<BatchState> = batches.map { info ->
        val items = entries.filter { it.batch?.id == info.id }
        val unfinished = items.filterNot { it.state is DownloadState.Done || it.state is DownloadState.Failed }
        BatchState(
            info = info, total = items.size, done = items.count { it.state is DownloadState.Done },
            paused = unfinished.isNotEmpty() && unfinished.all { it.state == DownloadState.Paused(PauseReason.USER) },
        )
    }

    /** Bytes of the finished downloads (spec 5.10: "Downloads use 1.4 GB"). */
    fun usedBytes(): Long = entries.sumOf { (it.state as? DownloadState.Done)?.bytes ?: 0L }

    /** The queue as it comes back after the app stopped: running, joining and held entries wait again; the holds are measured anew. */
    fun restored(): DownloadQueueState = copy(
        entries = entries.map { e ->
            when (e.state) {
                is DownloadState.Running, DownloadState.Joining, DownloadState.WaitingForWifi, DownloadState.Paused(PauseReason.BATTERY_LOW) -> e.copy(state = DownloadState.Waiting)
                else -> e
            }
        },
        conditions = QueueConditions(),
    )

    /** Applies [event]. */
    fun reduce(event: QueueEvent): QueueStep {
        val step = when (event) {
            is QueueEvent.Enqueue -> QueueStep(enqueue(event))
            is QueueEvent.Start -> QueueStep(edit(event.id) { if (it.state == DownloadState.Waiting) it.copy(state = DownloadState.Running(0f), choice = event.choice) else it })
            is QueueEvent.Progress -> QueueStep(edit(event.id) { if (it.state is DownloadState.Running) it.copy(state = DownloadState.Running(event.fraction.coerceIn(0f, 1f))) else it })
            is QueueEvent.Join -> QueueStep(edit(event.id) { if (it.state is DownloadState.Running) it.copy(state = DownloadState.Joining) else it })
            is QueueEvent.Finish -> QueueStep(edit(event.id) {
                if (it.state is DownloadState.Running || it.state == DownloadState.Joining) {
                    it.copy(state = DownloadState.Done(event.path, event.bytes), subtitleFiles = event.subtitleFiles, subtitleGot = event.subtitleGot)
                } else it
            })
            is QueueEvent.Fail -> QueueStep(edit(event.id) { if (it.state.isUnfinishedWork()) it.copy(state = DownloadState.Failed(event.reason)) else it })
            is QueueEvent.Pause -> pause { it.id == event.id }
            is QueueEvent.PauseBatch -> pause { it.batch?.id == event.batchId }
            is QueueEvent.Resume -> QueueStep(resume { it.id == event.id })
            is QueueEvent.ResumeBatch -> QueueStep(resume { it.batch?.id == event.batchId })
            is QueueEvent.Cancel -> drop { it.id == event.id }
            is QueueEvent.Remove -> drop { it.id == event.id }
            is QueueEvent.CancelBatch -> drop { it.batch?.id == event.batchId }
            is QueueEvent.Retry -> QueueStep(edit(event.id) { if (it.state is DownloadState.Failed) it.copy(state = DownloadState.Waiting) else it })
            is QueueEvent.Conditions -> QueueStep(copy(conditions = event.conditions))
        }
        return step.state.settled(step)
    }

    private fun DownloadState.isUnfinishedWork() = this is DownloadState.Running || this == DownloadState.Joining || this == DownloadState.Waiting

    private fun enqueue(event: QueueEvent.Enqueue): DownloadQueueState {
        var list = entries
        var number = nextNumber
        var added = false
        for (add in event.adds.distinctBy { it.item.id }) {
            if (add.item.isLive || add.item.isUpcoming) continue
            val existing = list.firstOrNull { it.item.id == add.item.id }
            when {
                existing == null -> {
                    list = list + DownloadEntry(
                        id = "$ID_PREFIX$number", item = add.item, kind = DownloadPlanner.kindOf(add.item, event.request), choice = add.choice,
                        request = event.request, state = DownloadState.Waiting, batch = event.batch, subtitleFiles = emptyList(),
                        subtitleGot = null, createdMs = event.nowMs,
                    )
                    number += 1
                    added = true
                }
                existing.state is DownloadState.Failed -> {
                    list = list.map {
                        if (it.id != existing.id) it
                        else it.copy(choice = add.choice, request = event.request, kind = DownloadPlanner.kindOf(add.item, event.request), state = DownloadState.Waiting, batch = event.batch ?: it.batch)
                    }
                    added = true
                }
            }
        }
        val batch = event.batch
        val batchList = if (batch != null && added && batches.none { it.id == batch.id }) batches + batch else batches
        return copy(entries = list, batches = batchList, nextNumber = number)
    }

    private fun edit(id: String, change: (DownloadEntry) -> DownloadEntry): DownloadQueueState = copy(entries = entries.map { if (it.id == id) change(it) else it })

    private fun pause(which: (DownloadEntry) -> Boolean): QueueStep {
        val stop = mutableListOf<String>()
        val list = entries.map { e ->
            if (!which(e)) return@map e
            when (e.state) {
                is DownloadState.Running -> { stop += e.id; e.copy(state = DownloadState.Paused(PauseReason.USER)) }
                DownloadState.Waiting, DownloadState.WaitingForWifi, DownloadState.Paused(PauseReason.BATTERY_LOW) -> e.copy(state = DownloadState.Paused(PauseReason.USER))
                else -> e
            }
        }
        return QueueStep(copy(entries = list), stop = stop)
    }

    private fun resume(which: (DownloadEntry) -> Boolean): DownloadQueueState =
        copy(entries = entries.map { if (which(it) && it.state is DownloadState.Paused) it.copy(state = DownloadState.Waiting) else it })

    private fun drop(which: (DownloadEntry) -> Boolean): QueueStep {
        val gone = entries.filter(which)
        val stop = gone.filter { it.state is DownloadState.Running || it.state == DownloadState.Joining }.map { it.id }
        return QueueStep(copy(entries = entries.filterNot(which)), removed = gone, stop = stop)
    }

    /** Applies the holds to every entry the holds concern, drops batches with no entry left, and keeps [step]'s removals and stops. */
    private fun settled(step: QueueStep): QueueStep {
        val hold = when {
            conditions.wifiHold -> DownloadState.WaitingForWifi
            conditions.batteryHold -> DownloadState.Paused(PauseReason.BATTERY_LOW)
            else -> null
        }
        val stop = step.stop.toMutableList()
        val list = entries.map { e ->
            when (e.state) {
                DownloadState.Waiting -> if (hold != null) e.copy(state = hold) else e
                is DownloadState.Running -> if (hold != null) { if (e.id !in stop) stop += e.id; e.copy(state = hold) } else e
                DownloadState.WaitingForWifi, DownloadState.Paused(PauseReason.BATTERY_LOW) -> if (e.state != (hold ?: DownloadState.Waiting)) e.copy(state = hold ?: DownloadState.Waiting) else e
                else -> e
            }
        }
        val kept = batches.filter { info -> list.any { it.batch?.id == info.id } }
        return QueueStep(copy(entries = list, batches = kept), step.removed, stop)
    }

    companion object {
        /** Entry ids are this and a number ("download-12"). */
        const val ID_PREFIX = "download-"

        /** The working files of entry [id] in the cache: the video and audio being fetched and the joined file being written. */
        fun partialNames(id: String): List<String> = listOf("$id.video.part", "$id.audio.part", "$id.joined.part")

        /**
         * Every file of [entry] (to delete on cancel and remove): its working files in [cacheDir] and, once done, its file (both
         * halves of a kept pair), its subtitle files and its sidecar beside it.
         */
        fun filesOf(entry: DownloadEntry, cacheDir: String): List<String> {
            val files = partialNames(entry.id).map { join(cacheDir, it) }.toMutableList()
            val local = entry.localFiles() ?: return files
            files += listOfNotNull(local.muxedPath, local.videoPath, local.audioPath)
            files += entry.subtitleFiles
            files += listOfNotNull(entry.sidecarPath())
            return files.distinct()
        }

        private fun join(dir: String, name: String): String {
            val separator = if ('\\' in dir && '/' !in dir) '\\' else '/'
            return if (dir.endsWith(separator)) "$dir$name" else "$dir$separator$name"
        }
    }
}
