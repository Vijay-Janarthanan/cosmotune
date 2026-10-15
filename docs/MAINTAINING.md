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
pixels of a cover into a `CoverColors` (every colour a screen needs, from one seed colour taken from the cover), `CoverPaletteSpec`
holds every number of the design, and `Oklab` blends and compares colours. It is pure Kotlin with no Android or Compose types,
so `CoverPaletteTest` runs it on the desktop JVM. How a screen calls it is at the top of `CoverPalette.kt`.

- To retune the design, change a default in `CoverPaletteSpec` (tones, chroma limits, hue shifts, the caps) and run
  `CoverPaletteTest`. The tests hold the safety rails (contrast, the luminance and tone caps, the gamut) for every hue, so a
  retune that breaks one fails there. A tone or chroma that cannot be shown is lowered to fit, never clipped.
- The seed is chosen in the private function `CoverPalette.chooseSeed`; the colours are derived from it by `derive`. Both
  use Google's Material Color Utilities, in MaterialKolor's Kotlin Multiplatform port: `com.materialkolor:material-color-utilities`
  4.1.1 (MIT, with Google's Apache-2.0 code; Android and desktop JVM, no Compose). Its Celebi quantiser mixes a cover of a few flat
  colours into one blend, so such a cover is read from its colour histogram instead (`maxFlatBins`). To use another picker,
  replace `chooseSeed`; nothing else needs to change.
- The library's version follows the Kotlin version: 4.1.1 is built on Kotlin 2.3, and 5.x needs the Kotlin 2.4 standard
  library. The `FORK.md` row for `shared/build.gradle.kts` says what to do when Kotlin is raised.

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
