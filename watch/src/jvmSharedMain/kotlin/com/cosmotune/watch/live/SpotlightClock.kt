package com.cosmotune.watch.live

import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveFocus
import com.cosmotune.watch.model.MatchClock
import com.cosmotune.watch.model.MatchPhase

/**
 * The football minute between two reads: ESPN's clock as last read keeps going on its own, so 67' becomes 68' without a fetch,
 * and stops at half-time and full time. Never more than [MAX_DRIFT_MIN] minutes past the last read (a source that stopped
 * answering must not count on for ever).
 */
object SpotlightClock {
    const val MAX_DRIFT_MIN = 15

    /** "67'", "45'+2'", "90'+4'", "105'+1'" while the ball is in play; null when [clock] is not in a half. */
    fun minute(clock: MatchClock, nowMs: Long): String? {
        val base = when (clock.phase) {
            MatchPhase.FIRST_HALF -> 45
            MatchPhase.SECOND_HALF -> 90
            MatchPhase.EXTRA_TIME -> if (clock.period >= 4) 120 else 105
            else -> return null
        }
        val elapsedSec = ((nowMs - clock.readAtMs).coerceAtLeast(0) / 1000).coerceAtMost(MAX_DRIFT_MIN * 60L)
        val shown = clock.display?.let(::parseDisplay)
        val minute = when {
            shown?.second != null -> shown.first + shown.second!! + (elapsedSec / 60).toInt()
            clock.seconds != null && clock.seconds > 0 -> ((clock.seconds + elapsedSec) / 60).toInt() + 1
            shown != null -> shown.first + (elapsedSec / 60).toInt()
            else -> return null
        }
        return if (minute > base) "$base'+${minute - base}'" else "$minute'"
    }

    /** "67'", "45'+2'" read into 67 and null, 45 and 2; null when it is not a minute. */
    fun parseDisplay(display: String): Pair<Int, Int?>? {
        val m = Regex("""^\s*(\d{1,3})'?\s*(?:\+\s*(\d{1,2})'?)?\s*$""").find(display) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toIntOrNull()
    }

    /**
     * The words of a match's pill: the running minute while in play, "Half-time", "Break", "Penalties", "Full time"; a cricket
     * match's own status (a break in play is said as it is: "Stumps", "Lunch"); null for what is not a match.
     */
    fun pill(event: LiveEvent, nowMs: Long): String? {
        if (!event.isMatch) return null
        if (event.kind == LiveEventKind.CRICKET) return when (event.status) {
            LiveEventStatus.LIVE -> cricketBreak(event.statusText) ?: "Live"
            LiveEventStatus.FINISHED -> "Result"
            else -> null
        }
        val clock = event.clock
        return when (clock?.phase) {
            MatchPhase.FIRST_HALF, MatchPhase.SECOND_HALF, MatchPhase.EXTRA_TIME -> minute(clock, nowMs) ?: "Live"
            MatchPhase.HALF_TIME -> "Half-time"
            MatchPhase.BREAK -> "Break"
            MatchPhase.PENALTIES -> "Penalties"
            MatchPhase.FULL_TIME -> event.statusText ?: "Full time"
            MatchPhase.POSTPONED -> "Postponed"
            else -> if (event.isLive) "Live" else if (event.status == LiveEventStatus.FINISHED) "Full time" else null
        }
    }

    /** Whether the ball is in play now (the pill's dot pulses): a half or extra time or penalties; cricket in play, not at a break. */
    fun inPlay(event: LiveEvent): Boolean {
        if (!event.isLive) return false
        if (event.kind == LiveEventKind.CRICKET) return cricketBreak(event.statusText) == null
        return when (event.clock?.phase) {
            MatchPhase.HALF_TIME, MatchPhase.BREAK, MatchPhase.FULL_TIME, MatchPhase.PRE, MatchPhase.POSTPONED -> false
            else -> true
        }
    }

    /** A break in a cricket match ("Stumps", "Lunch", "Tea", "Drinks", "Rain", "Innings break") from its status line, or null. */
    fun cricketBreak(status: String?): String? {
        val s = status?.lowercase() ?: return null
        return listOf("stumps" to "Stumps", "lunch" to "Lunch", "tea" to "Tea", "drinks" to "Drinks", "innings break" to "Innings break",
            "rain" to "Rain delay", "bad light" to "Bad light", "delayed" to "Delayed")
            .firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(s) }?.second
    }
}

