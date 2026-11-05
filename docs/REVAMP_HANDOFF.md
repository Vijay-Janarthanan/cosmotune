# Revamp handoff (November 2026)

This file lets a new session continue the UI/YouTube revamp exactly where it stopped. Read it fully before doing anything.

## Rules (from the owner)

- No AI attribution anywhere: no assistant name, no `Co-Authored-By`, no session links in commits, code, comments, docs or branch names. Commit as the repository owner.
- Keep every existing feature. Never delete, skip or weaken a test to get green; update an expectation only when it encodes a layout or wording that was deliberately replaced, and keep its intent.
- One consistent UI and UX across every mode (Music, Video, Podio, Read, Manga, Library, Settings), following `docs/design/DESIGN_SYSTEM.md` ("One"). Settings keep the Prism-style layout (one card per section, hairlines, coloured squircle tiles; §4.10 owner decision).
- Verify with CI: `.github/workflows/check.yml` is `workflow_dispatch` only; trigger it on the branch and wait for every job (Android, Desktop Linux/Windows/macOS, Browser, Go) to pass before merging.

## Build environment notes

- Local Gradle needs an Android SDK with `platforms/android-37` (Google now ships it as `android-37.0`; with AGP 8.10.1 a copy named `android-37` whose `source.properties`/`package.xml` say API level `37` works) plus `cmake;3.22.1`.
- `jitpack.io` must be reachable (NewPipeExtractor v0.26.3, innertubex v0.7.0). In the cloud session it was blocked, so every change was verified on GitHub CI only and no screenshots could be rendered.
- The machine has 4 CPUs/15 GB: run one Gradle build at a time with `--no-daemon`.

## Branches

**`main`** now contains every workstream: launch fix, ui-shell (One tokens, kit, dock, quality pills, mode switcher), youtube-account (single session, silent refresh, account lists), settings (hub with search), search-providers (all providers, "Search in" chips, automatic verification, silent provider session renewal), manga-read (separate Manga and Read modes), podio, signed-video (see item 4) and music-video (items 1 to 3: Music's four tabs and screens, Video's screens, the shared chrome without Video's copies, and the Pure black toggle). The workstream branches passed `check.yml` before merging and have been deleted. Start new work on a branch cut from `main`, and run `check.yml` on it before merging.

## Remaining work (in priority order)

1. **Done: Music 4-tab rule** (§5.2). Saved tab numbers did not change; Releases is number 4 and Search is a top-bar icon.
2. **Done: Music screens** (§6.1). Still old: Replay cards, the desktop collection page has no cover gradient, `CosmoPlayerBar` and `CosmoProfileDrawer` keep their superellipses.
3. **Done: Video screens** (§6.2). Video now uses the shared top bar, dock mini player and account sheet; `VideoAccent` is gone. The dock is hidden beside the phone's Shorts pager as §4.16 and §6.2 say, and dark beside Shorts in a narrow desktop window. Still open:
   * The player chrome and some download-sheet text use custom type sizes; solid `cosmoGlass` surfaces remain over the video, on the first-run notice and on the download toast.
4. **Signed video playback, comments and private playlists**: merged (see `docs/YOUTUBE_ACCOUNT.md`); unit-tested with fake HTTP, not yet tried against a live account or on a device.
7. **First-run "Your listening" setup dialog** still has the old look (brown panel, bordered tab row, text links); rebuild it on the kit. Tamil/Telugu show "· Manga" (manga-only translations); word that clearly.
8. **Mode switcher consistency**: Podio shows a "Music" text button top-right instead of the kit's top-left mode pill; several Manga/Read root pages also lack the pill. Use `KitModeSwitcher` on every root page.
9. **Kit promotions**: promote `ReadKit.kt` (collapsing top bar, 2:3 cover card, chapter row) and Podio's `MenuChip`, `MapSheet`, `EpisodeRow`, `StationRow`; add an inline banner with actions, an avatar, an attached sheet (§4.13), a chip status-dot slot, swipe-to-queue on `KitMediaRow`, warning/success colour tokens, a cover-colour header helper.
10. **Search landing** (Prism style): large "Search" title, recently-opened circles, recent-search pills; provider rows should read "artist · source".
11. **Manga/Read**: mini player in their docks (`LocalReadMiniPlayer` slot), reader chrome hidden at open, long-press multi-select, Popular/Latest/Random browse chips, translated strings (Read uses plain text; Manga falls back to English), book contents preview, reader background/keep-screen-on/crop options.
12. **Podio**: cover-colour headers, use the shared full Now Playing for episodes, desktop sidebar search and followed shows, kit dialogs instead of Material `AlertDialog`, pull-to-refresh on Episodes.
13. **Settings**: white icons on the dark green/orange/teal tiles are ~4.2:1 (test requires 3:1 on dark tiles); raise to 4.5:1. Translate the new mode names "Manga"/"Read" in non-English locales (English was fixed).
14. Rendered screenshots of every main screen compared with the references before release; device walkthroughs with a signed-in account. Music and Video were walked signed out on an emulator with live data; the reference images are not in the repository, so they were compared with the written blueprints.

## First-launch fix (done)

The "settings opens then closes" first-launch bug was the first-run Listening setup dialog deciding "new listener" before the library/history/queue loaded, then closing itself. Fixed in `5fefbb1` (wait for inputs to settle, latch the dialog, mark returning listeners complete, only reset the locale when it changes).
