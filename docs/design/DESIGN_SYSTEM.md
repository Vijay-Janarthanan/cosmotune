# Cosmotune Design System ("One")

Version 1.0 · 4 November 2026 · Target: Compose Multiplatform (Android phone/tablet, desktop JVM). TV/Wear inherit the tokens only.

This is one system for every mode: Music, Video, Manga, Read, Podio, Library and Settings. Every value below is a number, not a mood. If a screen needs something this file doesn't define, add it here first, then build it.

Evidence base (contact sheets in this folder, all looked at):
`current-cosmotune.jpg`, `ref-music.jpg`, `ref-video.jpg`, `ref-manga.jpg`, `ref-read.jpg`, `ref-podio.jpg`, `ref-settings.jpg`.

---

## 0. Diagnosis: why the current UI reads as "AI slop"

From `current-cosmotune.jpg` and the code:

1. **The whole app is tinted by the playing cover.** `CosmoLookTheme` turns the cover palette into the entire Material `ColorScheme` (`target.toColorScheme(...)`), and `VideoAccent` does the same with video thumbnails. As a result Video settings, Downloads, Library, Lyrics and Home are all the same wine/maroon. Nothing reads as neutral chrome, and everything looks like a generated mock-up.
2. **The display type is heavy condensed** (Archivo, weight 820, width 72 %, line-height 0.94: `CosmoType.display`), and the page title is a greeting ("Thursday afternoon") on both Music and Video. The references (Prism, Mornye, Librum, Kotatsu) all use a **light or regular large title that names the place** ("Library", "Settings", "Home").
3. **Too many containers and decorations.** There are glass capsules, superellipses, grain (`Grain.kt`), ambient radial light (`AmbientBackground.kt`), fanned cover stacks, a wavy scrubber, a glowing pink play blob, carded description and comments, and icon tiles in settings rows. Each one is individually "designed", and together they add noise.
4. **Every mode uses a different system.** Music and Video use Cosmo blocks. Manga, Read and Podio use stock Material 3 (`TextButton` walls, `FilledTonalButton("Music")`, `ModePageHeader` with a tagline, nested `ModeTabs`). Video also has its own shell copies (`VideoTopBar`, `VideoMiniPlayer`, `VideoCapsule`, `AccountSheet`). The phone even has two theme systems behind a "new look" switch (legacy `CosmotuneTheme` with SF Pro vs `CosmoLookTheme`).
5. **The UX is hidden.** Modes are switched from inside the profile drawer. Tabs show their label only when active. Manga/Read nest two tab rows (Books|Manga, then Library|Discover). Podio has a segmented control plus a side panel of tabs.

The fix is subtraction: **neutral chrome, one accent, one type family, fewer containers, one shell.**

---

## 1. Principles

| # | Principle | Rule you can test |
|---|---|---|
| P1 | **Content first** | Artwork, thumbnails, covers and pages are the only large colour areas. Chrome (bars, dock, lists, settings) uses neutral roles only. |
| P2 | **Neutral chrome** | No surface outside the content-colour zones (§2.3) may take a hue from content. A screenshot of Settings taken while any song plays must be pixel-identical to one taken with nothing playing. |
| P3 | **Colour from content + one accent** | There is one app accent (§2.2) and no per-mode accents. Content colour appears in exactly the zones listed in §2.3. |
| P4 | **Fewer containers** | Lists sit directly on the background with hairline dividers. Cards are used only for: media tiles, the dock, sheets, dialogs and inline banners. Never put a card inside a card. Settings rows are never individual cards. |
| P5 | **Light large titles** | Root pages have a 34 sp Light (300) title that names the place. No greetings as titles, no taglines under mode titles. |
| P6 | **One shell** | One top bar, one dock, one sidebar and one mode switcher, implemented once in `sharedUi`. Modes provide content and tab definitions, never chrome. |
| P7 | **Same place, same thing** | Search, account, mode switch and back sit in the same pixel position in every mode. |
| P8 | **Motion explains, never decorates** | Motion is allowed only for continuity (shared element, sheet, tab change). No idle animation, morphing, wobble or blobs. |

---

## 2. Colour

### 2.1 Neutral roles (hex, sRGB)

Contrast was computed with the WCAG 2.1 formula; values are listed against `bg / surface1 / surface2 / surface3`.

| Role | Dark | AMOLED | Light | Use |
|---|---|---|---|---|
| `bg` | `#0F0F11` | `#000000` | `#F7F7F8` | Window/page ground |
| `surface1` | `#17171A` | `#0E0E10` | `#FFFFFF` | Dock, tiles, list-card ground, fallback under blur |
| `surface2` | `#1F1F23` | `#18181B` | `#EFEFF1` | Sheets, menus, search field, unselected chip fill, skeleton base |
| `surface3` | `#29292E` | `#222226` | `#E4E4E7` | Pressed/selected row, tab indicator, dialog, skeleton shine |
| `hairline` | `#FFFFFF` @ 8 % | `#FFFFFF` @ 10 % | `#000000` @ 9 % | Dividers, field/chip outlines (1 dp; 0.5 dp ≥ xxhdpi allowed) |
| `outline` | `#FFFFFF` @ 16 % | `#FFFFFF` @ 18 % | `#000000` @ 16 % | Ghost button border, focus-adjacent edges |
| `textPrimary` | `#F2F2F3` (17.1 / 16.0 / 14.7 / 12.9) | `#F2F2F3` (18.8 / 17.2 / 15.8 / 14.2) | `#121214` (17.5 / 18.7 / 16.3 / 14.8) | Titles, body |
| `textSecondary` | `#B4B4BB` (≥ 6.8 on all) | `#B4B4BB` | `#55555D` (6.9 / 7.4 / 6.4 / 5.8) | Metadata, subtitles |
| `textTertiary` | `#93939B` (6.3 / 5.9 / 5.4 / 4.75) | `#93939B` | `#64646C` (5.5 / 5.9 / 5.1 / 4.6) | Timestamps, hints, disabled labels |
| `scrim` | `#000000` @ 56 % | `#000000` @ 64 % | `#000000` @ 40 % | Behind sheets/dialogs |
| `overlayOnMedia` | `#000000` @ 0→72 % gradient | same | same | Text over thumbnails/video (bottom 40 % of the image) |
| `inverseSurface` | `#F2F2F3` | `#F2F2F3` | `#1F1F23` | Selected filter chip, snackbar |
| `onInverse` | `#0F0F11` | `#000000` | `#F2F2F3` | Text on inverse |

The tertiary values above were raised from earlier drafts (`#7C7C85` / `#6E6E76`) because those failed 4.5:1 on `surface2`/`surface3`. With these values every text role passes AA (4.5:1) on every surface tier.

The **AMOLED** theme is the dark theme with `bg` = true black. The tiers are kept visible (`#0E0E10`, not `#000`) so that sheets still separate from the page. It is a user option (Settings > Appearance > Theme: System · Light · Dark · Black).

### 2.2 The one accent

| Role | Dark / AMOLED | Light | Contrast |
|---|---|---|---|
| `accent` | `#FFB98F` (brand apricot, the existing `CoverPalette` fallback seed) | `#A24B17` | dark 11.5 : 1 on bg · light 5.5 : 1 on bg, 4.66 on surface3 |
| `onAccent` | `#2A1607` | `#FFFFFF` | 10.4 : 1 · 5.9 : 1 |
| `accentContainer` | `#3A2418` | `#FFE3D3` | — |
| `onAccentContainer` | `#FFD9C2` | `#5A2508` | 11.0 : 1 · 10.1 : 1 |

**Where accent may appear** (this list is exhaustive): playback progress (mini player 2 dp line, seek bar active track, episode progress, reading progress), the primary filled button (max one per screen), switch/checkbox/radio "on", the "now playing" row indicator (equaliser glyph and title colour), unread/new badges and dots, links in body text, and the focus ring (2 dp `accent`, 2 dp offset).

**Accent never appears on:** tab bar selection (that is neutral, §4.16), filter chip selection (inverse neutral), headers, icons at rest, or backgrounds.

**Per-mode accent: none.** Modes are identified by their glyph and name in the mode switcher. This was a deliberate choice. Per-mode hues (red Video, teal Manga…) make the app look like five apps, which is the complaint we are fixing.

### 2.3 Content colour (the only places cover/thumbnail hues may appear)

The existing `CoverPalette` engine (`shared/.../theme/CoverPalette*.kt`) is kept but scoped to these zones:

| Zone | What is derived | Rule |
|---|---|---|
| Music Now Playing (full player) | background gradient, seek bar, play button fill | Top: `seed` at OKLCH L 0.30 / C ≤ 0.08 (dark) or L 0.92 / C ≤ 0.05 (light) → `bg` by 65 % of height. Play button = `CoverColors.accent` with `onAccent`; contrast ≥ 3:1 vs ground, else fall back to `accent`. |
| Detail headers (album, playlist, artist, podcast show, manga series, book, channel banner) | 320 dp top gradient behind the header only | `seed` at L 0.26 / C ≤ 0.06 (dark), L 0.94 / C ≤ 0.04 (light), fading to `bg` at 100 %. The list below is pure `bg`. |
| Podio station page | the same header gradient from the station favicon/photo | Same as detail headers. |
| Video watch page | nothing | Video gets no tint at all; the player is the colour (YTXTube pattern). Delete `VideoAccent`. |
| Mini player | nothing | Neutral `surface1`. Only the progress line uses `accent`. |

Cross-fade between palettes: 400 ms, `CubicBezier(0.2, 0, 0, 1)`; 0 ms under reduced motion (snap).

### 2.4 Status colours (fixed, never derived)

