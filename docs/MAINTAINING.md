# Working on the code

## Listening tools

`com.cosmotune.listening` in `shared` owns versioned saved sessions, smart rules, followed artists/inbox, setup choices and the last diagnostic report. `com.cosmotune.ui.listening` in `sharedUi` owns their adaptive interface. `PhoneListeningTools` and `DesktopListeningTools` supply real platform playback, playlist stores, file choosers and settings. Player layouts are unchanged.

- Storage is `filesDir/cosmotune-listening.json` on Android and `$XDG_DATA_HOME/cosmotune/listening.json` (default `~/.local/share/cosmotune/listening.json`) on desktop. Writes use a temporary file, fsync and atomic replacement where available. Invalid originals are retained for recovery.
- Listening export/import transfers metadata, sessions, rules and inbox entries. It is additive by stable ID; existing local edits win. It excludes credentials and media, includes local paths, and skips unavailable files on restore. This is explicit file transfer, not automatic cloud synchronization or a full account/library backup.
- Saved session repeat values follow Media3: 0 off, 1 one, 2 all. Preserve duplicate queue entries, their order/identity and the current playhead. Restore paused. Do not restore while participating in Listen Together.
- Release checks are manual. The first successful artist fetch establishes its baseline; later newly discovered release IDs enter the persistent inbox. No background notifications are scheduled.
- Diagnostics use the configured transport and actual playback source, report catalogue/media separately, bound time and byte reads, and never serialize request headers, signed URLs or raw exception messages.
- `uiTests` is a separate JVM module for real Compose interactions and screenshots. Its test dispatcher dependencies must not be moved onto the existing offscreen `sharedUi:jvmTest` classpath. Run `:uiTests:test` alongside `:shared:jvmTest :sharedUi:jvmTest :video:jvmTest :desktopApp:test :app:testDevDebugUnitTest`. Screenshots are in `uiTests/build/reports/listening-ui/`.

See [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md) for validation and remaining device checks, and `FORK.md` for upstream integration sites.

## Keeping in step with upstream

Cosmotune began as a fork of an upstream project (named in `UPSTREAM.md` and `NOTICE.md`) and still takes fixes from it by hand, on request, not on a schedule.

- `UPSTREAM.md` names the last upstream commit we have taken.
- `FORK.md` lists every edit we made to a file that came from upstream, with how to merge it. Read the row
  for a file before you change it or merge a change to it.
- A fresh clone has no upstream history. Add it once with `git remote add upstream <the original repository's address, named
  in `NOTICE.md`>` and `git fetch upstream`; the digest script tells you the same when the remote is missing.
- `tools/upstream-digest.sh` prints what changed upstream since that commit, grouped (playback fixes,
  security, features, files in the ledger that changed). It needs a git remote called `upstream` that points at
  the original repository.

