# Extension operators and verification

Checked 2026-10-26. These are current development fixes after v1.0.6, not changes to the published installers or to an external operator's servers.

## Who supplies the lossless extension gateway?

The inspected Mornye packages share the same third-party gateway:

| Extension | Package version | Signed operator base | Gateway application scope |
| --- | --- | --- | --- |
| Amazon Music | 2.3.14 | `https://api.zarz.moe/v2` | `amzn@2.3.12` |
| Qobuz | 1.2.23 | `https://api.zarz.moe/v2` | `qobuz-web@1.2.21` |
| Tidal | 1.2.9 | `https://api.zarz.moe/v2` | `tidal-web@1.2.5` |
| Deezer | 1.3.7 | `https://api.zarz.moe/v2` | `deezer@1.3.5` |

The [gateway's public page](https://api.zarz.moe/) identifies it as SpotiFLAC Mobile infrastructure and links to Mornye's site/status and project. The [extension repository](https://github.com/mornyeapp/Mornye-Extension) credits `zarzet`. These public project associations do not establish a verified legal identity for the server administrator. The gateway is separate from Amazon/Qobuz/Tidal/Deezer's own catalogue endpoints and from the supplied private Qobuz HTTP add-on.

The package's gateway application version intentionally differs from its package version. Upstream preserves that version during package-only fixes to retain session scope; Cosmotune does not rewrite it or share grants between these provider identities. Other inspected sources use their own public APIs, user OAuth or other routes; they do not all depend on this gateway.

## What was broken in Cosmotune?

The earlier [Amazon search investigation](AMAZON_RESOLUTION_INVESTIGATION.md) corrected the expanded fetch-envelope limit. Operator verification exposed additional native-host gaps:

1. The native callback validated and stored a signed grant but left it for a later extension completion action. The four inspected packages expose no sign-in settings action, leaving the user without a way to finish.
2. A fresh/expired signed provider had no native entry to start or restart verification independently of a failed audio request.
3. Desktop's default custom callback scheme was not registered with the OS. The manual fallback could be used, but a default browser return did not complete automatically.
4. The loopback completion budget was five seconds, too short to include some legitimate HTTPS session exchanges.
5. Signing removed every header sharing the signing prefix. This incorrectly discarded the extension's `X-Zarz-Ticket`: the verified session could mint a ticket, but its consumption request returned HTTP 403. Only generated signing-header names and Content-Type are now reserved; operation tickets survive.

## Implemented flow

- Provider settings identify the declared verification-service hostname and offer **Verify provider access**, **Restart provider verification**, or **Finish provider verification**, as appropriate. The user still opens the external verification page explicitly.
- Android retains its registered app-owned callback. Desktop generates an IPv4 loopback callback and binds the existing exact-state listener before opening the browser. A busy port or explicitly configured unsupported callback retains masked manual recovery.
- A valid signed callback immediately exchanges the grant at the declared operator endpoint and saves the protected session. The older SDK completion action is harmless after native completion. Ordinary OAuth providers retain their existing extension-specific exchange flow.
- An exchange failure retains the unexpired completed grant for an explicit retry. Restarting an expired challenge clears its previous grant/proof. Callback-state, address, one-use, installation-generation and permission checks remain in force.
- Loopback completion allows a bounded 25-second exchange, within the original challenge lifetime. No challenge is solved, skipped or bypassed by the host.
- Native verification windows now capture navigation, popups and the operator's grant-only fallback automatically; see [the browser implementation and live proof](PROVIDER_VERIFICATION_BROWSER.md). Explicit native pasting of `grant=…` is also supported, with the operator validating its expiry and binding to the selected install/application. OS callbacks retain their stricter address/state/deadline validation.

## Live checks and limits

`ProviderOperatorAccessLiveTest` executed the production native verification entry with isolated, reviewed packages. All four returned a real `/v2/challenge` on `api.zarz.moe`, and all four successfully prepared an automatic desktop callback. None issued an authenticated session without the operator's human check.

The same native HTTP client received HTTP 200 with `status: ok` from `/v2/health` and HTTP 200 with `status: degraded` from `/v1/health`. An overall health result does not establish per-song delivery, entitlement, or Dolby decoding/output. The public status site's shell is dynamic; its initial HTML alone is not evidence of service health.

`ProviderSignedSessionTest` covers the native start, exact-state app callback and automatic exchange, retry after a failed exchange, stale-grant replacement, a real desktop loopback return with a six-second operator response, legacy SDK compatibility, signing vectors, expiry, disconnect and provider-scoped isolation. `ProviderAuthenticationInteractionTest` exercises the native settings entry, browser launch, failed exchange and retry through the actual Compose UI. The affected provider/shared-UI/desktop/Android tests and Android/TV/desktop compilation passed in `build/provider-operator-final-validation.log`; the Android provider test APK also built.

The opt-in `ProviderOperatorVerificationLiveTest` can wait for an actual human-completed Amazon check, then attempt recording matching and bounded native audio decoding in an isolated profile. An unanswered or failed challenge does not count as successful audio. Its local status report is under ignored `build/operator-verification-amazon/`; challenge addresses, grants and session credentials must not be copied into documentation.

In the actual follow-up, the second user-supplied Amazon grant exchanged successfully (HTTP 200 and a valid future session expiry). The ticket-header fix restored media delivery. [Native preparation](PROVIDER_MEDIA_PREPARATION.md) now handles the returned `ffmpeg.mov_key` descriptor: **Blinding Lights — The Weeknd** passed actual Windows decoding as **24-bit FLAC at 44.1 kHz**. The earlier `native_processing_required` result was our missing preparation implementation. Qobuz verification also completed automatically without a pasted grant; its package's subsequent download-preparation request exposed our short response deadline, separately from audio decoding. These checks do not establish immersive device output.

External operator verification, service outages and account access remain external dependencies. These changes fix our integration; no external service availability or immersive audio output is implied.

## Primary sources

- [Amazon manifest](https://github.com/mornyeapp/Mornye-Extension/blob/main/sources/amazon/manifest.json), [Qobuz manifest](https://github.com/mornyeapp/Mornye-Extension/blob/main/sources/qobuz-web/manifest.json), [Tidal manifest](https://github.com/mornyeapp/Mornye-Extension/blob/main/sources/tidal-web/manifest.json), [Deezer manifest](https://github.com/mornyeapp/Mornye-Extension/blob/main/sources/deezer/manifest.json).
- [Lokal signed-session implementation](https://github.com/lokal-music/lokal/blob/dc43d85db7711279f58d969107883084965b2005/electron/mornye/session.js) exchanges a returned browser grant in its callback method.
- [Mornye native backend](https://github.com/mornyeapp/Mornye/blob/fb4945be910b0a7a8cce25eab3d1b3d80b3541e4/rust_backend/crates/desktop/src/backend.rs) explicitly finishes the signed-grant completion action.
- [Upstream Amazon issue #35](https://github.com/mornyeapp/Mornye-Extension/issues/35) reports failed downloads; it is user-reported evidence, not a diagnosis of our integration or proof of a global outage.
