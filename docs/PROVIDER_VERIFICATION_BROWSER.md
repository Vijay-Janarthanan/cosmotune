# Provider verification windows

Version 1.0.7 source. The normal flow is: open provider verification → complete the service's human check when requested → Cosmotune captures the result, exchanges it and closes the window. The user does not normally copy a grant. Protected sessions persist; another check is needed when the operator expires or revokes access, not for every song.

## Desktop

The host launches an installed Chrome, Edge or Chromium in an app window with a generated private profile. Browser cookies/storage are separate from the player's stores and from the user's usual browser profile. No browser binary is added to Cosmotune. macOS and Linux locators use their platform application/PATH locations; live proof here is Windows only.

A small helper process starts before Cosmotune's ordinary singleton/player/tray initialization. It monitors only its own generated browser context, captures completed navigation/popup callbacks, and reads a grant-only fallback solely from the exact active operator challenge. It does not expose app objects to JavaScript, read the ordinary browser profile, read the clipboard, submit credentials, solve a challenge or forge a verification token.

The return channel is loopback-only and protected by a per-window capability. Provider/hash, pending state, address and deadline are rechecked inside the manager's operation lock before sending a result to an operator. Profiles are removed after their owned processes close; cleanup does not follow symlinks. A Windows argument file avoids overflowing the process command-line limit.

If a compatible Chromium is not installed, the existing system-browser/callback/manual recovery remains available. No engine is downloaded implicitly.

## Android

Android 9+ opens an app-private, non-exported verification Activity in a separate `:provider_auth` process with its own WebView data suffix. That process skips player/application-store startup. Its previous cookies/storage are cleared before loading a provider, and its process is released on close. No music-player WebView cookies, source credentials or JavaScript/native app bridge are exposed.

Navigation and popup results use the same shared callback matcher. A verified grant-only result can return through Activity Result; the parent rechecks the provider package, pending state and deadline before exchange. The browser denies file/content access, mixed content, page downloads, geolocation and page permission requests, and never ignores TLS errors.

Android 8/8.1 retain the system browser because the isolated WebView data-suffix API is unavailable. A service may also reject a particular system WebView; **Open in browser** and the masked callback/grant field remain recovery paths. Real Android service compatibility and macOS/Linux browser execution are separate from compilation and fixture checks.

## Live proof and failed experiments

The first Windows prototype used the already bundled WebKit engine; the user reported a failed human check. The first Chromium launch also reported a failed check. Its port-zero debugging launch enabled Chrome's automation indicator. The corrected ordinary Chromium launch uses the documented explicit-port interface and leaves browser security, sandboxing, user agent and JavaScript properties untouched.

The subsequent real Qobuz verification passed without pasting: the operator's `/v2/challenge/verify` returned HTTP 200 with a grant, Cosmotune exchanged it, persisted the session and closed the window. `build/provider-embedded-browser-proof/result.json` recorded `SESSION_VERIFIED_WITHOUT_PASTING`. Redacted opt-in diagnostics recorded the automation indicator as false and only response paths/statuses/key names/error categories; they contain no grants, cookies or passwords. The unsuccessful prototypes are not playback proof.

`ProviderVerificationTargetTest` covers exact callbacks/state, popup wrappers, navigation domains, expiry and grant-only challenge ownership. `ProviderSignedSessionTest` rejects returns after a package change or disconnect and verifies immediate exchange/retry. `ProviderAuthenticationInteractionTest` covers the native settings/manual fallback UI. `ProviderVerificationWindowTest` passed on the dedicated Android emulator in 4.807 seconds: the Activity was non-exported, ran in a different private process, and closing it kept the app process alive. This used a harmless HTTPS page and does not establish Android service/challenge compatibility.

The earlier verification-window regression passed with **4,812 reported tests, 18 skipped, zero failures/errors**, across shared (2,317), providers (123), shared UI (662), desktop (469), Android app (1,180) and UI interactions (61). TV production-debug compilation also passed. See ignored `build/provider-final-current-regression.log` and `build/provider-browser-android-process.log`. Actual Windows Qobuz verification was run separately from the normally skipped live tests. Subsequent audio-preparation checks are recorded in [the preparation evidence](PROVIDER_MEDIA_PREPARATION.md).

## Audio is a separate stage

The [operator investigation](PROVIDER_OPERATORS.md) corrected the shared download-ticket header and verified Amazon access with a human-issued grant. [Native audio preparation](PROVIDER_MEDIA_PREPARATION.md) now processes the returned `ffmpeg.mov_key` descriptor through the app's bundled libraries, with stream copy and decoded-sample validation. **Blinding Lights — The Weeknd** passed real Windows decoding as **24-bit/44.1 kHz FLAC**. The earlier unsupported result was an unimplemented host step. The verified Mornye Qobuz follow-up returned twenty search results, then exposed a short download-preparation response deadline; it is separate from the previously proven Qobuz HTTP add-on. Verification and successful FLAC playback do not establish Atmos rendering.

Sources: [Lokal's isolated auth window](https://github.com/lokal-music/lokal/blob/dc43d85db7711279f58d969107883084965b2005/electron/mornye/authWindow.js), [Mornye's native grant completion](https://github.com/mornyeapp/Mornye/blob/fb4945be910b0a7a8cce25eab3d1b3d80b3541e4/rust_backend/crates/desktop/src/backend.rs), [Chrome's documented local debugging interface](https://developer.chrome.com/docs/devtools/remote-debugging/local-server).
