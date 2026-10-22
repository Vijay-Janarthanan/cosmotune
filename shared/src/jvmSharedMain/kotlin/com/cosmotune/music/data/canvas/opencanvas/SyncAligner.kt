package com.opencanvas.core.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Outcome of [SyncAligner.align]: how a song's timeline maps onto its music video's.
 *
 * @property offsetMs Offset of the longest segment, for hosts that can only use one number; 0 on failure.
 * @property confidence Matched share of the song times the mean match quality, in `0..1`; 0 on failure.
 * @property coverage Share of the song that has a trusted match in the video, in `0..1`.
 * @property segments The song-to-video map; empty on failure. See [SyncSegment] for the offset contract.
 * @property error Why no trustworthy map was found, or null when [segments] is a usable answer.
 */
private const val MAX_LEAD_IN_MS = 30_000L

class AlignResult(
    val offsetMs: Long,
    val confidence: Double,
    val coverage: Double,
    val segments: List<SyncSegment>,
    val error: String? = null,
) {
    /**
     * This result for a video whose envelope was stretched by [rate] before aligning (video time =
     * [rate] x stretched time): each segment gets that rate and its offset converted from the stretched
     * timeline back to the video's own.
     */
    /**
     * The first segment reaches back to the start of the song when the lead-in it leaves unmatched is
     * short (an intro the music video does not share windows with): the same offset and speed carry
     * on, so the picture is there from the first second instead of appearing part-way in.
     */
    internal fun fromTheStart(): AlignResult {
        val first = segments.firstOrNull() ?: return this
        if (first.songStartMs <= 0L || first.songStartMs > MAX_LEAD_IN_MS) return this
        val extended = SyncSegment(0L, first.songEndMs, first.offsetAtMs(0L), first.ncc, first.rate)
        return AlignResult(offsetMs, confidence, coverage, listOf(extended) + segments.drop(1), error)
    }

    internal fun atRate(rate: Double): AlignResult {
        val converted = segments.map {
            SyncSegment(it.songStartMs, it.songEndMs, ((rate - 1.0) * it.songStartMs + rate * it.offsetMs).roundToLong(), it.ncc, rate)
        }
        val longest = converted.maxByOrNull { it.songEndMs - it.songStartMs }
        return AlignResult(longest?.offsetAtMs(0L) ?: 0L, confidence, coverage, converted, error)
    }
}

/**
 * Every tunable of the alignment, named after and defaulting to its counterpart in the reference
 * implementation (`tools/opencanvas-stream-server/server.py`), which is tuned for audio waveforms.
 * [ENVELOPE] is the tuning for the coarse loudness envelopes the on-device pipeline feeds in.
 *
 * @property windowS Length, in seconds, of each song window that is matched against the video.
 * @property hopS Spacing, in seconds, of the windows.
 * @property minWindowS Windows shorter than this make the audio "too short to align".
 * @property silenceFloorRms Absolute RMS below which a window is silent and carries no information.
 * @property silenceRelRms ... or below this fraction of the whole song's RMS.
 * @property minLocalRms Video slices quieter than this RMS cannot produce a peak.
 * @property candidatesPerWindow How many of the strongest correlation peaks of a window are kept.
 * @property candidateMinNcc A peak weaker than this (normalised cross-correlation) is not a candidate.
 * @property candidateSeparationMs Peaks closer than this are the same match.
 * @property sameOffsetMs Offsets within this are "the same" (encodes differ slightly).
 * @property jumpBase Cost of any change of offset between two consecutive windows ...
 * @property jumpPer10s ... plus this much per 10 s of change: real edits move the video by seconds,
 * @property jumpMax ... capped here, while a chorus repeating a minute away is a different place, not an edit.
 * @property minSegmentWindows A segment shorter than this many windows is dropped.
 * @property fillGapMaxS Gaps up to this many seconds between two segments are split at the midpoint.
 * @property fillEdgeMaxS An unmatched head or tail up to this many seconds is absorbed by its neighbour.
 * @property minCoverage Below this share of the song matched, the answer is "could not align".
 * @property confidenceFullCoverage Coverage at which the match quality alone sets the confidence.
 */
