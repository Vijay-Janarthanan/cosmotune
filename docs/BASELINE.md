# Baseline

Built and tested from BitChord's unchanged code at fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f.

| Check | Result | Time |
| :-- | :-- | :-- |
| `:shared:jvmTest :desktopApp:test` | passed (46 + 342 tests) | 1 m 50 s |
| `:app:testDevDebugUnitTest` | **17 of 971 failed** (see below) | 1 m 23 s |
| `:app:assembleDevDebug` | built (arm64-v8a, armeabi-v7a, x86_64, universal) | 53 s |
| `:desktopApp:packageUberJarForCurrentOS` | built (`BitChord-windows-x64-1.8.29.jar`, 297 MB) | 1 m |
| Tests counted | 1359 tests, 0 skipped, 17 failed | |

Machine: Windows 11, 32 GB RAM, JDK 21.0.9 (Android Studio), Gradle 8.11.1 as pinned by the wrapper.
Times are Gradle's own "BUILD ... in" figures. The first command ran after `./gradlew --stop`
(cold daemon, warm `~/.gradle` caches); the later ones reused the daemon, so they are not cold-start numbers.
The first command (`:shared:jvmTest :desktopApp:test`, 1 m 50 s) also ran before `local.properties` was fixed
(see Notes), so the `:app` project was not configured in that run. Neither module depends on `:app`, so its
46 + 342 tests are unaffected, but its time is for a smaller build than the later ones.

## Known failures in the unchanged code

`:app:testDevDebugUnitTest` fails on BitChord's own code, before any Cosmotune change.
The unchanged tree is `fe95bb60` plus two docs-only files (`FORK.md`, `UPSTREAM.md`).
Upstream commit `2aa9a249` ("feat(locale): Traditional Chinese support (#679)") added the
`chinese_simplified` and `chinese_traditional` strings, the `zh-CN` and `zh-TW` entries in
`SUPPORTED_LANGUAGES` and Chinese resource folders (`values-zh-rTW`, `values-zh-rHK`,
`values-b+zh+Hant`, plus the desktop strings). The two tests look for other names than those folders:
`LocaleStringsTest` (16 failures) wants `values-zh-CN/strings.xml` and `values-zh-TW/strings.xml`, which
are not in the tree, and the two new strings in the other 14 locales; `LocalizationTest` (1 failure)
wants `zh-CN` in `locales_config.xml`:

| Test | Failures | Cause |
| :-- | :-- | :-- |
| `LocaleStringsTest.strings_containsAllBaseKeys` | 16 (es, fr, de, pl, pt, id, hi, ja, ru, he, it, tr, th, vi, zh-CN, zh-TW) | 14 locales lack `chinese_simplified` and `chinese_traditional`; `values-zh-CN/strings.xml` and `values-zh-TW/strings.xml` do not exist (the repo has `values-zh`, `values-zh-rTW`, `values-zh-rHK`, `values-b+zh+Hant`) |
| `LocalizationTest.localesConfig_matchesSupportedLanguages` | 1 | `locales_config.xml` has no `zh-CN` |

The other 954 Android unit tests pass. Later tasks should compare against "17 failed, same names",
not against zero, until these are fixed (in Cosmotune or by merging an upstream fix).

## Notes

- `local.properties` must hold `sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk` (every path separator doubled; it is read as a Java properties file).
  With single backslashes `settings.gradle.kts` reads a wrong path and builds the desktop targets only, with only a log line
  ("No Android SDK found"), so the `:app` project does not exist. The first command in the table ran in that state.
- Compiler warnings only (for example deprecated `Icons.Rounded.PlaylistAdd` and unnecessary safe calls in `DesktopStringsTest`); no errors.

## Release signing certificate

The Android release builds (`:app:assembleProdRelease`) are signed with Cosmotune's own key, made on 2026-10-13
(RSA 4096, valid until 2054-02-28). The fingerprint is recorded here so that the signer of
an installed build can be recognised later.

- Alias: `cosmotune`
- Certificate owner: `CN=Cosmotune, O=Cosmotune, C=IN`
- SHA-256: `2B:53:4C:DC:FC:47:17:C6:22:4E:CC:44:89:AB:5F:4D:81:A2:4E:C7:AC:BE:0F:FE:E5:CB:4C:A9:5F:33:2E:D1`
- Key file: kept outside the repository and never committed.
