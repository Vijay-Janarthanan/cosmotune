# Connectivity and desktop review

Reviewed 17 October 2026. This covers Sources settings and desktop networking, not a full application audit.

Update: an opt-in Tor prototype has now been implemented. See [TOR_PROTOTYPE.md](TOR_PROTOTYPE.md) for its tested scope, restart requirement and remaining Mac/playback validation. The research and initial recommendations below predate that implementation.

## Revised requirement: integrated macOS access

The intended listener uses a Mac. The requested feature must run inside Cosmotune, require no separately installed VPN or personally hosted server, default to off, and appear at the bottom of Settings. External VPN help does not meet this requirement.

Candidates checked against official project sources:

| Candidate | No personal server? | macOS integration assessment | Remaining limitation |
| --- | --- | --- | --- |
| [Tor Expert Bundle](https://dist.torproject.org/torbrowser/15.0.25/) | Yes, uses the public Tor network | Official bundles exist for macOS aarch64 and x86_64. Cosmotune could manage a bundled daemon and route its connections through its local SOCKS interface. | Music services may reject exits; throughput varies. SOCKS routing must cover every playback transport. Mac packaging and runtime validation are still needed. |
| [PowerTunnel with LibertyTunnel](https://github.com/krlvm/PowerTunnel) | Yes, works locally | Java-based, cross-platform local HTTP proxy; a plausible match for the existing JVM desktop runtime. | DPI circumvention, not a VPN. It does not change the public IP or provide a remote relay, so it cannot solve every firewall/IP block. Actual embedded API and dependency compatibility remain to be validated. |
| [Psiphon Forge](https://forge.psiphon.ca/) | Provider supplies infrastructure | Advertises embeddable circumvention infrastructure. | Public page asks developers to contact them. No self-service free entitlement, price or Mac integration package was verified; cannot ship a working integration from this page alone. |
| [Lantern](https://github.com/getlantern/lantern/blob/main/README-dev.md) | Lantern operates infrastructure | Its desktop app embeds a Go library, including on macOS. | The clearly documented public Kotlin SDK found is Android-specific. This does not establish a ready-to-use Mac SDK or third-party network entitlement. |

Tor is the clearest public-network option to prototype without provider onboarding. PowerTunnel is an alternative if the restriction is DPI-based and avoids a remote relay. Neither is verified to play music on the friend's network. Tor itself documents [exit blocking](https://support.torproject.org/tor-browser/general/website-blocking-tor-exits/) and [bridges for blocked Tor connections](https://support.torproject.org/little-t-tor/circumvention/using-bridges/).

Proposed UI: a final Advanced section containing “Restricted network mode (experimental)”, initially off. Do not start a helper or contact its network while off. When enabled, show starting/connected/failed states; stop the owned helper on disable and exit. An enabled toggle is not evidence of successful playback. Show connected only after tunnel readiness and a routed request succeed. Do not present this as system-wide VPN protection.

This research does not add a working tunnel or a placeholder toggle. End-to-end routing and validation are prerequisites to exposing the control.

## Options for restricted connections

- **Hosted, free:** the [official Proton VPN Windows app](https://protonvpn.com/free-vpn/windows). Its [free plan](https://protonvpn.com/free-vpn) has no data or time limit; the [Windows client is open source](https://github.com/ProtonVPN/win-app). Install the desktop client, sign in, connect, then reopen Cosmotune and test search and playback. A browser extension does not route this desktop player's traffic. Reachability and provider acceptance depend on the network and endpoint; this is not a guaranteed fix.
- **Your own server:** [Outline](https://getoutline.org/faq/) provides open-source server/client software and shareable access keys. You need a reachable server and hosting/bandwidth. It does not supply a universal free access key.
- **Embedded tunnel:** [WireGuard](https://www.wireguard.com/embedding/) supports a Windows tunnel service. Integration is possible, but needs service installation, routing, tunnel configuration, credential management, upgrades and platform-specific packaging. Open-source VPN software does not itself provide an exit server.

## Integration recommendation

Start with an external system-wide VPN. A proxy configured on one HTTP client would leave other traffic unaffected:

| Traffic | Current implementation examples |
| --- | --- |
| Provider APIs | Ktor CIO in DesktopJioSaavn and DesktopAddon; Ktor OkHttp in shared Innertube |
| Downloads/artwork | OkHttp, including DesktopDownloadHttp and Coil |
| Range streaming/modules | Java HTTP client in DesktopRangeStream and DesktopModules |
| Media decoding | Native FFmpeg URL handling in DesktopAudioDecoder and DesktopCanvasDecoder |
| Sign-in | JavaFX WebView |
| Listen Together | Ktor WebSockets |

An app-only proxy feature needs one stored configuration, explicit supported protocols, protected credentials, consistent DNS behavior and tests for redirects, seeks, downloads, video and reconnects. Define whether proxy failure may fall back to direct traffic. Test with direct egress blocked so an accidentally unproxied client cannot appear to work. Do not bundle shared public proxy credentials.

## Fixes in this change

- Source rows use a 16 dp horizontal inset in the new look, keeping numbers and switches away from the card edges; the legacy layout is preserved.
- Disabled sources say “Disabled” instead of “Checking…” or an old status.
- URL/name edits and removal are disabled during source identification. Previously Save could identify an earlier URL while the user edited a different one, then save the earlier value. Cancel remains available.

## Suggested next features

1. **Connection diagnostics:** distinguish DNS failures, timeouts, TLS failures, provider rejection and media-host failures. Check search and a short media read separately. Offer a redacted report without cookies, keys or signed stream URLs.
2. **Source test results:** last checked time, retry action and expandable full error. The current two-line status truncates details.
3. **Explain fallback:** show which source is playing and why earlier sources were skipped, particularly at different audio-quality settings.
4. **Restricted-bandwidth preset:** offer lower audio quality and disable video canvas together, showing each change before applying it. Reuse existing preferences.
5. **Settings layout audit:** check bespoke output-precision rows, download-quality chips and licence entries for missing inner padding at narrow widths and 125–200% scaling. Standard rows own their padding, so padding every group would double it.

These are proposals, not implemented VPN or diagnostics features. The modified UI still needs an interactive Windows visual check.