New Cosmotune-only code goes under `com.cosmotune`. The files that came from upstream live in `com.cosmotune.music`
(moved there from upstream's own package in one commit; see the rename row at the end of the table in `FORK.md`), so a
change taken from upstream has to be moved to the renamed folder and names (by hand, or by rewriting the paths and the
package of upstream's patch before applying it). `ForkGuardTest` (run with
`./gradlew :shared:jvmTest --tests "com.cosmotune.ForkGuard*"`) checks these rules, and `OldNameScanTest` fails when the
upstream name comes back anywhere outside its short allowlist.

## The OpenCanvas engine

- The 28 files under `shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/canvas/opencanvas/` are copied from the
  OpenCanvas repository (https://github.com/Vijay-Janarthanan/OpenCanvas) and must stay byte-identical to it, which is why
  they have no licence header. Change them there and copy them over; when its licence text or notice changes, refresh
  `licenses/` too.

## The cover palette

`shared/src/commonMain/kotlin/com/cosmotune/theme/` is the colour engine of the new interface. `CoverPalette` turns the
pixels of a cover into a `CoverColors` (every colour a screen needs, from one seed colour taken from the cover),
`CoverDerive` derives the palette from the seed, `CoverPaletteSpec` holds every number of the design, and `Oklab` holds
the colour maths. It is pure Kotlin with no Android or Compose types, so its tests run on the desktop JVM. How a screen
calls it is at the top of `CoverPalette.kt`.

- The derivation is the designer's: `CoverDerive` is a line-by-line port of `derive()` in `dynamic-colour.js` (the designer's
  reference implementation, `ui/v2/` of the design folder) in OKLCH, and `Oklab.kt` ports its colour maths (CSS Color 4 gamut
  mapping, WCAG contrast). `CoverPaletteSpec` holds the formulas' numbers. To retune, change a default there and run
  `CoverPaletteRailsTest`: the safety rails (contrast, the darkness rail, the gamut margin) are tested for every hue.
- `CoverPaletteGoldenTest` checks the Kotlin output against the reference, colour by colour: `shared/src/jvmTest/resources/theme-golden/golden.json`
  is made by `make-golden.mjs`, which runs the designer's `dynamic-colour.js` unchanged under Node on 61 seeds in both themes.
  When the designer changes the formulas, rerun it from that folder (`node make-golden.mjs "<path to dynamic-colour.js>" > golden.json`),
  port the change into `CoverDerive` and `CoverPaletteSpec`, and the test shows what still differs. `CoverPaletteDesignerTableTest` does the same
  against the twelve-cover table printed in `01-dynamic-colour.md`.
- The seed is chosen in the private function `CoverPalette.chooseColours`, after the designer's pre-filter (pixels with an OKLab L
  under 0.16 or over 0.95, or a chroma under 0.03, are dropped; under 1.5 percent left means a grey cover and the brand apricot).
  It uses Google's Material Color Utilities, in MaterialKolor's Kotlin Multiplatform port: `com.materialkolor:material-color-utilities`
  4.1.1 (MIT, with Google's Apache-2.0 code; Android and desktop JVM, no Compose). Its Celebi quantiser mixes a cover of a few flat
  colours into one blend, so such a cover is read from its colour histogram instead (`maxFlatBins`). To use another picker,
  replace `chooseColours`; nothing else needs to change.
- The library's version follows the Kotlin version: 4.1.1 is built on Kotlin 2.3, and 5.x needs the Kotlin 2.4 standard
  library. The `FORK.md` row for `shared/build.gradle.kts` says what to do when Kotlin is raised.

## The new interface (part one)

- Our Compose code is in `sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/` (one source set for Android and the desktop), our
  pure logic in `shared/.../com/cosmotune/`. A package inside `sharedUi` can use the player's `internal` pieces; a separate module could not.
- The switch is `CosmoLookSettings` (`NEW_LOOK_DEFAULT` in `CosmoConfig`, `true` since part two of the new look: a fresh install shows it, a stored choice is kept; SharedPreferences `cosmotune_look` on the phone, the Preferences
  node `com.cosmotune.desktop/look` on the desktop). `CosmoLookTheme` replaces the theme call in `MainActivity` and `DesktopApp` and gives
  the old screens the cover palette as a Material scheme (`ColorSchemeBridge`). With the switch off it is the old theme.
- `LocalCosmoShell` is null while the switch is off and the shell state while it is on: the few places in the old roots that draw new
  chrome check it. When upstream changes `NowPlayingScreen`'s parameters, update `NowPlayingSwitch` (see the next section).
- `FORK.md` has the rows for the upstream files involved: `MainActivity.kt`, `SettingsSheet.kt`, `DesktopApp.kt` and the `cosmo_*` strings.
  `CosmoStringsTest` fails when a locale lacks a `cosmo_*` key; `CoverFieldTest` fails when `CoverColors` gains a colour with no index.

## The new look: where it hooks into upstream

The new look reaches upstream files in four ways only, and with the switch off every one of them is the old code:

- **Switch**: a call `X(` became the fully qualified `com.cosmotune.<pkg>.XSwitch(` with the same arguments. `XSwitch` has `X`'s exact
  parameter list (names, types, defaults, order), then any parameter of its own at the end; with the look off it calls `X` with every
  argument by name.
- **Delegate**: the first statement of an upstream function is `if (com.cosmotune.ui.theme.LocalCosmoShell.current != null) return
  com.cosmotune.<pkg>.CosmoX(a = a, ...)`, every argument by name; the rest of the function is untouched.
- **Import swap**: `import androidx.compose.material3.X` became `import com.cosmotune.ui.bridge.X`. The forwarder has Material's own
  signature (material3 1.3.x) and starts `if (LocalCosmoShell.current == null) { androidx.compose.material3.X(a = a, ...); return }`.
- **Theme**: the root's theme call is wrapped by `CosmoLookTheme`, plus the inline `if (cosmoShell != null)` branches listed last.

Every site has a row in `FORK.md` (find it by the file name; the row quotes the text that finds the site) and is pinned by a contract
test in `sharedUi/src/jvmTest` that reads the source: `NowPlayingContractTest`, `HomeContractTest`, `SearchContractTest`,
`LibraryContractTest`, `PagesContractTest`, `PhoneChromeContractTest`, `SheetsContractTest`, `SettingsBlocksContractTest`,
`BlocksStructureTest`, `StatesContractTest`, `DesktopChromeStructureTest`, `DesktopShellContractTest`, `DesktopPagesContractTest`,
`BridgeContractTest`, `PolishContractTest`, `VisualPolishContractTest` and `UpstreamHooksContractTest` (the theme calls, the palette
that follows the song, the phone's ground, the way to the switch in Settings, the desktop's licence entries). `app/...` below is
`app/src/main/java/com/cosmotune/music`, `desktop/...` is `desktopApp/src/main/kotlin/com/cosmotune/music/desktop` and `components/...`
is `sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components`.

| Hook | Upstream site (found by its text) | Ours | How to re-apply after a merge |
| :-- | :-- | :-- | :-- |
| Theme | `app/.../MainActivity.kt`: the `CosmotuneTheme(darkTheme = darkTheme)` call | `com.cosmotune.ui.theme.CosmoLookTheme(dark = darkTheme, legacy = { CosmotuneTheme(darkTheme = darkTheme, content = it) })` | wrap upstream's call again as `legacy`; its block stays the content |
| Theme | `desktop/.../DesktopApp.kt`: `MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography())` in `CosmotuneDesktopApp` | `CosmoLookTheme(dark = true, legacy = { MaterialTheme(..., content = it) })` (the desktop is dark only) | the same |
| Theme | first lines inside it: `MainActivity.kt` `CosmotuneApp` and the desktop's theme content | `val cosmoShell = com.cosmotune.ui.theme.LocalCosmoShell.current`, then `cosmoShell?.FollowCover(song, nextSong)` | keep both lines first; without `FollowCover` the colours stop following the song |
| Switch | `NowPlayingScreen(` and its import, both roots | `NowPlayingSwitch` (`sharedUi/.../ui/nowplaying/`) | rename the call and the import |
| Switch | `HomeScreen(` (phone `TAB_HOME ->`, desktop `LISTEN_NOW ->`) | `com.cosmotune.ui.home.HomeSwitch(` + `browse = CosmoBrowse(exploreState, ...)`; the desktop also passes `currentSong`/`isPlaying` only with the look on | rename, add the `browse` line after `title = ...` |
| Switch | `MoodGenrePlaylistsScreen(`, `ExploreScreen(`, `SearchScreen(`, both roots | `com.cosmotune.ui.search.MoodGenreSwitch(`, `ExploreSwitch(`, `SearchSwitch(` (+ `browse`) | rename; Search gets the `browse` line |
| Switch | `LibraryScreen(`, `LibraryGridPage(`, both roots; `HistoryScreen(` (phone, `key == "history"`) | `com.cosmotune.ui.library.LibrarySwitch(`, `LibraryGridSwitch(`, `HistorySwitch(` | rename |
| Switch | `DetailScreen(` (phone, the `else if (page != null)` branch) | `com.cosmotune.ui.pages.DetailSwitch(` + `onPlayPause = togglePlayPause,` (its last, own parameter) | rename, add the last argument |
| Switch | `FrostedTopBar(`, `TopBarAccountButton(` (phone) | `com.cosmotune.ui.chrome.TopBarSwitch(`, `ProfileButtonSwitch(` (+ `entries`, the drawer) | rename |
| Switch | the `ModalBottomSheet(` around `PlaylistPickerSheet(` and the one inside `browseActions?.takeIf { browseMenuOrigin == null }` (phone) | `com.cosmotune.ui.sheets.SurfaceSheetSwitch(` (the song sheet's glass); the song sheet's own host gains `scrimColor = cosmoSheetScrim()` | rename; add the scrim argument |
| Delegate | `app/.../ui/components/SongActionsSheet.kt`: `GroupSeparator`, `TintedSheet`, `ActionRow`, `SheetTrackHeader`, `SheetHeading`; `BrowseActionsSheet.kt`: `BrowseSheetHeader`; `PlaylistPickerSheet.kt`: `PlaylistRow` | `CosmoSheetGap`, `CosmoSheetSurface`, `CosmoActionRow`, `CosmoSheetHeader` (+ `explicit`), `CosmoSheetHeading`, `CosmoPickRow` (`sharedUi/.../ui/sheets/`) | put the first statement back |
| Delegate | `app/.../ui/screens/SettingsSheet.kt`: `AccountCard`, `SettingsGroup`, `RowDivider`, `SettingsRow`, `SettingsSubRow`, `Badge`, `Chevron`, `SliderRow`, `DestructiveRow`, `SegmentedControl` | `CosmoProfileCard`, `CosmoSettingsGroup`, `CosmoRowDivider`, `CosmoSettingsRow`, `CosmoSettingsSubRow`, `CosmoBadge`, `CosmoChevron`, `CosmoSliderRow`, `CosmoDestructiveRow`, `CosmoSettingsSegmented` (`sharedUi/.../ui/settings/`) | the same |
| Delegate | `components/.../Common.kt`: `SongRow`, `SignInBanner`, `MessageState`; `SearchField.kt`: `SearchField`; `Skeletons.kt`: `ShimmerBox` | `CosmoSongRow`, `CosmoSignInBanner`, `CosmoEmptyState`, `CosmoSearchField`, `CosmoShimmer` | the same |
| Delegate | `desktop/.../DesktopApp.kt`: `DesktopSidebar`, `DesktopArtistPage`, `DesktopCollectionPage`, `DesktopSongRow`, `DesktopLoadingPage`, `DesktopErrorPage`, `DesktopEmptyPage` | `CosmoDesktopSidebar`, `CosmoDesktopArtistPage`, `CosmoDesktopCollectionPage`, `CosmoDesktopSongRow` (`desktopApp/.../com/cosmotune/desktop/`), `CosmoLoadingState`, `CosmoEmptyState` | the same |
| Delegate | `desktop/.../DesktopApp.kt`: `SettingsNavigationRow`, `SettingsToggle`, `SettingsRow` | `CosmoSettingsRow` | the second statement, after the settings search's `if (!settingsRowVisible(title, subtitle)) return`, so the search still filters |
| Import swap | `Switch` (11 files), `CircularProgressIndicator` (17), `AlertDialog` (4), `ModalBottomSheet` (4), `DropdownMenu` and `DropdownMenuItem` (4): 29 files listed in the `FORK.md` row that starts with `` `Switch` (11 files) `` | `sharedUi/.../com/cosmotune/ui/bridge/` | re-point the import line |
| Import swap | `IconButton` in `app/.../MainActivity.kt` (the top bar's page actions) and `app/.../ui/components/DownloadManagerSheet.kt` (`TopBarDownloadButton`) | `com.cosmotune.ui.bridge.IconButton`: a glass circle inside `CosmoTopBar`'s actions, Material's button anywhere else | re-point the import line |
| Tabs | phone `MainActivity.kt` | three tabs, Home, Search (Explore lives inside it), Library: `selectedIndex = when (selectedTab) { TAB_HOME -> 0; TAB_SEARCH, TAB_EXPLORE -> 1; else -> 2 }`, `onTabSelected` maps 0, 1, 2 to `TAB_HOME`, `TAB_SEARCH`, `TAB_LIBRARY`. The upstream tab and mini player bars are the `else` of `if (cosmoShell != null) CosmoBottomChrome(...)`. The desktop's compact window does the same with `DesktopDestination` (`SEARCH, EXPLORE -> 1`); its sidebar lights Search for both | put the branch back in front of `if (glassActive) Column(` |
| Redirect | phone `MainActivity.kt`: `rememberSaveable { mutableIntStateOf(0) }` of the tab | the saved tab is `selectedTabState`; `var selectedTab by ShownTab(selectedTabState) { stored -> TabRedirect.shown(stored, newLook = cosmoShell != null, moodPageOpen = selectedMoodGenre != null, ...) }`: with the look on, Explore without a mood page is shown as Search; every read is the shown tab, every write sets the saved one | rename the saved state and re-add the `ShownTab` line under `selectedMoodGenre` |
| Branch | the other `cosmoShell` / `LocalCosmoShell` tests in upstream code | phone: `dismissPlayer`, the Now Playing overlay (`if (playerRaised && cosmoShell == null)`), the root's ambient ground, the top fade's `pageColor`, the bottom fade (off), the Settings entry `row("new look", ...) { CosmoLookSettingsRows() }`; `QueueActionNotice.kt` (the toast), `HeldContextMenu.kt` (`cosmoMenuSurface`), `SongActionsSheet.kt` (the sheet palette), `EqualizerScreen.kt` (one haptic). Desktop: the frame's top bar, bottom bar and overlay lambdas, the backdrop, Ctrl K, `DesktopFrame` (ground and page padding), `CosmoLookDesktopToggles` in Settings, the settings groups, the providers `LocalCosmoPlaying` and `LocalCosmoPlayPause`, the Explore loader of Home and Search; `DesktopPlayerSheet.kt` (the player is the shared overlay that grows out of the bar), `DesktopPlaylistUi.kt` and `DesktopSurfaces.kt` (dialogs as `CosmoDialogCard`), `DesktopSidePanel.kt` (the glass card, no rule beside the page, no queue header of its own), `DesktopSongMenuUi.kt` (`cosmoMenuSurface`), `DesktopLicenses.kt` (three credits) | each `FORK.md` row quotes the old line and the new one |

**When upstream changes a parameter.** A switch must keep the old screen's list: add the new parameter to `XSwitch` with the same name,
type and default at the same place (before the switch's own trailing parameters: `browse`, `entries`, `onPlayPause`), pass it by name to
the old call, then decide whether the new screen needs it. A delegate's call compiles without the new argument, so the contract test that
checks "every parameter passed" fails instead: add the parameter to the `Cosmo` function and pass it. An import swap fails to compile
when upstream uses a parameter or an overload the forwarder lacks: add it with Material's name and default and pass it by name in both
branches (`BridgeContractTest` compares the lists); a file that starts to import one of the swapped widgets gets the same one-line swap.
A merge conflict on a hook line: take upstream's line, then re-apply the hook from its `FORK.md` row. Run
`./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:compileKotlin :app:assembleDevDebug` after any merge that touches these files.

## Video mode

Video mode is the second mode of the profile drawer: its own screens, a Media3 player on Android and a libmpv player on the
desktop, reading a video site's public pages with no account. It sits in its own module and packages, and Music never depends
on it. Comments in the code cite sections of its design document ("spec 6.1"); that document is not in this repository.

- `video/`: the module `:video` (screens, player chrome, the two engines, the two services, its strings in
  `video/src/commonMain/composeResources/values/strings.xml`, English only).
- `shared/src/*/kotlin/com/cosmotune/video/`: the contracts, fakes, stores, web clients, selection logic, importers, feed and download
  engine. `jvmSharedMain/.../video/source/newpipe/` is the only place that imports `org.schabi.newpipe`
  (`NewPipeImportGuardTest` checks it).
- `app/src/main/java/com/cosmotune/video/host/PhoneModeHost.kt` and
  `desktopApp/src/main/kotlin/com/cosmotune/video/host/DesktopModeHost.kt`: the hosts that draw Video in place of Music's root.
- The mode is only reachable while `AppModes.available(AppMode.VIDEO)` is true, the new look is on and the stored mode is Video.
  It is true since the first Cosmotune release; the one-line change in `AppModes.available` takes Video out of the drawer again.
  With the look off, or with the mode on Music, every hook below is a no-op.

### Hooks into upstream files

These are the only places where a file that came from upstream, or a build file, knows about Video. Each has a row in `FORK.md`.
Find a site by its quoted text: line numbers drift.

| File | Anchor (quoted) | Change | How to re-apply |
| :-- | :-- | :-- | :-- |
| `settings.gradle.kts` | `include(":desktopApp")` | adds `include(":video")` after it | add the line |
| `app/build.gradle.kts` | the first `implementation(project(":sharedUi"))`, the one after `newPipeExtractorStripped` | adds `implementation(project(":video"))` after it | add the line |
| `desktopApp/build.gradle.kts` | `implementation(project(":sharedUi"))` | adds `implementation(project(":video"))` after it | add the line |
| `desktopApp/build.gradle.kts` | the packaged runtime's `modules(...)` list, after `jdk.dynalink` | adds `"jdk.httpserver"`: the desktop player reads the video through a loopback range proxy built on `com.sun.net.httpserver` | add the entry again if the list is rewritten |
| `shared/build.gradle.kts` | `jvmTest.dependencies {` | adds `implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")`, `implementation("org.xerial:sqlite-jdbc:3.47.1.0")` and `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")` (test classpath only; the main code keeps NewPipe `compileOnly`) | add the three lines; keep the NewPipe tag equal to the one in `app` and `desktopApp` |
| `shared/build.gradle.kts` | `commonMain.dependencies`, the first line | `api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")`: the Video contracts in common code expose `StateFlow` | add the line first |
| `shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/innertube/StreamResolver.kt` | `private val init by lazy { NewPipe.init(OkHttpDownloader()) }` | `private val init by lazy { com.cosmotune.video.source.newpipe.NewPipeHost.install(OkHttpDownloader()) }`: NewPipe has one downloader per process, and the router installed here sends Music's calls to Music's downloader and Video's to Video's | put the line back; `NewPipeRoutingTest` proves Music keeps its empty answer for `next` |
| `app/src/main/java/com/cosmotune/music/MainActivity.kt` | `BoxWithConstraints(Modifier.fillMaxSize()) {` followed by `CosmotuneApp(` | the call is wrapped in `com.cosmotune.video.host.PhoneModeHost(windowWidth = maxWidth, windowHeight = maxHeight) {` ... `}`: one added line before `CosmotuneApp(` and one `}` after its closing `)` | wrap the call again |
| `app/src/main/AndroidManifest.xml` | `android:name=".MainActivity"` | adds `android:supportsPictureInPicture="true"` after `android:launchMode="singleTask"` | add the attribute; the activity's `configChanges` already hold what picture-in-picture needs |
| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt` | `) { contentPadding ->` of the `DesktopFrame` call | first line inside: `if (com.cosmotune.video.host.desktopVideoShown()) return@DesktopFrame com.cosmotune.video.host.DesktopVideoPage(contentPadding)` | add the line first |
| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt` | `modifier = Modifier.onPreviewKeyEvent { event ->` | first line inside: `if (com.cosmotune.video.host.DesktopModeHost.active) return@onPreviewKeyEvent false` | add the line first |
| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt` | `com.cosmotune.desktop.LocalCosmoPlayPause provides com.cosmotune.desktop.rememberStableAction(::togglePlayPauseFromUser),` | two lines after it: `com.cosmotune.video.host.LocalDesktopPlaySong provides com.cosmotune.video.host.rememberStableSongAction { playSong(it) },` and `com.cosmotune.ui.modes.LocalModeLinks provides com.cosmotune.video.host.rememberDesktopModeLinks(),` ("Play as a song" and "Watch the music video") | add the two lines to the provider list |
| `shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/canvas/CanvasArtwork.kt` | `val headers: Map<String, String> = emptyMap(),` | adds `val videoId: String? = null,` after it: the music video's id, filled by our `OpenCanvasProvider` | add the field with its default |
| `desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopCanvas.kt`, `DesktopCanvasVideo.kt`, `DesktopCanvasSources.kt` | the `DesktopCanvasArtwork(` parameter list; `internal fun DesktopCanvasArtwork.toShared(): CanvasArtwork = CanvasArtwork(`; `DesktopOpenCanvasProvider` | the same `videoId: String? = null` parameter, `videoId = videoId,` in `toShared()`, and the provider passes it on | add the parameter and pass it through |
| `sharedUi/src/commonMain/composeResources/values*/strings.xml` (18 files) | `</resources>` | appends `<string name="cosmo_watch_music_video">Watch the music video</string>` before it | add the key to every locale file, English text in each (`CosmoStringsTest` fails when one lacks a `cosmo_*` key) |

The last four rows belong to the link with Music (Now Playing's "Watch the music video", Video's "Play as a song"); the rest are
Video's own wiring. Our own files that know about the stored mode and the drawer (`CosmoLookSettings.kt`, `CosmoProfileDrawer.kt`,
`CosmoBottomChrome.kt`, `CosmoPaletteState.kt`) carry no `FORK.md` row and do not mention Video's package.

### Removing Video mode

1. **Make it unreachable, in one line.** In `shared/src/commonMain/kotlin/com/cosmotune/design/AppModes.kt` make
   `AppModes.available` answer false for `AppMode.VIDEO` again (`mode == AppMode.MUSIC`). The drawer shows Video as "Later", the
   hosts draw Music, and a stored Video falls back to Music. This alone is enough for a release that has to drop the mode.
2. **To remove the code**, delete `video/`, `shared/src/*/kotlin/com/cosmotune/video/` (main and test source sets),
   `shared/src/jvmTest/resources/video/`, `app/src/*/java/com/cosmotune/video/` and `desktopApp/src/*/kotlin/com/cosmotune/video/`.
3. **Revert the rows above**: the `StreamResolver` line goes back to `private val init by lazy { NewPipe.init(OkHttpDownloader()) }`;
   take out `include(":video")`, the two `implementation(project(":video"))` lines, the three test dependencies and the coroutines
   line of `shared/build.gradle.kts`, `"jdk.httpserver"`, the manifest attribute, the `PhoneModeHost` wrapper in `MainActivity.kt`, the
   lines in `DesktopApp.kt`, `videoId` in `CanvasArtwork.kt` and the desktop canvas files, and `cosmo_watch_music_video` in the 18
   string files. Delete the Video rows of `FORK.md`.
4. **Our own files that still call Video**: `desktopApp/src/main/kotlin/com/cosmotune/desktop/CosmoDesktopChrome.kt`,
   `CosmoDesktopSidebar.kt` and `CosmoDesktopSidePanel.kt` each test `com.cosmotune.video.host.desktopVideoShown()` (or call
   `VideoDesktopBack`, `videoDesktopDrawerEntries()` and `VideoDesktopSidebar`): keep the Music branch. Two pins in
   `sharedUi/src/jvmTest` quote those lines: `DesktopChromeStructureTest.kt` and `DesktopShellContractTest.kt`. `LegalTextsTest` goes
   with `video/`.
5. **Find what is left**: `grep -rl "com.cosmotune.video" --include=*.kt --include=*.kts --include=*.xml .` must print nothing, then run
   `./gradlew :shared:jvmTest :sharedUi:jvmTest :desktopApp:compileKotlin :app:assembleDevDebug` and the guard. `LegalTextsTest` fails
   when a file outside the Video folders mentions the package and this section does not name it: add the name here when you add a hook.
6. **Words**: delete the three Video paragraphs of `README.md` (under "What it contacts", "Disclaimer" and "Acknowledgements"),
   the "Video mode" section of `NOTICE.md` and this section. The data folder Video kept on a device (`filesDir/video/` on Android,
   `~/.local/share/cosmotune/video/` on the desktop) is harmless and stays.

### Bumping NewPipeExtractor

NewPipe's state is one per process: Music's fallback resolver and Video share one copy, so there is one version for both
(today v0.26.3; v0.26.4's player-script parser failed on Music's WEB_REMIX formats, see the comment in `app/build.gradle.kts`).

1. **Change the tag in all three modules together.** `app/build.gradle.kts` (the `newPipeExtractorRaw` dependency, and the jar
   name `NewPipeExtractor-v0.26.3-noutils.jar` in the task `stripNewPipeExtractorUtils`), `desktopApp/build.gradle.kts`, and
   `shared/build.gradle.kts` (`compileOnly` for the main code and `implementation` for `jvmTest`). `VideoModuleStructureTest` pins
   the version in `shared/build.gradle.kts`: change the pin in the same commit.
2. **Re-derive `app`'s dependency list.** The app consumes a stripped jar, so what the extractor itself needs (nanojson, jsoup, jsr305,
   protobuf-javalite, rhino, rhino-engine) is listed by hand after `implementation(files(newPipeExtractorStripped))`. Run
   `./gradlew :app:dependencies --configuration prodReleaseRuntimeClasspath` with the extractor declared as an ordinary
   dependency for the moment (or read the new tag's published `.module` file) and update the versions.
3. **Compare the patched `Utils.java`**: `app/src/main/java/org/schabi/newpipe/extractor/utils/Utils.java` is Music's copy for
   Android below API 33; check it against the new tag's `Utils` and carry the patch over.
4. **Tests**: `:shared:jvmTest` (`NewPipeRoutingTest`, `NewPipeMapperTest` and `SearchReplayTest` build the library's own objects, so a
   changed signature fails to compile there), `:video:jvmTest`, `:desktopApp:compileKotlin` and `:app:assembleDevDebug`. Once, by
   hand, with the network: `NewPipeLiveTest` (`VIDEO_LIVE=true` in the environment).
5. **By hand**: play ten Music tracks that need the NewPipe fallback (one of them age-restricted), and three videos in Video mode (an
   ordinary one, a Short and a live stream); on the desktop too.
6. **Record the result** in the message of the commit that bumps the tag: old and new tag, the ten tracks and three videos, and
   anything that got worse. Re-record the search files only when the replay tests ask for it (see the recorded test files below).

### Bumping the libmpv runtime

The desktop's libmpv comes from `org.openani.mediamp:mediamp-mpv-runtime-<platform>` (`windows-x64`, `linux-x64`, `macos-arm64`,
`macos-x64`), one version for all of them, declared once in `video/build.gradle.kts`:
`implementation("org.openani.mediamp:mediamp-mpv-runtime-$mpvPlatform:0.5.1") { isTransitive = false }` (today 0.5.1).

- Change the number there. `MpvNatives.RUNTIME_VERSION` (`video/src/jvmMain/kotlin/com/cosmotune/video/player/desktop/MpvNatives.kt`) is
  the fallback name of the cache folder `<cache>/cosmotune/mpv/<version>/`, and `MpvNativesTest` fails when it differs from the
  build: change both.
- Read the new package's POM for its licences and update the libmpv paragraph of `NOTICE.md` when they change (libmpv is LGPL-2.1-or-later
  on Windows and macOS and GPL-3.0 on Linux today).
- `:video:jvmTest` extracts the libraries of the machine's own platform into a scratch folder and loads them (`MpvNativesTest`). By hand on the desktop: a
  video, a live stream, a video with a separate audio track, speed and subtitles. The Linux and macOS libraries have never been run:
  say so in the commit if they still have not been.
- The installer grows or shrinks with the package (22 to 30 MB compressed per platform).

### The recorded test files

Everything under `shared/src/jvmTest/resources/video/` has one of these origins. No network is used by a unit test: a recorded
answer is replayed, and anything recorded is scrubbed before it is committed.

- `shared/src/jvmTest/resources/video/newpipe/` (`search_all_*`, `search_videos_*`, `search_shorts_*`): real answers of the video
  site's search, recorded with `shared/src/jvmTest/kotlin/com/cosmotune/video/source/newpipe/fixtures/Recorder.kt` and replayed by
  `ReplayDownloader`. To record again (network needed, by hand): run `com.cosmotune.video.source.newpipe.fixtures.RecorderKt` with the
  `:shared` `jvmTest` runtime classpath, from the repository root; `--only=<scenario>` records one scenario, `--rescrub` makes no
  request and applies the scrubber and the slimmer again to the files already there. A search gives different pages from one request
  to the next, so a recording is the only stable test input.
- The scrubber (`FixtureScrubber`, in `Recorder.kt`) replaces the values of `ip=` (digits), `ei`, `sig`, `lsig`, `n` and `pot` in
  stream addresses, `VISITOR_INFO1_LIVE`, `visitorData` and `visitor_data` (also in the key and value form), and the country the
  site guessed from the connection, with fixed placeholders, and drops every `Set-Cookie` header, keeping only `Content-Type`. The
  recorder refuses to write a file that `FixtureScrubber.problems` still flags, and `FixturesAreScrubbedTest` checks every committed
  file again. `FixtureSlimmer` also drops what the extractor never reads (menus, feedback forms, tracking tokens); the replayed answers are
  equal in content, not byte for byte. Never commit a recording that did not go through the recorder. The files hold the titles,
  channel names and counts of public videos: look at them before the repository goes public.
- `shared/src/jvmTest/resources/video/feed/`: public RSS feed pages saved on 2026-10-16. Every description is replaced by
  `(description removed)` (they carried business addresses and affiliate links); nothing else is changed (no visitor, cookie or
  signature value was in them). One file is a 404 page, for the retry tests.
- `shared/src/jvmTest/resources/video/segments/`: written by hand in the shape of the live answers of SponsorBlock, DeArrow and the
  dislike service (field names, number forms, nulls); a real answer is tens of kilobytes about other people's videos.
- `shared/src/jvmTest/resources/video/select/`: made-up stream lists for the quality ladder and the selector, with addresses on
  `media.cosmotune.invalid`.
- `shared/src/jvmTest/resources/video/importers/`: written for Cosmotune from the formats' descriptions; its README lists them. The
  ZIP archives and the NewPipe database are built by the tests. `ThirdPartyFixturesTest` also runs the importers on NewPipe's and
  NewPipeExtractor's own test files (GPL-3.0) when they are on disk: copy them into the folder, or set `VIDEO_IMPORT_FIXTURES` to
  the folders that hold them; without them its tests are skipped. They are not part of this repository (`NOTICE.md` covers them if
  they are added).

## Icons

The logo (a heavy C with a small moon-textured, waning o) is drawn in a design kit that lives in the OpenCanvas
author's own design folder; it is local only and not in this repository. Every icon file below is a copy of a file
from that kit, so no script makes them: `tools/make-icons.py`, which drew the old violet placeholder, is gone. To
change the logo, update the kit and copy the files over, keeping the file names:

- Android, `app/src/main/res/drawable/`: `ic_launcher_background.xml`, `ic_launcher_foreground.xml`,
  `ic_launcher_monochrome.xml`, `ic_notification_logo.xml` and `ic_logo.xml`; `app/src/main/ic_launcher-playstore.png`
  (512 px). `mipmap-anydpi/ic_launcher.xml` and `ic_launcher_round.xml` point their `<monochrome>` layer at
  `ic_launcher_monochrome`, never at the foreground (the system would tint the multi-tone foreground into one blob).
- Android 12+ splash: `values-v31/themes.xml` and `values-night-v31/themes.xml` (black; the system shows the launcher icon).
- Shared UI: `sharedUi/src/commonMain/composeResources/drawable/ic_logo.xml`, the same file as the Android `ic_logo.xml`.
- Desktop: `desktopApp/packaging/icons/AppIcon.png`, `AppIcon.ico` and `AppIcon.icns` (the installers and the Dock);
  `desktopApp/src/main/composeResources/drawable/logo.svg` (window and tray: it declares `width="1024"` and uses only
  `rect` and `path`, because the tray draws it with Skia at 22, 32 and 48 px) and `logo_mark.svg` (the header mark).

The rules:

- **Black tile.** Every icon has a black (#000000) background: launcher, Play Store, installers, Dock, window, tray and
  splash. The mark on it is apricot #FFB98F.
- **In the app** the mark stands alone on a transparent background: apricot in the dark theme, ink #0B0C0F in the light
  theme. `ic_logo.xml` is a one-colour mark that every call site tints, so keep its size (28 x 18 dp, viewport 730 x 484).
  `CosmoBrand` (`shared/src/commonMain/kotlin/com/cosmotune/CosmoBrand.kt`) holds the two colours and `HomeTitle` in
  `HomeScreen.kt` picks one from the theme's background; the Replay cards and posters draw the mark white on their own dark cards.
- The kit also has `android/alt/ic_launcher_foreground-flat.xml`, a launcher foreground with a flat o (no craters).
  It is not used; swap it in for `ic_launcher_foreground.xml` if the two craters look like specks on a real phone.
- `AppIcon.ico` and `AppIcon.icns` were built from the kit's PNGs with a Pillow script, `source/build_kit_v6.py` in the kit.
  There is no copy of that script here. Pillow can reopen both files to check them.

## Tests

`docs/BASELINE.md` lists the tests that already failed before our changes, so a new failure is easy to spot.

The opt-in desktop network playback test in `video/src/jvmTest` also declares NewPipeExtractor in
`video/build.gradle.kts`; keep that test dependency in step with the three application declarations when bumping it.
The synthetic blue 1920 x 1080 clip in `desktopApp/src/test/resources/canvas/full-hd.mp4` was generated locally
with FFmpeg's `color` source (one second, H.264) for the decoder clarity regression test.
