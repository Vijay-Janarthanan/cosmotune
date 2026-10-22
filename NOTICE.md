# Notice

Cosmotune is free software under the GNU General Public License, version 3 (see `LICENSE`).

It started as a fork of **BitChord** by kushagrasinghx and its contributors,
https://github.com/kushagrasinghx/BitChord, also GPLv3, and much of its playback engine is still
theirs. The full history of that code is in BitChord's repository. Changes made for Cosmotune are listed
in `FORK.md`; the last BitChord commit we have taken is in `UPSTREAM.md`.

Cosmotune is not affiliated with, endorsed by, or connected to BitChord, YouTube or Google.

Third-party components keep their own licences. The desktop app's licence screen lists them, and for code that is part
of this tree the licence text is in the `licenses/` folder or next to the code.

## OpenCanvas

Cosmotune includes the OpenCanvas engine by Vijay Janarthanan (https://github.com/Vijay-Janarthanan/OpenCanvas),
which finds a song's music video and lines it up with the song. OpenCanvas is under the Apache License, version 2.0
(https://www.apache.org/licenses/LICENSE-2.0); its licence file and notice are in `licenses/OpenCanvas-LICENSE.txt`
and `licenses/OpenCanvas-NOTICE.txt`. The engine is in
`shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/canvas/opencanvas/`; its files are kept exactly as in
OpenCanvas's own repository, so that changes can be merged, which is why they carry no licence header; this notice
and the files in `licenses/` stand in for one. Apache-licensed code may be combined into a GPLv3 work, and the combined work,
Cosmotune, is distributed under the GPLv3.

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

## Optional Tor client

Desktop builds include the Tor Expert Bundle 15.0.25 from https://dist.torproject.org/torbrowser/15.0.25/.
Tor is distributed under its upstream BSD-style licence. The complete bundle's component notices
(including Tor, OpenSSL, libevent and bundled transports) are included in the application's
`tor-bundle/docs/` resources and copied alongside the client when enabled. Upstream source and
licence information: https://gitlab.torproject.org/tpo/core/tor and the bundle's `docs/tor.txt`.
Cosmotune is not affiliated with or endorsed by the Tor Project.