data class AlignParams(
    val windowS: Double = 10.0,
    val hopS: Double = 5.0,
    val minWindowS: Double = 4.0,
    val silenceFloorRms: Double = 0.003,
    val silenceRelRms: Double = 0.10,
    val minLocalRms: Double = 1e-3,
    val candidatesPerWindow: Int = 6,
    val candidateMinNcc: Double = 0.45,
    val candidateSeparationMs: Double = 1500.0,
    val sameOffsetMs: Double = 150.0,
    val jumpBase: Double = 0.6,
    val jumpPer10s: Double = 0.25,
    val jumpMax: Double = 3.0,
    val minSegmentWindows: Int = 2,
    val fillGapMaxS: Double = 45.0,
    val fillEdgeMaxS: Double = 20.0,
    val minCoverage: Double = 0.25,
    val confidenceFullCoverage: Double = 0.6,
) {
    init {
        require(windowS > 0.0 && hopS > 0.0 && minWindowS > 0.0) { "window, hop and minimum window must be > 0" }
        require(candidatesPerWindow >= 1) { "candidatesPerWindow must be >= 1, was $candidatesPerWindow" }
    }

    companion object {
        /**
         * For the frame-size envelopes (50 Hz, high-passed): no silence gate, since an envelope has no
         * absolute loudness; a lower floor for quiet video slices; and a lower correlation for a peak to
         * count as a candidate, since an envelope is only a coarse proxy of the waveform. The rest is the
         * reference tuning.
         */
        val ENVELOPE = AlignParams(
            silenceFloorRms = 0.0,
            silenceRelRms = 0.0,
            minLocalRms = 1e-4,
            candidateMinNcc = 0.2,
        )
    }
}

/**
 * Measures how a song's signal lines up with that of its music video, entirely on the device: a
 * faithful port of `estimate_map` from the reference daemon.
 *
 * A music video is usually an *edit* of the song (intro, repeated or dropped bars, outro), so the answer
 * is a map from song time to video time rather than one offset. The song is cut into overlapping windows
 * ([AlignParams.windowS], hopping [AlignParams.hopS]); each is cross-correlated with the *whole* video by
 * FFT and normalised by the energy of the matched slice. The strongest few peaks of each window are
 * candidate positions. Because pop songs repeat loops and choruses, the windows are decided together: a
 * Viterbi pass picks one candidate (or none) per window, maximising the match quality minus a penalty
 * for every change of offset, so a repeat that would make the picture jump around loses to the chain
 * that stays continuous while a genuine edit, followed by many windows that agree with the new offset,
 * survives.
 *
 * Both signals are mono floats at the same sample rate. Offsets follow the [SyncSegment] contract:
 * inside a segment `videoTime = songTime + offsetMs`.
 */
object SyncAligner {

    /**
     * Builds the full song-to-video map.
     *
     * @param track Samples of the song (time 0 is the start of the song).
     * @param video Samples of the music video (time 0 is the start of the video).
     * @param sampleRate Sample rate of both signals, in Hz.
     * @param params The tuning; the default suits the frame-size envelopes.
     * @return The map, or a result with [AlignResult.error] set when no trustworthy map was found.
     */
    fun align(
        track: FloatArray,
        video: FloatArray,
        sampleRate: Int,
        params: AlignParams = AlignParams.ENVELOPE,
    ): AlignResult {
        val plain = alignAtRate(track, video, sampleRate, params)
        if (plain.error == null && plain.confidence >= RATE_SEARCH_BELOW) return plain.fromTheStart()
        // A film video often plays the song faster or slower than the album track (a PAL transfer is
        // 25/24 off, other masters differ by more): look for a speed at which the two do line up, coarse
        // grid first and then around the best, and keep it only when it clearly beats the plain fit.
        var best = plain
        var bestRate = 1.0
        fun tryRate(rate: Double) {
            val fit = alignAtRate(track, stretch(video, rate), sampleRate, params)
            if (fit.error != null || fit.confidence < MIN_RATE_CONFIDENCE) return
            if (fit.confidence > best.confidence + (if (bestRate == 1.0) RATE_MARGIN else 0.0)) {
                best = fit.atRate(rate)
                bestRate = rate
            }
        }
        var rate = RATE_FROM
        while (rate <= RATE_TO + 1e-9) {
            if (abs(rate - 1.0) > 0.004) tryRate(rate)
            rate += RATE_STEP
        }
        if (bestRate != 1.0) for (delta in doubleArrayOf(-0.005, -0.0025, 0.0025, 0.005)) tryRate(bestRate + delta)
        return best.fromTheStart()
    }

