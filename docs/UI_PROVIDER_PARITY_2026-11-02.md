# UI and provider parity — 2 November 2026

This pass addresses navigation, provider persistence and discoverability, settings organization, and the player Visuals control on the current 1.0.13 source baseline. It does not claim that every upstream feature is implemented or that every platform has been tested on hardware.

## Causes and repairs

| Problem | Cause | Repair |
| --- | --- | --- |
| Android Manga/Podio lose the profile/settings entry | Their roots replace the Music composition, while desktop keeps its window frame. | Shared mode-header action slot; Android uses its real account/mode drawer with Settings and Sources destinations. Closing these screens returns to the originating mode. Desktop retains its existing frame action. |
| Mornye packages seem absent on Android | The package launcher was a plain text action inside Sources; phone storage is independent of desktop. | Full-width provider card in Android Settings and Sources, a prominent Browse Mornye providers action, device-local counts and first-install guidance. Packages still require review before installation. |
| Saved provider connection or enabled choice can disappear | Concurrent store instances wrote from different stale snapshots, despite serializing their final disk moves. | Atomic read/modify/write against current protected disk state, including regression tests for a late settings write after token save, disable and disconnect. Accepted state is also published to other live stores for the same canonical file; late responses cannot repersist cookies after disconnect. |
| Request failures look like disabled or logged-out providers | Expired-token checks and generic errors did not clearly describe local connection state. | Distinct disabled, connected, renewal-needed, verification-needed and changed-connection labels; safe HTTP/request errors; saved connection retained after service errors. No failure path was found that deliberately auto-disables a provider. |
| Confusing settings categories | Category chips replaced the search query, and categories could be hidden offscreen. | Real independent categories shared by Android and desktop; wrapping controls; global search; explicit Sources destination. Existing values and nested routes are retained. |
| Visuals looks like stray text under the video | The desktop sheet placed an independent text button at a fixed bottom/right offset, outside the player's action layout. | Shared icon action inside the wide header, phone header and legacy action row; visible button surface, accessible label and tooltip. The existing desktop full-screen presentation remains. Narrow legacy player layouts move the capsule to a second row rather than shrinking touch targets; visual-mode choices wrap at large text sizes. |
| Visuals exists only on desktop | Its UI and sampling state lived in the desktop module. | Shared Artwork/Pulse/Waveform view and bounded read-only PCM sampling. Android's existing precision sink publishes to the same view without changing playback samples or requiring microphone permission. |
| Manga Discover opens in a red error state before a search | The initial empty query was reported as a failed search, alongside a no-results message. | Neutral search guidance until a valid query is entered; no premature no-results/error state. Folder import uses the same outlined action style as comic import. |

The visualizer samples only while its audio scenes have subscribers, at most 25 times per second. It reads existing local PCM and does not change gain, bit depth, DSP or the audio sent to the device. Remote/Cast/offloaded paths that do not provide local PCM show an explanatory state; no simulated audio response is presented as measured playback. Reduced-motion mode suppresses reactive drawing.

## Provider limits

Provider packages and credentials are saved separately on each device. Desktop installation is not automatic Android installation. The repository/package trust review, encrypted storage, signed-session scope checks and human-verification boundaries remain active. A saved token does not guarantee operator availability, and an operator can expire or block a session. Mornye's own signed-session implementation also renews per-install, scoped sessions; it does not offer permanent authentication. This pass does not bypass verification or automatically install/enable packages.

The UI comparison also inspected Mornye's pinned [shared extension rows](https://github.com/mornyeapp/Mornye/blob/7db0fc76f6cfeaef903327ebc73e13d9bdc3d936/lib/widgets/extension_row.dart), [repository surfaces](https://github.com/mornyeapp/Mornye/blob/7db0fc76f6cfeaef903327ebc73e13d9bdc3d936/lib/widgets/extension_repo_card.dart), [provider health indicators](https://github.com/mornyeapp/Mornye/blob/7db0fc76f6cfeaef903327ebc73e13d9bdc3d936/lib/widgets/extension_health.dart) and [package details](https://github.com/mornyeapp/Mornye/blob/7db0fc76f6cfeaef903327ebc73e13d9bdc3d936/lib/screens/repo/extension_details_screen.dart). Relevant patterns are separate discovery/installed states, consistent row surfaces, responsive trailing actions and a distinction between connection and service health. Cosmotune now clarifies discovery and local connection state; Mornye-style live operator-health indicators are still not integrated. A local saved-session label is not an online-health check. Lokal's existing account-connection cards and separate playback-priority controls were also reviewed in the local upstream checkout; their Electron account backend is not a portable Android session implementation.

