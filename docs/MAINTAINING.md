# Working on the code

## Keeping in step with upstream

Cosmotune began as a fork of BitChord and still takes fixes from it by hand, on request, not on a schedule.

- `UPSTREAM.md` names the last upstream commit we have taken.
- `FORK.md` lists every edit we made to a file that came from upstream, with how to merge it. Read the row
  for a file before you change it or merge a change to it.
- A fresh clone has no upstream history. Add it once with `git remote add upstream <the original repository's address, named
  in `NOTICE.md`>` and `git fetch upstream`; the digest script tells you the same when the remote is missing.
- `tools/upstream-digest.sh` prints what changed upstream since that commit, grouped (playback fixes,
  security, features, files in the ledger that changed). It needs a git remote called `upstream` that points at
  the original repository.

New Cosmotune-only code goes under `com.cosmotune`; the Kotlin packages of upstream files keep their names so
that merges stay small. `ForkGuardTest` (run with `./gradlew :shared:jvmTest --tests "com.cosmotune.ForkGuard*"`)
checks these rules.

## The OpenCanvas engine

- The 28 files under `shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/opencanvas/` are copied from the
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
`app/src/main/java/com/music/bitchord`, `desktop/...` is `desktopApp/src/main/kotlin/com/music/bitchord/desktop` and `components/...`
is `sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/components`.

| Hook | Upstream site (found by its text) | Ours | How to re-apply after a merge |
| :-- | :-- | :-- | :-- |
| Theme | `app/.../MainActivity.kt`: the `BitChordTheme(darkTheme = darkTheme)` call | `com.cosmotune.ui.theme.CosmoLookTheme(dark = darkTheme, legacy = { BitChordTheme(darkTheme = darkTheme, content = it) })` | wrap upstream's call again as `legacy`; its block stays the content |
| Theme | `desktop/.../DesktopApp.kt`: `MaterialTheme(colorScheme = desktopColorScheme(), typography = desktopTypography())` in `BitChordDesktopApp` | `CosmoLookTheme(dark = true, legacy = { MaterialTheme(..., content = it) })` (the desktop is dark only) | the same |
| Theme | first lines inside it: `MainActivity.kt` `BitChordApp` and the desktop's theme content | `val cosmoShell = com.cosmotune.ui.theme.LocalCosmoShell.current`, then `cosmoShell?.FollowCover(song, nextSong)` | keep both lines first; without `FollowCover` the colours stop following the song |
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