| Role | Dark | Light | Contrast on bg |
|---|---|---|---|
| `danger` | `#FF8A80` | `#C62828` | 8.4 / 5.25 |
| `success` | `#7BD88F` | `#1E7B34` | 11.0 / 5.0 |
| `warning` | `#F2C14E` | `#8A5A00` | 11.4 / 5.5 |
| `info` | `#8AB4FF` | `#2F5FBF` | 9.2 / 5.6 |

Status colours only appear in status pills (§4.11), inline banners (§4.21) and destructive buttons. Quality tiers use neutral pills with text, not colour (see §4.11).

### 2.5 Elevation (tonal, not shadowed)

| Level | Fill | Shadow (light theme only) | Used by |
|---|---|---|---|
| 0 | `bg` | none | pages, lists |
| 1 | `surface1` | none | dock, tiles, desktop player bar, sidebar (desktop sidebar = `bg` + right hairline) |
| 2 | `surface2` | `0 4 16 #000 @ 8 %` | bottom sheets, menus, search field |
| 3 | `surface3` | `0 8 24 #000 @ 12 %` | dialogs, snackbars use `inverseSurface` |

Dark themes have **no drop shadows**; separation comes from the tier step plus hairline.

### 2.6 Blur / glass

* Exactly **two** surfaces may blur: the **dock** (§4.16) and the **top app bar when content is scrolled under it**. Nothing else is glass: no sheets, no cards, no chips, no player bar.
* Recipe: blur radius 24 dp, tint = `surface1` @ 78 % (dark/AMOLED), `bg` @ 82 % (light), plus a 1 dp top `hairline` for the dock and a bottom one for the bar.
* Fallbacks are opaque: `surface1` (dock) and `bg` (top bar), used when any of these is true: Android API < 31; the desktop renderer is software/remote; "Reduce transparency" (new accessibility toggle) is on; Battery Saver is on; the haze backend reports unsupported. `GlassPolicy.kt` already chooses; set its limit to 2 layers.

---

## 3. Typography, spacing, shape, icons, motion

### 3.1 Font family

**One family: Hanken Grotesk (variable, OFL, already bundled at `sharedUi/src/commonMain/composeResources/font/hanken_grotesk_variable.ttf`).**

* Remove **Archivo** from UI use. Its condensed display width is the main "generated" signal; keep the file only if the brand wordmark needs it.
* Remove **SF Pro Display** (`app/src/main/res/font/sf_pro_display_*.otf`, the desktop and the wear copies). Apple's licence limits SF Pro to Apple platforms, so it is not redistributable in an Android/desktop build, and it costs about 7.5 MiB in the APK (`docs/PACKAGE_SIZE.md`).
* Non-Latin scripts fall back to the system (Noto) as today. Keep `CosmoType.forText()` (line height 1.5 for Indic scripts).
* Reader typefaces (Read mode only): Publisher default · Serif (platform serif: Noto Serif on Android, the system serif on desktop) · Sans (Hanken Grotesk) · Dyslexia-friendly (system, if present). These are not UI fonts.

### 3.2 Type scale (fixed sp; it changes only at window size classes, not fluidly per dp)

The fluid `TypeScale.sizeSp` is retired for UI text, because fluid scaling across widths was part of the inconsistency.

| Token | Compact (< 600 dp) | Medium / Expanded (≥ 600 dp) | Weight | Tracking | Use |
|---|---|---|---|---|---|
| `displayLarge` | 34 / 40 | 40 / 46 | 300 | −0.5 % | Large top-bar title (root pages) |
| `headline` | 26 / 32 | 30 / 36 | 400 | −0.3 % | Detail title (album, series, show, book, channel) |
| `titleLarge` | 20 / 26 | 22 / 28 | 500 | 0 | Section header, sheet title, collapsed top-bar title (500) |
| `titleMedium` | 16 / 22 | 16 / 22 | 600 | 0 | Card title, dialog title |
| `rowTitle` | 15 / 20 | 15 / 20 | 500 | 0 | Media-row title, settings row title |
| `body` | 14 / 20 | 15 / 22 | 400 | 0 | Body, descriptions |
| `meta` | 13 / 18 | 13 / 18 | 400 | +0.1 % | Row subtitles, counts (tabular `tnum` for numbers/times) |
| `label` | 14 / 20 | 14 / 20 | 600 | +0.5 % | Buttons, chips, segmented |
| `tabLabel` | 12 / 16 | 12 / 16 | 600 | +0.5 % | Dock/rail labels |
| `overline` | 11 / 16 | 11 / 16 | 600 | +8 %, UPPERCASE | Section overline, settings group header |
| `caption` | 12 / 16 | 12 / 16 | 400 | +0.2 % | Pills, footnotes, timestamps on thumbnails |
| `lyric` | 28 / 36 | 34 / 44 | 600 | −0.3 % | Lyrics active line; inactive 0.40 alpha, same size |
| `playerTitle` | 22 / 28 | 26 / 32 | 600 | −0.2 % | Now Playing title |

Text never goes below 11 sp. Every size multiplies by the user font scale. At 200 % scale, rows grow in height instead of truncating to one character (`maxLines` 2 for titles).

### 3.3 Spacing (4 dp grid)

`space` = 0, 2, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64.

| Token | Value |
|---|---|
| Page side margin | compact 16 · medium 24 · expanded 32 |
| Grid gutter / shelf item gap | 12 (compact), 16 (≥ 600) |
| Section gap (between shelves) | 32 |
| Section header to content | 12 |
| Row vertical padding | 8 (56 dp row) / 12 (72 dp row) |
| Row icon/cover → text | 16 |
| Bottom content padding | dock height + 16 (computed from `ShellMetrics`) |

### 3.4 Corner radii (one table; the `RoundedCornerShape(n.dp)` literals in mode code are banned)

| Token | dp | Applies to |
|---|---|---|
| `r0` | 0 | full-bleed video, reader pages, dividers |
| `rXs` | 4 | progress tracks, tiny thumbs (≤ 40 dp), pills' inner badges |
| `rSm` | 8 | covers 40–64 dp in rows, 2:3 portrait covers (all sizes), 16:9 row thumbs (≤ 160 dp wide) |
| `rMd` | 12 | square covers > 64 dp, 16:9 cards, tiles, inline banners, menus |
| `rLg` | 20 | dock container, desktop player bar (docked: 0, see §4.16), search field = pill |
| `rXl` | 28 | bottom-sheet top corners, dialogs |
| `rFull` | 50 % | buttons, chips, segmented, avatars, icon buttons, pills, mode pill |

The superellipse (`SuperellipseShape`) is retired. Everything uses `RoundedCornerShape`.

### 3.5 Icons

* One set: the existing `CosmoIcons` normalised to a **24 dp grid, 1.75 dp stroke, rounded caps**. Outline = rest; Filled = selected tab or active state only. Where a glyph is missing, use Material Symbols Rounded (weight 400, grade 0, optical size 24); do not mix sets on one screen.
* Sizes: 24 (default, top bar, dock, rows), 20 (in buttons and chips; leading icon gap 8), 18 (inside pills), 32 (player prev/next), 36 (player play glyph inside a 72 dp circle), 16 (inline meta glyphs such as the explicit/download marks).
* Touch target ≥ 48 × 48 dp always (an icon button = 48 dp box, 40 dp visual circle on press).
* Settings rows: icons are `textSecondary` monochrome. No coloured icon tiles.

### 3.6 Motion

| Token | Duration | Easing | Use |
|---|---|---|---|
| `instant` | 0 | — | reduced-motion replacement for movement |
| `fast` | 100 ms | standard | press state, ripple |
| `short` | 150 ms | standard | fades, chip select, icon swap |
| `medium` | 220 ms | emphasized-decelerate (enter) / accelerate (exit) | tab change (fade-through), mode switch, snackbar |
| `long` | 300 ms | emphasized | sheet open, top-bar collapse settle, page push (shared-axis X 30 dp) |
| `xl` | 400 ms | emphasized | mini player ↔ Now Playing shared-element expand; palette cross-fade |

Easing curves: `standard = CubicBezier(0.2, 0, 0, 1)`, `emphasizedDecelerate = (0.05, 0.7, 0.1, 1)`, `emphasizedAccelerate = (0.3, 0, 0.8, 0.15)`. Springs are allowed only for drag-release (sheet/player): `stiffness 400, damping 0.85` (close to `Springs.MOVE`).

The rest of the motion vocabulary:

* **Page push:** shared axis X, 30 dp travel, 300 ms. Pop is the reverse.
* **Tab switch:** fade-through, 90 ms out and 130 ms in, no movement.
* **Mode switch:** fade-through plus scale 0.96 → 1.00, 220 ms. This is the same for every mode and on every platform.
* **Reduced motion** (system animator 0 / OS "reduce motion" / in-app toggle): all movement becomes a 120 ms cross-fade, palette changes snap, lyrics scroll without smooth-follow, and the shimmer becomes static `surface2`.
* **Banned motion:** shape morphing (`MorphBox`, `PlayMorphButton` morph), the loading blob (`CosmoLoadingBlob`), font-axis animation (`titlePath`), wave scrubber oscillation, and parallax on lists.

---

## 4. Component kit

All components live in `sharedUi/.../ui/kit/` (see IMPLEMENTATION_PLAN). States for every interactive component:

| State | Visual |
|---|---|
| rest | — |
| hover (desktop) | +4 % `textPrimary` overlay |
| pressed | +10 % `textPrimary` overlay, no scale |
| focused (keyboard / D-pad) | 2 dp `accent` ring, 2 dp outside |
| selected | per component |
| disabled | 38 % content alpha, no ripple |
| loading | inline 16 dp spinner replacing the leading icon, width locked |