## Upstream features not yet integrated

The [full repository audit](UPSTREAM_REVIEW_2026-11-02.md) records exact heads, source evidence and decisions. The previous pass integrated a selected correctness subset; it did not complete upstream parity. These items remain outside this UI/provider repair:

- Watch Podio; bidirectional library/file synchronization; native DSD/DoP.
- Direct userspace USB/DAC hardware volume, focus isolation and recovery beyond the existing output paths.
- SABR-native transport and broader cipher/extraction recovery; original-language versus dubbed stream selection.
- Automatic additions to downloaded playlists with full pagination, deletion guards and large-change review.
- Durable artist portrait/logo caching, expanded artist-release browsing and incremental artist sections.
- Additional home moods/categories, motion-cover discovery and presentation work.
- Mouse back/forward navigation, queue locking and a broader accessibility audit.
- Expanded download/backup layouts, EQ/convolver response/headroom and additional dual-deck DSP tools.
- Repository service-health feeds and richer package detail/icon presentation beyond the repaired discovery and connection states.
- WebDAV/NAS and nearby-device synchronization updates that require adaptation to our own storage and pairing model.
- Wider Manga live-site/device compatibility and provider/codec/hardware validation. An imported parser or advertised audio mode is not proof of successful playback.

Settings search already existed; this pass repairs its category behavior and exposes providers consistently. OpenCanvas has no newer commit than our current import. The eight previously tracked podcast utility repositories had no new source delta. No new account-free adapter for paid-only podcasts was verified.

## Verification

The final combined gate passed in **6m 26s**, covering shared, provider, Manga, desktop, Android unit, shared UI, Video-mode integration contracts and UI interaction tests, plus Android app, TV and watch Kotlin compilation. XML reports contain **5,299 cases: 5,261 passed, 38 skipped, zero failures/errors**. This gate selects the 14 Video mode-hook contracts rather than repeating the unrelated full Video rendering suite. The broader Video suite had already passed in the preceding run. The final rendered-Manga cleanup then passed all Manga/UI interaction tests and Android/desktop/TV compilation in a focused **57s** recheck.

UI evidence includes real drawer navigation/tab restoration, stable settings category selection at phone width/150% text, preserved narrow-player action targets, Visuals dismissal/mode selection at 200% text, and connection-expiry/disconnect behavior. Provider regressions cover protected-state propagation across overlapping stores, late cookie responses after disconnect, matching signed-session scope and a single shared bootstrap/verification proof.

Native component captures were rendered and inspected locally: `build/ui-parity-2026-11-02/manga-phone-header.png`, `podio-phone-header.png`, and `build/ui-parity-review/visuals-phone.png`, `visuals-desktop.png`. They use controlled fixtures, not a physical Android device or a live provider account. Translated settings and Visuals labels were added to the supported app locales. Changed/new XML resources parse successfully; tracker evidence, companion/OpenCanvas attribution and whitespace checks pass.

An existing desktop concurrent-launch test timed out in the earlier loaded run; it passed unchanged in the serial final gate. Missing fixture hosts, an outdated authentication assertion and incomplete settings translations found by the initial checks were corrected before the final pass. Validation logs are local and ignored: `build/upstream-audit-2026-11-02/ui-parity-verified.log` and `ui-parity-render-recheck.log`.

No signed-in provider searches, authentication attempts, installed-package replacement, release tag or deployment is part of this pass. Actual Android/macOS/Linux interaction, account renewal at real gateways, Cast/offload behavior and physical output remain separate device/service checks. These source changes require a new app build.
