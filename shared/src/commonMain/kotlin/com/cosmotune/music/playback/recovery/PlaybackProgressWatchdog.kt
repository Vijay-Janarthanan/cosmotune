package com.cosmotune.music.playback.recovery

/**
 * Notices a song that says it is playing while its position stands still, which no error ever
 * reports: a stream that stopped delivering without failing, a decoder that hung, an output that
 * stopped taking audio. The idea is Metrolist's (GPL-3.0, `PlaybackProgressWatchdog.kt`).
 *
 * Fed a sample every second or two. Once the position has not moved for [stallMs] while the
 * player is playing and not buffering, [sample] answers [Verdict.Recover] — the host re-prepares
 * the song at the same position with a fresh stream link — at most [maxPerSong] times per song,
 * then [Verdict.GaveUp] once so the host can log it, and nothing after that for the same song.
 */
class PlaybackProgressWatchdog(
    private val now: () -> Long,
    private val stallMs: Long = STALL_MS,
    private val maxPerSong: Int = MAX_RECOVERIES_PER_SONG,
) {
    sealed interface Verdict {
        data object Fine : Verdict

        /** Re-prepare the song where it stands; [attempt] counts from 1. */
        data class Recover(val attempt: Int, val stalledMs: Long) : Verdict

        /** The song has stalled again after its last recovery; reported once. */
        data object GaveUp : Verdict
    }

    private var songId: String? = null
    private var recoveries = 0
    private var reportedGiveUp = false
    private var anchorPositionMs = Long.MIN_VALUE
    private var anchorAt = 0L

    fun sample(songId: String?, playing: Boolean, buffering: Boolean, positionMs: Long): Verdict {
        val at = now()
        if (songId != this.songId) {
            this.songId = songId
            recoveries = 0
            reportedGiveUp = false
            anchor(positionMs, at)
            return Verdict.Fine
        }
        if (songId == null || !playing || buffering || positionMs != anchorPositionMs) {
            anchor(positionMs, at)
            return Verdict.Fine
        }
        val stalled = at - anchorAt
        if (stalled < stallMs) return Verdict.Fine
        // Measured afresh from here, so a recovery that has not landed yet is not counted twice.
        anchor(positionMs, at)
        if (recoveries < maxPerSong) {
            recoveries++
            return Verdict.Recover(recoveries, stalled)
        }
        if (reportedGiveUp) return Verdict.Fine
        reportedGiveUp = true
        return Verdict.GaveUp
    }

    /** Forget the song in hand, e.g. when the player is released. */
    fun reset() {
        songId = null
        recoveries = 0
        reportedGiveUp = false
        anchorPositionMs = Long.MIN_VALUE
    }

    private fun anchor(positionMs: Long, at: Long) {
        anchorPositionMs = positionMs
        anchorAt = at
    }

    companion object {
        const val STALL_MS = 12_000L
        const val MAX_RECOVERIES_PER_SONG = 2

        /** How often the hosts sample. Well under [STALL_MS], so a stall is caught within a second or two of it. */
        const val SAMPLE_EVERY_MS = 1_000L
    }
}

/**
 * Queue rules for the moment a song fails or a repeat-all round starts over, shared by the phone
 * and the desktop. From LastWave Native (GPL-3.0, commit c3594b90a6): stop at the end of the queue
 * instead of going round again, and deal a new shuffle order for every repeat-all round.
 */
object QueueRecoveryRules {
    /**
     * Whether moving past a song that failed may go to [nextId]. Nothing after it ([nextId] null)
     * means stop: with repeat off that is the end of the queue, and stopping is the honest end
     * state rather than a reason to retry. A next song that already failed since anything last
     * played (in [failedSinceAudible]) means the skips have gone all the way round a repeat-all
     * queue — or a queue of one — and going on would loop forever.
     */
    fun mayMoveTo(nextId: String?, failedSinceAudible: Set<String>): Boolean =
        nextId != null && nextId !in failedSinceAudible

    /**
     * Whether a transition is repeat-all starting the queue over with shuffle on, which is when a
     * fresh order is dealt. Without it every round replays the same "shuffled" sequence. Two songs
     * have only one order after the first, so it takes three.
     */
    fun reshuffleOnWrap(
        shuffle: Boolean,
        repeatAll: Boolean,
        fromIndex: Int,
        toIndex: Int,
        queueSize: Int,
    ): Boolean = shuffle && repeatAll && queueSize > 2 && fromIndex == queueSize - 1 && toIndex == 0
}