    private fun stretch(video: FloatArray, rate: Double): FloatArray {
        val n = (video.size / rate).toInt().coerceAtLeast(2)
        return FloatArray(n) {
            val x = it * rate
            val i = x.toInt().coerceAtMost(video.size - 2)
            val f = (x - i).toFloat()
            video[i] * (1 - f) + video[i + 1] * f
        }
    }

    private const val RATE_SEARCH_BELOW = 0.75
    private const val MIN_RATE_CONFIDENCE = 0.5
    private const val RATE_MARGIN = 0.1
    private const val RATE_FROM = 0.90
    private const val RATE_TO = 1.10
    private const val RATE_STEP = 0.01

    internal fun alignAtRate(
        track: FloatArray,
        video: FloatArray,
        sampleRate: Int,
        params: AlignParams,
    ): AlignResult {
        require(sampleRate > 0) { "sampleRate must be > 0, was $sampleRate" }
        val minWindow = (params.minWindowS * sampleRate).toInt()
        var window = (params.windowS * sampleRate).roundToInt()
        if (track.size < window || video.size < window) {
            window = min(track.size, video.size) / 2 // short media: half of the shorter signal
        }
        if (window < minWindow) return noMap("audio too short to align")
        // leave room for several windows: on a short song they shrink to a third of it
        window = min(window, max(minWindow, track.size / 3))
        val hop = min(max(1, (params.hopS * sampleRate).roundToInt()), window)

        val threshold = max(params.silenceFloorRms, params.silenceRelRms * rms(track, 0, track.size))
        val starts = windowStarts(track.size, window, hop)
        val loud = BooleanArray(starts.size) { rms(track, starts[it], window) >= threshold }
        if (loud.none { it }) return noMap("track audio is silent")

        val correlator = VideoCorrelator(video, params.minLocalRms)
        val candidates = starts.indices.map { i ->
            val curve = if (loud[i]) correlator.curve(track, starts[i], window) else null // near silence: no information
            if (curve == null) emptyList() else topCandidates(curve, starts[i], sampleRate, params)
        }

        val hypotheses = clusterHypotheses(candidates, params)
        val reward = windowRewards(candidates, hypotheses, params)
        val chain = chooseChain(reward, hypotheses, candidates.size, params)
        val (segments, matchedMs) =
            buildSegments(starts, candidates, chain, hypotheses, reward, window, hop, track.size, sampleRate, params)

        val trackMs = track.size * 1000.0 / sampleRate
        val coverage = min(1.0, matchedMs / trackMs)
        if (segments.isEmpty() || coverage < params.minCoverage) {
            val percent = (coverage * 100).roundToInt()
            return noMap("could not align: only $percent% of the song has a confident match in the video", coverage)
        }
        val quality = segments.sumOf { it.ncc * it.lengthMs } / segments.sumOf { it.lengthMs }
        val confidence = (quality * min(1.0, coverage / params.confidenceFullCoverage)).coerceIn(0.0, 1.0)
        val longest = segments.maxBy { it.lengthMs }
        return AlignResult(
            offsetMs = longest.offsetMs.roundToLong(),
            confidence = confidence,
            coverage = coverage,
            segments = segments.mapNotNull { it.toSyncSegment() },
        )
    }

    /** One place a song window could sit in the video. */
    private class Candidate(val offsetMs: Double, val ncc: Double)

    /** A stretch of the song that maps to the video with one constant offset. */
    private class Segment(var songStartMs: Double, var songEndMs: Double, val offsetMs: Double, val ncc: Double) {
        val lengthMs: Double get() = songEndMs - songStartMs

        fun toSyncSegment(): SyncSegment? {
            val start = songStartMs.roundToLong()
            val end = songEndMs.roundToLong()
            return if (end > start) SyncSegment(start, end, offsetMs.roundToLong(), ncc) else null
        }
    }

