# Implementation plan: Cosmotune "One" design system

This plan maps `DESIGN_SYSTEM.md` onto the code in `/home/user/cosmotune`. All paths are repository-relative.
`UI = sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui`, `DESIGN = shared/src/commonMain/kotlin/com/cosmotune/design`.

## 0. Ground rules

* **One theme path.** The new kit is built **inside `CosmoLookTheme`**, the existing composition root. The "new look" switch (`settings.newLook`, `LocalCosmoShell` null-branches, `TopBarSwitch` / `ProfileButtonSwitch` / `SurfaceSheetSwitch` / `HomeSwitch` / `LibrarySwitch` / `NowPlayingSwitch`) stays only until the last phase, then the legacy branches and `app/.../ui/theme/Theme.kt` (SF Pro) are deleted. No third theme is introduced.
* **Pure metrics stay pure.** Every number in the spec gets a constant in `DESIGN` (commonMain, no Compose), so it can be unit-tested and shared with TV/Wear/web. Compose wrappers live in `UI/theme` and `UI/kit`.
* **Mode modules never draw chrome.** Video, Manga, Read and Podio provide `ModeSpec` (tabs, pages, search scope, sidebar "Yours" list). The shell draws everything else.
* **Feature-flag each phase.** A `DesignGeneration` setting (`V3` current / `ONE`) applies per mode while a phase is in flight. It is removed at the end of the plan, along with the new-look switch.

## 1. Shared kit: what becomes what

### 1.1 Tokens (`UI/theme`, `DESIGN`)

| Spec | Existing file | Action |
|---|---|---|
| Neutral roles §2.1 + accent §2.2 + status §2.4 | `UI/theme/CosmoTokens.kt`, `ColorSchemeBridge.kt`, `MaterialBridge.kt` | Add `DESIGN/ColorRoles.kt` (hex constants for Dark/AMOLED/Light, with the contrast table as a test). Add `UI/theme/OneColors.kt` (`@Immutable data class OneColors` + `LocalOneColors`). `MaterialBridge` maps `OneColors` onto `ColorScheme`, so leftover Material widgets turn neutral automatically. **Stop** feeding `CoverColors` into `toColorScheme`. |
| Content colour zones §2.3 | `CosmoPaletteState.kt`, `CoverPaletteLoader.kt`, `FollowCover.kt`, `PaletteFollower` in `CosmoLookTheme.kt`, `shared/.../theme/CoverPalette*.kt` | Keep the engine. Expose `rememberContentPalette(url)` used **only** by `NowPlaying*`, `pages/PageParts.kt` headers, the Podio station header and the Read/Manga detail header. Delete `video/ui/shell/VideoAccent.kt` and the app-wide `LocalContentColor` override. |
| Elevation / blur §2.5–2.6 | `components/Glass.kt`, `DESIGN/GlassPolicy.kt`, `components/Shadows.kt`, `components/TileSurface.kt`, `components/ControlFills.kt` | `GlassPolicy`: max 2 layers, opaque fallbacks, a new "Reduce transparency" flag. Collapse `ControlFills`/`TileSurface` into the tier fills. Delete `theme/Grain.kt` and `theme/AmbientBackground.kt` (Grain is referenced by `GrainTest`). |
| Type §3.1–3.2 | `theme/CosmoFonts.kt`, `theme/CosmoType.kt`, `DESIGN/TypeScale.kt` | Add `DESIGN/OneType.kt` (size/line/weight table per size class). Make `CosmoType` expose the 13 named styles and build them with Hanken Grotesk only. Remove the Archivo instances and `titlePath`. Keep `forText()` and `tabular()`. `TypeScale.sizeSp` stays for lyrics only, then retires. |
| Spacing / radii §3.3–3.4 | `CosmoSpacing`, `CosmoRadii` (`CosmoTokens.kt`), `DESIGN/MaterialShapeRadii.kt`, `components/SuperellipseShape.kt`, `DESIGN/Superellipse.kt` | Rewrite `CosmoRadii` to the 7 tokens. `CosmoMaterialShapes` maps from them. Delete the superellipse. Add a lint test that fails on new `RoundedCornerShape(<literal>.dp)` in `video/`, `manga/`, `podio/`. |
| Icons §3.5 | `components/CosmoIcons.kt`, `video/ui/VideoIcons.kt` | Move video glyphs into `CosmoIcons`, normalise to 24/1.75 stroke, outline+filled pairs. `CosmoIconsTest` / `VideoIconsTest` keep guarding the set. |
| Motion §3.6 | `CosmoMotion` (`CosmoTokens.kt`), `DESIGN/Springs.kt`, `theme/ReducedMotion.kt`, `DESIGN/Morph.kt`, `components/MorphBox.kt`, `PlayMorphButton.kt`, `CosmoLoadingBlob.kt`, `DESIGN/LoadingBlob.kt` | `CosmoMotion` gets the 6 durations + 3 easings. Keep `press`/`move` springs for drag-release. Delete Morph, LoadingBlob and the shape-morph path of PlayMorphButton (keep its icon cross-fade). |

