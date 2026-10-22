# App review and proposed roadmap

Reviewed 22 October 2026 at source commit `a0d2690f`.

This is the original review and baseline. Follow-up implementation and current validation are recorded in [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

Cosmotune already has a strong identity and a broad feature set. The next release should concentrate on correct results, recovery from failures, desktop/Android consistency, and finishing the small interactions people use every day. Keep the existing artwork colours, Archivo/Hanken typography, custom icons, and shared Compose components.

## What was inspected

- README, maintenance and fork rules, build workflows, previous audits, theme and component code, Home, player, lyrics, settings, queues, and test coverage.
- The installed Windows app: Home, expanded player, queue, lyrics, Settings, and the Downloads empty state. The installed binary was not rebuilt or established to be identical to the checked-out commit.
- Gradle verification results are recorded below. Source inspection and an installed-app walkthrough are not end-to-end certification of every feature.
- No Android device, Mac or Linux walkthrough, account sign-in, real download, Cast session, or multi-device party was performed in this review. Playback remained paused during the walkthrough.

## Fix and validate first

| Priority | Finding and evidence | Proposed work | Acceptance criterion |
| --- | --- | --- | --- |
| P1 | Desktop lyrics cache ignores provider configuration. `DesktopLyricsClient.lookup` keys its 12-hour cache by video ID, duration and explicit provider only; lookup results also depend on enabled/ordered providers and `KEY_PRIORITIZE_SYLLABLES`. `DesktopApp` reloads when these settings change, but the cache can answer with the old result. | Key the cache by the effective ordered provider list and word-sync preference, or invalidate affected entries when those settings change. Include failures in the same policy. | Disable the previously successful provider or change its order/preference while a track is selected: the next lookup follows the new configuration without restarting. Test this with controlled providers and no live network dependency. |
| P1 | Existing Android reports contain 17 locale failures, documented in `BASELINE.md`. The locale test assumes folder names match language tags; the app has different Chinese resource qualifiers, missing language-label translations, and a locale config that does not match the offered languages. | Correct resource/tag mapping, complete the missing labels, and reconcile the declared locales with Android's supported resource naming. | All offered locales load; the locale suites pass without removing assertions or ignoring failures. |
| P1 | Many visual tests inspect source strings and parameter contracts rather than rendered interactions. `HomeContractTest` explicitly describes this approach. These checks protect upstream integration but cannot establish that a click reaches the right control or that text fits on screen. | Keep the contracts; add a small set of actual UI interaction tests and reference screenshots for important screens. Select APIs compatible with the pinned Compose versions. | Tests exercise search → detail → play, player → queue/lyrics, settings changes, and empty/error recovery. Screenshots cover narrow/wide layouts and larger text. |
| P1 | `.github/workflows/check.yml` omits `:sharedUi:jvmTest` and `:video:jvmTest` from its desktop test commands. Manual-only workflow triggers are intentional and guarded in `FORK.md`. | Add the missing suites and their failure reports to the manually triggered checks and review release-workflow coverage. Preserve manual triggers. | A manually started check runs all relevant suites and surfaces any failing result. |
| P1 | Desktop accessibility inspection exposed only the window and a pane. This alone does not prove missing screen-reader support: Windows Compose uses Java Access Bridge. The explicit packaged module list does not name `jdk.accessibility`. | Inspect the packaged runtime, Java Access Bridge integration, keyboard traversal, labels, roles and focus restoration. Validate with NVDA/JAWS, plus TalkBack on Android. | Supported readers announce actionable controls and state; keyboard users can search, play/pause, seek, dismiss overlays and return focus. No system accessibility settings were changed during this review. |
| P2 | The installed app showed Telugu-script lyrics for “Aadiyile Sedhi” by Deva, playing from “Tamil Retro Essentials”, attributed to YouTube Music. This is a matching suspicion, not a confirmed diagnosis: the exact recording and provider response were not compared. | Reproduce with a fresh lookup; compare the selected recording, duration, provider identity and returned lyrics. Make the existing provider picker easier to discover and provide a way to reject an incorrect result. | Wrong-result reproduction is resolved or explained. Selecting another provider does not immediately restore the rejected result. Never reject lyrics solely because their script differs from the title. |
| P2 | Desktop passes `lyricsOffsetOpen = false` and an empty dismiss callback to the shared player. The timing sheet exists but is unreachable through that host. | Wire an accessible desktop timing-offset action and persistent offset state. | Change the offset, observe the lyric timing/seek result, reopen the player, and confirm the chosen setting is respected. |
| P2 | Android has per-track loudness normalization; no equivalent desktop normalization path was found. A desktop equalizer preset named Loudness is a separate feature. | Bring normalization to desktop, with clear behavior when a source provides no loudness metadata. | Known loud/quiet tracks use the intended gain, toggling takes effect, and missing metadata does not mute or amplify unexpectedly. |

## Make the existing interface feel finished

These are design recommendations, not claims that the current theme is broken.

1. **Make empty screens useful.** Downloads already has a clear illustration and message, but no action. Add “Find music” and brief download instructions. Replace the sidebar's passive “Your playlists will appear here” with a route to create or import a playlist, using existing functionality.
2. **Make Home feel personal.** Keep the artwork hero and mixes, but ensure recently played and a meaningful continue-listening entry are easy to reach. Avoid repeating the hero's same playlist as the first item immediately below where practical. Distinguish personal recommendations from general community content.
3. **Clarify player tools.** Add discoverable labels/tooltips and shortcut hints to queue, lyrics, output and video controls. Show the selected audio source and useful quality information through the existing audio-details surface. Keep the transport visually dominant.
4. **Improve library organisation.** Pin favourite playlists, expose create/import actions, and retain sorting/filter choices. Ensure any “Library” mode marked Later in the profile drawer is clearly distinguished from the working Music Library page; consider removing the unfinished mode from normal navigation until it has its own complete purpose.
5. **Organise long settings.** Retain the existing settings search. Add category navigation or jump links for Playback, Appearance, Library, Downloads, Sources and Integrations. Keep advanced controls within their relevant category and show inline explanations for unavailable integrations.
6. **Check readable contrast across real artwork.** The palette already has contrast tests; verify actual rendered overlays, tertiary labels and controls against very bright, very dark, monochrome and saturated covers. Include Tamil, Telugu, Hindi and long mixed-script titles at larger text sizes.
7. **Finish responsive behavior.** Check a small laptop, compact desktop window, phone portrait/landscape, and 125–200% scaling. Confirm the last row stays accessible above the player bar, dialogs fit, and hover-only actions have keyboard/touch equivalents.
8. **Unify recovery states.** Every failed search, image, stream, lyric lookup and download should explain the immediate problem and offer an appropriate next step. Keep cached content visible when a refresh fails. Avoid unbounded loading and repeated automatic retries.

## Additions worth building next

| Order | Addition | Why it helps | Minimum complete version |
| --- | --- | --- | --- |
| 1 | Connection and playback diagnostics | Users need to understand why search works while a song or video fails. | Separate API/media checks; last check time; retry; source/fallback explanation; user-reviewable redacted report. Never include cookies, tokens or signed stream URLs. |
| 2 | Saved listening sessions | One restored queue exists; named queues would make switching between work, travel and discovery useful. | Save/name/open/delete a session containing tracks, current index, position and queue modes; define unavailable-track and duplicate behavior; survive restart. |
| 3 | Replay → playlist | Replay already computes listening periods. Turn those results into something people can play. | “Create playlist from this month/year”, reuse the current playlist store, handle no history, and prevent accidental duplicates. |
| 4 | A short first-run setup | The app's breadth can be difficult to discover. | Skippable choices for local/online listening, preferred content language, data usage and importing an existing library. Reuse existing settings; do not require an account to explore. |
| 5 | Smart playlists | Makes the library useful as it grows. | Explicit rules such as liked + not recently played, downloaded favourites, or recently added; preview the result; persist rules; provide edit/delete paths. |
| 6 | New-release inbox | Following an artist is more useful when new music has somewhere to appear. | Start with an in-app inbox, persisted seen state and a manual refresh. Add opt-in notifications only after refresh reliability and deduplication work. |

Later candidates: desktop lyric-image sharing, a combined lower-data preset using existing audio/canvas preferences, backup/restore parity across platforms, and user-controlled library synchronisation. Validate existing backup and import capabilities before presenting them as new features. Synchronisation needs conflict handling and recovery, so it should follow local reliability work.

## What “working” must mean for a release

| Journey | Evidence required |
| --- | --- |
| Fresh install | Home loads signed out, local music is usable where supported, setup is skippable, and unavailable integrations explain their requirements. |
| Search and playback | Search, open results, play/pause, skip, seek, repeat and shuffle behave correctly; unavailable media produces a recoverable error; the displayed track matches the recording. |
| Queue and persistence | Add/reorder/remove tracks, restart the app, restore the expected queue and position, and handle missing tracks without losing the rest. |
| Offline listening | Start and complete a real download, restart offline, play and seek it, show available cached/embedded lyrics, and recover from interrupted downloads. |
| Lyrics | Verify exact recording/provider, synchronized and unsynchronized results, translation, provider changes, missing lyrics, and timing offset on each platform that offers it. |
| Music/video transition | Enter Video from a song, ensure the music overlay is gone, verify only intended audio plays, return to Music, and verify restored controls/state. |
| Background and outputs | Lock screen/media keys, suspend/resume, audio interruptions, headphone removal, output changes, and Android foreground/background lifecycle. |
| Integrations | Actual two-device Listen Together session and reconnect, Cast on supported hardware, configured/absent API keys, source failures, and expired sign-in. |
| Restricted network | Test the opt-in Tor route, searches, stream reads, redirects, seeking and downloads on the intended Mac/network. A successful Tor check endpoint alone does not validate music playback. |
| Presentation/accessibility | Narrow windows, large text, relevant scripts, light/dark where supported, reduced motion, keyboard operation and platform screen readers. |
| Packaged install | Install and run release artifacts on each supported OS/architecture; verify bundled runtime/native libraries and update behavior. Development launch is not sufficient. |

Run this matrix on Android and Windows, then the Mac and Linux release targets. Record platform, version, exact steps, expected/actual result and reproduction details. Keep live-provider smoke tests opt-in and separate from deterministic unit tests.

## Verification in this review

Command, using the installed Android Studio JDK and existing Gradle cache:

```powershell
.\gradlew.bat :shared:jvmTest :sharedUi:jvmTest :video:jvmTest :desktopApp:test :app:testDevDebugUnitTest --offline --console=plain
```

| Suite | Reported tests | Failures | Skipped | Result in this invocation |
| --- | --- | --- | --- | --- |
| Shared | 2,058 | 0 | 6 | Gradle accepted existing outputs as up to date; report retained from 17 October. |
| Shared UI | 584 | 0 | 0 | Executed successfully. |
| Video | 635 | 0 | 1 | Executed successfully; opt-in live playback test skipped. |
| Desktop | 357 | 0 | 1 | Gradle accepted existing outputs as up to date; report retained from 17 October. |
| Android | 979 | 17 | 0 | Executed; the same 16 `LocaleStringsTest` failures and one `LocalizationTest` failure documented in the baseline. |

The overall command failed because of Android's locale tests, in 4 minutes 25 seconds. These numbers count skipped cases in each suite total. No result above establishes current live-provider availability or validates all packaged native components.

Environment limits: the build reported no usable CMake for the Automix analyser, and missing CMake/Ninja/MinGW tooling for Windows native window integration. Those native build tasks were skipped. The Android Gradle plugin also warns about the configured compile SDK. This review did not rebuild release installers or run backend Go tests.

## Implementation order

1. Fix lyrics cache invalidation and locale failures; complete manually triggered suite coverage.
2. Add a small interaction/screenshot test harness and run the core journey matrix. Resolve resulting failures before expanding scope.
3. Polish empty states, player discoverability, responsive spacing and settings navigation using the existing components.
4. Add diagnostics, saved sessions and Replay playlists in that order, each with persistence, failure states and Android/desktop validation.
5. Expand discovery, onboarding and cross-device features after the earlier work is stable.

## Technical references

- [Compose Multiplatform UI testing](https://kotlinlang.org/docs/multiplatform/compose-test.html): finders, actions and assertions for rendered UI. Use versions compatible with this repository rather than copying the documentation's latest dependency versions.
- [Desktop accessibility](https://kotlinlang.org/docs/multiplatform/compose-desktop-accessibility.html): Windows uses Java Access Bridge; packaged distributions need the accessibility module.
- [Android Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing): combine manual reader testing with automated semantic, contrast, target-size and traversal checks.
