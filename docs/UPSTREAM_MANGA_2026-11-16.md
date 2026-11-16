# Manga parser integration — 16 November 2026

## Delivered

- Canonical parser dependency is `com.github.Kotatsu-Redo:kotatsu-parsers-redo:a90648aebd` (full reviewed revision `a90648aebdf61c47b9eed4bec351332306aeff6a`, API 1, parser version 1794785322). The published main JAR contains the contract classes and generated keep rules; its sources JAR omits contract sources. No additional contract dependency is needed.
- `ParserContext.parsers` supplies the active `ParsersBundle`; adapters, source discovery and the first-use catalogue no longer rely on the compiled enum. Removed sources produce an actionable error. Parser changes invalidate repository and persistent catalogue caches, including paginated catalogue generations.
- Every active catalogue excludes adult content types and the existing normalized `hent` name restriction. Parser construction repeats that check. Library books/progress/offline pages are retained independently of source availability.
- `manga/parser-channel.properties` contains the app-owned HTTPS release channel and public P-256 key. A Gradle task generates the shared runtime constants. Shipped apps use this channel without user setup; the private key is confined to publication tooling/secrets.
- The channel publishes separate `parsers-jvm-v1.json` and `parsers-android-v1.json` manifests. Envelopes contain Base64 exact UTF-8 payload bytes and a DER `SHA256withECDSA` signature. Signed fields identify format, API, monotonic version, revision, runtime, minimum SDK, safe artifact filename, exact byte count and SHA-256; Android also signs every DEX entry's name, size and hash.
- Downloads are bounded and cancellable, use an isolated cookie-free HTTP client, require storage headroom and verify authentication before trusting payload fields. Archives are checked for entry count, decompression bounds, duplicate names, namespace/contract replacement, runtime format and entry point. Android DEX definitions are bounds-checked and each DEX hash is verified. Runtime identity must match the signed API/version/revision.
- Android uses a read-only DEX ZIP in private app storage with `DexClassLoader`; desktop uses a JVM implementation JAR with `URLClassLoader`. Only verified implementation classes load child-first; the stable host contract and libraries remain parent-loaded. Desktop cannot consume a DEX manifest/archive.
- Atomic active/previous records preserve a known-good slot. A separate monotonic version/rejection history prevents replay after rollback. Load failure retains existing parsers; manual rollback and incompatible parser linkage restore a verified previous slot or the bundled baseline. Two process interruptions while executing downloaded parser operations quarantine that publication on the next start. Normal source/network errors and cancellations clear the execution marker.
- Sources exposes automatic parser updates, signed update status, Check parser updates and Roll back. Automatic checks run on Manga entry/open, coalesce within six hours in a process, and check again after source failure with a five-minute cooldown; a successful refresh retries the request once. Disabling automatic updates keeps already verified installed parsers and stops automatic network checks.
- Adapted Mihon's scroll metric migration independently: Manga sources, library, browse/catalogue and chapter lists draw Orbit-colored, non-interactive indicators from Compose `scrollIndicatorState`. This avoids custom averages over variable/full-span items, respects bottom insets and RTL, and clamps short/overscrolled tracks. The installed Material3 version lacks Mihon's newer `nonInteractiveScrollbar`, so it was not imported or used to force a dependency upgrade.

The publication workflow and signer (`.github/workflows/parser-bundles.yml`, `tools/ParserBundleSigner.java`) are owned by the coordinating integration. They use a secret-free upstream build job and a fresh signing/publication job; implementation JARs omit contract/META-INF entries. Published parser fixes must retain the exact code version/revision in their manifest and keep API 1 compatibility.

## Deferred validation commands

No tests or builds were executed during implementation, following the user's requested sequencing. After every integration owner's edits are finished, run these one at a time:

```powershell
.\gradlew.bat :manga:jvmTest --tests "com.cosmotune.manga.data.ParserBundleUpdatesTest" --tests "com.cosmotune.manga.data.ParserCatalogueUpdateTest" --tests "com.cosmotune.manga.data.KotatsuAdapterTest" --tests "com.cosmotune.manga.data.MangaSourcePolicyTest" --tests "com.cosmotune.manga.data.ParserRuntimeTest" --tests "com.cosmotune.manga.ui.ReadScrollbarTest"
.\gradlew.bat :manga:compileDebugKotlinAndroid
```

The signed updater regressions generate ephemeral test keys and locally compile a Java fixture implementation with the build JDK. They exercise cryptographic rejection, wrong API/platform/SDK, traversal, size/hash mismatch, contract/package injection, actual child-first JVM loading, restart, replay protection, code/manifest mismatch, crash rollback, catalogue exclusions and bounded DEX definitions. Existing adapter fixtures cover catalogue/details/chapter/page/header behavior. None contacts a third-party site.

## Remaining release/device qualification

The app-owned release must contain both complete signed artifacts/manifests before advertising dynamic updates. The first publication at the already bundled revision is intentionally ignored as an equal version; subsequent newer signed revisions exercise live update delivery.

Android DEX loading has a real platform implementation and offline format/security fixtures, but was not executed on a device in this work. Qualify the signed publication on Android 26 and 34+ (read-only loading, minified release, rollback/process interruption) and desktop JVM before release. Live source repairs and variable-size/RTL/font-scale layouts also need device review. Runtime signature checks authenticate the publisher; downloaded parser code is trusted executable code, not a sandbox.