### 1.2 Components (`UI/kit/`, a new package; existing blocks are folded in)

| Spec component | Built from (existing) | Notes |
|---|---|---|
| Top app bar §4.1 | `UI/chrome/CosmoTopBar.kt`, `video/ui/shell/VideoTopBar.kt` | One `OneTopBar(large/small, leading = ModePill/Back, actions, scrollBehavior)`. Delete `VideoTopBar`. |
| Mode switcher §4.2 | `UI/chrome/CosmoProfileDrawer.kt` (mode list ~l. 440–600), `app/.../ui/chrome/PhoneModeProfileButton`, `ModeNavigationRequest.kt` | New `UI/kit/ModeSwitcher.kt` (pill + sheet + desktop menu). Mode list and status lines come from `ModeSpec`. Remove the modes from the drawer. |
| Account sheet §5.4 | `CosmoProfileDrawer.kt`, `video/ui/shell/AccountSheet.kt` | Merge into `UI/kit/AccountSheet.kt`. |
| Search field / page §4.3, §5.9 | `UI/blocks/CosmoSearchField.kt`, `UI/search/*`, `video/ui/search/*` | One `OneSearchPage(scope: ModeSearchScope)`. Each mode supplies a `SearchProvider` (suggest, results by type). |
| Chips, pills, buttons, segmented §4.4, §4.11–4.12 | `components/CosmoChip.kt`, `CosmoButton.kt`, `CosmoIconButton.kt`, `CosmoSegmented.kt`, `CosmoGlassActionButton.kt`, `nowplaying/QualityPill.kt`, `video/ui/blocks/PillChip.kt`, `SubscribePill.kt`, `VideoChips.kt` | Consolidate to `OneChip`, `OnePill`, `OneButton(kind)`, `OneIconButton`, `OneSegmented`. `ControlMorph` sizing is replaced by fixed heights 32/40/48/56. |
| Section header §4.5 | `UI/blocks/CosmoHeaders.kt` | Keep the API (`CosmoSectionHeader`, `CosmoOverline`). Restyle it. `CosmoPageHeader` becomes the large-title region. |
| Media rows §4.6 | `UI/blocks/CosmoListRow.kt` (`CosmoRowFrame`), `CosmoSongRow.kt`, `video/ui/blocks/VideoRow.kt`, `PlaylistRow.kt`, Podio/Manga/Book ad-hoc rows | `CosmoRowFrame` becomes `OneMediaRow` with 6 variant factories. Delete `IconTile`. Keep `CosmoEqualiser` (static under reduced motion). |
| Cards/shelves/grids §4.7–4.8 | `UI/blocks/CosmoCards.kt` (`CosmoCard`, `CosmoShelf`), `CosmoTiles.kt`, `library/CosmoLibraryGrid.kt`, `DESIGN/CollectionColumns.kt`, `video/ui/blocks/VideoCard.kt`, `VideoShelf*.kt`, `VideoGrid.kt`, `ThumbnailBox.kt` | `OneCoverCard(aspect = Square/Circle/Wide/Portrait/Short)`, `OneShelf`, `OneGrid(minWidth)`. `CollectionColumns` gets the min-width rule. Delete `CosmoMixTile` fan + `Bento`. |
| List groups / shortcut grid §4.9 | `CosmoLikedTile`, `home/HomeBento.kt`, Prism-like tiles | `OneListGroup`, `OneShortcutGrid` (max 8). |
| Settings rows §4.10 | `UI/settings/CosmoSettingsBlocks.kt`, `SettingsCategories.kt`, `app/.../screens/SettingsSheet.kt` (2,770 lines), desktop `DesktopAppearanceSettings.kt` etc., `video/ui/settings/VideoSettingsPage.kt` | `OneSettingsRow(type)` + `OneSettingsGroup`. `SettingsCategory` enum maps to hub rows (§6.6). Keep the existing settings stores untouched. |
| Sheets/dialogs/menus/snackbar §4.13–4.14 | `UI/sheets/*` (`CosmoSheetSurface`, `CosmoSheetHeader`, `CosmoActionRow`, `CosmoDialogCard`, `CosmoMenuSurface`, `CosmoToast`), `video/ui/blocks/VideoActionsSheet.kt`, `video/ui/downloads/DownloadOptionsSheet.kt` | Attached sheet, 28 dp top radius. Change `ShellMetrics.SHEET_INSET_DP` → 0 and `SHEET_RADIUS_DP` → 28 (top only). `CosmoToast` becomes the snackbar. |
| States §4.15, banner §4.21 | `UI/states/CosmoEmptyState.kt`, `CosmoShimmer.kt`, `CosmoSignInBanner.kt` | Add `OneErrorState`, `OneOfflineBanner`, `OneBanner`. Skeleton geometry helpers per card/row variant. |
| Dock §4.16 | `UI/shell/CosmoBottomChrome.kt`, `UI/player/MiniPlayerPanel.kt`, `video/ui/shell/VideoTabs.kt`, `VideoMiniPlayer.kt`, `VideoCapsule.kt`, `DESIGN/ShellMetrics.kt`, `DESIGN/CapsuleGeometry.kt` | `OneDock(tabs, nowPlaying: NowPlayingSummary?)`. The mini player is fed by a **shell-level** `NowPlayingSummary` flow (music, Podio, video audio). Labels are always shown. `ShellMetrics.phoneChromeHeightDp` is updated (it stays the single source). `CapsuleGeometry`/player-morph overlay (`CosmoPlayerOverlay.kt`, `PlayerDismiss.kt`) keep the shared-element expand with new geometry. |
| Sidebar/rail/player bar §4.17 | `desktopApp/.../desktop/CosmoDesktopSidebar.kt`, `CosmoDesktopChrome.kt`, `CosmoDesktopSidePanel.kt`, `video/ui/desktop/VideoDesktopSidebarContent.kt`, `UI/shell/CosmoPlayerBar.kt`, `DESIGN/PlayerBarMetrics.kt`, `DesktopApp.kt` (`DesktopSidebar` ~l. 3583, `DesktopFrame`) | Move the sidebar into `UI/kit/OneSidebar.kt` (jvmShared, so the phone in landscape/tablet uses the same rail/sidebar). The player bar docks at 72 dp. `DESKTOP_BAR_MARGIN_DP` → 0. |
| Reader chrome §4.19 | `manga/ui/MangaReader.kt`, `reading/ui/BookReader.kt`, `ReadingDocumentView.kt` | `OneReaderChrome(top, bottom, audioChip)` shared by both readers. |

