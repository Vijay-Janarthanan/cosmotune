# Notice

Cosmotune is free software under the GNU General Public License, version 3 (see `LICENSE`).

It started as a fork of **BitChord** by kushagrasinghx and its contributors,
https://github.com/kushagrasinghx/BitChord, also GPLv3, and much of its playback engine is still
theirs. The full history of that code is in BitChord's repository. Changes made for Cosmotune are listed
in `FORK.md`; the last BitChord commit we have taken is in `UPSTREAM.md`.

Cosmotune is not affiliated with, endorsed by, or connected to BitChord, YouTube or Google.

Third-party components keep their own licences. The desktop app's licence screen lists them, and for code that is part
of this tree the licence text is in the `licenses/` folder or next to the code.

The Android TV and browser companion ports include code from the same GPLv3 upstream at
https://github.com/kushagrasinghx/BitChord/tree/edc1e6f36febf71a118e649411d2fc21b70ead45.
Original source hashes, file links and adaptations are recorded in `tv/UPSTREAM_IMPORT.json` and
`web/UPSTREAM_IMPORT.json`. The browser helper has a separate Cosmotune identity; the original extra fonts,
icons and development extension key were not imported. Browser release archives include their installed
third-party package notices and licence texts; `tools/package-web.py` produces them with the packages. Three of the
browser player's YouTube playback files (`web/app/src/playback/youtube/botguard.ts`, `shakaHelpers.ts` and
`ShakaPlayerAdapter.ts`) are ported from LuanRT's googlevideo example and Kira (https://github.com/LuanRT/googlevideo,
https://github.com/LuanRT/kira, MIT License, copyright LuanRT); each file's header names its source.

## OpenCanvas

