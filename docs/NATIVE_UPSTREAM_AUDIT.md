# Native upstream audit for 1.0.4 (BC-27)

Compares the upstream range from the last complete merge `fe95bb60` to the pinned `edc1e6f3` (UPSTREAM.md) with this branch,
so the upstream audit can be called complete: every upstream commit is mapped to a tracker row or a recorded decision below,
and the changes no earlier row covered became rows BC-28 to BC-37 in FEATURE_TRACKER.md.

## Method

The range has 190 non-merge commits and 753 changed files: 235 under `web/` (BC-25), 55 `tv/` (BC-24), 51 `wearos/`
(BC-12 to BC-16) and 94 in the split desktop UI module (BC-23). For the native rest, each upstream diff was renamed to this
fork's package and names and split into hunks, and `git apply --check` asked of each hunk whether it is already applied
(reverse applies), still missing (forward applies) or diverged (neither: the fork changed the same lines, or a later commit
rewrote them). This is a mechanical aid, not a verdict: a feature re-implemented in the fork's own structure (BC-01 to BC-10
landed that way) shows as missing or diverged, and an intermediate work-in-progress commit shows as diverged once a later one
replaced it. Every commit was therefore read and mapped by hand; the counts say where to look.

Whole range, final state of 200 native files: 918 hunks, 451 present, 384 missing, 83 diverged.

## Changes no earlier row covered

- **BC-28** classic phone/tablet player redesign (`75c8aaa2`, the player half of `7359bcc8`, `2424d99e`, the lyrics-header
  share entry `89912638`): the classic player is kept only behind the new-look switch; recorded as a decision.
- **BC-29** album/playlist header redesign of the classic release page (`376b6329`): a redraw; recorded as a decision.
- **BC-30** list behaviour from `7359bcc8`: only the played row lights in Home/Search/release/History lists, and the add-on
  quality badge in song rows (`SourceQualityBadge` exists but is unused).