### 1.3 Shell & navigation

* **`ModeSpec`** (new, `UI/kit/ModeSpec.kt`) holds `mode`, `glyph`, `title`, `tabs: List<TabSpec>(4)`, `status: Flow<String?>`, `search: SearchProvider`, `sidebarYours: @Composable`, `settingsRoute`. Video, Manga, Read, Podio, Music and Library each provide one.
* **`OneAppScaffold`** (new) holds the top bar, content, dock/rail/sidebar by size class, snackbar host, the account sheet and the mode sheet. It replaces the phone's per-mode roots in `app/.../video/host/PhoneModeHost.kt` and the desktop's `DesktopFrame`.
* `AppMode` (`DESIGN/AppModes.kt`): **append** `READ` as the last entry. Never reorder: `shared/src/jvmSharedMain/kotlin/com/cosmotune/settings/CosmoLookSettings.kt` stores the mode as its **ordinal** (`KEY_MODE = "app_mode"`), so a reorder silently moves users into another mode. Migration: the stored `MANGA` stays Manga. The `ReadingApp` Books section becomes `READ`. `LIBRARY` becomes available in phase 7.
* Per-mode state: keep `rememberSaveableStateHolder` keyed by mode (already in `PhoneModeHost`), plus a per-tab key, giving each tab its own back stack. `ModeNav` flows (`ModeChrome.kt`) become the tab state of `ModeSpec` and are deleted.
* Back: implement §5.3 once in `OneAppScaffold` (Android `BackHandler`/predictive back; desktop mouse 4/5).

