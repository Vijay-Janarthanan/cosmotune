# Local recommendations in 1.0.3

AutoPlay and radio on Android, Windows, macOS and Linux share a small local ranker. It uses a **3,186,092-byte compressed model** with **4,176,439 bytes** mapped at runtime. It does not require ONNX, Python, a GPU, or a Cosmotune recommendation server.

The full training graph contains **583,496 source records and 7,810,857 links**. These are source records, not a count of unique songs. ListenBrainz, Music4All-Onion, Yambda, FMA, Discogs, Wikidata, Open Opus and retained MusicBrainz metadata contribute different kinds of evidence. Source identities and acoustic feature spaces remain separate while building the graph. Anonymous Yambda items calibrate confidence and are never assigned guessed song names.

To stay under the requested **5 MB** limit, the app includes quantised 32-dimensional embeddings and a **4,096-name discovery vocabulary**. The full catalogue, graph and listening histories are not bundled. The embeddings incorporate the larger training corpus; YouTube Music radio/search supplies the broad and current catalogue.

The model conservatively reranks radio candidates. Weak similarities leave the provider order alone. Discovery is allowed to abstain. Provider searches for additional names run in the background, never delaying the current radio batch, and an actual provider match is required before a new playback ID enters the queue. Missing/corrupt resources or insufficient cache space preserve provider radio.

## Measurements and limits

On the Windows development machine, after warming the JVM, local vocabulary discovery plus scoring 50 candidates measured **1.80 ms median / 2.35 ms p95** over 100 runs. This excludes network requests. Physical Android, macOS and Linux latency measurements are pending; the shared reader is compiled for Android release and JVM.

The source ListenBrainz graph uses events up to 20 October 2026 UTC, with later days reserved for validation/test; its 500-user test hit rate at 10 was 0.172. Onion's separate user-held-out aggregate protocol measured 0.836 over 500 users; it has no chronological interpretation. Yambda's relative-time calibration selected shrinkage 10 on validation and measured 0.562 on its separate 500-user test. These protocols and target windows differ and their values are not directly comparable.

Distillation preserved a positive-vs-random graph-neighbour preference in 0.819 of 9,600 sampled pairs. This is **compression fidelity, not next-song accuracy**. No end-to-end improvement over YouTube Music radio is claimed from these measurements. Hash collisions, quantisation, uneven regional coverage and source/name ambiguity remain limitations; online fallbacks and confidence thresholds limit their effect.

The exact aggregate reports and checksums are in `shared/src/jvmSharedMain/resources/recommendations/manifest.json`. [Dataset attribution and provenance](../shared/src/jvmSharedMain/resources/recommendations/ATTRIBUTION.md) accompanies the model in app resources. This feature does not provide audio, streaming rights or a complete offline catalogue.

## Reproduce and update

The Python build tools need NumPy, SciPy and PyArrow. Validated frozen source inputs live in the ignored `build/recommendation-datasets-v3`, `build/recommendation-lab` and `build/recommendation-lab-v2` workspaces. The trainer preserves a 15 GiB free-space reserve. New inputs need ingestion, validation and licence/provenance review; the app cannot recreate private training caches from the compact model.

```sh
python tools/recommendations/test_training.py
python tools/recommendations/train.py
python tools/recommendations/distill.py
python tools/verify-recommendations.py
```

The full trainer writes to the ignored training workspace, never directly to app assets. An interrupted run against the **same frozen data and parameters** can resume with `--reuse-trained-graphs`; do not use that option after changing inputs. Distillation alone writes the tiny app assets, with a hard 5,000,000-byte limit on both compressed and mapped data.

No recurring update job is configured. Model updates currently ship with app releases. Scheduling and independent monthly pack downloads are future work.

## 1.0.4 work in progress

The radio path now encodes a batch's seed once while preserving identical similarity scores. Android emulator discovery plus 50-song ranking measured 11.12 ms median / 15.83 ms p95, including an untrained [65,568-byte local preference learner](LOCAL_PREFERENCE_LEARNING.md). Base and personal model files total 4,242,007 bytes. Physical-phone timing and end-to-end relevance remain unverified; these changes have not been published yet.
