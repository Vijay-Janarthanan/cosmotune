package com.cosmotune.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How smoothly the playing song is arriving, and how the output's clock keeps time against this device's own clock.
 * Everything here is measured from the player's position readings; nothing is guessed.
 */
data class PlaybackHealthSnapshot(
    val mediaId: String? = null,
    /** Times the song stopped to load more after it had started (seeks and the first start do not count). */
    val stalls: Int = 0,
    /** Time spent in those stops, including one still running. */
    val stalledMs: Long = 0,
    /** How much is loaded past the playhead, where the player says. */
    val bufferedAheadMs: Long? = null,
    /** How far the output's clock runs from this device's clock, in parts per million; null until enough was heard. */
    val driftPpm: Double? = null,
    /** How much steady playing the drift figure rests on. */
    val measuredMs: Long = 0,
) {
    /** "Smooth, no stops to load" or "Stopped to load 2 times, 4 s in all". */
    val streamLabel: String
        get() = if (stalls == 0) "Smooth, no stops to load"
        else "Stopped to load ${if (stalls == 1) "once" else "$stalls times"}, ${seconds(stalledMs)} in all"

    /** "38 s loaded ahead", or null where the player does not say. */
    val bufferLabel: String?
        get() = bufferedAheadMs?.let { "${seconds(it)} loaded ahead" }

    /** The clock comparison in plain words, or a line saying it is still listening. */
    val clockLabel: String
        get() {
            val ppm = driftPpm ?: return "Measuring while the song plays (needs a minute without pauses or skips)"
            if (abs(ppm) < 1.0) return "Keeps time with this device's clock"
            val msPerMinute = abs(ppm) * 60.0 / 1_000.0
            val pace = if (ppm > 0) "fast" else "slow"
            return "Runs ${"%.2f".format(Locale.ROOT, msPerMinute)} ms a minute $pace (${"%+.0f".format(Locale.ROOT, ppm)} ppm)"
        }

    private fun seconds(ms: Long): String {
        val s = (ms / 1_000.0).roundToInt()
        return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
    }
}

/**
 * Fed the player's position about once a second (or more often) by each platform's player. A reading is the playhead
 * [positionMs] at [nowNanos] on [System.nanoTime]'s clock. A jump of the playhead (a seek, a skip back) or a change of
 * speed starts a new stretch, so only steady playing counts towards the clock figure.
 */
class PlaybackHealthMonitor {
    private val mutable = MutableStateFlow(PlaybackHealthSnapshot())
    val state: StateFlow<PlaybackHealthSnapshot> = mutable.asStateFlow()

    private var mediaId: String? = null
    private var lastPos: Long? = null
    private var lastNanos: Long = 0
    private var lastAdvancing = false
    private var lastRate = 1f
    private var lastSeeks: Int? = null
    private var playedMs = 0L

    private var anchorPos: Long? = null
    private var anchorNanos = 0L
    private var segActual = 0.0
    private var segExpected = 0.0
    private var sumActual = 0.0
    private var sumExpected = 0.0

    private var stalls = 0
    private var stalledMs = 0L
    private var stallStart: Long? = null
    private var waitingAfterJump = false

    @Synchronized
    fun observe(
        mediaId: String?,
        positionMs: Long,
        wantsToPlay: Boolean,
        buffering: Boolean,
        rate: Float,
        nowNanos: Long,
        bufferedAheadMs: Long? = null,
        seeks: Int? = null,
    ) {
        if (mediaId != this.mediaId) reset(mediaId)
        val previous = lastPos
        val seeked = seeks != null && lastSeeks != null && seeks != lastSeeks
        val expectedStep = if (previous != null && lastAdvancing) (nowNanos - lastNanos) / 1e6 * lastRate else 0.0
        val jumped = seeked || (previous != null && abs((positionMs - previous) - expectedStep) > JUMP_MS)
        val advancing = wantsToPlay && !buffering && rate > 0f

        if (previous != null && lastAdvancing && !jumped) playedMs += (positionMs - previous).coerceAtLeast(0)

        // Stops to load: only once the song is under way, and not the wait that follows a jump.
        if (buffering && wantsToPlay) {
            if (jumped || playedMs < MIN_PLAYED_MS) waitingAfterJump = true
            if (!waitingAfterJump && stallStart == null) { stalls++; stallStart = nowNanos }
        } else {
            stallStart?.let { stalledMs += (nowNanos - it) / 1_000_000 }
            stallStart = null
            waitingAfterJump = false
        }

        // The clock: steady stretches of playing, compared with the wall clock at the same speed.
        val continues = advancing && lastAdvancing && !jumped && rate == lastRate && anchorPos != null
        if (continues) {
            segActual = (positionMs - anchorPos!!).toDouble()
            segExpected = (nowNanos - anchorNanos) / 1e6 * rate
        } else {
            closeSegment()
            if (advancing) { anchorPos = positionMs; anchorNanos = nowNanos } else anchorPos = null
        }

        lastPos = positionMs; lastNanos = nowNanos; lastAdvancing = advancing; lastRate = rate; lastSeeks = seeks
        val running = stallStart?.let { (nowNanos - it) / 1_000_000 } ?: 0
        val expected = sumExpected + segExpected
        val actual = sumActual + segActual
        mutable.value = PlaybackHealthSnapshot(
            mediaId = mediaId,
            stalls = stalls,
            stalledMs = stalledMs + running,
            bufferedAheadMs = bufferedAheadMs?.coerceAtLeast(0),
            driftPpm = if (expected >= MIN_MEASURED_MS) (actual - expected) / expected * 1e6 else null,
            measuredMs = expected.toLong(),
        )
    }

