# Music discovery and local feedback

Working-tree implementation, 2 November 2026. This is not a claim that a release was published or that real-device validation passed. The parent validation batch owns test results.

## Candidates and truthfulness

`MusicCandidateService` combines bounded metadata pools from the local library (1,200), recent listening (80), enabled provider Home/search, selected-region charts, language catalogue searches, two recent-song radios and two explicitly followed artists. Pools expire after 15 minutes, retain no stream URLs or credentials, and are capped at 12 pools of 120 rows / 2,000 candidates. Independent requests have concurrency and timeout limits; one failed provider does not erase other candidates. It does not ship or download an entire streaming catalogue.

Language and exact release dates are read only when a provider states them. A language search is labelled a search match, not verified language. Year-only/missing release dates do not receive a freshness bonus. Followed-artist selections say “From followed artists”; an unseen release is new to the artist watch, not necessarily newly released. No artist is followed implicitly from listening history.

Recording deduplication uses compatible editions and explicit status, stated valid ISRCs, or native title/translated-title, shared-artist and duration/album evidence. Live, remix, instrumental, cover, remaster and re-recorded editions remain distinct when metadata identifies them. Unknown evidence can leave duplicates rather than merge unrelated recordings. Local playable copies take precedence and alternative source IDs remain attached to the recording.

## Ranking and actions

The existing mapped tiny graph and hashed online preference model rank candidates on background dispatchers. No new model or giant catalogue is bundled. The existing graph is 4,176,439 bytes unpacked, the online weights are 65,568 bytes, and the explicit-feedback file has a hard 256 KiB limit; together these remain below the requested decimal 5 MB budget. These are file-size bounds, not a claim that process RAM is under 5 MB.

Native successful music Like actions and shared **Less like this** actions update device-local preferences. The latter excludes the recording from recommendations until cleared/reset. Explicit feedback persists SHA-256 recording fingerprints, not a second listening log. Existing meaningful sampled skips remain negative; pauses, source failures and app shutdown are not dislikes. An explicit Like after a learned completion does not train that completion again. Podio/video items do not train music feedback. Turning local preferences off bypasses these learned adjustments.

## Home and editable playlists

Home presents Daily mix, Rediscover (library recordings outside the latest 80 distinct listens), followed-artist selections, regional charts and honest language/search shelves as data becomes available. Useful local shelves remain available if online Home fails, with a separate online retry state. Changing library/history/likes/followed artists or local feedback recomputes the local shelves.

**Make my playlist** previews Daily mix, Rediscover, From followed artists, Regional discovery or Based on a song. Online enrichment is optional. Before saving an ordinary editable native playlist, the listener can rename it, reorder/remove recordings and see the source rationale and partial-source warning. A small or unavailable catalogue can produce fewer tracks than requested; no rows are fabricated to reach a target.

Current coverage is deliberately bounded: fresh artist enrichment checks two watched artists per request and up to two releases each; metadata without full dates cannot power a guaranteed chronological new-release feed. No live recommendation-quality benchmark, latency claim or provider catalogue completeness claim is made here. Candidate fixtures, feedback tests, provider-metadata fixtures and a real preview/reorder/save interaction test accompany the source; release/device validation is separate.