## 2. Rebuild order

| Phase | Scope | Why this order | Exit criteria |
|---|---|---|---|
| **P0 · Tokens** (1 sprint) | §1.1 rows. Remove the cover→ColorScheme bridge, Grain, Ambient, the superellipse and Archivo usage. | Gives the biggest visual delta for the least risk; every screen goes neutral at once. | All existing screens render with neutral roles. Contrast test green. APK −7.5 MiB after the SF Pro removal is scheduled (actual removal in P8). |
| **P1 · Shell** (2 sprints) | `OneAppScaffold`, `ModeSpec`, mode switcher, account sheet, unified dock, sidebar/rail, docked player bar, search page frame, back rules. | Fixes the UX complaints (hidden mode switch, inconsistent chrome) for all modes. | Every mode reachable via the mode pill and `Ctrl+1…6`. Mini player visible in all modes. 4 labelled tabs everywhere. |
| **P2 · Kit** (2 sprints) | §1.2 components plus a gallery screen (`UI/kit/KitGallery.kt`, debug-only) rendered by snapshot tests in dark/light/AMOLED at 100 % and 200 % font scale. | The kit has to exist before any page can be rebuilt. | Gallery snapshots approved. No component missing for P3–P7. |
| **P3 · Read + Manga (pilot)** (2 sprints) | Split Read/Manga. Rebuild `manga/ui/MangaApp.kt` (526 lines), `MangaReader.kt`, `reading/ui/*` (884 lines) per §6.4–6.5. | The smallest, least-tested and most "stock Material" code, so the kit gets proven where breakage is cheapest. | Existing `Manga*InteractionTest`/`Books*InteractionTest` (uiTests) pass with the testTags preserved. |
| **P4 · Settings** (2 sprints) | Hub + pages §6.6 for phone (`SettingsSheet.kt`), desktop (`Desktop*Settings*.kt`) and Video settings. A single `SettingsGraph` data model drives both hosts. | Settings is shared by every mode; doing it once removes 3 separate implementations. | `SettingsCategoriesInteractionTest`, `SettingsBlocksContractTest` rewritten; global settings search over the graph. |
| **P5 · Music** (3 sprints) | Home/Explore/Releases/Library/Search/Album/Artist/Now Playing (`UI/home`, `library`, `pages`, `search`, `nowplaying`, desktop `CosmoDesktop*Page.kt`). | The highest traffic and the most contract tests, so it goes on a stable kit. | Music contract tests replaced (see §3). Lyrics/queue panels unchanged functionally. |
| **P6 · Video** (3 sprints) | `video/ui/**` (26.8 k lines): home, watch (`WatchPage.kt`, `WatchMeta.kt`), shorts, subscriptions, channel, library, downloads. Remove the duplicate shell (`video/ui/shell/*`, except the playback lifecycle files). | It depends on the shell (P1) and the kit's 16:9 cards. | 63 video jvm tests updated; `VideoMiniPlayerInteractionTest` moves to the shell mini player. |
| **P7 · Podio + Library mode** (2 sprints) | `podio/ui/PodioApp.kt` (688), `PodioGlobe.kt`, `PodioRadioDetail.kt`, `app/.../podio/PhonePodioPage.kt`. New Library mode (Recent/Downloads/Devices/Collections) aggregating existing download queues. | It reuses rows/cards/player from P5. Library needs every mode's data adapters. | `PodioInteractionTest`, `PodioToolsInteractionTest` pass. Globe default vector, satellite optional. |
| **P8 · Cleanup** (1 sprint) | Delete the new-look switch and legacy branches, `app/.../ui/theme/Theme.kt` + SF Pro fonts (app, desktopApp, wearos), `DesignGeneration` flag, `ModeNav`, unused blocks. Re-run the `PACKAGE_SIZE` measurement. | Only safe once every mode runs on One. | No reference to `newLook`, `SuperellipseShape`, `Grain`, `WaveScrubber`, `Archivo` in UI code. |