    private fun closeSegment() {
        sumActual += segActual; sumExpected += segExpected
        segActual = 0.0; segExpected = 0.0
    }

    private fun reset(id: String?) {
        mediaId = id; lastPos = null; lastAdvancing = false; lastSeeks = null; playedMs = 0
        anchorPos = null; segActual = 0.0; segExpected = 0.0; sumActual = 0.0; sumExpected = 0.0
        stalls = 0; stalledMs = 0; stallStart = null; waitingAfterJump = false
    }

    companion object {
        /** A playhead further than this from where steady playing would put it was moved on purpose. */
        const val JUMP_MS = 1_500.0
        /** A wait in the first two seconds is the song starting, not a stop. */
        const val MIN_PLAYED_MS = 2_000L
        /** A minute of steady playing before the clock figure means anything. */
        const val MIN_MEASURED_MS = 60_000.0
    }
}

/** The one monitor the playing song feeds, read by the player's Audio details. */
object PlaybackHealth {
    val monitor = PlaybackHealthMonitor()
}

/**
 * A fidelity score out of 100 for what is playing: what the decoder is fed (lossless or lossy, and at what bitrate),
 * then each thing between the decoder and the speakers that changes the samples. Each lost point comes with a reason.
 */
object FidelityScore {
    data class Deduction(val points: Int, val reason: String)

    data class Score(val value: Int, val deductions: List<Deduction>) {
        val label: String
            get() = when {
                value >= 95 -> "Excellent"
                value >= 85 -> "Very good"
                value >= 70 -> "Good"
                value >= 50 -> "Fair"
                else -> "Low"
            }
    }

    /** Null until the decoder has reported what it is being fed. */
    fun of(
        lossless: Boolean?,
        bitrateKbps: Int?,
        decodedRateHz: Int?,
        outputRateHz: Int?,
        facts: OutputFidelityFacts?,
    ): Score? {
        lossless ?: return null
        val out = mutableListOf<Deduction>()
        if (!lossless) out += when {
            bitrateKbps == null -> Deduction(25, "Lossy stream")
            bitrateKbps >= 256 -> Deduction(12, "Lossy stream at $bitrateKbps kbps")
            bitrateKbps >= 160 -> Deduction(20, "Lossy stream at $bitrateKbps kbps")
            bitrateKbps >= 128 -> Deduction(28, "Lossy stream at $bitrateKbps kbps")
            else -> Deduction(38, "Lossy stream at $bitrateKbps kbps")
        }
        if (facts?.dsdConvertedToPcm == true) out += Deduction(3, "DSD turned into PCM to play")
        facts?.alteringStage?.let { out += Deduction(5, "${it.replaceFirstChar(Char::uppercase)} changes the sound") }
        val rateChanged = decodedRateHz != null && outputRateHz != null && decodedRateHz > 0 && outputRateHz > 0 && decodedRateHz != outputRateHz
        if (rateChanged) out += Deduction(5, "Resampled from ${khz(decodedRateHz!!)} to ${khz(outputRateHz!!)}")
        else if (facts?.resampledByApp == true) out += Deduction(5, "Resampled on the way out")
        if (facts != null && !facts.encodingExact) out += Deduction(8, facts.encodingDetail?.replaceFirstChar(Char::uppercase) ?: "The output format can't carry every bit")
        when (facts?.mixing) {
            OutputMixing.SYSTEM_MIXER -> out += Deduction(2, "${facts.systemName.replaceFirstChar(Char::uppercase)}'s mixer may still change it")
            OutputMixing.DIRECT_UNCONFIRMED -> out += Deduction(1, "Direct output, not confirmed bit-perfect")
            else -> Unit
        }
        return Score((100 - out.sumOf { it.points }).coerceIn(0, 100), out)
    }

    private fun khz(hz: Int) = "${"%.1f".format(Locale.ROOT, hz / 1000f).removeSuffix(".0")} kHz"
}
