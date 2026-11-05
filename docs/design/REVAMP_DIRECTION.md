# Revamp direction

This file is now a pointer. The design system is `docs/design/DESIGN_SYSTEM.md` ("One") and the order of the work is
`docs/design/IMPLEMENTATION_PLAN.md`; where this file and those differ, they win. In short: one neutral UI for every mode
(near-black or off-white grounds, one apricot accent, cover colour only on Now Playing and detail headers), one family (Hanken
Grotesk) with large light titles, few containers, one dock with exactly four labelled tabs, and the same mode switcher in every
mode. What is kept here is the list of the kit's composables and their APIs.

## The kit (`com.cosmotune.ui.kit`, sharedUi)

Mode-agnostic composables that every mode (Music, Video, Manga, Read, Podio, Library, Settings) is rebuilt on, so the whole app
has one UI and one UX. They read the Cosmo palette (`LocalCosmoColors`) and type (`LocalCosmoType`) and need `CosmoLookTheme`
above them. Keep screens to these pieces; add a piece here before inventing a one-off.

| Piece | Use it for | Key parameters |
|---|---|---|
| `KitLargeTopBar` | Every page's top bar: a large light title that collapses on scroll into a small centred one | `title`, `collapse: () -> Float` (use `collapseFraction(index, offset, thresholdPx)` from the list state), `overline`, `onBack`, `actions` |
| `KitSectionHeader` | A section's heading: all-caps overline, light title, "See all" | `title`, `overline`, `onSeeAll` |
| `KitIconButton` | Round ghost icon buttons in bars and on the player (like, more, search) | `icon`, `label`, `onClick`, `on` |
| `KitChip`, `KitChipRow` | Thin filters (All, Today, Videos; Playlists, Songs, Albums): low-contrast fill, the chosen one in the accent | `label`, `selected`, `onClick`; `labels`, `selectedIndex`, `onSelect` |
| `KitPill` | A small text pill ("Continue ...", a status) | `text`, `icon`, `trailing`, `onClick` |
| `KitButton`, `KitTextButton` | Actions: primary (accent), tonal (`surface2`), quiet (outlined); text-only | `text`, `onClick`, `kind`, `icon`, `dense` (40 dp) |
| `KitActionRow` | The action row of a watch or detail page: one sideways-scrolling row, never wrapping | `contentPadding`, `scrollState`, `content` |
| `KitPageTitle` | A root page's large title, with actions at its end | `title`, `actions` |
| `KitPlayCircle` | A detail header's play button: a 56 dp accent circle | `label`, `onClick`, `icon`, `enabled` |
| `KitRootActions` | What ends a root page's top bar: Search, then the account | `onSearch`, `searchLabel`, `showSearch`, `account`, `pageAction` |
| `KitCoverGradient` | The page's own cover colour behind a detail header, fading to the ground (320 dp) | `url` |
| `QualityPill` (nowplaying) | Audio quality, measured (filled) or catalogue-reported (outlined) | `badge` from `measuredQualityBadge()` / `catalogueQualityBadge()`, `compact` |
| `KitArtwork` | Art in one of four shapes | `url`, `art` (`SQUARE`, `ROUND`, `WIDE` 16:9, `POSTER` 2:3), `height` |
| `KitMediaRow` | Songs, episodes, videos, chapters, books in a list: number or art, title over subtitle, quality pill, duration, more | `title`, `subtitle`, `onClick`, `artUrl`, `art`, `number`, `quality`, `trailingText`, `current`, `onMore`, `artHeight` |
| `KitCard`, `KitShelf`, `KitQuickTile` | Shelves and grids of square, round, 16:9 or 2:3 cards; the home's two-column quick-access tiles | `title`, `subtitle`, `artUrl`, `art`, `width` (null in a grid), `badge`, `progress`, `current`, `overlay`; a tile takes `icon`, `onLongClick` |
| `KitVideoCard` | Full-width 16:9 video with avatar and two-line metadata (Video home, subscriptions, search) | `title`, `meta`, `thumbnailUrl`, `avatarUrl`, `duration`, `progress`, `onMore`, `onAvatarClick`, `thumbnail`, `avatar`, `margin` |
| `KitGroup`, `KitDivider`, `KitSettingsRow` | Settings and other real groups: one card, hairlines, rows with small coloured squircle icons | `title`; `title`, `icon`, `tint`, `summary`, `onClick`, `trailing` |
| `KitSheet` | Bottom sheets (detached glass) | `onDismiss`, `title`, `content` |
| `KitEmptyState`, `KitErrorState`, `KitOfflineBanner`, `KitSkeletonRows` | Empty, error, offline and loading states | `title`, `message`, `actionLabel`/`onAction`; `onRetry`; `count`, `art` |
| `CosmoBottomChrome` (shell) | The phone's dock: the mini player row on top of the labelled tabs in one surface | `tabs`, `song` or `miniPlayer`, `onPrevious`, `mode`; pages pad with `rememberDockClearance()` |
| `KitNavRail` | The desktop's rail: the same tabs on the start edge | `tabs`, `selectedIndex`, `onSelect`, `header` |
