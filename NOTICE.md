# Notice

Cosmotune is free software under the GNU General Public License, version 3 (see `LICENSE`).

It started as a fork of **BitChord** by kushagrasinghx and its contributors,
https://github.com/kushagrasinghx/BitChord, also GPLv3, and much of its playback engine is still
theirs. The full history of that code is in BitChord's repository. Changes made for Cosmotune are listed
in `FORK.md`; the last BitChord commit we have taken is in `UPSTREAM.md`.

Cosmotune is not affiliated with, endorsed by, or connected to BitChord, YouTube or Google.

Third-party components keep their own licences; the in-app licence screen lists them.

## OpenCanvas

Cosmotune includes the OpenCanvas engine by Vijay Janarthanan (https://github.com/Vijay-Janarthanan/OpenCanvas),
which finds a song's music video and lines it up with the song. OpenCanvas is under the Apache License, version 2.0
(https://www.apache.org/licenses/LICENSE-2.0); its licence file and notice are in `licenses/OpenCanvas-LICENSE.txt`
and `licenses/OpenCanvas-NOTICE.txt`. The engine is in
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/canvas/opencanvas/`; its files are kept exactly as in
OpenCanvas's own repository, so that changes can be merged, which is why they carry no licence header; this notice
and the files in `licenses/` stand in for one. Apache-licensed code may be combined into a GPLv3 work, and the combined work,
Cosmotune, is distributed under the GPLv3.
