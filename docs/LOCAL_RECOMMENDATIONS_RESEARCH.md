# On-device music recommendations: research and proposal

Research date: 23 October 2026. This document records the original research and proposal. The implemented 1.0.3 design, its measured size/speed and its limits are described in [LOCAL_RECOMMENDATIONS.md](LOCAL_RECOMMENDATIONS.md); those implementation notes supersede the proposal's deployment assumptions.

## Recommendation

Build a hybrid recommender that runs on the device. Start with MusicBrainz core metadata, ListenBrainz's openly licensed listening events, and the person's own Cosmotune activity. Train or aggregate shared knowledge periodically outside the app, publish compact versioned data packs, and personalize their results locally. Keep the existing YouTube Music radio integration as an optional online candidate source.

This removes the need for our own continuously running recommendation server. Preparing datasets, publishing updates, and downloading them still require infrastructure occasionally. Streaming songs still requires an available playback source; offline recommendations do not make un-downloaded songs playable offline. Listen Together's existing party-coordination server serves a separate purpose and would still be needed by its current implementation.

There is no verified, complete, freely redistributable catalogue covering every recording on Spotify, Amazon Music and YouTube Music, with all their private listening relationships. A metadata catalogue also does not contain audio or confer playback rights.

## What Cosmotune already has

Source inspection at release commit `7cc79d5761128ab8d88bda332d64b7332067563a` found:

- `shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/innertube/Innertube.kt`: `next(videoId)` requests YouTube Music's `RDAMVM` radio queue with `isAudioOnly=true`.
- `shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/YtMusicRepository.kt`: `radio(videoId)` parses those candidates.
- `app/src/main/java/com/cosmotune/music/playback/Autoplay.kt` and `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopAutoplay.kt`: Android and desktop already consume that radio. Local tracks can be matched to a YouTube song to obtain a radio seed. Desktop platforms share the desktop implementation.
- Android's `ListeningRecorder.kt` samples time actually played rather than treating the seek position as listening time. `ListeningStats.kt` aggregates listening information. Desktop has `DesktopListeningStats.kt`.
- The shared `ListeningStore.kt` persists preferences, saved sessions and smart-playlist configuration; it is not a trained recommendation model.

We can reuse these foundations. We would still need a shared candidate/ranking engine, catalogue identity mapping, a suitable local event history, pack generation, and quality evaluation. Existing aggregate statistics are not automatically a complete session/skip training log.

## Sources we can investigate or use

