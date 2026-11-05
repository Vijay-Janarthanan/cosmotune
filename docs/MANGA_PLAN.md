# Manga mode: source review and implementation plan

Reviewed 2026-10-23. Status: implementation in the working tree for the coordinated 1.0.4 release; not published. The user confirmed that “tsuke” means **Tsuki**.

## What Tsuki actually provides

The public source is [TsukiAndroid/Tsuki](https://github.com/TsukiAndroid/Tsuki), inspected at commit `89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7`. It is an Android application, rather than a hosted manga catalogue API. It does not contain all manga pages in its APK. Its reader fetches catalogues, chapters and image URLs from separate websites.

There are three relevant source paths:

| Path | Evidence | Fit for Cosmotune |
| --- | --- | --- |
| Built-in Kotlin parsers | [ParserMangaRepository](https://github.com/TsukiAndroid/Tsuki/blob/89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7/app/src/main/kotlin/io/github/landwarderer/futon/core/parser/ParserMangaRepository.kt) wraps `getList`, `getDetails`, `getPages`, `getPageUrl` and source-specific request headers. | Best starting point for a common Android/desktop adapter. |
| Mihon/Keiyoushi extension APKs | [MihonExtensionLoader](https://github.com/TsukiAndroid/Tsuki/blob/89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7/app/src/main/kotlin/io/github/landwarderer/futon/mihon/MihonExtensionLoader.kt) scans Android packages and loads source implementations through a Dalvik class loader. | Separate Android integration; APKs cannot be loaded directly by ordinary desktop JVMs. |
| Custom website parsers | [CustomMangaRepository](https://github.com/TsukiAndroid/Tsuki/blob/89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7/app/src/main/kotlin/io/github/landwarderer/futon/customsource/data/CustomMangaRepository.kt) selects known website/CMS parsers and browser sources. | A website URL alone is insufficient for arbitrary sites. Support depends on a compatible parser and sometimes a browser. |

Tsuki advertises 1,200+ sources. That is a project claim, **not a count of sources tested working for Cosmotune**. Sources can change their page structure, require accounts, vary by language/region, or require interactive verification.

The dependency declared in Tsuki's [Android build](https://github.com/TsukiAndroid/Tsuki/blob/89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7/app/build.gradle) is the Kotatsu parser fork. Its old `clquwu` GitHub location currently redirects to [Kotatsu-Redo/kotatsu-parsers-redo](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo), inspected at `dc223c3d8098415b29cf1acfedc401a88d01c9fe`. Use a verified, pinned artifact/commit, rather than a moving branch or copying an obsolete dependency coordinate from the README.

## Cross-platform approach

The parser library is Kotlin/JVM, with an Android-compatible API. Its [MangaLoaderContext](https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/blob/dc223c3d8098415b29cf1acfedc401a88d01c9fe/src/main/kotlin/org/koitharu/kotatsu/parsers/MangaLoaderContext.kt) requires HTTP, cookies, source configuration, JavaScript evaluation, bitmap operations and browser actions. Thus the same parser API can underpin Android, Windows, macOS and Linux, but identical source coverage is **not yet demonstrated**. Browser interception and interactive verification require platform implementations; unsupported features should produce an actionable source error.

Recommended structure:

* A `manga` module owning source adapters, models, repository, downloads and Compose reader screens. Music must not depend on manga internals.
* A common `MangaSource` interface: capabilities, browse/search, details, chapters, pages and image-request headers. Represent pagination and language explicitly.
* Source-qualified IDs for series, chapters and progress. Titles alone must never merge two translations or editions.
* Android and desktop host implementations for file selection, image decoding, cookies and browser-assisted sign-in where necessary.
* Local CBZ/image-folder support first; an optional [Komga REST source](https://komga.org/docs/openapi/komga-api/) can expose a user's own server library. Komga requires a user server and credentials; it is not a free public catalogue.
* Built-in network parsers and Android APK-extension compatibility are both included in the user's complete tracker scope. The Android loader remains separate from the shared JVM parser path.

No central Cosmotune server is necessary for local reading or direct website parsers. An optional user-hosted library is a separate choice.

## User experience beside Music and Video

Add **Manga** to the app-mode selector beside Music and Video, with a book icon. Cosmotune currently has `MUSIC`, `VIDEO` and an unavailable `LIBRARY` mode in `AppModes`; the host routing and exhaustive mode mappings must be updated together. Do not ship a clickable entry that merely falls through to Music.

Inside Manga:

1. **Home:** continue reading, recently added chapters, favourites and source-labelled discovery.
2. **Browse:** installed/enabled sources, language filters, source search and bounded global search with cancellation.
3. **Series:** cover, description, authors, language, chapter list, library action and download status.
4. **Reader:** right-to-left and left-to-right paging, vertical webtoon mode, fit width/page, zoom, chapter navigation and persistent progress.
5. **Library/downloads:** collections, unread counts, resumable downloads, storage limit and clear-cache action.

Music should continue while reading if the user left it playing. Leaving Video for Manga should stop hidden video playback and release its decoder. Each mode keeps its own navigation and search state. Reading controls must respect system insets and never obscure page content without a way to hide them.

## Storage, speed and validation

Keep manga covers, pages and downloaded chapters outside the installer and outside the recommendation model. Use a bounded image cache and a small page prefetch window. Source catalogues should be paged; never download entire catalogues at startup. Initial discovery can use source-provided lists plus local reading history without adding any ML model.

Package-size impact is **unmeasured**; the parser source checkout size does not predict the final APK or installer delta. Compare minified Android ABI packages and desktop archives with and without the dependency before selecting it. Avoid bundling a second browser engine just for Manga if existing platform facilities suffice.

Release gates: source search → details → chapters → image reading on each supported platform; both reading directions and long-strip memory use; restart progress recovery; failed/expired image requests; download cancellation/resume and disk-full recovery; source removal; archive path/size limits; music continuity and video shutdown; keyboard/touch accessibility. Label a source supported only after a real smoke test, not because a parser class exists.

Both inspected projects include GPLv3 licence texts. Preserve applicable notices and source attribution when reusing code. Parser availability does not itself grant permission to redistribute a site's manga; adapters should retain source attribution and respect the access the source grants.

## Next implementation checkpoint

The user subsequently requested every tracker feature in one coordinated 1.0.4 release. The earlier proposal to release Manga separately is superseded. Do not publish the native/watch subset while this work remains incomplete.

## Working implementation and evidence

The new `manga` module is routed by both app hosts beside Music and Video. It includes local CBZ/ZIP and image-folder import, natural page ordering, source-qualified library entries, atomic progress/library persistence, RTL/LTR paging, webtoon scrolling, zoom, keyboard navigation, enabled-source/language selection, catalogue search/pagination, series/chapter pages, manual chapter refresh, offline chapter download/resume and storage/cache controls. Music is left running while reading; the existing mode observer stops hidden Video playback. Those audio/lifecycle behaviours still need device walkthroughs.

The pinned Kotatsu artifact is `com.github.clquwu:kotatsu-parsers-redo:dc223c3d80`, the verified coordinate used by the inspected source. Android bitmap/QuickJS and desktop bitmap/Graal implementations reuse runtime versions already present in Cosmotune. Android JS has a 64 MiB limit, 512 KiB stack and deadline; desktop denies Java host access and limits statements and execution time. Desktop JS does not have a separately isolated hard heap quota. Browser-intercepting sources remain outstanding. Android private APK import, publisher/fingerprint review, settings, removal and modern extension-store browsing are now implemented and compiling; Android emulator fixture verification passed; physical-device/provider checks remain.

Metadata reads are bounded after decompression, including chunked responses. Individual images are capped at 32 MiB; imported archives at 512 MiB and 10,000 entries. Archive entry names are never extracted as filesystem paths. Chapter downloads publish offline metadata only after all pages finish, omit credential headers and reuse completed cache files on retry. Downloaded pages are protected from ordinary cache clearing. Imported comic archives are separate from the page-cache limit.

Android state/cookies use an Android Keystore AES-GCM key; Windows uses the current user's DPAPI. Linux/macOS use private owner-only files. These are app-private state files, not credential-free transfer exports. Komga configuration includes a test-and-save action; authenticated requests reject redirects to avoid forwarding server keys. Komga has fixture HTTP coverage, not a live user-server claim.

The opt-in public Manga Plus English check passed on 2026-10-23: **81 catalogue items → 14 chapters → 18 page links → one 1,506,816-byte image decoded**. No image is bundled. This is one live desktop source check, not a claim that every listed parser works, or that Android/macOS/Linux device execution has been verified. Run it explicitly with `:manga:jvmTest --tests '*MangaLiveSourceTest' '-Pcosmotune.manga.live=MANGAPLUSPARSER_EN'`.

Core tests cover archive containment/order, corrupt import cleanup, content deduplication, restart/concurrent-write progress recovery, image-folder copies, authenticated catalogue/chapter/page mapping, decoded response budgets, redirect key isolation and offline cache persistence. Compose interaction tests open a real imported comic, change page, reopen it, inspect persisted progress and check controls at 400 dp / 1.5× text. Their component screenshots are in `uiTests/build/reports/manga-ui/`; these are not full-device screenshots.

Webtoon completion now requires the decoded final image's bottom, with visible loading/error images preventing premature completion. Scroll offsets within long pages are persisted, flushed on leaving, and restored after the decoded image replaces its short loading placeholder. Next/Previous and keyboard commands scroll a screenful in webtoon mode. A real 2,200-pixel-tall image interaction passed unread-at-top, partial scrolling, reopening at the saved offset and completion at the bottom; the paged-reader interactions still pass. Progress save failures have a visible retry action.

Still outstanding: custom/CMS and interactive-browser sources, real third-party APK execution/ABI checks, broader source tests, accessibility and library-management refinements, final package-size measurements and physical-device checks. The feature tracker remains authoritative; this module is not a completed 1.0.4 release.

## Current extension-store compatibility

A deeper current-API check found that Keiyoushi now publishes a protobuf store at `index.pb`; its old `index.min.json` endpoint contains upgrade notices. This is documented by the [publisher's current setup guide](https://keiyoushi.github.io/docs/guides/getting-started) and [repo metadata](https://raw.githubusercontent.com/keiyoushi/extensions/repo/repo.json). The real protobuf index decoded successfully on 2026-10-23: **1,426 packages, all declaring API 1.6**, with the publisher fingerprint matching the official metadata. This verifies catalogue decoding and publisher identity, not execution of 1,426 APKs.

The Android bridge now incorporates the current [Mihon source API](https://github.com/mihonapp/mihon/tree/d001e6f210e6a077a30dd9a1392573a3225bba2f/source-api) under Apache-2.0, retaining the required older compatibility surface. Original file hashes, adaptations and both licences are in `manga/third-party/`. It accepts explicitly supported 1.4/1.6 metadata, preserves series/chapter memo JSON, handles direct Source and HttpSource implementations, and registers network/cookies/JSON/protobuf/XML/JavaScript capabilities. Missing binary APIs become recoverable source failures.

Private APK imports inspect metadata before an explicit trust action, pin the exact APK hash, keep loaded files read-only, and reject publisher changes or version downgrades. Repository downloads additionally require matching package/version and the repository publisher's signer fingerprint. Store reads have an 8 MiB decoded limit, support gzip/protobuf/JSON and legacy index_v2 migrations, and reject insecure artifact URLs or changed pinned keys. Fixture HTTP tests cover these contracts; no downloaded third-party APK has been executed during the desktop metadata checks.

At the initial reader/extension checkpoint, Android production assembly passed in 4 minutes 9 seconds. All four phone APKs retained matching embedded Wear bytes, package/version/signing-certificate parity, and the unchanged 3,186,092-byte model. ARM64 was 77,208,327 bytes, ARMv7 67,395,027, x86_64 83,801,601 and universal 180,977,139; about 3.34 MB per APK above the preceding native/watch checkpoint. These sizes precede the latest modern-store/XML additions and are not final release sizes.

Android 16 emulator instrumentation passed three tests: signed private APK loading after trust for both Source/HttpSource APIs, series/chapter memo mapping and Keystore progress; changed-APK rejection; and model loading/ranking. The source-update path also retained a disabled source. This uses our own test-only APK, not a real third-party source. An Android private-directory permission error was caught and fixed using platform chmod. The fixture is absent from production assets. The headless read-only emulator was closed after testing.


Android extension preferences now use device-key encrypted, atomically written files under `noBackupFilesDir`, exposed through both the Application/Context source APIs and the AndroidX settings data-store adapter. All primitive types and string sets are preserved, values are copied, oversized transactions are refused, and unreadable originals block replacement. Legacy development preferences migrate only after protected readback succeeds. Manga/provider app files and offline media are now excluded from automatic cloud backup and device transfer because their Keystore keys cannot be restored. This prevents an unusable automatic restore; explicit portable library/settings export remains outstanding. Android production compilation and two dedicated Android emulator checks passed: typed persistence/Keystore encryption/reopening/data-store values/listeners/limits, plus legacy migration and unreadable-file preservation. The dedicated emulator was closed afterward.

## Custom HTML website templates (working tree)

Sources → Custom websites and templates now adds, tests, edits, enables/disables and removes a site on Android and desktop. The shared adapter supports declarative HTML selectors, GET/POST form requests, page/offset/WordPress path pagination, search parameters, detail metadata, chapter lists/AJAX fallback and reader images. Included presets cover WordPress Madara, MangaThemesia and MangaStream families. A JSON editor accepts this native HTML schema and is limited to 128 KiB. It does not execute JavaScript or claim full compatibility with every Tsuki template/API/CMS. JSON response templates, additional CMS adapters and an isolated interactive verification browser are still outstanding.

Configuration is stored with the protected Manga library. Renaming or editing a template preserves the source identity; changing its website requires adding a new source so existing series cannot silently change owners. Navigation and redirects stay on the configured origin, image URLs use a checked transport, private-address access requires the explicit local-network option, and cleartext is limited to that configured local server. Android's external cleartext policy still applies. Template covers are fetched through the Manga repository before Coil decoding, using the same transport and cache as pages. Music-account cookies are not used.

Metadata bodies are bounded to 2 MiB, selectors and result lists are bounded, regex selectors are rejected, and cancelling a source request closes its active HTTP call. A verification response is an actionable source error, never an empty successful catalogue. Four real HTTP/filesystem tests passed Unicode search/pagination, source-qualified stable identities, relative cover/chapter/page mapping, metadata reopening, AJAX POST, foreign redirects/links, byte limits, cancellation, private access and validation. One actual Compose interaction passed add/edit/disable/remove and identity preservation. Both Android production and desktop Kotlin compile. No arbitrary external custom website or browser-only source has been verified by these fixtures.

Android extension pages now preserve GET or POST image requests through the native adapter. Request bodies stay in the adapter's bounded in-memory request map, and are not serialized in MangaPage or downloaded-page records. A missing POST request requires reloading that chapter; it never silently becomes GET. Request bodies are limited to 256 KiB and images to 32 MiB. Transfers close blocked calls and join the writer before cache cleanup or retry. Three real HTTP fixture tests pass POST/body/header preservation, oversized-request refusal and cancellation followed by a successful fresh write. The shared Manga core suite and Android/desktop Kotlin builds pass.

The current extension JSON-response helper now has a bounded implementation with the same JVM parameter order as the pinned API's Json context parameter. The signed test-only APK calls it while browsing and also returns a POST image request. All five Android emulator Manga runtime checks passed, including both source APIs, POST request/header/body preservation, JSON helper linkage, changed-APK rejection, Keystore reader/source preferences and the bundled ranker. No real third-party extension APK or remote POST image server has been executed by these fixture checks.

Global Manga search is now available in the source picker. It searches enabled sources with three concurrent requests, streams source-owned partial results, isolates failures/timeouts and offers Stop search. It searches the first catalogue page (up to 25 series per source, 2,000 results overall); choosing an individual source provides its further pages. Short queries do not fan out, disabled sources are skipped and changing the search cancels earlier work. Three core tests pass concurrency/partial failure, timeout/cancellation, ownership and aggregate limits; an actual Manga interaction passes source labels, unavailable-source feedback and opening the result on its own source. The full Manga UI suite and both native compiles pass. Additional CMS types/browser verification and wider source-management/accessibility work remain separate gaps.

## Real Android extension follow-up (2026-10-24)

A real, publisher-verified Keiyoushi Manga Plus APK **1.6.67**, package `eu.kanade.tachiyomi.extension.all.mangaplus`, SHA256 `206862dfa399c5f1f57d6078cfe97123769adc148ec083426efda8a6ea3031d8`, now passed on the dedicated Android emulator. Its signer matched the documented publisher fingerprint before loading. It exposed nine language sources; the English path returned 100 catalogue entries, six chapters and 53 page references, and one public 787,681-byte image decoded. No account was used and the external APK is never bundled.

This caught real gaps beyond the fixtures: its generated entry `keiyoushi.source.Generated` is outside the Android application ID, and its SDK expects the standard exception/user-agent/verification interception points plus Brotli/Zstandard APIs. Entry classes are now accepted only when their ClassLoader is the reviewed APK's loader, allowing generated names while rejecting host/parent classes before instantiation. A fixture outside the application ID and a parent-class rejection test cover this. Extension clients now retain the protected Manga cookie adapter and upstream exception/user-agent interceptors. The verification interceptor surfaces a browser-required error; it does not claim an automatic browser session. Android Brotli and Zstandard modules match the pinned OkHttp 5.3.2 family.

The Android direct instrumentation runner passed **seven tests together**, including the real package and six controlled source/model/preferences/ownership checks (`build/upstream/manga-complete-direct-runtime.log`). Gradle's UTP collector lost its result connection after the compression dependencies changed; the identical APK installed and ran successfully through `adb shell am instrument`. That collector issue is still recorded separately; it was not reported as a successful Gradle connected test. This establishes one real APK's tested path, not compatibility with all store packages or all browser-dependent sources.