/**
 * When the Spotlight reads its sources again. While a match is live: every 30 s with the sheet open, every 15 s with a match's
 * detail open, every minute for the strip alone. With nothing live: every 10 minutes, and at the next kick-off so a match turns
 * live on time (then every minute for a while if the source is late to say so). Nothing at all while the app is not in front:
 * the caller stops, and reads at once on return.
 */
object SpotlightSchedule {
    const val SHEET_LIVE_MS = 30_000L
    const val DETAIL_LIVE_MS = 15_000L
    const val STRIP_LIVE_MS = 60_000L
    const val IDLE_MS = 10 * 60_000L
    /** The soonest a kick-off wake-up comes, so a clock a little off does not spin. */
    const val MIN_WAKE_MS = 5_000L
    /** After its kick-off a match not yet live is asked about each minute for this long. */
    const val LATE_START_MS = 15 * 60_000L
    /** A live match whose data is older than this is said to be behind. */
    const val STALE_MS = 2 * 60_000L

    /** The wait before the next read, or null for none (the app is not in front). */
    fun nextPollMs(events: List<LiveEvent>, nowMs: Long, focus: LiveFocus, foreground: Boolean = true): Long? {
        if (!foreground) return null
        val matches = events.filter { it.isMatch }
        if (matches.any { it.isLive }) return when (focus) {
            LiveFocus.DETAIL -> DETAIL_LIVE_MS
            LiveFocus.SHEET -> SHEET_LIVE_MS
            LiveFocus.STRIP -> STRIP_LIVE_MS
        }
        val upcoming = matches.filter { it.status == LiveEventStatus.UPCOMING }.mapNotNull { it.startMs }
        if (upcoming.any { it <= nowMs && nowMs - it <= LATE_START_MS }) return if (focus == LiveFocus.STRIP) STRIP_LIVE_MS else SHEET_LIVE_MS
        val next = upcoming.filter { it > nowMs }.minOrNull() ?: return IDLE_MS
        return (next - nowMs).coerceIn(MIN_WAKE_MS, IDLE_MS)
    }

    /** A live match's scores are older than [STALE_MS]: the sheet says they may be behind. */
    fun isBehind(events: List<LiveEvent>, nowMs: Long): Boolean =
        events.any { it.isMatch && it.isLive && it.updatedAtMs > 0 && nowMs - it.updatedAtMs > STALE_MS }
}

/** The time windows of the Spotlight's lists. */
object SpotlightWindows {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    /** Matches coming up are listed this far ahead. */
    const val MATCHES_AHEAD_MS = 7 * DAY
    /** Major events (elections, summits) are listed this far ahead. */
    const val EVENTS_AHEAD_MS = 60 * DAY

    /** When [e] ended, as well as it is known: its own end, or its start and a match's usual length. */
    fun endOf(e: LiveEvent): Long? = e.endMs ?: e.startMs?.let { it + if (e.kind == LiveEventKind.CRICKET) 8 * HOUR else 2 * HOUR }

    /** A result of the last 24 hours. */
    fun justFinished(e: LiveEvent, nowMs: Long): Boolean {
        if (e.status != LiveEventStatus.FINISHED) return false
        val end = endOf(e) ?: return false
        return minOf(end, nowMs) >= nowMs - DAY
    }

    /** Coming up: a match in the next week (or one whose kick-off has just passed), a major event in the next 60 days, a show today. */
    fun upcoming(e: LiveEvent, nowMs: Long): Boolean {
        if (e.status != LiveEventStatus.UPCOMING) return false
        val start = e.startMs ?: return false
        return when (e.kind) {
            LiveEventKind.EVENT -> start in (nowMs - DAY)..(nowMs + EVENTS_AHEAD_MS)
            LiveEventKind.SHOW -> start in (nowMs - HOUR)..(nowMs + DAY)
            else -> start in (nowMs - HOUR)..(nowMs + MATCHES_AHEAD_MS)
        }
    }

    /** Whether [e] belongs in the Spotlight now. */
    fun shown(e: LiveEvent, nowMs: Long): Boolean = when (e.status) {
        LiveEventStatus.LIVE -> true
        LiveEventStatus.UPCOMING -> upcoming(e, nowMs)
        LiveEventStatus.FINISHED -> justFinished(e, nowMs)
        LiveEventStatus.FEATURED -> true
    }
}