    /** Start samples of overlapping windows covering the track (the last one flush with the end). */
    private fun windowStarts(trackLength: Int, window: Int, hop: Int): IntArray {
        if (trackLength < window) return IntArray(0)
        val starts = ArrayList<Int>()
        var start = 0
        while (start <= trackLength - window) {
            starts += start
            start += hop
        }
        val flush = trackLength - window
        if (starts.last() != flush && flush - starts.last() >= hop / 2) starts += flush
        return starts.toIntArray()
    }

    /** The strongest distinct peaks of one window's correlation curve, as offsets. */
    private fun topCandidates(curve: DoubleArray, start: Int, sampleRate: Int, params: AlignParams): List<Candidate> {
        val separation = max(1, (params.candidateSeparationMs * sampleRate / 1000.0).toInt())
        val work = curve.copyOf()
        val out = ArrayList<Candidate>(params.candidatesPerWindow)
        repeat(params.candidatesPerWindow) {
            var k = 0
            for (i in 1 until work.size) if (work[i] > work[k]) k = i
            val value = work[k]
            if (value < params.candidateMinNcc) return out
            val position = k + parabolicOffset(curve, k)
            out += Candidate((position - start) * 1000.0 / sampleRate, value)
            work.fill(-1.0, max(0, k - separation), min(work.size, k + separation + 1))
        }
        return out
    }

    /** Sub-sample position (`-0.5..0.5`) of the peak at index [k], from a parabola through its two neighbours. */
    private fun parabolicOffset(values: DoubleArray, k: Int): Double {
        if (k > 0 && k < values.size - 1) {
            val a = values[k - 1]
            val b = values[k]
            val c = values[k + 1]
            val curvature = a - 2.0 * b + c
            if (curvature < 0.0) return (0.5 * (a - c) / curvature).coerceIn(-0.5, 0.5)
        }
        return 0.0
    }

    /** Distinct offsets proposed by any window (peaks within `sameOffsetMs` of the group's first are one). */
    private fun clusterHypotheses(windows: List<List<Candidate>>, params: AlignParams): DoubleArray {
        val offsets = windows.flatMap { cands -> cands.map { it.offsetMs } }.sorted()
        val hypotheses = ArrayList<Double>()
        val group = ArrayList<Double>()
        for (value in offsets) {
            if (group.isNotEmpty() && value - group[0] > params.sameOffsetMs) {
                hypotheses += median(group)
                group.clear()
            }
            group += value
        }
        if (group.isNotEmpty()) hypotheses += median(group)
        return hypotheses.toDoubleArray()
    }

    /** `reward[i * hypotheses.size + h]`: how well window `i` supports offset `h` (0 when it does not). */
    private fun windowRewards(windows: List<List<Candidate>>, hypotheses: DoubleArray, params: AlignParams): DoubleArray {
        val count = hypotheses.size
        val reward = DoubleArray(windows.size * count)
        for ((i, cands) in windows.withIndex()) {
            for (cand in cands) {
                for (h in 0 until count) {
                    if (abs(hypotheses[h] - cand.offsetMs) <= params.sameOffsetMs) {
                        reward[i * count + h] = max(reward[i * count + h], cand.ncc - params.candidateMinNcc)
                    }
                }
            }
        }
        return reward
    }

    /** Cost of the picture jumping by [deltaMs] between two consecutive windows. */
    private fun jumpCost(deltaMs: Double, params: AlignParams): Double =
        if (deltaMs <= params.sameOffsetMs) 0.0 else min(params.jumpMax, params.jumpBase + params.jumpPer10s * deltaMs / 10_000.0)