Cosmotune includes the OpenCanvas engine by Vijay Janarthanan (https://github.com/Vijay-Janarthanan/OpenCanvas),
which finds a song's music video and lines it up with the song. OpenCanvas is under the Apache License, version 2.0
(https://www.apache.org/licenses/LICENSE-2.0); its licence file and notice are in `licenses/OpenCanvas-LICENSE.txt`
and `licenses/OpenCanvas-NOTICE.txt`. The engine is in
`shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/canvas/opencanvas/`; its files are kept exactly as in
OpenCanvas's own repository, so that changes can be merged, which is why they carry no licence header; this notice
and the files in `licenses/` stand in for one. Apache-licensed code may be combined into a GPLv3 work, and the combined work,
Cosmotune, is distributed under the GPLv3.

OpenCanvas subject framing also includes the YuNet model from OpenCV Zoo (MIT) and YOLOX-Nano from
Megvii-BaseDetection/YOLOX (Apache-2.0). Their upstream licence texts are retained in
`licenses/OpenCanvas-YuNet-LICENSE.txt` and `licenses/OpenCanvas-YOLOX-LICENSE.txt`, with exact model
sources and SHA-256 values in `shared/src/jvmSharedMain/resources/models/PROVENANCE.md`.

## Material Color Utilities

Cosmotune picks the main colour of a cover with MaterialKolor's Kotlin port of Google's Material Color Utilities
(`com.materialkolor:material-color-utilities`, https://github.com/jordond/materialkolor). The library's POM names the MIT
licence (copyright 2025 Jordon de Hoog), and its README puts the module `material-color-utilities`, which is Google's code
(https://github.com/material-foundation/material-color-utilities) converted to Kotlin, under the Apache License, version 2.0
(copyright 2021 Google LLC). Both licence texts are in `licenses/`: `MaterialKolor-LICENSE.txt` and
`MaterialColorUtilities-LICENSE.txt`. Both licences may be combined into a GPLv3 work.

## Archivo and Hanken Grotesk

The new look sets its titles in **Archivo** (copyright 2020 The Archivo Project Authors, https://github.com/Omnibus-Type/Archivo) and its
text in **Hanken Grotesk** (copyright 2021 The Hanken Grotesk Project Authors, https://github.com/marcologous/hanken-grotesk). Both are
variable fonts under the SIL Open Font License, version 1.1, bundled unmodified, exactly as published in the Google Fonts repository
(https://github.com/google/fonts, `ofl/archivo` and `ofl/hankengrotesk`); they are in `sharedUi/src/commonMain/composeResources/font/`.
The licence texts are in `licenses/Archivo-OFL.txt` and `licenses/HankenGrotesk-OFL.txt`. The fonts stay under the OFL; they are
not sold on their own, and the combined program, Cosmotune, is distributed under the GPLv3.

## Video mode

Video mode, the second mode of the profile drawer, reads a video site's public pages without an account and plays what it
finds. It uses the libraries and the data below.

### NewPipeExtractor

Video mode finds videos, channels, playlists, comments and streams with NewPipeExtractor
(https://github.com/TeamNewPipe/NewPipeExtractor), version v0.26.3, by the NewPipe team and its contributors. Music already used
it to find a song's stream; there is one copy of it in the app, and both modes use it. It is licensed GPL-3.0-or-later: the licence
file is the GNU General Public License, version 3, and the source headers allow version 3 or any later version. It is combined into
Cosmotune, which is distributed under the GPLv3. Its source is at the address above, at the tag v0.26.3.

### libmpv

The desktop app plays video with libmpv (https://mpv.io/). Cosmotune does not build it: it takes it, unchanged, from the runtime
packages of the mediamp project (`org.openani.mediamp:mediamp-mpv-runtime-<platform>`, version 0.5.1,
https://github.com/open-ani/mediamp, Apache-2.0) on Maven Central. In those packages libmpv is LGPL-2.1-or-later on Windows and
macOS and GPL-3.0 on Linux. They also carry the libraries libmpv is built from (FFmpeg, libass, libplacebo, FreeType, HarfBuzz,
OpenSSL and others), each under its own licence. The first time a video is played on the desktop the app copies the libraries into
its cache folder (`<cache>/cosmotune/mpv/<version>/`) and loads libmpv from there as a separate shared library; it is not linked
into the app's own code. The GPLv3 of Cosmotune allows combining with LGPL-2.1-or-later and GPL-3.0 code.

### SponsorBlock, DeArrow and Return YouTube Dislike

Uses SponsorBlock data from https://sponsor.ajay.app/ under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/).
The segments are the work of SponsorBlock's contributors; Video mode asks for them with the first four characters of a hash of the
video id, and skips, mutes or marks what they describe. SponsorBlock is by Ajay Ramachandran.

Uses DeArrow data from https://sponsor.ajay.app/ under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/). The
titles and thumbnails (the thumbnails come from https://dearrow-thumb.ajay.app/) are the work of DeArrow's contributors. DeArrow is
by Ajay Ramachandran; its site is https://dearrow.ajay.app/.

Dislike counts come from Return YouTube Dislike (https://returnyoutubedislike.com/): archived counts and an estimate made from the
votes of its users, read from its public API (https://returnyoutubedislikeapi.com/) within the limits it states.

CC BY-NC-SA 4.0 lets the data be shared with credit, for non-commercial use, under the same licence. Cosmotune is free and shows
no ads. The data is not code and is not combined into the program: it is read over the network and shown. Video settings credit the
three services, and each of them can be switched off there; Video mode works without them.

### Test files

Video mode's importer tests read the files in `shared/src/jvmTest/resources/video/importers/`, which were written for Cosmotune.
One optional test, `ThirdPartyFixturesTest`, also reads the files that NewPipe and NewPipeExtractor keep for their own tests
(GPL-3.0, by the NewPipe team and its contributors); those files are not part of this repository. A copy placed in
`shared/src/jvmTest/resources/video/importers/` stays under GPL-3.0, with its authors' notices, as Cosmotune is.

## Manga source parsers

Manga mode uses the GPL-3.0 Kotatsu parser fork at
https://github.com/Kotatsu-Redo/kotatsu-parsers-redo/tree/dc223c3d8098415b29cf1acfedc401a88d01c9fe,
published as `com.github.clquwu:kotatsu-parsers-redo:dc223c3d80`. Copyright belongs to its upstream
authors and contributors. The parser architecture was reviewed through the GPL-3.0 Tsuki project at
https://github.com/TsukiAndroid/Tsuki/tree/89b8d9cf6bb53303f340505f46c2ad2c1c8ac5b7.
Cosmotune's shared adapter, stores and reader are implemented in this repository's `manga/` module.
The Android source API also incorporates Mihon source-api at revision
`d001e6f210e6a077a30dd9a1392573a3225bba2f`, under Apache-2.0. Its original
licence is preserved in `manga/third-party/LICENSE.mihon`; source hashes and changes are recorded
in `MIHON_SOURCE_API.json`. Tsuki-derived compatibility files retain their GPL-3.0 provenance.
Source websites and their comics retain their own rights and availability; no comics are bundled.

On Android the Mihon bridge also needs the runtime libraries that Mihon extensions are built against:
Injekt (`uy.kohesive.injekt:injekt-core:1.16.1`, https://github.com/kohesive/injekt, MIT, by Jayson Minard),
RxJava (`io.reactivex:rxjava:1.3.8`, https://github.com/ReactiveX/RxJava, Apache-2.0) and RxAndroid
(`io.reactivex:rxandroid:1.2.1`, https://github.com/ReactiveX/RxAndroid, Apache-2.0), and xmlutil
(`io.github.pdvrieze.xmlutil:core-android` and `serialization`, version 1.0.2, https://github.com/pdvrieze/xmlutil,
Apache-2.0, by Paul de Vrieze). MIT and Apache-2.0 code may be combined into a GPLv3 work.

## JSON-java and AndroidSVG

On the desktop, the artist page lookups (Apple Music artwork and artist signatures) and the Manga parsers read JSON with
JSON-java (`org.json:json:20240303`, https://github.com/stleary/JSON-java), whose POM names its licence as Public Domain.
It is bundled unmodified; the Android and TV builds use the platform's own `org.json` instead.

Artist signatures in SVG are drawn with Coil's SVG module (`io.coil-kt.coil3:coil-svg:3.0.4`, Apache-2.0), which on Android
uses AndroidSVG (`com.caverock:androidsvg-aar:1.4`, https://github.com/BigBadaboom/androidsvg, by Paul LeBeau); its POM
names the Apache License, version 2.0.

## Watch installer

The phone and TV apps install and update the Wear OS app over the watch's wireless debugging with libadb-android
(`com.github.MuntashirAkon:libadb-android:3.1.1`, https://github.com/MuntashirAkon/libadb-android, by Muntashir
Al-Islam). Its POM names no licence; its README states that it is dual licensed under GPL-3.0-or-later or Apache-2.0, and
Cosmotune uses it under either, both being compatible with the GPLv3. It brings spake2-android
(`com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1`, https://github.com/MuntashirAkon/spake2-java), whose POM
names the GNU Lesser General Public License, version 3.0, for the pairing code exchange. The apps also bundle Conscrypt
(`org.conscrypt:conscrypt-android:2.5.3`, https://github.com/google/conscrypt, Apache-2.0), which libadb-android uses for the
TLS of its pairing and connection, and Bouncy Castle (`org.bouncycastle:bcpkix-jdk18on:1.85` with its `bcprov` and `bcutil` modules,
https://www.bouncycastle.org, under the Bouncy Castle Licence, an MIT-style licence), with which the phone makes the
certificate it pairs with. The `bcprov-jdk15to18` that libadb-android asks for is left out, so that only one copy of Bouncy
Castle is bundled. LGPL-3.0, Apache-2.0 and MIT-style code may be combined into a GPLv3 work.

## Provider package compatibility

The provider runtime adapts the guest-only compatibility factory from Lokal at revision
`3199796ef90743a5abbac6ffc96495beec855a4e`, copyright (c) 2026 sipbuu, under MIT.
The licence and original source hash are preserved in `providers/third-party/`.
Cosmotune implements its own Kotlin host and platform script-engine bindings. Provider packages
remain separate software with their own licences, authentication and availability requirements.

## Optional Tor client

Desktop builds include the Tor Expert Bundle 15.0.25 from https://dist.torproject.org/torbrowser/15.0.25/.
Tor is distributed under its upstream BSD-style licence. The complete bundle's component notices
(including Tor, OpenSSL, libevent and bundled transports) are included in the application's
`tor-bundle/docs/` resources and copied alongside the client when enabled. Upstream source and
licence information: https://gitlab.torproject.org/tpo/core/tor and the bundle's `docs/tor.txt`.
Cosmotune is not affiliated with or endorsed by the Tor Project.

Android extension networking also uses the Apache-2.0 OkHttp Brotli/Zstandard modules at 5.3.2,
Google's MIT-licensed Java Brotli decoder, and Apache-2.0 Square zstd-kmp 0.4.0 (with its Zstandard native decoder).
Their upstream projects are https://github.com/square/okhttp, https://github.com/google/brotli,
https://github.com/square/zstd-kmp and https://github.com/facebook/zstd.
The Zstandard source provides the BSD-3-Clause licence option. No external manga extension APK is redistributed.
