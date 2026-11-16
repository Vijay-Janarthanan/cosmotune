# Podio chapter artwork and follow freshness — 16 November 2026

Independent additions to the existing Podio publisher chapter/transcript work:

- Podcast chapter URLs travel through PodioItem, Song, Android media-item/bundle transport, saved downloads, desktop persistence and ListeningTrack restoration.
- Application/service-owned `PodioChapterMedia` loads chapter metadata once per episode URL (bounded in-memory cache), selects the chapter from existing position events, and caches bounded publisher images. It has no polling loop. Episode generations and chapter checks prevent delayed results from replacing a newer episode or seek. Missing/failed images leave the episode cover in use. Cache files are app-owned and capped to 64 image files; response reads stop at 2 MB.
- Android's existing Podio position events drive the chapter owner. Current-episode image bytes are dimension checked before the session wrapper publishes artwork and chapter subtitle, with Media3 metadata/events shared by notification and Android Auto. Playback items, music lyrics/history/normalization and Orbit rendering are not rewritten.
- Desktop consumes the same owner/state and publishes matching chapter file artwork through system metadata only (root integration).
- Following a podcast explicitly refreshes its initial inbox even if a prior inbox snapshot remains fresh. Generation checks prevent an older inbox completion from overwriting a newer subscription selection.

Offline regressions: `PodioChapterMediaTest` covers one metadata fetch, failed-image fallback, cached seek-back, music ownership exclusion and out-of-order episode art. `PodioFollowFreshnessTest` uses a local fixture server to prove Follow refreshes a still-fresh old inbox.

No tests/builds were run during implementation. Run after all implementation edits are frozen:

```powershell
.\gradlew.bat :shared:jvmTest --tests "com.cosmotune.podio.PodioChapterMediaTest" --tests "com.cosmotune.podio.PodcastExtrasTest" --tests "com.cosmotune.podio.PodioCatalogueTest"
.\gradlew.bat :sharedUi:jvmTest --tests "com.cosmotune.podio.ui.PodioFollowFreshnessTest"
```

Physical notification/Auto artwork delivery, desktop system-control integration and real publisher feeds still need platform verification. An unavailable chapter image falls back to the episode cover rather than an unrelated prior chapter. Pending-playlist same-ID recovery was not expanded in this bounded Podio pass: the current host createPlaylist callback returns Unit and exposes no native playlist ID/update callback; the durable review behavior is documented in the library handoff.