- **BC-31** add-on results in Search (`2160a822`).
- **BC-32** snapping carousels (#737, part of `a6ea0ed1`).
- **BC-33** right-to-left lyrics alignment and word sweep (the RTL lyric commits): mostly diverged from the fork's lyric views.
- **BC-34** liquid-glass tab indicator clamps and shelf/"show all" back-stack fixes (partly reverted upstream by `9fdde092`).
- **BC-35** desktop functions inside the redesign PR that are not the redesign: SMTC seek, borderless fullscreen, playlist
  context menu and reorder, Last.fm with the listener's own key (BC-23 said to compare them individually).
- **BC-36** party server and deployment changes (`backend/`): Go handlers for the browser client and Caddy/setup tuning.
- **BC-37** upstream's separate CI workflows (`tv.yml`, `web.yml`, the Wear step of `android.yml`): recorded as a decision.

Found and fixed during the audit: Replay and settings pages now close when the player opens an album or artist (`3e56da1f`,
BC-18), the desktop account button follows the selected profile (`52c0683f`, BC-21) and the live Genius scrape no longer
runs in unit tests without `LIVE_TESTS=1` (`66f9c546`). Flagged for its owner: the Cast crash fix `8636423e` is still missing
(BC-11).

## Every commit

Columns: native files / other files (web, TV, watch, desktop UI module, binaries); hunks present / missing / diverged in
this branch; files with missing hunks; the row that owns it.

| Commit | Subject | Files | Hunks p/m/d | Missing in | Row |
| --- | --- | --- | --- | --- | --- |
| `0a5c8479` | feat(web): play music videos as video, in the mini player and full screen | 0 / 7 | 0 / 0 / 0 |  | BC-25 |
| `dbb39f55` | feat(web): explicit badge in the players, a centred E, and no focus box | 0 / 8 | 0 / 0 / 0 |  | BC-25 |
| `4b09b59c` | refactor(web): style the app with Tailwind CSS v4 instead of CSS Modules | 0 / 90 | 0 / 0 / 0 |  | BC-25 |
| `a50e34e7` | chore(web): expose the UI store to browser-driven tests in dev builds | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `6109d5d7` | ci(web): typecheck, test and build the web app, extension and shared code | 1 / 1 | 0 / 1 / 0 | web.yml:1 | BC-25 / BC-37 |
| `c86a2209` | feat(web): Apple Music motion artwork, permission prompts and full-screen polish | 0 / 27 | 0 / 0 / 0 |  | BC-25 |
| `d48afbe8` | test(backend): reset party creation limit between test servers | 1 / 0 | 0 / 1 / 0 | main_test.go:1 | BC-36 |
| `5e6e73cc` | fix(web): keep the Apple page parser free of browser-only imports | 0 / 4 | 0 / 0 / 0 |  | BC-25 |
| `91eb7486` | feat(web): artist pages take Apple Music's photo, logo, colour and Play fill | 0 / 6 | 0 / 0 / 0 |  | BC-25 |
| `a58f9d31` | feat(web): the LCD's title opens the album, each artist their own page | 0 / 6 | 0 / 0 / 0 |  | BC-25 |
| `b2d887e8` | feat(web): hovering the player pill dims the artwork and shows full screen | 0 / 2 | 0 / 0 / 0 |  | BC-25 |
| `35ee78fe` | feat(web): full-screen player with lyrics over the artwork's mesh | 0 / 16 | 0 / 0 / 0 |  | BC-25 |
| `67ec9481` | fix(web): a slow extension worker no longer flips the site to 'no extension' | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `0a3931e5` | feat(web): extension 0.4.4 also serves upstream-8bw.pages.dev | 1 / 3 | 0 / 0 / 1 |  | BC-25 |
| `f6736f69` | feat(web): extension 0.4.3; the zip and its folder carry the version | 0 / 4 | 0 / 0 / 0 |  | BC-25 |
| `0a3aa440` | fix(web): glass blur was missing from production builds | 0 / 9 | 0 / 0 / 0 |  | BC-25 |
| `c49a8794` | fix(web): www.upstream.in sends visitors to upstream.in | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `0f7031af` | fix(web): spaced, centred shuffle and repeat; volume slides in over the actions | 0 / 2 | 0 / 0 / 0 |  | BC-25 |
| `ca4f4d97` | feat(web): Apple Music's player icons; LCD hover shows times above the line | 0 / 4 | 0 / 0 / 0 |  | BC-25 |
| `86af8923` | fix(web): Settings says when the extension is too old to share listening activity | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `45db574d` | fix(web): crossfade went silent in a background tab | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `87388fff` | fix(web): album and playlist rows mark the playing song in white with a play icon | 0 / 2 | 0 / 0 / 0 |  | BC-25 |
| `2c614ddb` | fix(web): frosted search suggestions; drop the Radio page | 0 / 6 | 0 / 0 / 0 |  | BC-25 |
| `3da69680` | feat(web): plays go to the YouTube Music history, as on the phone | 0 / 9 | 0 / 0 / 0 |  | BC-25 |
| `e6f21590` | feat(web): Recently Played on Home; a clearer Canvas behind lyrics | 0 / 4 | 0 / 0 / 0 |  | BC-25 |
| `b7b45544` | fix(web): roomier shelves, and arrows on the artist's Top Songs | 0 / 5 | 0 / 0 / 0 |  | BC-25 |
| `af132daa` | feat(web): lyrics in SF Pro Display, the phone's lyric typeface | 0 / 4 | 0 / 0 / 0 |  | BC-25 |
| `aa04b3d3` | fix(web): lyric lines past the 4th stay faded; blur the Spotify Canvas | 0 / 2 | 0 / 0 / 0 |  | BC-25 |
| `b2423d09` | build(web): drop wrangler config; the site deploys on Cloudflare Pages | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `80fd71b0` | build(web): wrangler config for Cloudflare Workers static assets | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `89b68ebf` | build(web): build:site for Cloudflare Pages | 0 / 2 | 0 / 0 / 0 |  | BC-25 |
| `b45459d3` | feat(web): extension serves upstream.in | 1 / 3 | 0 / 0 / 1 |  | BC-25 |
| `62161375` | fix(web): YouTube Music glyph on white in Settings | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `afdf4421` | fix(web): centered settings pages, 10 AutoPlay tracks kept queued, 20 top songs | 0 / 7 | 0 / 0 / 0 |  | BC-25 |
| `ebe0c103` | version upgrade | 1 / 1 | 0 / 0 / 1 |  | BC-25 |
| `df0a3293` | feat(web): Settings page, and the Listen Together client (phase 10, unverified) | 4 / 21 | 0 / 5 / 1 | main.go:3, README.md:1, main_test.go:1 | BC-25 / BC-36 |
| `bcb3e440` | feat(web): phase 9 Spotify and Discord | 0 / 46 | 0 / 0 / 0 |  | BC-25 |
| `68cd8ff3` | fix(web): lyrics follow the song, and a granted host is asked again | 0 / 5 | 0 / 0 / 0 |  | BC-25 |
| `4d029801` | feat(web): phase 8 lyrics | 0 / 32 | 0 / 0 / 0 |  | BC-25 |
| `5601f888` | fix(web): an empty last page ends a feed instead of wiping it | 0 / 8 | 0 / 0 / 0 |  | BC-25 |
| `093c006b` | feat(web): phase 6 queue and player | 0 / 25 | 0 / 0 / 0 |  | BC-25 |
| `b4990eb2` | fix(web): sign in as the Google login YouTube Music uses | 0 / 8 | 0 / 0 / 0 |  | BC-25 |
| `2a0b4bb3` | docs(web): YouTube sign-in needs all three SAPISID hashes | 0 / 1 | 0 / 0 / 0 |  | BC-25 |
| `4427cd33` | fix(web): read the YouTube account more robustly and say why when it can't | 0 / 8 | 0 / 0 / 0 |  | BC-25 |
| `5608a1ab` | feat(web): phase 5 YouTube Music sign-in through the extension | 0 / 54 | 0 / 0 / 0 |  | BC-25 |
| `534456f9` | feat(web): phase 4 browse UI over yt.music | 0 / 60 | 0 / 0 / 0 |  | BC-25 |
| `f35e9794` | feat(web): phase 3 YouTube playback in the browser | 0 / 28 | 0 / 0 / 0 |  | BC-25 |
| `a7cdebcf` | feat(web): phase 2 scaffold for the web player and extension | 0 / 81 | 0 / 0 / 0 |  | BC-25 |
| `e468e3a2` | feat(web): phase 1 investigation for the web player | 0 / 36 | 0 / 0 / 0 |  | BC-25 |
| `e517e767` | fix(innertube): read continuation token only from the first renderer's endpoint | 2 / 0 | 1 / 0 / 1 |  | BC-06 |
| `5e1a001a` | fix(queue): review fixes for bounded album/playlist playback | 12 / 0 | 49 / 1 / 3 | MainActivity.kt:1 | BC-05 |
| `bdf82d77` | Artist signatures: signed-by caption, About signature, addon-style URL editor; desktop account button wash | 7 / 14 | 3 / 17 / 5 | DetailScreen.kt:8, MainActivity.kt:5, SettingsSheet.kt:2, DesktopAccountUi.kt:1, DesktopHover.kt:1 | BC-02 |
| `697a75b8` | Listen Together: idle health poll back to 10s, debounce back to 5s | 1 / 0 | 0 / 0 / 1 |  | BC-09 |
| `f2defbfb` | tv: add coil-svg, the shared app sources register SvgDecoder | 0 / 1 | 0 / 0 / 0 |  | BC-24 |
| `aeeaa3f8` | feat(locale): translate signature strings plus chinese_* names | 0 / 14 | 0 / 0 / 0 |  | BC-02 |
| `9b6ec7ec` | feat(artist): white-ink scans, hi-res SVG decode | 2 / 0 | 0 / 2 / 4 | SignatureInk.kt:1, DetailScreen.kt:1 | BC-02 |
| `b08a4a6f` | feat(signatures): break best-pick ties by resolution | 1 / 0 | 0 / 0 / 2 |  | BC-02 |
| `93b44598` | fix(images): decode SVG in Coil and identify to Wikimedia | 2 / 0 | 0 / 4 / 0 | CosmotuneApplication.kt:3, build.gradle.kts:1 | BC-02 |
| `f80a3d47` | feat(artist): show signature fallback in artist header | 1 / 0 | 0 / 7 / 0 | DetailScreen.kt:7 | BC-02 |
| `3a2af8fb` | feat(settings): add Artist signatures row and URL dialog | 1 / 0 | 0 / 2 / 2 | SettingsSheet.kt:2 | BC-02 |
| `0d74981b` | feat(signatures): add signature API settings and strings | 2 / 0 | 5 / 1 / 0 | strings.xml:1 | BC-02 |
| `f11f828b` | feat(signatures): add ArtistSignatureRepository with best-pick ranking | 1 / 0 | 0 / 1 / 0 | ArtistSignatures.kt:1 | BC-02 |
| `9d40d7e3` | fix: prevent repeat-all context queue duplication | 2 / 0 | 3 / 0 / 0 |  | BC-05 |
| `70af3651` | fix(android): throttle idle Listen Together health polls | 2 / 0 | 3 / 1 / 2 | ListenTogetherIdleHealthTest.kt:1 | BC-09 |
| `ff4f2d69` | fix(innertube): extract playlist continuation token wrapped in commandExecutorCommand | 2 / 0 | 1 / 1 / 1 | DesktopParityTest.kt:1 | BC-06 |
| `be45e203` | fix: prevent queue resurrection and repeat duplicates | 7 / 0 | 8 / 0 / 3 |  | BC-05 |
| `94cba2fa` | Desktop player: fix the PR #795 regressions | 5 / 6 | 1 / 2 / 16 | DesktopApp.kt:1, DesktopScrobbling.kt:1 | BC-23 / BC-35 |
| `6f30aa27` | Desktop: Apple Music redesign, Automix FX and phrase lengths (PR #795) | 29 / 9 | 32 / 76 / 17 | DesktopApp.kt:31, DesktopSongMenuUi.kt:8, DesktopPlaylistUi.kt:6, smtc_abi.cpp:5, window_jni.cpp:4, DesktopIntegrationsUi.kt:4 | BC-08 (Automix FX), BC-23 (redesign), BC-35 (SMTC seek, borderless fullscreen, playlist menus, Last.fm key) |
| `fc742ffa` | Split the desktop UI off :sharedUi into its own :desktopUi module | 4 / 93 | 0 / 1 / 7 | settings.gradle.kts:1 | BC-23 |
| `9334cfbe` | bug fixes | 5 / 0 | 20 / 0 / 0 |  | BC-27 (present) |
| `7359bcc8` | main player changes + addon changes + icon changes | 34 / 117 | 53 / 57 / 20 | DesktopApp.kt:13, PlayerControls.kt:8, HomeScreen.kt:8, DetailScreen.kt:7, SearchScreen.kt:6, Common.kt:5 | BC-10 (add-on), BC-17 (status bar), BC-22 (icons), BC-28 (classic player), BC-30 (lit row, quality badge) |
| `8cf4efcf` | Harden PIN lock: 120k PBKDF2 iterations with rehash-on-login, monotonic cooldown clock | 0 / 4 | 0 / 0 / 0 |  | BC-24 |
| `65fca531` | eq fix phone + pc | 12 / 0 | 47 / 0 / 0 |  | BC-07 |
| `17ebe3c5` | ssc hq and wifi support for lossless addon | 4 / 0 | 4 / 3 / 0 | SourcesScreen.kt:2, strings.xml:1 | BC-10 |
| `d80f7a97` | discord rpc fix | 3 / 0 | 1 / 8 / 2 | DesktopDiscordRpc.kt:5, DiscordRPC.kt:2, DiscordApplication.kt:1 | BC-26 |
| `32bfa3d3` | Only change google image hosts to the new regex | 2 / 0 | 2 / 0 / 0 |  | BC-01 (image hosts) |
| `ca66070e` | redesign local and downloads folder | 10 / 0 | 5 / 24 / 19 | MainActivity.kt:8, DetailScreen.kt:8, LibraryPhoneParts.kt:5, MainViewModel.kt:1, CoverPlaceholder.kt:1, LocalFolder.kt:1 | BC-20 |
| `ef310bf4` | Fix sign out - signing out discord & spotify | 2 / 0 | 2 / 0 / 0 |  | BC-21 |
| `760a57dd` | if opened on a locked profile, it will show profile selector | 0 / 2 | 0 / 0 / 0 |  | BC-24 |
| `eb1687c9` | feat: persist bounded context window state | 7 / 0 | 17 / 2 / 4 | CosmotuneApplication.kt:1, BoundedContextRequests.kt:1 | BC-05 |
| `81b0f666` | Implement PIN input throttles | 0 / 4 | 0 / 0 / 0 |  | BC-24 |
| `a590968d` | store the PIN in a 🧂 hash | 0 / 3 | 0 / 0 / 0 |  | BC-24 |
| `54e18a59` | feat(desktop): show measured audio quality on Discord presence | 6 / 0 | 0 / 17 / 0 | DesktopIntegrationsDialog.kt:6, DesktopDiscordRpc.kt:5, DesktopDiscordSettings.kt:3, DesktopApp.kt:1, DesktopDiscordAudioQuality.kt:1, DesktopDiscordAudioQualityTest.kt:1 | BC-26 |
| `113e8908` | fix(desktop): reconnect Discord IPC on failure, retry poisoned tracks, log pushes | 2 / 0 | 0 / 6 / 0 | DesktopDiscordRpc.kt:4, DesktopDiscordIpc.kt:2 | BC-26 |
| `4c7209a3` | Fill missing locale strings, release metadata retriever in finally | 1 / 16 | 1 / 0 / 0 |  | BC-20 |
| `a6ea0ed1` | fix: easy issues on v1.8.1 (#744 locale zh, #737 carousel snap, #754 local track numbers) | 9 / 14 | 14 / 7 / 5 | ShelfRow.kt:3, DetailScreen.kt:2, LocalMusicScreen.kt:2 | BC-20 (#754), BC-32 (#737 snap), locales (done) |
| `cbe3fd4e` | added liquid glass icon | 4 / 2 | 0 / 9 / 0 | SettingsSheet.kt:5, AndroidManifest.xml:2, AppIcon.kt:1, strings.xml:1 | BC-22 |
| `19989d70` | clean up unnecessary changes during bug fixing | 4 / 3 | 2 / 0 / 2 |  | BC-27 (present) |
| `d233bae7` | clear PIN's after sign out | 3 / 2 | 2 / 0 / 3 |  | BC-24 |
| `9f002e99` | Remove multiple account support for yt | 1 / 1 | 1 / 0 / 0 |  | BC-21 |
| `a74aa47c` | Initial Implementation of profiles and pins for TV | 0 / 2 | 0 / 0 / 0 |  | BC-24 |
| `2424d99e` | layout fixes | 6 / 0 | 4 / 4 / 2 | LiquidGlass.kt:2, DetailScreen.kt:2 | BC-28 / BC-34 |
| `d45c5d21` | remove cast logs | 5 / 0 | 18 / 0 / 1 |  | BC-11 (present) |
| `75c8aaa2` | main player ui optimziations | 14 / 0 | 24 / 43 / 4 | PlayerControls.kt:27, DetailScreen.kt:4, NowPlayingScreen.kt:4, ArtworkPalette.kt:2, ArtworkPaletteTest.kt:2, ic_player_next.xml:1 | BC-28 |
| `db94eb97` | feat: gate bounded playback for parties and autoplay | 1 / 0 | 5 / 1 / 0 | PlaybackService.kt:1 | BC-05 |
| `ff0fca0d` | feat: defer bounded edits until crossfade settlement | 4 / 0 | 12 / 1 / 2 | PlaybackService.kt:1 | BC-05 |
| `6cbb4591` | feat: bound album and playlist context windows | 8 / 0 | 17 / 3 / 16 | MainActivity.kt:1, PlaybackService.kt:1, MainViewModel.kt:1 | BC-05 |
| `376b6329` | Redesign album/playlist header after Apple Music | 4 / 0 | 0 / 30 / 3 | DetailScreen.kt:21, MainActivity.kt:5, ArtworkPalette.kt:3, Common.kt:1 | BC-29 |
| `8636423e` | fix: chromecast crash | 5 / 1 | 3 / 13 / 6 | CastController.kt:6, CastPlayback.kt:5, CastLog.kt:1, CastOptionsProvider.kt:1 | BC-11 (Cast crash fix still missing) |
| `c65bff5c` | wear os support | 29 / 56 | 55 / 32 / 8 | MainActivity.kt:17, build.gradle.kts:4, SettingsSheet.kt:3, PlayerHost.kt:2, android.yml:1, proguard-rules.pro:1 | BC-12 to BC-16 |
| `58ec7391` | setup.sh: smaller TCP buffers and a watchdog that restarts Caddy when /healthz keeps failing | 1 / 0 | 0 / 1 / 0 | setup.sh:1 | BC-36 |
| `2160a822` | addon search | 7 / 0 | 14 / 15 / 1 | SearchScreen.kt:8, MainActivity.kt:5, strings.xml:1, strings.xml:1 | BC-31 |
| `f4394514` | feat: add bounded context startup and top-up | 8 / 0 | 12 / 8 / 6 | MainActivity.kt:5, PlaybackService.kt:2, PlayerConnection.kt:1 | BC-05 |
| `522b9213` | fix(desktop): publish MPRIS capability changes | 2 / 0 | 2 / 1 / 4 | DesktopMpris.kt:1 | BC-19 |
| `ff7e4bb3` | Revert "fix(desktop): publish MPRIS capability changes" | 2 / 0 | 1 / 2 / 4 | DesktopMpris.kt:2 | BC-19 |
| `aa5ff748` | fix(lyrics): header share glyph only while the sleeve is collapsed | 1 / 0 | 0 / 0 / 1 |  | BC-28 |
| `59211f2a` | feat(android): add optional queue history display | 9 / 0 | 14 / 3 / 2 | SettingsSheet.kt:1, strings.xml:1, strings.xml:1 | BC-04 |
| `93916182` | feat: add bounded queue logical foundation | 4 / 0 | 2 / 0 / 2 |  | BC-05 |
| `52c0683f` | fix(desktop-ui): Profile switcher icon and name not updating (#614) | 1 / 0 | 0 / 0 / 2 |  | BC-21 (ported in this audit) |
| `60b718ac` | fix tv version | 2 / 23 | 0 / 2 / 1 | build.gradle.kts:1, AppUpdateChecker.kt:1 | BC-24 |
| `f2b6566e` | tv: rebuild the TV app as an Apple Music tvOS-style client | 2 / 38 | 2 / 2 / 1 | .gitignore:1, ExploreScreen.kt:1 | BC-24 |
| `36d55dd6` | fix(search): default all searches to anonymous and record YTMusic history strictly on explicit submission | 3 / 0 | 4 / 0 / 1 |  | BC-27 (present) |
| `563912fe` | fix(search): isolate YTMusic search query recording strictly to explicit query submission | 3 / 0 | 3 / 0 / 4 |  | BC-27 (present) |
| `39ce9419` | fix premium albums | 1 / 0 | 2 / 0 / 0 |  | BC-27 (present) |
| `aa537484` | onboarding screen | 8 / 0 | 4 / 14 / 3 | MainActivity.kt:6, MeshGradient.kt:5, OnboardingScreen.kt:1, ic_google_g.xml:1, Common.kt:1 | BC-22 |
| `d0370d22` | spotify login fix | 6 / 15 | 15 / 3 / 0 | MainActivity.kt:2, strings.xml:1 | BC-21 |
| `89912638` | feat(lyrics): header 3-dot becomes a share entry while lyrics are up | 2 / 0 | 3 / 2 / 0 | NowPlayingScreen.kt:2 | BC-28 |
| `e41d3107` | fix(ui): mirror lyric strip chevron icon for RTL lyrics | 1 / 0 | 1 / 0 / 0 |  | BC-33 |
| `7bdda1c0` | fix(ui): minimal fix for RTL lyric strip chevron positioning | 1 / 0 | 0 / 1 / 1 | PlayerLyrics.kt:1 | BC-33 |
| `2fe30205` | Revert "fix(ui): position lyric strip chevron at the end of sentence for RTL lyrics" | 1 / 0 | 0 / 0 / 2 |  | BC-33 |
| `a569c83f` | wip: instrument CrossfadeController begin queue copy and setMediaItems timing metrics | 1 / 0 | 0 / 0 / 2 |  | BC-05 |
| `b792593f` | fix(ui): position lyric strip chevron at the end of sentence for RTL lyrics | 1 / 0 | 0 / 0 / 2 |  | BC-33 |
| `d8113a39` | wip: use immutable CanonicalCtx, tier-aware key, refresh original snapshot, and add imminent transition gate | 4 / 0 | 19 / 0 / 2 |  | BC-05 |
| `b799b681` | fix(ui): restore LTR left alignment and fix RTL lyric chevron icon placement | 1 / 0 | 2 / 0 / 0 |  | BC-33 |
| `91af62d6` | fix(ui): force right alignment for RTL lyric strip container | 1 / 0 | 1 / 0 / 0 |  | BC-33 |
| `6e8694ce` | fix(ui): apply LocalLayoutDirection provider to lyric strip for content-based RTL | 1 / 0 | 1 / 0 / 2 |  | BC-33 |
| `9102d5a8` | wip: add replaceChanged prefix/suffix trimming and canonicalContext staleness guard | 2 / 0 | 8 / 3 / 7 | QueueShuffle.kt:2, QueueShuffleTierTest.kt:1 | BC-05 |
| `06f66262` | wip: prune dead controller shuffle branch, use two-segment in-place restore, and add real-path tests | 4 / 0 | 12 / 1 / 9 | QueueShuffle.kt:1 | BC-05 |
| `820b3aad` | WIP: async queue reorder with stale guard, inFlight deduplication, and context parity | 3 / 0 | 0 / 1 / 13 | QueueShuffleTierTest.kt:1 | BC-05 |
| `8c72c3fd` | feat(tv): add upstream TV app in upstreamtv folder (#632) | 6 / 59 | 6 / 2 / 1 | tv.yml:1, CanvasArtworkPlayer.kt:1 | BC-24 |
| `66f9c546` | test: make the live Genius scrape opt-in | 1 / 0 | 0 / 2 / 0 | GeniusTest.kt:2 | BC-27 (ported in this audit) |
| `3f503898` | fix(ui): resolve Compose Alignment type mismatch for RTL lyric strip | 1 / 0 | 0 / 0 / 1 |  | BC-33 |
| `b7323d71` | fix(ui): align lyric strip container dynamically for RTL lyrics | 1 / 0 | 0 / 0 / 2 |  | BC-33 |
| `0d0b54e5` | fix(lyrics): apply content-based RTL and fix sweep animation on lyric strip | 1 / 0 | 0 / 0 / 1 |  | BC-33 |
| `bc8a587f` | fix: preserve context history on forward seeks and prevent duplicate tracks on shuffle | 4 / 0 | 12 / 0 / 3 |  | BC-05 |
| `4008a7df` | WIP: sync queue snapshot on timeline reorder and fix canonical index resolution on shuffle off | 3 / 0 | 1 / 1 / 2 | QueueShuffleTierTest.kt:1 | BC-05 |
| `c19cff95` | WIP: restore full collection context on shuffle off and preserve jump history | 5 / 0 | 4 / 1 / 6 | QueueCoordinatorTest.kt:1 | BC-05 |
| `6c2c2806` | fix(queue): guard header shuffle against empty lists and verify startingOrder lifecycle | 2 / 0 | 0 / 1 / 2 | MainActivity.kt:1 | BC-05 |
| `6bee168f` | WIP: align album and playlist queue shuffle session lifecycle and provenance | 6 / 0 | 6 / 4 / 6 | QueueShuffle.kt:1, DetailScreen.kt:1, QueueCoordinatorTest.kt:1, QueueShuffleTierTest.kt:1 | BC-05 |
| `f8144f8d` | Listen Together: jitter the reconnect backoff, skip health polling during a live party | 2 / 0 | 4 / 0 / 1 |  | BC-09 |
| `d7b23535` | Caddy: answer /healthz directly, drop gzip, cap memory, keep idle at 5m | 2 / 0 | 0 / 1 / 2 | setup.sh:1 | BC-36 |
| `a6c99bcb` | setup.sh: add a 2 GB swap file so a connection spike cannot OOM-kill Caddy | 1 / 0 | 0 / 1 / 0 | setup.sh:1 | BC-36 |
| `ea55bd08` | setup.sh: load nf_conntrack at boot so the raised limit survives a reboot | 1 / 0 | 0 / 0 / 1 |  | BC-36 |
| `540bcd1a` | Backend: raise the conntrack table and close idle Caddy connections | 2 / 0 | 0 / 2 / 0 | Caddyfile:1, setup.sh:1 | BC-36 |
| `b06e64b6` | Listen Together: stop polling /healthz on every network change | 1 / 0 | 2 / 0 / 4 |  | BC-09 |
| `eb9f77bf` | fix(ui): close unclosed KDoc comment in PlayerLyrics | 1 / 0 | 1 / 0 / 0 |  | BC-33 |
| `12086e13` | fix(ui): fix RTL xOn boundary and far-edge detection in horizontalAt | 1 / 0 | 1 / 0 / 1 |  | BC-33 |
| `fb79ee09` | Delete empty file (0) | 1 / 0 | 0 / 0 / 0 |  | none (empty file) |
| `e556f634` | fix(ui): use getParagraphDirection and getLineForOffset in RTL word-sweep | 1 / 0 | 4 / 0 / 0 |  | BC-33 |
| `10a8c124` | fix(ui): make RTL word-sweep direction-aware from the layout, fix start-of-line flash and visual-line handoff | 1 / 0 | 2 / 0 / 5 |  | BC-33 |
| `610487d2` | fix(ui): fix LayoutDirection imports and composable scope for RTL row override | 1 / 0 | 1 / 0 / 0 |  | BC-33 |
| `8c03d1a0` | fix(ui): keep RTL word-sweep stable through end of line | 1 / 0 | 0 / 0 / 2 |  | BC-33 |
| `0693381f` | fix(ui): right-align RTL lyrics from their own text direction, independent of app language | 1 / 0 | 8 / 1 / 2 | PlayerLyrics.kt:1 | BC-33 |
| `966e81d4` | revert(ui): drop past-line dimming/active-state attempts, restore base activeLyricRows | 2 / 0 | 4 / 0 / 0 |  | BC-33 |
| `075b1932` | fix(player): resolve unresolved positionMs references in LyricsPanel | 1 / 0 | 0 / 0 / 2 |  | BC-33 |
| `9fdde092` | revert(shelf-navigation): undo shelf grid / detail stack navigation fix; keep replay and liquid glass | 5 / 0 | 11 / 0 / 1 |  | BC-34 |
| `a2f13dd1` | fix(kotlin): add missing Flow imports for SharedFlow and collect | 2 / 0 | 0 / 2 / 0 | MainActivity.kt:1, MainViewModel.kt:1 | BC-34 |
| `1ce8835a` | fix(navigation): scope activeShelf to detailStack entries for clean backstack restoration | 3 / 0 | 0 / 6 / 1 | MainViewModel.kt:4, MainActivity.kt:1, Models.kt:1 | BC-34 |
| `b2777f73` | fix(navigation): preserve activeShelf state for 2-step backstack flow | 2 / 0 | 4 / 0 / 1 |  | BC-34 |
| `21e7080c` | fix(ui): clamp liquid glass bubble in absolute tab index space | 2 / 0 | 0 / 4 / 1 | FloatingTabBar.kt:3, FloatingBottomBar.kt:1 | BC-34 |
| `4efa6e98` | fix(navigation): properly stack album destination over shelf grid while preserving overlay backstack | 2 / 0 | 0 / 4 / 1 | MainActivity.kt:2, DetailScreen.kt:2 | BC-34 |
| `fbca2d28` | fix(ui): absolute clamp liquid glass indicator between first and last tabs | 1 / 0 | 0 / 0 / 1 |  | BC-34 |
| `18305993` | fix(navigation): preserve shelf grid state and fix backstack hierarchy | 1 / 0 | 0 / 0 / 1 |  | BC-34 |
| `b6f859ea` | fix(compilation): correct activeShelf state mutation in DetailScreen | 1 / 0 | 0 / 0 / 1 |  | BC-34 |
| `5bd7b905` | fix(ui): clamp liquid glass indicator to right boundary limits | 1 / 0 | 0 / 0 / 1 |  | BC-34 |
| `22fcaf8b` | fix(navigation): dismiss grid overlay when navigating to item from shelf grid | 1 / 0 | 0 / 1 / 0 | DetailScreen.kt:1 | BC-34 |
| `be35176f` | fix(artist): add missing LazyGridState imports for grid state preservation | 1 / 0 | 0 / 1 / 1 | DetailScreen.kt:1 | BC-02 |
| `5396c0cd` | fix(nav): clamp liquid glass indicator drag within right boundary | 1 / 0 | 0 / 1 / 0 | FloatingBottomBar.kt:1 | BC-34 |
| `7b5bff54` | fix(artist): preserve show all grid in backstack and retain scroll position | 2 / 0 | 0 / 2 / 0 | MainActivity.kt:1, DetailScreen.kt:1 | BC-02 |
| `3e56da1f` | fix(replay): dismiss replay overlay when navigating to artist or album | 1 / 0 | 1 / 0 / 1 |  | BC-18 (ported in this audit) |
| `2bdfdef3` | fix(desktop): publish MPRIS capability changes | 2 / 0 | 2 / 1 / 4 | DesktopMpris.kt:1 | BC-19 |
| `64f508c1` | fix(replay): resolve 0 track count issue in weekly and monthly summary ranges | 1 / 0 | 0 / 0 / 2 |  | BC-18 |
| `d46f1d35` | fix(replay): merge entity maps and daily stats in weekly MergedBucket.add | 1 / 0 | 1 / 0 / 0 |  | BC-18 |
| `6fbdefe9` | fix(stats): fix val reassignment compilation errors | 1 / 0 | 7 / 0 / 0 |  | BC-18 |
| `fa92c822` | fix(stats): populate entity daily stats and fix weekRange calculation | 1 / 0 | 2 / 1 / 4 | ListeningStats.kt:1 | BC-18 |
| `b1fda689` | fix(stats): fix range property access and LocalDate type mismatch | 1 / 0 | 2 / 0 / 2 |  | BC-18 |
| `517d0c50` | fix(stats): change val to var for reassigned variables in ListeningStats | 1 / 0 | 1 / 0 / 0 |  | BC-18 |
| `382bacf2` | fix(stats): resolve remaining syntax and variable compilation errors in ListeningStats | 1 / 0 | 4 / 1 / 3 | ListeningStats.kt:1 | BC-18 |
| `a058fe2c` | fix(stats): resolve compilation errors in ListeningStats | 1 / 0 | 2 / 0 / 2 |  | BC-18 |
| `03ae7c92` | feat(replay): accurate weekly stats with daily granularity | 3 / 0 | 9 / 4 / 8 | ListeningStats.kt:4 | BC-18 |
| `c6853229` | fix(replay): wrap THIS_WEEK if-expressions as Pair<String, Boolean> | 1 / 0 | 1 / 0 / 0 |  | BC-18 |
| `3e6edd2a` | feat(replay): add This week mode | 4 / 0 | 2 / 4 / 3 | strings.xml:4 | BC-18 |
| `f264f305` | fix(ui): restore auto-scroll and active state for line-synced lyrics | 1 / 0 | 0 / 0 / 1 |  | BC-33 |
| `2285edef` | fix(ui): correct RTL word-sweep feather direction and reveal boundary | 1 / 0 | 5 / 1 / 9 | PlayerLyrics.kt:1 | BC-33 |
| `5fb13d0a` | fix(ui): resolve RTL alignment, auto-scroll freeze, sweep mask rendering, and scroll-back dimming | 1 / 0 | 0 / 0 / 2 |  | BC-33 |
| `23287d27` | fix(ui): fix RTL alignment, word-sync sweep animation, and past lyric dimming | 1 / 0 | 0 / 2 / 4 | PlayerLyrics.kt:2 | BC-33 |
| `99460d21` | fix(ui): resolve jvm signature clash in isRtl detection | 1 / 0 | 0 / 0 / 1 |  | BC-02 |
| `2db15519` | refactor(ui): achieve complete unicode RTL detection coverage in lyrics | 1 / 0 | 0 / 0 / 1 |  | BC-33 |
| `e0a25fce` | fix(ui): resolve compilation errors and misplaced import in PlayerLyrics.kt | 1 / 0 | 1 / 0 / 1 |  | BC-33 |
| `42e575f4` | fix(ui): fix RTL lyrics formatting, word-sync animation, alignment, and scroll highlight bug | 2 / 0 | 0 / 4 / 8 | PlayerLyrics.kt:3, LyricFocus.kt:1 | BC-33 |
