package com.cosmotune.music.data.recommendations

/**
 * The deterministic work one radio ranking does (MN-02), counted by the real ranking code when a
 * counter is passed. Wall-clock time depends on the phone; these counts do not, so the per-ranking
 * budget is checked in a unit test and the phone latency is measured separately on a device.
 */
internal class RankingWork {
    /** Rows of the mapped embedding table read by local discovery (one full scan, at most 4,096). */
    var embeddingRowsScanned = 0L
    /** Multiply-adds over the 32-dimension quantised vectors (discovery scan, encodes, candidate dots). */
    var multiplyAdds = 0L
    /** SHA-256 bucket digests (three per encoded song). */
    var sha256Digests = 0
    /** Hashed features read by the online preference layer (at most eight per candidate). */
    var preferenceFeatures = 0
    /** Weights and accumulators multiplied by the daily decay (at most once per day, 8,192 values). */
    var decayedValues = 0L
    /** Discovery neighbours above the similarity floor that had to be sorted. */
    var sortedCandidates = 0

    override fun toString() = "rows=$embeddingRowsScanned multiplyAdds=$multiplyAdds sha256=$sha256Digests " +
        "preferenceFeatures=$preferenceFeatures decayed=$decayedValues sorted=$sortedCandidates"
}
