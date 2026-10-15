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
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/opencanvas/`; its files are kept exactly as in
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