### 4.1 Top app bar (large → small)

```
Root page, expanded (112 dp + status inset)           Collapsed (64 dp)
┌──────────────────────────────────────────────┐      ┌──────────────────────────────────────────────┐
│ [◉ Music ▾]                      [🔍] [(A)]  │ 64   │ [◉ ▾]  Library                   [🔍] [(A)]  │ 64
│                                              │      └──────────────────────────────────────────────┘
│ Library                                      │ 48     title fades in at 60 % collapse; large title
└──────────────────────────────────────────────┘        scrolls away under the bar (Prism / M3 large)
```

* Height: 64 dp row plus a 48 dp title region (`displayLarge`, baseline 36 dp from the region top), with 16/24/32 dp side margin.
* Leading slot: on root pages, the **mode pill** (§4.2). On pushed pages, a back icon button (48 dp).
* Trailing slot, in fixed order: page action(s) (max 1, e.g. Sort/Filter or Add), then **Search** (`🔍`), then the **Account avatar** (32 dp circle in a 48 dp target). Search and Account are present on every root page of every mode.
* Collapse: driven by `exitUntilCollapsed` scroll behaviour. The background is transparent until content scrolls under it, then glass (§2.6) with a bottom hairline.
* Detail pages (album, series, show…) use the small bar (64 dp) over a header. The title appears in the bar only after the header title scrolls off.
* Desktop: there is no large title region in the window chrome. The page shows its `displayLarge` title as the first item of the scroll, and search lives in the sidebar.

### 4.2 Mode switcher

The mode switcher is one control, in the same place, with the same animation in every mode.

* **Phone/tablet:** the mode pill sits top-left on every root page. It is 40 dp high (48 dp target) with `rFull`, `surface2` fill, mode glyph 20 dp, mode name `label`, chevron 18 dp. Collapsed bar: glyph + chevron only.
* Tap opens the **mode sheet**: a bottom sheet (§4.13) with a 3-column grid of modes. Each tile is 96 dp high with a glyph (28 dp), name (`rowTitle`), and one-line status (`meta`): "Playing · Golden Hour", "3 new chapters", "Resume p. 112". The current mode shows a `surface3` fill and a check. Order is fixed: **Music · Video · Podio · Read · Manga · Library**. Disabled/unavailable modes are hidden rather than greyed. A "Manage modes" row at the bottom links to Settings > Modes (show/hide, order).
* **Desktop:** the first row of the sidebar is a mode menu button (56 dp high, glyph + "Music" + chevron) that opens the same list as a menu. Shortcuts: `Ctrl/Cmd+1…6` switch modes, and `Ctrl/Cmd+K` focuses search.
* **Tablet with rail:** the mode pill sits at the top of the rail as glyph only (56 dp).
* Switching keeps each mode's back stack and tab (saveable state per mode, which `PhoneModeHost` already holds via `SaveableStateHolder`). Animation: §3.6 mode switch.
* Gestures: none (no edge swipe between modes, which conflicts with back and with pagers).

### 4.3 Search field

* 48 dp high, `rFull`, `surface2` fill, no border. Leading search icon 20 dp in `textSecondary`, placeholder in `textTertiary` ("Search music", "Search videos", "Search manga"…). Trailing: clear (×) when there is text, a voice/mic icon if available, and a scope chip on the global search page.
* Focused: 1 dp `outline` border, cursor in `accent`. Desktop: 40 dp high, shows the `Ctrl K` hint in `caption` at the trailing edge.

### 4.4 Filter chips

* 32 dp high (48 dp target via padding), `rFull`, horizontal padding 14 dp, `label`.
* **Unselected:** `surface2` fill, `textPrimary` label, no border.
* **Selected:** `inverseSurface` fill and `onInverse` label, with a leading check 18 dp only when multi-select.
* The row scrolls horizontally with 8 dp gaps and 16 dp start padding, and is never wrapped (`FlowRow` wrapping is banned in chip rows; settings categories become a list, §4.10).
* Dropdown chip: trailing chevron 18 dp, opens a menu.

### 4.5 Section header

```
OVERLINE (optional, 11/16 600 +8% textSecondary)
Section title (titleLarge 20/26 500)                 See all ›   ← label, textSecondary, 48 dp target
```

* 16 dp side margin, 32 dp above (section gap), 12 dp below.
* "See all" only appears when the section is truncated. The whole header row is tappable when "See all" exists.

### 4.6 Media rows: one anatomy, six variants

The base row is 64 dp (one-line meta) or 72 dp (two-line meta / progress), with 16 dp side padding.

```
┌──┬────────┬─────────────────────────────────────────────┬─────────┐
│16│ LEAD   │ Title (rowTitle, 1–2 lines)                 │ TRAIL   │16
│  │ 48/56  │ Meta (meta, textSecondary, 1 line)          │ 48 tgt  │
│  │        │ [progress 2dp accent | pills]  (optional)   │         │
└──┴────────┴─────────────────────────────────────────────┴─────────┘
```

