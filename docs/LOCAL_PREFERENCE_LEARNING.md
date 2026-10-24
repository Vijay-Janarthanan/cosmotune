# Local listening preferences for 1.0.4

Implemented in the working tree; not published yet. This layer adjusts the existing tiny radio ranker on Android and desktop. It follows the online-learning approach reviewed in [Mornye](MORNYE_REVIEW.md), with a separate Cosmotune implementation.

The model has 4,096 hashed features, logistic predictions and AdaGrad updates. Artist, recording, album, duration, time of day and previous-artist interactions contribute features. Structured artist credits take priority. Observations gradually increase its influence; an adjustment is bounded to ±0.15. Weights decay by 0.98 per inactive day. This does not supply a catalogue or stream audio.

Two Float64 arrays use 65,536 bytes. The versioned file, including its header and CRC, is **65,568 bytes**. Together with the 4,176,439-byte mapped base model, numeric model storage is **4,242,007 bytes**, below the requested 5 MB target. Object overhead and ordinary app caches are separate.

The playback sampler uses monotonic elapsed time, capped at eight seconds per sample. Position does not enter the signal, so seeking cannot fabricate completion. Pause/resume clears the sampling clock while retaining the session's listening. A positive signal requires 80% of known duration, bounded to 30 seconds–four minutes, or two minutes for unknown duration. Only explicit Next/Previous controls can produce an early-skip signal; automatic transitions, decoder failures, pauses and shutdown do not. Accidental taps under three sampled seconds are ignored. A signal is applied at most once per session and checked against the expected track ID.

Settings → Playback has **Learn from my listening** and **Reset learning**. Disabling preserves weights without applying or updating them. Reset clears weights and counts. The file contains hashed weights and accumulators, not raw song names, IDs or listening events. It remains in the app's local data directory and is never uploaded. Writes use a flushed temporary file and atomic replacement where available. A damaged file is left untouched until an explicit reset.

## Checks

Unit tests cover positive/negative learning, generalization to an unheard recording, influence/size budgets, state round trips, opt-out/reset, decay, corruption rejection, pause gaps, stale skip IDs and automatic track changes. The final four learner tests, Android/desktop compilation and the combined download/runtime follow-up passed.

On the Android 16 x86_64 development emulator, radio discovery plus ranking 50 candidates measured **17.27 ms median / 22.07 ms p95** before batching the shared seed encoding. After batching, it measured **11.12 ms median / 15.83 ms p95**, including the untrained preference layer, after 30 warm-ups and over 100 samples. These are emulator results, exclude network requests and do not establish physical-phone performance or better recommendation relevance. A small synthetic hold-out check is a regression test, not a listening-quality evaluation.

The quantized base-model weights and training hashes were not changed. No backend or recurring training job was introduced.

## Per-ranking work budget (cx-p1)

Wall-clock latency depends on the phone, so the work one ranking does is now counted by the ranking code itself (`RankingWork`, passed only by tests) and bounded in `RankingWorkBudgetTest` on the bundled model. Ranking 50 candidates, exactly the three calls `LocalRecommendations.enhance` makes, scans the 4,096-row embedding table once and does 140,992 multiply-adds (budget 150,000), 156 SHA-256 bucket digests and 400 preference-feature reads; the first ranking of a day also decays 8,192 values once. Each further candidate adds exactly 192 multiply-adds, three digests and at most eight feature reads, whatever the catalogue size. On this development desktop's JVM, discovery plus 50 scores took 0.97 ms median and 1.49 ms p95. A physical-phone measurement is still to be taken; the emulator figures above remain the only Android timings.