    /**
     * Viterbi over offset hypotheses: one per window, jumps charged, silence free.
     *
     * Every window picks one hypothesis and earns its reward (zero when the window has no evidence
     * for it, which just means "the picture carries on as it was"). Changing hypothesis between
     * consecutive windows costs [jumpCost], so a repeat that would make the picture jump around loses
     * to the chain that stays continuous. Ties go to the lowest hypothesis index.
     */
    private fun chooseChain(reward: DoubleArray, hypotheses: DoubleArray, windows: Int, params: AlignParams): IntArray {
        val count = hypotheses.size
        if (windows == 0 || count == 0) return IntArray(0)
        val back = Array(windows) { IntArray(count) }
        var previous = DoubleArray(count) { reward[it] }
        var current = DoubleArray(count)
        for (i in 1 until windows) {
            for (h in 0 until count) {
                var best = Double.NEGATIVE_INFINITY
                var bestFrom = 0
                for (from in 0 until count) {
                    val candidate = previous[from] - jumpCost(abs(hypotheses[from] - hypotheses[h]), params)
                    if (candidate > best) {
                        best = candidate
                        bestFrom = from
                    }
                }
                back[i][h] = bestFrom
                current[h] = best + reward[i * count + h]
            }
            val swap = previous
            previous = current
            current = swap
        }
        val chain = IntArray(windows)
        var last = 0
        for (h in 1 until count) if (previous[h] > previous[last]) last = h
        chain[windows - 1] = last
        for (i in windows - 1 downTo 1) chain[i - 1] = back[i][chain[i]]
        return chain
    }

    /** Groups the chain into segments, fills short gaps and edges, and returns them with the matched time. */
    private fun buildSegments(
        starts: IntArray,
        windows: List<List<Candidate>>,
        chain: IntArray,
        hypotheses: DoubleArray,
        reward: DoubleArray,
        window: Int,
        hop: Int,
        trackLength: Int,
        sampleRate: Int,
        params: AlignParams,
    ): Pair<List<Segment>, Double> {
        val ms = 1000.0 / sampleRate
        val count = hypotheses.size
        val halfHop = hop / 2.0
        val segments = ArrayList<Segment>()
        var matchedMs = 0.0
        var i = 0
        while (i < chain.size) {
            var j = i
            while (j + 1 < chain.size && chain[j + 1] == chain[i]) j++
            val hypothesis = hypotheses[chain[i]]
            // the picture only has to be trusted from the first window that supports the offset to the last
            val runs = ArrayList<ArrayList<Int>>()
            for (k in i..j) {
                if (reward[k * count + chain[i]] <= 0.0) continue
                val last = runs.lastOrNull()
                // split where support disappears for longer than fillGapMaxS
                if (last != null && (starts[k] - starts[last.last()]) * ms <= params.fillGapMaxS * 1000.0) {
                    last.add(k)
                } else {
                    runs.add(arrayListOf(k))
                }
            }
            for (run in runs) {
                if (run.size < params.minSegmentWindows) continue
                val refined = run.mapNotNull { k ->
                    windows[k].filter { abs(it.offsetMs - hypothesis) <= params.sameOffsetMs }.maxByOrNull { it.ncc }
                }
                val begin = starts[run.first()] + window / 2.0 - halfHop
                val end = starts[run.last()] + window / 2.0 + halfHop
                segments += Segment(
                    max(0.0, begin) * ms,
                    min(trackLength.toDouble(), end) * ms,
                    median(refined.map { it.offsetMs }),
                    refined.sumOf { it.ncc } / refined.size,
                )
                matchedMs += run.size * hop * ms
            }
            i = j + 1
        }
        segments.sortBy { it.songStartMs }

        // fill: split short gaps at their midpoint, absorb short heads and tails
        val trackMs = trackLength * ms
        for (s in 0 until segments.size - 1) {
            val left = segments[s]
            val right = segments[s + 1]
            if (right.songStartMs - left.songEndMs <= params.fillGapMaxS * 1000.0) {
                val middle = (left.songEndMs + right.songStartMs) / 2.0
                left.songEndMs = middle
                right.songStartMs = middle
            }
        }
        if (segments.isNotEmpty()) {
            if (segments.first().songStartMs <= params.fillEdgeMaxS * 1000.0) segments.first().songStartMs = 0.0
            if (trackMs - segments.last().songEndMs <= params.fillEdgeMaxS * 1000.0) segments.last().songEndMs = trackMs
        }
        return segments to matchedMs
    }

    private fun noMap(message: String, coverage: Double = 0.0): AlignResult =
        AlignResult(offsetMs = 0L, confidence = 0.0, coverage = coverage, segments = emptyList(), error = message)

    private fun rms(signal: FloatArray, from: Int, length: Int): Double {
        var sum = 0.0
        for (i in from until from + length) {
            val v = signal[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / length)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
