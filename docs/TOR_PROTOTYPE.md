# Integrated Tor prototype

In Settings, scroll to the final “Restricted network · experimental” group, enable
“Connect through Tor”, then fully quit and reopen Cosmotune. Closing to the tray is
not quitting. Turn the option off and restart to restore direct connections.

The default is off. Off mode starts no Tor helper and makes no Tor check request.
Enabled mode starts the bundled Tor client, binds its HTTP CONNECT listener to
127.0.0.1 on a selected port, and sets routing before the music clients initialize.
The app reports connecting progress and verifies the route with one HTTPS request
to https://check.torproject.org/api/ip before reporting connected. A failure leaves
the proxy route selected; it does not automatically switch back to direct access.
Initial connection has a three-minute deadline. Restart to retry a failed startup.

## Scope and limitations

- This is an experimental HTTPS connectivity feature, not a system-wide VPN or an anonymity guarantee.
- Java/OkHttp clients use the startup proxy selector; desktop Ktor CIO clients receive an explicit proxy.
- Native audio/video URL opens receive FFmpeg proxy, TLS verification and CA options. The CA bundle is generated from the JVM's trusted issuers. Native local files remain usable.
- Plain HTTP remote sources are unsupported by this CONNECT-only route. Local loopback services remain direct. HLS/DASH manifests, redirects and provider-specific playback need testing on the target Mac.
- Signing into a music account still identifies the account to its provider. Tor does not prevent exploitation of vulnerable software. This client does not run a public relay or open a listener on the LAN.
- Music providers may block exits. Tor itself may be blocked by the network. Bridges and transport selection are not implemented in this first prototype.
- Background retries, video playback and downloads can be slower. Existing app startup requests may fail while Tor bootstraps; retry search/playback once the status says connected.

## Packaging and maintenance

`prepareTorBundle` downloads version 15.0.25 from the Tor Project and verifies its
pinned SHA-256 before unpacking it into generated application resources. The bundle
includes upstream component licence documents. The build chooses macOS aarch64 or
x86_64 using the existing host architecture selection; Windows and Linux x86_64 are
also configured. Build Mac distributions on the corresponding Mac architecture.

No runtime download is required. Enabled mode extracts the packaged files under
`~/.local/share/cosmotune/tor/15.0.25`; state lives in the adjacent `state` directory.
The child process exits when Cosmotune quits. The setting is stored in Java
Preferences under `com.cosmotune.desktop/tor`. Keep this dependency updated; update
the version, official checksums and runtime extraction version together.

The local Windows tunnel successfully bootstrapped and the official check endpoint
reported Tor use. Tests exercise Java, OkHttp and CIO CONNECT routing with a fake
proxy and an unresolvable destination, exact loopback exceptions and no direct
fallback. The Apple Silicon archive checksum/layout was checked. No macOS execution,
full song playback on the friend's network, or visual UI verification is claimed.