| Variant | Lead | Meta line | Trailing | Height |
|---|---|---|---|---|
| Song | 48 dp square cover `rSm` (or track # in album context, 32 dp wide, `meta` tabular) | Artist · Album · 3:42 (explicit "E" glyph 16 dp before the artist) | ⋮ overflow; playing: title `accent` + 16 dp equaliser glyph replacing cover overlay | 64 |
| Video | 16:9 thumb 128 × 72 `rSm`, duration pill bottom-right inside the thumb | Channel · 1.2M views · 2 h ago | ⋮ | 88 (72 + 16 padding) |
| Episode | 56 dp square `rSm` | Show · 12 Oct · 42 min (or "31 min left") | Play/Resume 40 dp circular ghost button, then ⋮; resume shows 2 dp accent progress under meta | 72 |
| Chapter | none (16 dp unread dot `accent` or nothing) | Ch. 81 · 21 Jun 2026 · Scanlator | Download state icon 24 (outline, spinner, check-filled) | 56; read chapters use `textTertiary` title |
| Book | 2:3 cover 40 × 60 `rSm` | Author · EPUB · 37 % | ⋮ ; 2 dp accent progress under meta | 76 |
| Station | 48 dp square logo `rSm` on `surface2` (logos are often transparent) | City, Country · genre · 128 kbps | Favourite toggle 24 (accent when on) | 64 |

Rules shared by all variants:

* Lists sit on `bg`, divided by a 1 dp `hairline` inset to start at the text column (16 + lead + 16). There are no row cards.
* Long-press enters selection (§5.8). Tap plays or opens. Overflow opens the item's actions sheet.
* Swipe (phone only, opt-in per list): swipe right = queue next / mark read, swipe left = remove/archive. Each reveals a 72 dp action with an icon on `surface3`. It is never the only path to an action.

### 4.7 Cover cards and shelves

| Card | Image | Text block | Width on compact | Shelf |
|---|---|---|---|---|
| Square (album, playlist, mix, podcast, station) | 1:1 `rMd` | title `rowTitle` 2 lines + meta 1 line, 8 dp below image | 148 dp (2.3 visible) | horizontal LazyRow, 16 start, 12 gap, snap to start |
| Artist / channel | 1:1 circle | name centred 1 line | 112 dp | same |
| 16:9 (video, music video) | 16:9 `rMd` | 36 dp channel avatar left + title 2 lines + meta (YouTube anatomy) for feeds; title 2 + meta 1 in shelves | full width − 32 in feeds; 280 dp in shelves | feed = vertical list; shelf = LazyRow |
| 2:3 portrait (manga, book) | 2:3 `rSm` | title 2 lines `rowTitle` (13/18 on grids ≥ 4 columns) + meta 1 | 112 dp in shelves | LazyRow |
| Short (vertical video) | 9:16 `rMd` | title overlay bottom, 2 lines, on `overlayOnMedia` | 132 dp | LazyRow |

On-image badges, max 2 per card:

* Top-left: progress or "NEW n" pill.
* Top-right: downloaded/check glyph on a 24 dp `#000 @ 56 %` circle.
* Bottom-right: duration or episode count.

There are no heart overlays or percentage blobs (the Tsuki/Kotatsu % circles become a 3 dp progress bar along the bottom edge of the cover, in `accent`).

### 4.8 Grid tiles

* Columns are adaptive by min width: 2:3 covers min 104 dp (compact gives 3 columns, which matches Mihon); square min 148 dp (2 columns); 16:9 min 280 dp.
* Gutter 12/16 and side margin 16/24/32. Title below the image by default; the "Compact" option overlays the title on the cover (Mihon "compact grid"). The library grid offers List · Comfortable grid · Compact grid as a segmented control in the Library sort sheet.

### 4.9 List groups (hairline lists)

Used for library categories ("Liked", "Downloads", "History"…), detail metadata and settings.

* Each row is 56 dp with a leading 24 dp icon (`textSecondary`), title `rowTitle`, optional value in `meta` `textSecondary` at the trailing edge, then a chevron 20 dp.
* The hairline divider is inset 56 dp. A group ends with a full-width gap of 24 dp rather than a divider.
* **Shortcut grid (Prism/Lokal-mobile pattern)** for Home and Library top: 2 columns × n rows of 56 dp tiles, `surface1`, `rMd`, a 56 dp cover/icon at the leading edge flush to the tile, label `rowTitle`. A maximum of 8 tiles. This is the only card grid allowed on Home besides shelves.

### 4.10 Settings rows

| Type | Height | Anatomy |
|---|---|---|
| Navigation | 56 (one line) / 72 (summary) | icon 24 `textSecondary` · title `rowTitle` · summary `meta` (shows current value: "Auto", "Lossless · Crossfade") · chevron |
| Toggle | 56 / 72 | icon · title · summary · switch (52 × 32 track, `accent` on). The whole row toggles. |
| Choice (opens sheet) | 56 / 72 | icon · title · current value `meta` · no chevron (opens a radio sheet) |
| Inline segmented | 72 + 48 | title, then a full-width segmented control (§4.12) below, 12 dp gap |
| Slider | 72 + 40 | title + value (tabular) right · slider full width |
| Destructive | 56 | title in `danger`, no icon tint change, confirmation dialog required |

Group header: `overline`, 32 dp top / 8 dp bottom, 16 dp side. Groups are separated by 24 dp.

**Owner decision (amends this section and §3.5's last bullet):** settings keep the Prism-style layout the owner praised: one card
per section (`surface1`, `rMd`) holding its rows, with hairline dividers between them, and small coloured squircle icon tiles on
category rows (the settings branch already built this; `KitGroup`, `KitDivider`, `KitSettingsRow` with `KitTileTint`). Rows
inside a card keep the anatomy and heights of the table above.

### 4.11 Pills (quality / status / metadata)

* 20 dp high, `rFull`, horizontal padding 8, `caption` 600, uppercase not used.
* **Quality pills** are neutral: a `surface2` fill with `textSecondary` text. Examples: "FLAC 24/96", "Hi-Res", "320 kbps", "1080p", "HDR", "EPUB", "PDF". The highest quality tier may use a 1 dp `outline` border plus `textPrimary`. Colour is never used for quality (the Lokal coloured LOW/HIGH/SUSPECT chips are not adopted outside the dedicated Audio Quality tool).
* **Status pills** use the status colour at 16 % fill with the status colour as text: "Ongoing" (info), "Completed" (success), "Failed" (danger), "Verification needed" (warning), "Live" (danger with a 6 dp dot).
* There is a maximum of 3 pills in a row on a detail header and 1 in a list row.

### 4.12 Buttons and segmented control

| Kind | Height | Fill | Label | Use |
|---|---|---|---|---|
| Filled (primary) | 48 (40 dense) | `accent` | `onAccent`, `label` | One per screen: Play, Continue reading, Subscribe (when not subscribed), Download |
| Tonal | 48 / 40 | `surface2` | `textPrimary` | Secondary: Shuffle, Add to library, Follow |
| Ghost | 48 / 40 | none, 1 dp `outline` | `textPrimary` | Tertiary: Share, Sort |
| Text | 40 | none | `textPrimary` (link context: `accent`) | Dialog actions, "See all" |
| Icon | 48 target / 40 visual | none (tonal variant `surface2`) | 24 icon | Toolbars |
| Play FAB-like (detail headers) | 56 circle | `accent` | play glyph 28 | Album/playlist/series/show header, right-aligned |

* Shape `rFull`. Padding horizontal 20 (48 dp) / 16 (40 dp). Leading icon 20 + 8 gap.
* **Action rows** (watch page, detail pages) are one horizontally scrolling row of 40 dp tonal pill buttons with 8 dp gaps. They never wrap onto a second line.
* **Segmented control:** 40 dp high, `rFull` track `surface2`, selected segment `surface3` with `textPrimary`, unselected `textSecondary`. It has 2–4 segments; at 5 or more, use chips. Used for: Library view (List/Grid), reader mode (Paged/Scroll/Webtoon), settings choices with ≤ 4 options.

### 4.13 Bottom sheets

* Attached to the bottom edge (not detached/inset), top corners `rXl` 28 dp, `surface2`, drag handle 32 × 4 dp `outline` 12 dp from the top.
* Max height 92 % of the window. Content padding 16. The title (`titleLarge`) sits 8 dp below the handle.
* Kinds:
  * **Action sheet:** media header row (cover + title + meta, 72 dp) + hairline + 56 dp action rows (icon + label).
  * **Options sheet:** settings rows.
  * **Pick sheet:** radio rows plus a primary button at the bottom.
* Expanded/tablet (≥ 600 dp): sheets become a centred 560 dp-wide sheet. Desktop: action sheets become context menus (§4.14) and option sheets become dialogs.

### 4.14 Menus, dialogs, snackbars

* **Menu:** `surface2`, `rMd`, min width 200 / max 320 dp, 48 dp rows (40 on desktop), and `outline` 1 dp in light theme only.
* **Dialog:** `surface3`, `rXl`, width 312–560 dp, 24 dp padding, title `titleMedium`, body `body`, actions as Text buttons right-aligned, with the destructive action in `danger`. Use one for confirmations of destructive/irreversible actions only.
* **Snackbar:** `inverseSurface`, `rMd`, 48 dp min, sits 8 dp above the dock, max 1 action (`label`; colour `#A24B17` on the light `inverseSurface` of the dark theme, `#FFB98F` on the dark `inverseSurface` of the light theme). Duration 4 s, or 10 s with an action. Undo replaces confirmation dialogs for reversible deletes.

### 4.15 Empty, loading, error, offline

| State | Anatomy |
|---|---|
| **Loading (skeleton)** | The same geometry as the real content: `surface2` blocks (covers `rMd`, text bars 12 dp high `rXs` at 60 %/40 % widths). Shimmer: a `surface3` band moving left→right over 1200 ms linear, paused under reduced motion. Never a centred spinner for page loads. Spinners (24 dp, 2.5 dp stroke, `textSecondary`) are only used inside buttons and infinite-scroll footers. |
| **Empty** | Centred column, max width 320: glyph 48 dp `textTertiary` (no illustration), `titleMedium` headline, `body` `textSecondary` one sentence, one Tonal button (e.g. "Browse sources", "Import books"). |
| **Error** | Same layout, glyph "warning", headline that states what failed ("Couldn't load MangaDex"), body with the reason, and buttons: Retry (Tonal) + secondary Text action ("Source settings"). Inline/partial errors use a banner (§4.21) above the content, never replacing the cached content. |
| **Offline** | A persistent 36 dp banner under the top bar: `surface2`, cloud-off glyph 18, "Offline · showing downloads" `meta`. Network-only items render at 38 % with "Not downloaded" in meta, and downloaded items stay at full strength. |
| **Verification needed** (sources) | Warning banner with "Open in browser" Tonal. This matches the existing human-verification flow. |

### 4.16 Unified bottom dock (phone & compact desktop)

```
┌───────────────────────────────────────────────────────────┐  ← container: surface1 glass, rLg 20, inset 8 dp L/R,
│ ▣  Golden Hour                              ⏯    ⏭      │ 64   8 dp above nav-bar inset (16 if gesture bar)
│    Mira and the Coast                                     │
│━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━│ 2 dp progress, accent, flush to the mini-player bottom
│   ⌂        ◫         ◉         ▤                         │ 64
│  Home    Explore   Releases   Library                     │
└───────────────────────────────────────────────────────────┘
```

* **One** container holds the mini player (when something is loaded) and the tab bar. This replaces the two floating capsules (`CosmoBottomChrome` capsule + tab pill) with a single blurred layer.
* **Mini player** (64 dp): cover 44 dp `rSm` at 10 dp inset; title `rowTitle` and subtitle `meta`; trailing play/pause and next (48 dp each). Its progress line is 2 dp `accent` on a `hairline` track along the bottom edge.
  * Tap expands to the full player (shared element on the cover, `xl`). Swipe up expands, swipe down on the mini player dismisses with a "Stop playback" undo snackbar, and horizontal swipe skips track (opt-in, off by default).
  * The mini player shows **whatever is playing in any mode** (song, episode, station, background video audio), with the source mode's glyph (16 dp) before the subtitle when it differs from the current mode.
* **Tabs** (64 dp): exactly 4, equal width, icon 24 + `tabLabel` **always visible**. Selected: filled icon, a 56 × 32 dp `surface3` pill behind the icon, and the label in `textPrimary`. Unselected: outline icon, label `textSecondary`. Re-tapping the selected tab scrolls to top, and a second re-tap pops to the tab root.
* Hidden: on Now Playing, the Video watch page (full player), the Shorts pager, the manga/book reader and the Podio full-screen globe; the mini player then lives in the reader toolbar (§6.4).
* Height reserved for content: `16 + 64 (+ 64 with mini player) + 8 + navInset`. Keep `ShellMetrics.phoneChromeHeightDp()` as the single source.

### 4.17 Desktop sidebar, tablet rail, desktop player bar

**Window size classes:** compact < 600 dp · medium 600–839 · expanded ≥ 840 · large ≥ 1200.

```
Expanded/desktop                                           Medium (tablet portrait)
┌──────────┬─────────────────────────────────┬──────────┐  ┌────┬─────────────────────────┐
│◉ Music ▾ │  Library            [sort][(A)] │ Queue    │  │ ◉  │ Library        🔍 (A)   │
│[🔍 Search]│                                 │ Lyrics   │  │ ⌂  │                         │
│ ⌂ Home   │  content (max width 1280,       │ Details  │  │ ◫  │  content                │
│ ◫ Explore│  centred above 1440)            │ (360 dp, │  │ ◉  │                         │
│ ◉ Releases│                                │ optional)│  │ ▤  │                         │
│ ▤ Library│                                 │          │  │    │                         │
│ ──────── │                                 │          │  │ ⚙  │                         │
│ PLAYLISTS│                                 │          │  ├────┴─────────────────────────┤
│  …       │                                 │          │  │ mini player bar 64 (rLg dock)│
│ ⚙ Settings│                                │          │  └──────────────────────────────┘
├──────────┴─────────────────────────────────┴──────────┤
│ ▣ Title · Artist   ⤨ ⏮ ⏯ ⏭ ↻   0:41 ━━━━━━── 3:34   🔊 ≡ ⧉ │ 72 dp docked player bar, surface1, top hairline
└────────────────────────────────────────────────────────┘
```

* **Sidebar**: 248 dp wide (collapsible to the 80 dp rail with `Ctrl+B`), `bg` with a right `hairline`.
  * Row 1 is the mode menu (§4.2), then the search field (40 dp), then the mode's 4 tabs as 44 dp nav rows (icon 20 + `rowTitle`; selected = `surface3` fill, `rMd`).
  * Then a hairline, then the mode's "Yours" list under an `overline` (Music: playlists; Video: subscriptions; Manga/Read: collections; Podio: followed shows). Avatars/covers are 28 dp.
  * The footer has Settings and the account (avatar + name).
* **Rail** (medium): 80 dp, mode glyph pill on top, 4 tabs (icon 24 + `tabLabel`, 56 × 32 pill indicator), Settings at the bottom.
* **Desktop player bar:** **docked** (not floating), full window width under sidebar and content, 72 dp high, `surface1`, top `hairline`.
  * Left 30 %: cover 48 `rSm`, title/artist. Centre 40 %: transport + seek bar (4 dp track, `accent`). Right 30 %: volume, queue, lyrics, device, mini window.
  * The bar is present in every mode whenever anything is loaded.
* **Right panel** (Lokal pattern): 360 dp resizable (320–480), tabs Queue · Lyrics · Details, toggled from the player bar.

### 4.18 Full player (Now Playing), shared by music and Podio audio

See §6.1. Components: cover (max 360 dp, `rMd`), `playerTitle`, artist (`body` `textSecondary`), like + overflow, seek bar (4 dp track; thumb 12 dp shown on touch only; times `meta` tabular), transport (shuffle 24, prev 32, **play 72 dp circle**, next 32, repeat 24), and a bottom tool row of 4 icon buttons (Lyrics, Up next, Device/Output, More).

### 4.19 Reader chrome (manga & books)

* Hidden by default. Tap the centre third to toggle.
* Top bar 64 dp: `surface1` @ 92 %, back, title + chapter (2 lines), bookmark and overflow.
* Bottom bar 104 dp: page slider (4 dp track, `accent`), "12 / 48" tabular, then a 56 dp row with prev chapter, chapters list, reader settings, next chapter.
* When audio is playing: a 40 dp mini chip (cover 28 + play/pause) sits at the right end of the bottom bar.

### 4.20 Globe (Podio)

* Full-bleed canvas on `bg` (dark) with land in `surface3`, ocean in `bg`, station dots 4 dp in `textSecondary` @ 60 %, the selected dot 10 dp `accent` with a 24 dp ring.
* There is no satellite imagery by default. The NASA GIBS tiles move to a "Satellite" map style toggle in the globe options.

### 4.21 Inline banner

`surface2`, `rMd`, 16 dp padding, leading status glyph 20 in the status colour, text `body`, up to 2 Text actions trailing or below. Used for: sign-in prompts, source errors, verification, "update available". At most one banner per screen.

---

## 5. UX rules (all modes)

### 5.1 Navigation model

```
App
 ├─ Mode (Music | Video | Podio | Read | Manga | Library)       ← mode switcher, state kept per mode
 │   ├─ Tab 1..4 (each its own back stack)                     ← dock / rail / sidebar
 │   │   └─ pushed pages (detail, see-all, search results)
 │   ├─ Search (full page, scoped to mode)                     ← top-bar 🔍
 │   └─ full-screen surfaces (player, watch, reader, shorts)   ← over everything, dock hidden
 ├─ Account sheet (avatar) → Settings hub, profiles, sign-in, downloads
 └─ Settings (one hub for all modes; a page per mode)
```

### 5.2 Tabs per mode: always 4, with fixed slot meaning

| Slot | Meaning | Music | Video | Podio | Read (books) | Manga | Library (cross-mode) |
|---|---|---|---|---|---|---|---|
| 1 | **Home**: what's next for you | Home | Home | Radio (globe + favourites) | Home (Continue reading, shelves) | Home (Continue reading + recent updates) | Recent |
| 2 | **Discover**: browse the catalogue | Explore (moods, charts, new) | Shorts | Podcasts (charts, genres) | Browse (catalogues, OPDS) | Browse (sources) | Downloads |
| 3 | **Following**: new from what you follow | Releases (followed artists) | Subscriptions | Episodes (new from followed shows, AntennaPod "Inbox") | Bookmarks (notes, highlights; the documented exception) | Updates (new chapters) | Devices & folders |
| 4 | **Library**: yours | Library | Library | Library (stations, shows, downloads) | Library | Library | Collections |

* **Search is never a tab.** It is the 🔍 in the top bar of every root page (same place), and the search field at the top of the desktop sidebar. Search opens a full page scoped to the current mode, with a scope chip row "This mode · All modes". Recent queries and type chips are mode-specific (Music: Songs/Albums/Artists/Playlists/Videos; Manga: per source).
* History lives inside Library (row "History") in every mode. Downloads lives in each mode's Library plus Library-mode slot 2.
* The default tab on entering a mode is the last used; on first run it is slot 1.
* Migration of today's tabs:
  * Music Home/Search/Library becomes Home/Explore/Releases/Library, with Search moving to the top bar.
  * Video Home/Shorts/Subscriptions/You keeps its slots, and "You" becomes "Library".
  * Read's nested Books|Manga split becomes two modes (Read, Manga). The persisted `AppMode.MANGA` keeps meaning Manga; a new `AppMode.READ` is appended.

### 5.3 Back behaviour

1. A sheet, menu or dialog that is open closes first.
2. Selection mode exits.
3. Search with text clears focus, then closes.
4. Full-screen surfaces (player, watch, reader) collapse. The watch page minimises to the mini player with playback continuing; the reader closes and saves its position.
5. Pop the current tab's stack.
6. If not on slot 1 of the mode: go to slot 1.
7. If not in the user's **home mode** (Settings > Modes > Start in; default Music): switch to the home mode.
8. Otherwise, exit (Android standard; desktop does nothing).

* Predictive back (Android 14+): pages preview the previous page with scale 0.92; sheets preview dismissing.
* Mouse buttons 4/5 and `Alt+←/→` map to back/forward on desktop.

### 5.4 Account & settings

* The avatar (top bar trailing, desktop sidebar footer) opens the **Account sheet**. The header row shows avatar, name and account email/"Local profile", plus switch profile. Then come rows: Connected services (YouTube, providers) · Downloads · Listening history · Settings · Help & about.
* One account sheet for all modes (merge `CosmoProfileDrawer` and `video/ui/shell/AccountSheet.kt`). The mode switcher is **not** in this sheet anymore.
* **Settings** is one hub (§6.6) reachable from the Account sheet, the desktop sidebar footer, and `Ctrl/Cmd+,`. A page's overflow "Mode settings" deep-links to that mode's settings page; back returns to the page it came from.

### 5.5 Playback across modes

* One playback session for the whole app; the mini player (phone) and player bar (desktop) are part of the shell and render in every mode.
* Reading or browsing manga/books never stops audio. Starting a video with sound pauses music (with "Resume music" in the snackbar). Starting music stops a Podio stream, and vice versa (existing lanes kept). Video in background continues as audio when "Background play" is on.
* Inside full-screen readers the dock is hidden, and audio controls are in the reader's bottom bar (§4.19). The system media notification remains.
* The mini player's tap opens the player of its own content type (music → Now Playing; episode/station → the same player layout with the podcast/radio variant; video → the watch page in the Video mode, switching mode).

### 5.6 Sheets vs pages

| Use a **sheet** when… | Use a **page** when… |
|---|---|
| it's an action on one item (add to playlist, download options, share, quality) | it has its own content to browse (album, artist, series, show, channel, settings page) |
| it's a short choice (≤ 7 options, sort, filter, sleep timer, speed) | it needs search or a long list (> 7 items, chapter list on phone = page section, not sheet) |
| it must keep the context visible (queue, lyrics on phone are pages within the player, not sheets) | it can be deep-linked or shared |

Desktop: sheets become menus/dialogs (§4.13), and pages stay pages.

### 5.7 Pull-to-refresh

Pull-to-refresh is available on feed/library roots: Music Home, Video Home/Subscriptions, Manga Updates/Library, Podio Episodes, Read Browse. The indicator is a 40 dp circle, `surface2`, spinner `textSecondary`. On desktop: `F5`/`Ctrl R` plus a refresh icon in the overflow. It is never on detail pages (use a "Refresh" overflow item instead).

### 5.8 Long-press & selection

* Long-press on any row, card or tile enters **selection mode** for that list.
  * The top bar becomes a contextual bar: × close, a count ("3 selected" `titleLarge`), then actions (max 3 icons + overflow) per content type: Add to playlist / Download / Delete; Mark read / Download / Remove; and so on.
  * Items show a 24 dp check circle top-left (cards) or replace the lead (rows). The selected fill is `surface3`.
* Tap toggles while in selection. Select-all is in the overflow. Back exits.
* Desktop: `Ctrl/Shift`-click multi-select, right-click = context menu (the same actions as the item's sheet).
* Long-press never triggers a preview or play.

### 5.9 Search behaviour

* Focus opens the keyboard immediately. Below the field, before typing: recent searches (chips row, max 8, each with ×) and mode browse content (Music: genres/moods grid; Manga: sources list; Podio: countries/languages).
* Typing shows suggestions after a 250 ms debounce (rows with a search glyph and an ↖ fill action). Enter shows results with type chips. Results keep their scroll when returning from a detail page.
* Scope: the default is the current mode. "All modes" shows a grouped page (Music, Videos, Podcasts, Books, Manga), with 3 rows each plus "See all".
* Empty results: "No results for 'x' in MangaDex" plus a "Search all sources" Tonal button.

### 5.10 Gestures

| Gesture | Where | Effect |
|---|---|---|
| Swipe up | mini player | open player |
| Swipe down | player, watch page | minimise |
| Horizontal swipe | player cover | prev/next track (with haptic) |
| Double-tap left/right third | video, also podcast cover | seek −/+10 s (configurable) |
| Vertical drag, left/right half | video full screen | brightness / volume (opt-in) |
| Pinch | video | zoom to fill |
| Pinch, double-tap | manga/PDF reader | zoom |
| Tap left/right third | paged reader | prev/next page (mirrored for RTL manga) |
| Edge swipe | — | reserved for system back; no app gesture within 24 dp of the edges |

Every gesture has a visible-control equivalent.

### 5.11 Accessibility

* Touch targets are ≥ 48 × 48 dp, and the spacing between adjacent targets is ≥ 8 dp or they merge into one target.
* Text contrast ≥ 4.5:1 and non-text (icons, focus, progress) ≥ 3:1 against adjacent colours. All tokens in §2 satisfy this. Content-colour zones run `Contrast.kt` checks at runtime and fall back to neutral if they fail.
* Font scale is supported up to 200 % with no clipped labels: tabs drop to icon + label on 2 lines, max 2, then fall back to icon-only with tooltip at > 175 %.
* Reduced motion (§3.6) and the new Reduce transparency toggle (§2.6).
* Screen reader:
  * Every icon button has a label. Rows are merged nodes ("Golden Hour, Mira and the Coast, 3 minutes 42, playing").
  * Progress is announced as a percentage, and custom actions are exposed for swipe actions.
  * Focus order: top bar → content → dock.
* D-pad/keyboard: every interactive element is focusable with the 2 dp accent ring (`FocusRing.kt`, keyboard modality only).

### 5.12 Tablet & desktop adaptations

| Width | Nav | Layout |
|---|---|---|
| < 600 | dock | single pane |
| 600–839 | rail 80 + bottom mini player bar | single pane, 24 margins, grids grow columns |
| 840–1199 | sidebar 248 (collapsible) + docked player bar 72 | list-detail on Library/Settings/Podio (list 360 + detail) |
| ≥ 1200 | sidebar + optional right panel 360 | content max width 1280 centred; Now Playing becomes a two-pane (art left, lyrics/queue right, Lokal/Prism wide) |

* Hover states, tooltips (500 ms delay), right-click menus and keyboard shortcuts are mandatory on desktop.
* Window controls: the existing `WindowCaptionArea` stays. The title bar is the top 40 dp of the sidebar/content (no separate app bar).

---

## 6. Screen blueprints

Each blueprint names the reference pattern it adopts. "→" marks tap targets.

### 6.1 Music

**Home** (adopts: Prism Home shortcut grid + "Made for you" shelves; Mornye "Continue listening")
```
[◉ Music ▾]                                   [🔍] [(A)]
Home                                                     ← displayLarge, not a greeting
┌────────────┐┌────────────┐                            ← shortcut grid 2×4, 56 dp tiles, surface1 rMd
│▣ Liked     ││▣ Downloads │
│▣ Mix 1     ││▣ Last album│ …
└────────────┘└────────────┘
Continue listening                              See all  ← square shelf 148 dp
[▢][▢][▢]
MADE FOR YOU
Your mixes                                      See all  ← square shelf (mix covers = 2×2 collage, no fan/tilt)
Quick picks                                              ← 4-row song list paged horizontally (YTM pattern), rows 64 dp
New from artists you follow                     See all
Moods → chips row (scroll), each opens Explore filtered   ← replaces the mood-chip header row
```

**Explore** (adopts: YT Music Explore structure, Prism search-landing genres)
* 2-col tiles "New releases · Charts · Moods & genres · Podcasts", then shelves: New albums, Trending, Charts list (rank # in a 32 dp column).

**Releases**: a vertical list grouped by week (`overline` "THIS WEEK"), with square 64 dp covers, "Album · Artist · 12 tracks", and a "Hide singles" chip.

**Library** (adopts: Prism Library, Mihon sort sheet)
```
[◉ Music ▾]                       [⇅] [🔍] [(A)]
Library
(Playlists)(Songs)(Albums)(Artists)(Local)(Videos)     ← chip row, single-select, inverse when selected
6 playlists                    Recent activity ▾  [▦] ← meta + sort dropdown chip + view toggle
▢ Liked songs        412 songs
▢ Downloads          88 songs
▢ History
──────────
[grid of square cards or rows]
```

**Search**: §5.9. Landing = recent chips + "Your artists" circle shelf + genre tiles (Prism search-landing).

**Album / playlist** (adopts: LastWave album header, Lokal album list, Prism mix)
```
[←]                                         [⋯]           ← small bar, transparent → glass
        ┌────────────┐                                     ← 220 dp cover rMd, centred (compact);
        │   cover    │                                       left-aligned 240 dp at ≥ 600
        └────────────┘
Afterglow Drive                                            ← headline
Neon Harbor · Album · 2023 · 8 songs, 32 min               ← meta
[FLAC 24/96]                                               ← quality pill if known
[⤨ Shuffle][+ Save][↓][⋯]                       (▶ 56)     ← tonal pills row + play circle accent
1  Afterglow Drive                         3:22  ⋮         ← song rows, track #
2  Chrome Horizon                          4:05  ⋮
Header gradient: seed (§2.3) behind the top 320 dp only.
```

**Artist** (adopts: LastWave artist hero, Lokal artist)
* Hero 16:9 or 4:3 artist image full-bleed (`r0`) fading to `bg` (bottom 40 %), name `headline` over the fade, then "1.2M monthly · Follow" and a Play circle.
* Sections: Popular (5 song rows + "See all"), Albums shelf, Singles shelf, Videos shelf (16:9), About (body, 3 lines + "More"), Similar artists (circle shelf).

**Now Playing** (adopts: Prism player, Mornye player, LastWave quality line)
```
[⌄]            PLAYING FROM ALBUM              [⋯]         ← overline centred, small source name
                Afterglow Drive
      ┌──────────────────────────────┐                     ← cover, width − 48, max 360, rMd
      │                              │                       canvas/video: letterboxed, never under controls
      └──────────────────────────────┘
Golden Hour                                     [♡]        ← playerTitle, like
Mira and the Coast                                          ← body textSecondary, → artist
●━━━━━━━━━━━━━━━━━━━──────────────────────                 ← 4 dp straight track (no wave)
1:03                 FLAC · 24-bit · 96 kHz          -2:30 ← tabular; format centre in caption
      ⤨        ⏮        ( ▶ 72 )        ⏭        ↻
 [Lyrics]        [Up next]        [Output]        [⋯]      ← icon buttons 48, labels in a11y only
Background: §2.3 cover gradient. Lyrics/Up next are pages inside the player (swipe up / tap); at ≥ 840 they are the right pane.
```

### 6.2 Video

**Home** (adopts: YTXTube home, YouTube feed anatomy)
```
[◉ Video ▾]                                   [🔍] [(A)]
Home
(All)(Music)(Gaming)(Live)(Podcasts)(Recently uploaded)   ← chips
Continue watching                               See all   ← 16:9 shelf 280 dp with 3 dp accent progress
┌───────────────────────────────────────────┐            ← feed card full width, 16:9 rMd
│                                    12:34  │
└───────────────────────────────────────────┘
(●) Building a tiny synth from scratch             ⋮     ← avatar 36, title 2 lines rowTitle
    Northern Signal · 1.2M views · 2 h ago               ← meta
Shorts                                          See all   ← 9:16 shelf inserted after feed item 3
```

**Watch** (adopts: YTXTube watch)
```
┌─────────────────────────────────────────────┐           ← player 16:9 full width, r0, black
│               video                          │             controls overlay: §4.12 icons on overlayOnMedia
└─────────────────────────────────────────────┘           ← 2 dp accent scrub line at the bottom edge when hidden
Building a tiny synth from scratch                         ← titleMedium 2 lines, tap → description sheet
1.2M views · 2 h ago  …more                                ← meta
(●) Northern Signal  1.2M                 [Subscribe]      ← 56 dp row, filled accent (tonal when subscribed)
[👍 48K | 👎][Save][Download][Share][Play as song][Clip]   ← ONE horizontally scrolling tonal row
Comments 25 · "Thanks for watching. The patch…"            ← 64 dp surface1 rMd teaser (the only card) → comments sheet
Up next                                                     ← video rows 88 dp
```
* The description and comments open as sheets (phone) or a right column (≥ 840: player + meta left 2/3, up next right 1/3).

**Shorts** (adopts: YTXTube Shorts)
* Full-screen vertical pager on `#000`, no dock. The top-left is ← "Shorts"; top-right has search and mute.
* The right rail of actions sits 16 dp from the edge, each a 48 dp icon with a `caption` count below: like, dislike, comments, share, ⋯.
* The bottom-left has channel avatar 32 + name + Subscribe (tonal small) and the title 2 lines. Scrim gradient on the bottom 35 %.

**Subscriptions**
* A channel avatar strip (56 dp circles + name `caption`, horizontal), chips "All · Today · Continue watching · Unwatched", then the same feed cards as Home.

**Channel** (adopts: YouTube channel; Cosmotune `ChannelPage`)
* Banner 16:5 full-bleed, avatar 72 overlapping by 24 dp, name `headline`, "@handle · 1.2M · 312 videos" meta, Subscribe (filled), a description line.
* Tabs (segmented-like scrolling chips): Home · Videos · Shorts · Live · Playlists. Content: grid of 16:9 (2 col ≥ 600) / list rows (compact).

### 6.3 Podio

**Radio (slot 1)** (adopts: Radio Garden-style globe idea, implemented with the existing `PodioGlobe` and Radio Browser data; AntennaPod list rows)
```
[◉ Podio ▾]                                   [🔍] [(A)]
Radio
┌─────────────────────────────────────────────┐           ← globe 1:1 (compact) / fills left pane ≥ 840
│        ·  ·    ◉ selected      ·            │             drag/zoom; tap dot → station rows below
│  ·        ·         ·                ·      │             [⛶] expand to full screen (dock hidden)
└─────────────────────────────────────────────┘
Lisbon, Portugal · 14 stations          [Nearby][Random]  ← meta + tonal small
▢ Antena 1          Lisbon · pop · 128 kbps          ☆    ← station rows
▢ RFM               …
Favourites                                     See all    ← square shelf
```

**Podcasts (slot 2)** (adopts: AntennaPod "Add podcast" discover, Prism podcasts)
* Chips (countries/genres), "Top in Portugal" ranked rows (#, 56 cover, show, publisher), and shelves by genre. Overflow: "Add by RSS address", "Import OPML".

**Station page** (adopts: AntennaPod player for audio, Podio's existing station detail)
* Header gradient (§2.3) with logo 120 `rMd` on `surface2`, name `headline`, "Lisbon · Portuguese · MP3 128" pills, and **▶ Listen** (filled).
* Then "Local time 14:32 · 19 °C Clear" in meta, a photo strip (Wikimedia credit `caption`), "Nearby" as a station rows list, and "About this place" body.

**Podcast show** (adopts: Prism podcast page, AntennaPod feed)
```
[←]                                          [⇪][⋯]
▢120  The Monday Music Club                                ← cover + headline (2 lines)
      Will Anderson · Music · 312 episodes                 ← meta → publisher
[▶ Latest episode] [Follow] [↓ Auto]                       ← filled + tonal + tonal
Monday Music Club is a podcast about… more                 ← body 3 lines
Episodes                    (All)(Unplayed)(Downloaded) ⇅
▢ De La Soul: A Conversation with…        ▶  ⋮             ← episode rows (§4.6)
  Monday Music Club · 1 h ago · 22 min
```

**Episode page / player**
* The episode page has a small bar, cover 160, title `headline`, show (→), date · duration, a "Play"/"Resume 31 min left" filled button + Download + Queue tonal, then show notes (body, links in `accent`, timestamps tappable).
* Player: the Now Playing layout with transport (−15 · ▶ · +30), speed (pill "1.5×") left of transport, and sleep timer right. The tool row is Chapters · Show notes · Output · ⋯. Live radio replaces the seek bar with a "● LIVE" status pill and a buffer-back indicator.

### 6.4 Manga

**Library (slot 4)** (adopts: Mihon Library grid, Kotatsu chips)
```
[◉ Manga ▾]                       [⫶] [🔍] [(A)]          ← filter/sort/display sheet
Library
(All)(Reading)(Plan to read)(Completed)(+)               ← category chips (user categories)
┌───┐┌───┐┌───┐                                         ← 2:3 grid, 3 cols compact, min 104
│ 3 ││   ││ ✓ │   badge: unread count top-left, downloaded top-right
└───┘└───┘└───┘   3 dp accent progress bottom edge
Title 2 lines
```

**Browse (slot 2)** (adopts: Mihon Browse, Kotatsu Explore)
* Top: search field "Search all sources". Chips: Popular · Latest · Random.
* Then "Sources" grouped list (`overline` per language): 48 dp source icon `rSm` · name · language pill · health dot (8 dp: success/warning/danger, from the provider health state) · pin. Plus "Manage sources" → Settings > Manga > Sources. Empty state: "No sources enabled" + Tonal "Browse source packages".

**Updates (slot 3)** (adopts: Mihon Updates)
* Rows grouped by day (`overline` TODAY / YESTERDAY): cover 40 2:3, series title, "Ch. 100 · Family Circus", trailing download state. Pull-to-refresh; "Library last updated 1 h ago" in meta under the title.

**Home (slot 1)** (adopts: Kotatsu History/Feed)
* "Continue reading" 2:3 shelf with progress, a "New chapters" short list (5 rows, See all → Updates), and "Recently added".

**Series details** (adopts: Kotatsu/Tsuki details layout, Mihon chapter list)
```
[←]                                   [⇪][↓][⋯]
┌──────┐ My Bias Gets on the Last Train                   ← cover 120×180 rSm + headline (3 lines)
│cover │ Author Name
│      │ [Ongoing] [MangaFire · EN]                        ← status pill + source pill
└──────┘
[Continue · Ch. 81]   [♡ In library ▾]                     ← filled + tonal (category picker)
A male office worker falls in love… more                    ← body 3 lines
(Romance)(Drama)(Office)                                    ← genre chips (tap = search)
85 chapters · 4 unread         (Unread)(Downloaded)  ⇅      ← meta + chips + sort
● Ch. 85  ·  21 Jun 2026                              ↓     ← chapter rows (§4.6)
  Ch. 84  ·  14 Jun 2026                              ✓
```
* There is no detached sheet with list/grid/bookmark icons (the Tsuki/Kotatsu bottom panel). Chapters are the page body.

**Reader** (adopts: Mihon reader chrome, Kotatsu read-mode sheet)
* Full-screen `#000` (or white per page theme), chrome hidden. Chrome as §4.19.
* Reader settings sheet: segmented Reading mode (Paged · Paged RTL · Vertical · Webtoon), "Remember for this series" toggle, Scale (Fit width/height/Original), background colour segmented (Black · Grey · White), Keep screen on, Crop borders, Tap zones overlay preview.

### 6.5 Read (books)

**Home (slot 1)** (adopts: Booklore/Kavita dashboard shelves, simplified for phone)
* "Continue reading": a large card (one only) with cover 2:3 120, title, author, "Chapter 7 · 37 %", a 3 dp progress bar, and a ▶ Resume filled button. Then a "Recently added" shelf and "Collections" shelf (square collage), plus a Discover shelf (Open Library trending, with provider + retrieval time in `caption`).

**Library (slot 4)** (adopts: Librum library grid, Mihon category chips)
* Chips: All · Reading · Unread · Finished · Downloaded · collections… Grid 2:3 (3 cols) or list (Book rows §4.6). Sort sheet: Recent, Title, Author, Progress, Added.
* Header action ⊕ "Import" (EPUB/PDF/CBZ).

**Browse (slot 2)** (adopts: Kavita OPDS browsing, Booklore discover shelves)
* Search field (Open Library), chips by catalogue (Open Library · Gutenberg · Standard Ebooks · Your OPDS…), shelves "Trending on Open Library" / "New on Standard Ebooks", and a catalogue list. Access-state pills: Free · Borrow · Preview only · Metadata only.

**Bookmarks (slot 3)**
* Grouped by book (`titleMedium` + cover 40). Rows: quote (`body` regular, 3 lines max) or a bookmark name, plus "Ch. 3 · p. 42 · 2 days ago". Tap jumps to the reader at the locator.

**Book details** (adopts: Booklore details, Kavita series detail)
```
[←]                                          [⋯]
┌──────┐ A Brief History of Time
│cover │ Stephen Hawking
│      │ [EPUB] [Downloaded] · 256 pages · 1988
└──────┘
[▶ Continue · 37 %]  [+ Collection]  [↓]
About this book … more
Contents                                         See all   ← first 5 TOC rows
Details: Publisher · ISBN · Language · Source               ← hairline list group (§4.9)
```

**Reader** (adopts: Librum reading view, Kavita book settings)
* Page on the user's reading theme: Light `#FFFFFF`/`#1A1A1A`, Sepia `#F4ECD8`/`#3B2F1E`, Dark `#121212`/`#E6E6E6`, Black `#000000`/`#CCCCCC`, plus System. These are reader-only colours, not app tokens.
* Chrome §4.19, with the bottom row being TOC · Search in book · Aa (typography sheet) · Bookmark.
* Typography sheet: typeface list (§3.1), size slider 12–32 sp, line spacing 1.2–2.0 segmented, margins segmented (S/M/L), justify toggle, Paged · Scroll segmented. PDF: zoom fit width/page, and single/double page on ≥ 840.

### 6.6 Settings

**Hub** (adopts: Prism Settings hub structure *minus* cards and coloured tiles; Librum settings sidebar on desktop)
```
[←]                                                        ← pushed page, from Account sheet
Settings                                                   ← displayLarge
[🔍 Search settings]                                        ← searches every row title+summary across all pages
┌ (A) Alex · Local profile                          › ┐    ← 72 dp, no card: avatar 40 row
GENERAL
 ◐  Appearance        Dark · Accent · Text size      ›
 ▶  Playback & sound  Lossless · Crossfade            ›
 ↓  Downloads & storage  1.4 GB used                  ›
 ⌗  Sources & providers  6 connected                  ›
 ◎  Accounts & sync                                   ›
MODES
 ♪  Music                                             ›
 ▷  Video                                             ›
 ◉  Podio                                             ›
 ▤  Read                                              ›
 ▦  Manga                                             ›
 ⚙  Modes             Start in Music · Order          ›
DEVICE
 ⌁  Output & devices   ·  ⧉ Remote & Listen Together  ·  ⌚ Wear OS  ·  ▭ TV
ABOUT
 ⓘ  About Cosmotune   1.0.13 · Up to date             ›
```
* The FilterChip category bar (`SettingsCategories.kt`) is replaced by this hub. `SettingsCategory` values map to hub rows.
* Desktop ≥ 840: a two-pane layout (hub list 320 dp left, page right). This is the Librum pattern.

**Settings page** (e.g. Video)
```
[←]  Video                                                 ← small bar (64) + displayLarge first item
PLAYBACK
 Quality on Wi-Fi               Auto                       ← choice row → radio sheet
 Quality on mobile data         Auto
 Preferred codec                                            ← inline segmented
 (Auto)(AV1)(VP9)(H.264)
 Default speed                  1×
 Double-tap to seek             10 s
 Autoplay next                                    [●  ]     ← toggle row
CAPTIONS
 …
```

### 6.7 Library mode (cross-mode)

* **Recent** shows a mixed timeline (rows of every variant with the source mode glyph).
* **Downloads** has chips All · Music · Video · Episodes · Manga · Books, a storage bar (4 dp segmented by type in neutral tints of `textSecondary` at 100/70/45/25 %; not colour), and active downloads on top with progress and pause/cancel icons. This adopts YTXTube Downloads and the Kotatsu download list without its cards.
* **Devices & folders** lists local folders, WebDAV/SMB, and paired devices.
* **Collections** holds cross-mode collections.

---

## 7. Remove list (current patterns that make Cosmotune look generic)

| # | Pattern | Where (screenshot / file) | Replace with |
|---|---|---|---|
| R1 | App-wide cover-tinted Material scheme; every screen maroon | all of `current-cosmotune.jpg`; `ui/theme/CosmoLookTheme.kt` (`target.toColorScheme`), `ui/theme/MaterialBridge.kt`, `video/ui/shell/VideoAccent.kt` | Neutral roles §2.1; content colour only in §2.3 zones |
| R2 | Heavy condensed display titles, font-axis title motion | "Thursday afternoon", "Golden Hour" headings; `CosmoType.display/title`, `CosmoFonts.titlePath` (Archivo 820/72) | Hanken Grotesk 300 `displayLarge` |
| R3 | Greeting as page title on Music & Video Home ("Listen now / Thursday afternoon", "Watch now") | `phone-music-home`, `phone-video-home`, `desktop-video-home`; `design/HomeGreeting.kt`, `ui/home/HomeHero.kt` | Title = "Home" |
| R4 | Mood chip row as the first element (Unwind/Focus/Lift/Drive) | `phone-music-home`; `ui/blocks/CosmoMood.kt` | Moods row lower on Home; Explore |
| R5 | Fanned/tilted cover stacks ("Quick picks", "Mixed for you") | `phone-music-home`; `ui/blocks/CosmoTiles.kt` (`CosmoMixTile`), `design/Bento.kt`, `HomeBento.kt`, `MixFanFit` | Square card with 2×2 collage |
| R6 | Big hero card with "Recents" tag and inline pause | `phone-music-home`; `HomeHero.kt` | Shortcut grid + Continue shelf |
| R7 | Wavy scrubber | `phone-now-playing-*`; `components/WaveScrubber.kt`, `design/Wave.kt` | Straight 4 dp seek bar |
| R8 | Glowing pink play blob & morphing controls | Now Playing screens; `PlayMorphButton.kt`, `MorphBox.kt`, `CosmoLoadingBlob.kt`, `design/Morph.kt` | 72 dp circle, no morph |
| R9 | Two floating glass capsules (mini player + icon-only tab pill; label only when active) | all phone shots; `shell/CosmoBottomChrome.kt` | Unified dock §4.16 with always-visible labels |
| R10 | Grain overlay & ambient radial light on every page | `theme/Grain.kt`, `theme/AmbientBackground.kt` | Flat `bg` |
| R11 | Superellipse corners and 12 different hard-coded radii (16, 12, 24, 14, 6, 20, 18, 7, 4, 22, 2, 10 dp) plus `CosmoRadii` tile 28 / card 24 / sheet 36 | `components/SuperellipseShape.kt`, `theme/CosmoTokens.kt`, mode code | Radius table §3.4 |
| R12 | Detached sheets inset 8 dp, radius 36 on all corners | `ShellMetrics.SHEET_INSET_DP/RADIUS`, `sheets/CosmoSheetSurface.kt` | Attached sheet, 28 top corners |
| R13 | Watch page actions wrapped into 3 rows of outlined pills; description and comments in bordered cards | `phone-video-watch*.webp`; `video/ui/watch/WatchMeta.kt` | One scrolling tonal row; description sheet; one comments teaser |
| R14 | Settings rows as individual cards with icon tiles; wrapping FilterChip categories | `phone-video-settings.webp`; `ui/settings/SettingsCategories.kt`, `CosmoSettingsBlocks.kt`, `video/ui/settings/VideoSettingsPage.kt` | Hairline list groups §4.10; hub §6.6 |
| R15 | Downloads page: tile-style chips + floating mini player covering list | `phone-video-downloads.webp` | Library-mode Downloads §6.7 |
| R16 | Stock Material walls in Manga/Read/Podio: `TextButton` rows (29 in `MangaApp.kt`, 32 in `PodioApp.kt`, 16 in `BookDiscover.kt`), `OutlinedTextField`, `FilledTonalButton("Music")` escape | `manga/ui/MangaApp.kt`, `reading/ui/*.kt`, `podio/ui/PodioApp.kt` | Kit components; mode switcher |
| R17 | Mode header with tagline ("Read: Books and manga, with a place for every story.", "Podio: Radio and podcasts"); nested tab rows (Books\|Manga → Library\|Discover); Podio segmented + PanelTab sidebar | `ui/blocks/ModeChrome.kt` (`ModePageHeader`, `ModeTabs`), `reading/ui/ReadingApp.kt`, `podio/ui/PodioApp.kt` | Large title + 4 dock tabs |
| R18 | Mode switch hidden in the profile drawer | `chrome/CosmoProfileDrawer.kt` (mode list at l. 440+), `PhoneModeProfileButton` | Mode pill §4.2 |
| R19 | Duplicate per-mode shells | `video/ui/shell/VideoTopBar.kt`, `VideoMiniPlayer.kt`, `VideoCapsule.kt`, `AccountSheet.kt` | Shared shell |
| R20 | Two theme systems behind the "new look" switch; SF Pro in legacy theme | `app/.../ui/theme/Theme.kt`, `LocalCosmoShell` null-branches, `TopBarSwitch`/`ProfileButtonSwitch` | One theme; remove legacy after migration |
| R21 | Floating desktop player bar (80 dp, 14 dp margins) and tinted desktop Now Playing | `desktop-now-playing-light.webp`, `hero-dark.webp`; `ShellMetrics.DESKTOP_BAR_*` | Docked 72 dp bar §4.17 |
| R22 | Footnote chatter under content ("Lyrics saved with this download", "Downloads use 1.4 GB · 21 GB free" under title) | `phone-lyrics.webp`, `phone-video-downloads.webp` | Move into an info sheet / storage bar |
| R23 | Satellite imagery as default globe | `podio/ui/PodioGlobe.kt` (NASA GIBS) | Flat vector globe; Satellite as option |

---

## 8. Reference adoption & licences

We take patterns and ideas only. No assets, icons, illustrations, copy or branding are copied from any reference.

| Reference | Licence | Adopted pattern |
|---|---|---|
| Prism (Yigder/Prism) | GPL-3.0 | Light large titles, Home shortcut grid, Library chips, mini player + labelled tabs in one dock, Settings hub structure, search landing |
| YTXTube (Koustubh12345/YTXTube) | GPL-3.0 | Video feed anatomy, watch-page single action row, Shorts rail, download sheet, Downloads page |
| Lokal (sipbuu/lokal) | MIT | Desktop sidebar + right panel (Details/Lyrics/Queue), album/artist lists, overline section labels |
| Lokal mobile (lokal-music/lokal-mobile) | GPL-3.0 | Library shortcut grid, "Recently added" shelf |
| BitChord (kushagrasinghx/BitChord) | GPL-3.0 | Lyrics typography direction (banner only; no app screenshots in repo) |
| LastWave Native | GPL-3.0 | Artist hero, album header, quality line under seek bar |
| Mornye (mornyeapp/Mornye) | LGPL-3.0 / GPL-3.0 | Calm neutral surfaces, Continue listening card, provider rows/health |
| Mihon (mihonapp/mihon) | Apache-2.0 | Library grid + 5-tab IA (mapped to our 4), Updates by day, reader chrome |
| Kotatsu (KotatsuApp/Kotatsu) / Tsuki | GPL-3.0 | Details header layout, filter chips, read-mode segmented sheet, tablet rail |
| kotatsu-parsers-redo | GPL-3.0 | none (parsers only, no UI) |
| AntennaPod | GPL-3.0 | Episode rows (date · size · duration, trailing action), Inbox → "Episodes", Add podcast/RSS, speed/skip player |
| Librum (+ Librum-Website screenshots) | GPL-3.0 | Library grid, reader view, settings sidebar |
| Kavita (+ Kareadita/Wiki screenshots) | GPL-3.0 | Book settings panel, TOC, series detail with volumes shelf |
| Booklore | AGPL-3.0 | Dashboard shelves (Last read / Latest added / Discover) |
| Audimo (audaro/audimo) | MIT | none visual (no screenshots in repo; backend/podcast architecture only) |
| Dotify (AsHfIEXE/Dotify) | MIT | none (CLI) |
| **Non-open, idea-only** | proprietary | Radio Garden (globe idea), YouTube / YouTube Music (feed, Explore, Shorts IA), Apple Books / Google Play Books (Continue-reading card), Spotify (dock). Never use their marks, glyphs or names in UI. SF Pro font is proprietary: remove it. |