TV (`tv/`) and Wear (`wearos/`) take only the colour/type tokens from `DESIGN` in P8. Their layouts are out of scope.

## 3. Risk areas

### 3.1 Contract tests that encode the old layout as source text

* `sharedUi/src/jvmTest` has **71** test files. **20** read source files with `readText()` and assert literal code or ordering.
  * `ShellStructureTest` asserts `CosmoBottomChrome`'s exact parameter list and order.
  * `PhoneChromeContractTest` (19 tests) asserts the literal string `CosmoTab(homeLabel, CosmoIcons.Home, …), CosmoTab(searchLabel, …), CosmoTab(libraryLabel, …)` inside `MainActivity.kt`, plus `TopBarSwitch`/`ProfileButtonSwitch` branch shapes.
  * The rest are `DesktopShellContractTest` (20), `DesktopChromeStructureTest`, `NowPlaying*ContractTest`, `VisualPolishContractTest` (14), `PolishContractTest` (24), `HomeContractTest` (15), `SettingsBlocksContractTest` (16), `ControlsStructureTest`, `MaterialBridgeTest` (11), `ColorSchemeBridgeTest`, `CosmoTypeTest` and `FontVariationTest` (Archivo widths).
* `video/src/jvmTest` has **63** files: `*StructureTest.kt` files grep `video/ui/**` (e.g. `ShellStructureTest`, `WatchStructureTest`, `WiringStructureTest`, `ModeHooksContractTest`).
* `shared/src/jvmTest/.../design` holds the metric tests for constants this plan changes or deletes: `ShellMetricsTest`, `CapsuleGeometryTest`, `TypeScaleTest` (11), `ControlMetricsTest` (10), `BentoTest` (13), `MixFanFitTest`, `WaveTest`, `SuperellipseTest`, `ShellClearanceTest`, `PlayerBarMetricsTest`, `NowPlayingMetricsTest`, `LandscapeMetricsTest`.

Mitigation:

1. Before P0, tag every source-text test with `@LegacyLayout` and record the list in this plan. Each phase **deletes or rewrites** the tests for what it replaced, in the same PR, and never weakens a test without that replacement.
2. Replace source greps with **behavioural** tests: Compose UI tests in `uiTests/` (42 existing interaction tests use real composition and testTags), plus **snapshot tests** of `KitGallery` and key pages (desktop Skia `ImageComposeScene` is already used, e.g. `BooksVisualReviewTest`).
3. Keep the **68 testTags** in Manga/Read/Podio code and the existing tag names (`manga:options`, `reading:sections`, `settings:category:*`…) or update them together with `uiTests`.
4. The pure metric tests in `shared/.../design` are good: update the numbers to the spec and keep them as the spec's executable form (add a `ColorRolesContrastTest`, `OneTypeTest`, `RadiiTest`, `DockMetricsTest`).