| Source | Relevant information | Decision and restrictions |
|---|---|---|
| [MusicBrainz database](https://musicbrainz.org/doc/MusicBrainz_Database) | Recordings, artists, releases, works, ISRCs, credits and relationships, including musician/writer relationships where contributed. | Primary metadata source. Core data is CC0. User tags, **genre associations**, ratings and other supplementary data are CC BY-NC-SA 3.0. The genre vocabulary being core does not make every track-to-genre association CC0. |
| [MusicBrainz canonical dumps](https://metabrainz.org/datasets/derived-dumps) | Maps related release/recording entries to representative identifiers. | Useful CC0 identity aid. Preserve exact recording/version information alongside canonical grouping; grouping must not silently turn a live version into a studio version. |
| [Wikidata structured data](https://www.wikidata.org/wiki/Wikidata:Licensing) | Optional structured links for music/film and artist enrichment where statements have been contributed. | CC0 structured data; coverage must be measured. Use a selected subset of its [downloadable exports](https://www.wikidata.org/wiki/Wikidata:Database_download), not an entire Wikidata database in the app. Wikipedia prose and linked artwork have separate terms. |
| [ListenBrainz dumps](https://metabrainz.org/datasets/postgres-dumps) | Public listening events usable to learn associations between recordings and listeners. | Primary collaborative-data source; listed as CC0 with commercial use allowed. Export aggregate neighbours or item vectors, not other people's identifiable listening histories. |
| [Discogs data/API terms](https://support.discogs.com/hc/en-us/articles/360009334593-API-Terms-of-Use) | Discographies, tracklists, credits and release metadata; genre/style information is documented in its [database guidelines](https://support.discogs.com/hc/en-us/articles/360005055213-Database-Guidelines-9-Genres-Styles). | Optional enrichment. Terms distinguish CC0 metadata from restricted images, user and marketplace data, and impose API access/cache conditions. Do not harvest its API into a permanent app database. The dump host returned HTTP 403 during this research, so a usable current export and its field-level terms remain to be verified. |
| [AcousticBrainz](https://acousticbrainz.org/) | Acoustic characteristics, including tempo/key and some genre/mood descriptors, linked to MusicBrainz recordings. | CC0 archival enrichment. Collection stopped in 2022; useful coverage must be measured, especially for newer and regional releases. |
| [AcoustID](https://acoustid.org/database) | Audio fingerprints and links to MusicBrainz recordings. | Helps identify a local recording, not recommend different songs. Fingerprint data is CC BY-SA 3.0; the MusicBrainz–AcoustID mapping is CC0. No need to bundle its full fingerprint database initially. |
| [MLHD+](https://metabrainz.org/datasets/derived-dumps) | Corrected historical listening events mapped to MusicBrainz IDs. | Research reference. MetaBrainz explicitly lists **non-commercial use only**, without a general licence. Do not confuse this with the CC0 ListenBrainz event dumps or ship its derivatives without resolving intended-use permission. |
| [Million Song Dataset](https://millionsongdataset.com/) | Historical metadata/audio features; linked Last.fm tags/similarity and Taste Profile user-song play counts. | Useful benchmark/reference, not our default shipping dataset. It has component-specific provenance/terms and ageing identifiers. The Last.fm/Taste Profile subpages were unavailable during this check, so current archive availability and redistribution rights were not confirmed. |
| [Spotify Million Playlist Dataset](https://research.atspotify.com/2020/09/the-million-playlist-dataset-remastered) | One million playlists and their track co-occurrence. | Released for non-commercial open research. This is a separate dataset agreement, not permission to extract Spotify's entire catalogue or deploy any resulting model in an app. Check the challenge terms before an experiment. |
| [LFM-2b](https://www.cp.jku.at/datasets/LFM-2b/) | A formerly available large listening-event dataset. | Exclude: its official host says downloads were withdrawn because of licence issues. A third-party mirror does not resolve that issue. |
| [Free Music Archive dataset](https://github.com/mdeff/fma) | Genre-labelled music, metadata and audio features. | Useful for genre research. Metadata is CC BY 4.0, code MIT, and audio has per-artist licences. This is not a replacement catalogue of mainstream film songs. |
| [MTG-Jamendo](https://github.com/MTG/mtg-jamendo-dataset) | Music tagging research data for genres, instruments and mood/theme. | Research reference: metadata is CC BY-NC-SA 4.0, code Apache-2.0, audio has individual licences. The maintainer specifies non-commercial research/academic use and written authorization for other uses. Exclude from the initial shipping pack. |

For bulk preparation, use exports rather than crawling a metadata API. MusicBrainz's [API guidance](https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting) requires an identifiable user agent and normally limits each IP to about one request per second. Its [JSON exports](https://metabrainz.org/datasets/postgres-dumps) offer a more convenient starting point than rebuilding its PostgreSQL schema for a mobile database. Keep a manifest identifying exactly which files and fields entered each pack.

## Spotify, Amazon and YouTube access

**Spotify:** Its [2024 access change](https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api) removed recommendations, related artists, audio features and analysis from new Web API use cases, while preserving specified existing extended-access integrations. Current [development mode](https://developer.spotify.com/documentation/web-api/concepts/quota-modes) also requires a Premium app owner and permits only five allowlisted authenticated users. Its [Developer Policy](https://developer.spotify.com/policy) prohibits training or otherwise ingesting Spotify Content into ML/AI models. Spotify Web API is therefore unsuitable as the training-data foundation for this proposal. An accessible endpoint or unofficial scraper does not establish reuse rights.

**Amazon Music:** Its [official developer portal](https://developer.amazon.com/docs/music/landing_home.html) still labels the Web API closed beta. It is not a generally available catalogue or recommendation graph we can assume every Cosmotune user can access. No open Amazon Music listening-relationship export was verified. Amazon retail product-review datasets are not equivalent to music-stream listening sessions.

**Official YouTube Data API:** Search is available, but its old `relatedToVideoId` facility was [removed in August 2023](https://developers.google.com/youtube/data/revision_history#august-7,-2023). The current [search reference](https://developers.google.com/youtube/data/reference/rest/v3/search/list) supports category, topic, country and language-relevance filters; it does not expose YouTube Music's personalized song-radio algorithm. `relevanceLanguage` can still return other languages, and `regionCode` concerns availability, not sung language. The current reference describes a separate Search Queries bucket with 100 search calls/day by default: old tutorials describing 100 general quota units per call are outdated.

**Unofficial YouTube Music access:** Maintainer documentation for [ytmusicapi](https://github.com/sigma67/ytmusicapi), [watch/radio](https://ytmusicapi.readthedocs.io/en/stable/reference/watch.html), and [filtered search](https://ytmusicapi.readthedocs.io/en/stable/reference/search.html) confirms the practical capabilities already reflected in our Innertube integration. Use song-filtered search to resolve recordings, and radio to obtain candidates. These are web-client interfaces that can change, not an official public recommendation API or an openly licensed bulk dataset. Audio-only requesting does not guarantee a language, genre or exact recording match.

YouTube's [data policies](https://developers.google.com/youtube/terms/developer-policies) include restrictions on aggregation/derived data and refresh/deletion requirements for stored API data. Do not use a permanent crawl of YouTube metadata/recommendations as our open training corpus. Keep provider-specific online integrations separate from the openly sourced model/data packs; unresolved intended uses need provider confirmation.

**Last.fm:** [track.getSimilar](https://www.last.fm/api/show/track.getSimilar) supplies listening-based similar tracks with an API key, without user authentication for that method. Artist similarity and tags are other useful discovery signals. Its [terms](https://www.last.fm/api/tos) require attribution, restrict sublicensing, normally cap stored data at 100 MB, and require contacting Last.fm for commercial or research use. It can be an optional appropriately licensed online adapter, not an unrestricted catalogue to bundle in every installation.

**ListenBrainz online options:** Its [personalized recommendation API](https://listenbrainz.readthedocs.io/en/latest/users/api/recommendation.html) returns collaborative-filtered recording IDs for users with generated recommendations; it is marked experimental. The [Labs dataset interface](https://labs.api.listenbrainz.org/) also exposes similar-recording/artist lookups. An accessible result is not evidence that it was trained exclusively on CC0 ListenBrainz events: verify provenance, particularly the separately named MLHD endpoints, before exporting results into an app pack.

## Small live catalogue check

Six rate-limited public MusicBrainz searches succeeded on 23 October. Results are saved locally in `build/recommendation-research/musicbrainz-probes.json`. These are catalogue observations, not a recommendation-quality benchmark.

| Query | Observed result |
|---|---|
| Godavari | 22 recording matches; results included different films sharing the album title. |
| Manasavacha / Manasa Vacha | Correct Godavari recording, Unnikrishnan and K. S. Chithra, MBID `501038e1-6f44-44a3-8382-609b1aa86435`; other same-title recordings also appeared. |
| Tippulu Tappulu | Godavari / Shreya Ghoshal, MBID `6b7fd637-3c47-4abd-8d8b-3192ce4c12b8`. |
| Naan Pizhai | Original plus flute and lo-fi versions. Original MBID `43ba2df7-1a1e-445d-b0da-3a30c5aff116`. |
| Chuttamalle | Devara Part 1, MBID `9532a27d-fb94-4931-a466-0e5045293138`. |
| Blinding Lights / The Weeknd | 31 matches including alternate releases and live material. |

These checks establish some relevant catalogue coverage. They do not establish sufficient collaborative listening coverage, accurate language labels, universal streaming-ID mappings or a complete catalogue. MusicBrainz search scores are search relevance, not recommendation confidence.

## Proposed data and model design

### Identity and hashing

Retain recording MBIDs, artist IDs, release/work relationships, ISRCs when present, and separately verified provider IDs. Support aliases and transliterations without deleting meaningful version markers. Match with artist, album/film, duration and version evidence. Duplicate or inconsistent identifiers must not force an incorrect merge. A release's text language or an artist's country is not proof of the sung language.

SHA-256 is useful for data-pack integrity, not measuring musical taste. A hash of a title cannot discover related music. Acoustic fingerprints can help identify the same recording across encodings, but are not themselves a recommendation graph. Locality-sensitive hashing can accelerate approximate vector matching later; it still requires meaningful features first. Keep original identifiers and collision checks even when compact integer/hash keys are used internally.

### Shared preparation, outside the app

1. Select explicitly reusable fields from pinned metadata exports. Keep source/licence provenance per field; exclude artwork, lyrics and audio from the base catalogue.
2. Resolve redirects and canonical identities while retaining exact versions. Measure missing and ambiguous matches.
3. From CC0 ListenBrainz events, construct recording-to-recording associations from shared listeners and sessions. Downweight heavy repetition/popularity and require support from multiple listeners. A listening event is not automatically a positive rating.
4. Begin with normalized item co-occurrence and content similarities. Compare a matrix-factorization baseline using [implicit](https://github.com/benfred/implicit) or a hybrid baseline using [LightFM](https://github.com/lyst/lightfm), whose implementations have MIT and Apache-2.0 licences respectively. Software licensing does not replace training-data licensing.
5. Export top neighbours, compact item vectors if they improve measured quality, a minimal searchable catalogue and a versioned manifest. Distribute data files through static release assets/CDN hosting, independently of normal app builds.

This preparation can run on a workstation or occasional appropriately sized batch machine. Its actual storage/RAM/runtime needs must be measured; full historical exports should not be placed into every normal release build or processed on users' phones.

### Ranking on Android, Windows, Linux and macOS

Implement one shared Kotlin engine. Generate candidates from the downloaded graph, compatible artists/credits, the local library and, while online, supported provider adapters. Rank a bounded candidate set against a locally maintained taste profile. A small embedding dot product or linear score does not require an LLM, GPU, Python runtime or ONNX model in the app.

Keep long-term taste and current-session context separate. Learn from real played time, explicit likes/dislikes, replays and deliberate skips. Do not interpret buffering, unavailable songs, scrubbing or an interrupted session as dislike. Apply repeat controls, artist diversity, version matching and explicit-content preferences. Language preference needs track-level evidence and an honest unknown state; do not infer it from title script alone.

Cold start can use chosen artists/languages and popular tracks within the available pack. Maintain recent/familiar picks and exploration rather than repeatedly serving the same globally popular songs. Explanations can name real signals such as a shared singer or a liked artist. Personal history stays local unless a separate sync/scrobbling option is enabled.

If offline, filter to local/downloaded playable songs. If online, resolve promising catalogue entries to the correct available provider recording on demand and revalidate cached mappings. The metadata catalogue must not supply guessed stream URLs.

### Pack size and updates

Use a small bundled starter pack plus optional language/region/catalogue packs, with bounded overlap and cross-pack references. Do not put the entire world's catalogue into the APK/EXE. Unknown and newly released songs can still enter through the existing online search/radio path and the user's own library.

Illustrative uncompressed storage arithmetic, **not measured product sizes**:

| Records | 64-dimensional int8 vectors | 32 neighbours each, 4-byte ID + 2-byte score | Subtotal |
|---:|---:|---:|---:|
| 100,000 | 6.4 MB | 19.2 MB | 25.6 MB |
| 1,000,000 | 64 MB | 192 MB | 256 MB |
| 10,000,000 | 640 MB | 1,920 MB | 2,560 MB |

Names, IDs, relationship tables, aliases, database indexes, offsets and search structures add storage beyond these figures. Quantization quality needs comparison with an unquantized baseline. Read bounded portions from disk instead of loading all packs into RAM.

Publish schema/model versions, source snapshots, byte sizes and SHA-256 checksums; authenticate manifests with a signature. Install updates atomically and retain a working version for rollback. Existing packs must continue to rank available songs when the update host is unavailable. Static hosting removes a running API bill, not bandwidth or publisher maintenance.

## Implementation order and acceptance evidence

1. **Coverage and identity prototype:** A diverse sample of Tamil, Telugu, Hindi, other Indian languages and global music, including recent releases, older films, covers and ambiguous titles. Measure metadata match precision, available-credit/language coverage, listening-graph coverage and provider-resolution accuracy separately.
2. **Local baseline:** Reuse existing radio/local-library candidates, add a shared local ranker and explicit event semantics, and compare against the existing unmodified radio order. This can work before building a large shared dataset.
3. **Open-data pack:** Build a small reproducible MusicBrainz-core/ListenBrainz-only pack. Confirm every exported field's provenance, test offline operation and assess regional recommendation quality before expanding it.
4. **Model comparison:** Add embeddings only if they improve results beyond co-occurrence plus content ranking. Use chronological holdouts and avoid leaking future listening events into training. Compare Recall/NDCG alongside language/version errors, repeats, diversity and catalogue coverage.
5. **Device validation:** Measure Android battery, memory and latency on an actual modest phone, and desktop performance on Windows/Linux/macOS. Test corrupt/interrupted pack downloads, removed provider tracks and a long period offline. Set performance targets from those measurements rather than promising untested timings.

The first product decision should be this shared local engine and compact open-data packs. A personalized ranking service, an 8 GB always-on VM, and a large generative model are not prerequisites. Recommendation quality and regional coverage remain the main unproven parts.