### 3.2 Two hosts, two shells

* The phone host `app/src/main/java/com/cosmotune/music/MainActivity.kt` (**5,755 lines**) and the desktop host `desktopApp/.../music/desktop/DesktopApp.kt` (**8,697 lines**) each own Music's chrome wiring, tabs and settings. `PhoneModeHost.kt` and `CosmoDesktopSidebar.kt` route the other modes differently. For example, desktop keeps its window frame while the Android Manga/Podio roots replace Music's composition (see `docs/UI_PROVIDER_PARITY_2026-11-02.md`).
* Mitigation: P1 moves the chrome into `OneAppScaffold` (jvmShared), and both hosts call it with platform callbacks (`PlayerPlatform`, file pickers, window caption). Do it in two PRs, desktop first (where hover/keyboard bugs show) and phone second. Do not refactor the 5.7 k/8.7 k-line files beyond extracting the chrome call site. A size-class preview test renders `OneAppScaffold` at 360, 600, 840 and 1280 dp.

### 3.3 Playback ownership for a cross-mode mini player

* Today Music's chrome draws only for the stored mode (`CosmoBottomChrome(mode=…)`). Video has its own mini player. Podio has a "live-station bar". Browser audio lanes are separate.
* Mitigation: add `NowPlayingSummary` (title, subtitle, art, progress, kind, sourceMode, controls) to the shell, fed by the existing Music `PlaybackPosition`, Video `VideoPlayback.state`, and Podio session. Lane rules (music stops Podio, video pauses music) stay in the existing engines; the shell only renders.

### 3.4 Palette engine regressions

`CosmoPaletteStateTest`, `ArtworkPaletteTest` and `CoverPalette` tests stay valid (the engine is kept). Risk: screens that relied on `LocalContentColor` = cover text colour become invisible on neutral grounds. Mitigation: in P0, run a screenshot pass of every page in dark and light, and grep for `Color(target.` / `palette.textPrimary` use outside content zones.

### 3.5 Other risks

* **Mode split (Read/Manga):** the persisted mode and `MusicLink`/`ModeNavigationRequest` (`require(returnMode == MANGA || PODIO)`) need `READ` added. Books/Manga library stores stay separate (no data migration).
* **Localisation:** 17 locale folders in `sharedUi/src/commonMain/composeResources/values-*`, and new strings (Explore, Releases, Episodes, Bookmarks, mode status lines, settings hub rows) are needed in all of them; `CosmoStringsTest`/`VideoStringsGuardTest` guard this.
* **Blur:** haze on Android < 12 and desktop software rendering must fall back (§2.6). Test on the API 26 emulator (minSdk) and a Linux VM.
* **Fonts:** removing Archivo breaks `FontVariationTest` and `FontCharacterMap`; rewrite them for Hanken. SF Pro is removed in P8 only after Hanken covers Latin/Vietnamese/Greek (the existing fallback already applies to other scripts).
* **TV/Wear:** they share `DESIGN` constants. Changing `ShellMetrics`/`ControlMetrics` must not change TV focus sizes, so give TV its own `TvMetrics` if needed.

## 4. Definition of done (per page)

1. Uses only `UI/kit` components and `OneColors`/`CosmoType`/`CosmoRadii`/`CosmoSpacing` tokens (lint: no `MaterialTheme.colorScheme.*` and no literal `dp` radii in mode code).
2. Has a skeleton, empty, error and offline state.
3. Passes 200 % font scale, the TalkBack/desktop screen-reader label check and keyboard focus traversal.
4. Has a snapshot in dark, light and AMOLED, with a reviewer sign-off against the blueprint in `DESIGN_SYSTEM.md` §6.
5. Its old source-text contract tests are removed or replaced by behavioural/snapshot tests in the same PR.
