# 1.0.4 device checklist

The checks that automated tests could not do: they need a real phone, watch, TV, computer, account or piece of hardware. Each item names its [tracker](../FEATURE_TRACKER.md) row; when a check passes or fails, write the result into that row. Use the release packages (or CI's signed build), not a local debug build, unless the item says otherwise. Never paste private addresses, tokens or account names into the tracker.

## Before the devices: CI

- [ ] **BC-35** Run the Check workflow by hand on the 1.0.4 branch. *Expected:* the Windows job compiles `cosmotune_smtc.dll` and the window library (first compile of the ported SMTC seek and borderless full-screen code) and the jar check finds `native/cosmotune_smtc.dll`.
- [ ] **BC-36** Same run, backend job. *Expected:* `go build`, `go vet` and `go test ./...` pass, including `TestWebSocketTokenAsSubprotocol`.
- [ ] **CT-12** Same run, Linux and macOS jobs and the web job. *Expected:* all tests pass, the distributable builds, the browser packages verify.

## Android phone

Use a phone with Android 14 or newer where possible, and one older phone (Android 9 to 13) for the version-specific items.

- [ ] **CT-12** Install the 1.0.4 phone APK over 1.0.3. *Expected:* installs as an update, keeps your library and settings, About shows 1.0.4.
- [ ] **LK-20 / BC-21** On a phone that had a Last.fm, ListenBrainz or Spotify (sp_dc) sign-in in 1.0.3, update and open the app. *Expected:* still signed in; the values are no longer in the backed-up settings (a fresh install restored from a cloud backup asks you to sign in again).
- [ ] **BC-01** Open an artist with an Apple Music motion header on Wi-Fi. *Expected:* the photo, then a silent moving loop. With reduced motion on, or on mobile data without "Play animated cover over cellular", only the still photo.
- [ ] **BC-03** Play a FLAC file and a streamed song in the new player. *Expected:* the format line shows the decoder's codec, rate, bit depth and bitrate.
- [ ] **BC-04** Turn on queue history; check the classic player, the side panel and the new-look queue. *Expected:* recently played songs above Now playing in each.
- [ ] **BC-05** Tap a song in the middle of an album, then a playlist row, then Shuffle. *Expected:* playback starts at the tapped song, the queue grows as you go, and after force-stopping the app the queue comes back on the same song. Repeat it with repeat on, and once while a Cast session starts.
- [ ] **BC-07** Drag one equalizer band up and down quickly. *Expected:* the other bands stay where they are; boosted bands do not clip audibly.
- [ ] **BC-17** Turn on "hide status bar" and open the player on a phone with a camera cutout, on Android 9 to 14 and on 15 or newer, portrait and landscape. *Expected:* the bar hides, a swipe shows it briefly, nothing jumps, the cutout area is used and restored on leaving.
- [ ] **BC-18** Put the 4x1 widget on the home screen, sign out and back in. *Expected:* the Like button disappears when signed out and returns after sign-in. Open Replay, This week. *Expected:* plays from Monday to today, also across a month boundary.
- [ ] **BC-20** Play a tagged local album and an album from Downloads; delete one download; upload one to WebDAV if a server is configured. *Expected:* release track order, only the playing row lit, delete and upload work.
- [ ] **BC-30 / BC-31** Search with the Add-ons source using an add-on that offers stereo and Atmos rows, and a provider package. *Expected:* results from both, quality badges on the rows, only the playing row lit; nothing is sent while typing.
- [ ] **BC-32** Fling a Home shelf and an artist carousel. *Expected:* it stops on a card.
- [ ] **BC-33** Play songs with synced Hebrew and Arabic lyrics, app in English, then app in Hebrew. *Expected:* each line on its own side, word sweep from the right for right-to-left lines.
- [ ] **BC-34** Switch to the classic look and drag the glass tab indicator past both ends. *Expected:* it stops at the first and last tab.
- [ ] **MN-01** With a provider package that has a Home feed enabled, open Home and pull to refresh. *Expected:* the provider's regional shelves appear; a refresh that fails keeps the old ones.
- [ ] **MN-02** Measure radio ranking time on a mid-range phone (a debug log or profiler). *Expected:* about the emulator's 11 ms median or less; then judge whether suggestions get more relevant after a few days of skips and plays.
- [ ] **MN-06** Run Listening tools, Library review, Analyse local audio on a few local files; turn on normalization and play them. *Expected:* loudness, peak and album gain shown; loud and quiet tracks play at similar levels. Also run the instrumentation test `LibraryAnalysisDeviceTest`.
- [ ] **MN-08** Merge two identical files with "move to trash". *Expected:* Android asks for consent, the copy goes to the trash, the library refreshes.
- [ ] **MN-09** Lyrics share, Save as .lrc. *Expected:* the document picker opens and the saved file has timed lines.
- [ ] **LK-05** Queue several downloads, force-stop the app mid-transfer, reopen. *Expected:* the queue returns, interrupted items can be retried at the quality you asked for.
- [ ] **LK-08** Analyse a lossy and a lossless local file, then "Get lossless copies from sources". *Expected:* correct codec and quality on each; only a verified lossless copy is offered as the upgrade; no file is overwritten.
- [ ] **LK-09** In Local Music and Downloads, add columns and filter by quality and genre. *Expected:* columns and filters apply and are remembered per list.
- [ ] **LK-10** Look at a local album with an album-artist tag but no artist tag. *Expected:* the album artist is used.
- [ ] **LK-12** Move a downloaded file to another folder of the library under the same name, restart. *Expected:* the download points at the moved file; a deleted one shows as failed with Retry.
- [ ] **MG-01** Play music, open Manga and read for a few minutes; then play a video, switch to Manga. *Expected:* music keeps playing while reading; the video stops and its decoder is released.
- [ ] **MG-02** Import a large CBZ (hundreds of pages) and a series folder with one subfolder per chapter from the document tree. *Expected:* chapters in order, titles from `ComicInfo.xml` when present.
- [ ] **MG-03** Open a source whose parser needs JavaScript. *Expected:* the catalogue loads, or an actionable message.
- [ ] **MG-04 / MG-05** Read with TalkBack on; pinch, pan and double-tap a page; read a long webtoon on a low-RAM phone. *Expected:* controls are announced, zoom feels right, no out-of-memory.
- [ ] **MG-06** Download a few chapters, then fill the storage until a download fails. *Expected:* a clear failure, finished chapters still read offline.
- [ ] **MG-07** Install two or three more real extension APKs from a Keiyoushi store on a physical phone. *Expected:* review screen before loading, catalogue, chapters and pages work.
- [ ] **MN-04** Install a provider package that only offers file downloads, play a track, then disable the provider. *Expected:* it plays; after disabling, the temporary copy is gone. Run the provider instrumentation tests on a device.

## Wear OS watch

Needs a Wear OS 3 or newer watch (a Galaxy Watch 4 class watch for the speed checks) and a phone with the release build.

- [ ] **BC-12 / BC-16** On the phone, Settings, Cosmotune for Wear OS, Install, with the watch's Wireless debugging pairing code. *Expected:* the watch app installs; the phone then shows "Up to date".
- [ ] **BC-16** Install an older watch build, then press Update on the phone. *Expected:* the update is sent over the Data Layer and the watch asks to install (later updates are silent once Cosmotune installed it).
- [ ] **BC-12** Pair a dev phone build with a dev watch build and a release phone with a release watch. *Expected:* each pair connects; a dev watch never connects to a release phone.
- [ ] **BC-13** Use each control: play/pause, next, previous, hold to seek, Love, shuffle, repeat, an Up Next jump. *Expected:* the phone, its notification and widgets follow each change.
- [ ] **BC-14** Turn the bezel and the crown; let the watch go to always-on. *Expected:* one volume step per bezel detent, smooth crown steps with haptic ticks, a dim always-on player that shifts slightly against burn-in, a smooth progress ring.
- [ ] **BC-15** Choose "this watch" in the Output picker, with the watch speaker and with Bluetooth headphones; turn the screen off; seek; switch back to the phone. *Expected:* audio continues on the watch, seeking works over Bluetooth, the phone takes over at the same point.
- [ ] **BC-11 / BC-15** While casting, switch the output to the watch and back. *Expected:* one receiver at a time, no double playback.

## Android TV

Needs an Android TV or Google TV (Android 8.0 or newer) with a D-pad remote.

- [ ] **BC-24** Install the TV APK for the TV's processor; go through setup with the remote only. *Expected:* every screen reachable by D-pad, focus always visible.
- [ ] **BC-24** Make two profiles, one with a PIN; enter wrong PINs until the cooldown; reboot the TV. *Expected:* the cooldown continues after the reboot instead of starting again; the right PIN opens the profile.
- [ ] **BC-24** Play music from a USB stick and through a USB DAC; turn spatial audio on; play a video at a different frame rate. *Expected:* USB music plays, the DAC is used, spatial audio is audible only when on, the display switches refresh rate when the setting allows.
- [ ] **LK-18** Settings, Connections, Phone remote: scan the QR with a phone camera on the same network. *Expected:* play/pause, seek, volume, shuffle, repeat, Love, Up Next and search work from the phone; opening account settings on the TV disconnects the phone.
- [ ] **CT-12** Let the TV app check for updates against the release. *Expected:* it offers only a CosmotuneTV package for its own processor, never the phone or watch APK.

## Windows

- [ ] **CT-12** Install the 1.0.4 setup.exe (and separately the MSI) over 1.0.3. *Expected:* upgrades in place, one Start menu entry, settings kept.
- [ ] **LK-20** After the update, check that Last.fm and ListenBrainz are still signed in. *Expected:* signed in; the tokens are no longer readable in the registry as plain text.
- [ ] **BC-35** Drag the scrubber in the Windows media flyout and on the lock screen. *Expected:* playback seeks; nothing happens while a party host holds the controls.
- [ ] **BC-35** Press F11 (or the full-screen button) with the taskbar set to stay on top, then leave full screen. *Expected:* the player covers the taskbar with no border; the window returns to its place and material.
- [ ] **BC-35** Edit order on one of your own playlists, move rows, Save. *Expected:* the new order shows on YouTube Music too.
- [ ] **BC-35** In a build without a Last.fm key, sign in to Last.fm with your own API key and secret. *Expected:* sign-in works and scrobbles arrive.
- [ ] **BC-08** Turn on Automix with 16-bar phrases and "Automix on skip"; skip mid-song. *Expected:* a blended transition on the beat, no clicks.
- [ ] **BC-09** Open the Listen Together panel and leave it open with a party server set. *Expected:* the status refreshes while the panel is open.
- [ ] **LK-19** Open the mini player from the tray and with Ctrl+Shift+M. *Expected:* a small always-on-top window with working controls.
- [ ] **LK-14** Replay, Share card, save. *Expected:* a 1080 x 1920 PNG with your top songs and artists.
- [ ] **LK-18** Listening tools, Phone remote: scan the QR with a phone. *Expected:* the phone controls the desktop; Stop disconnects it.
- [ ] **MN-05** Open the audio pipeline readout with and without EQ. *Expected:* "Unchanged by Cosmotune" with the system mixer named when nothing alters the sound, otherwise the first stage that does; never "Bit-perfect".
- [ ] **MG-01** Read Manga while music plays. *Expected:* music continues, the music keys stay out of the reader's way.

## macOS (Apple Silicon and Intel)

Do each item on both an Apple Silicon and an Intel Mac.

- [ ] **CT-12** Install the DMG (and separately the PKG) over 1.0.3. *Expected:* opens after the usual "Open Anyway"; settings kept.
- [ ] **CT-06** Play a video in Video mode. *Expected:* video and sound play (still unverified on macOS; if not, report it, it is a known limit).
- [ ] **LK-20** After the update, check Last.fm and ListenBrainz sign-in. *Expected:* still signed in; the credentials are in owner-only files (folder 0700, files 0600) in the app data folder.
- [ ] **LK-19** Open the mini player with Command+Shift+M. *Expected:* stays above other windows.
- [ ] **MN-05** Check the audio pipeline readout. *Expected:* as on Windows.
- [ ] **MG-01** Read Manga while music plays. *Expected:* music continues.
- [ ] **MN-07** Add a Subsonic server and restart. *Expected:* the server is remembered (the credential is in an owner-only file, not the keychain).

## Linux

- [ ] **BC-19** In GNOME and in KDE, use the media panel and `playerctl position`, `playerctl metadata`, `playerctl position 5+`; press the media keys. *Expected:* the panel shows the song with artwork, the position is live, a relative seek moves 5 s on, media keys work.
- [ ] **LK-20** After updating from 1.0.3, check sign-ins. *Expected:* still signed in; owner-only files as on macOS.
- [ ] **LK-19** Open the mini player under your window manager. *Expected:* always on top (behaviour may differ per window manager; note which one).
- [ ] **MN-05** Check the audio pipeline readout. *Expected:* as on Windows.

## Browser with the helper extension

- [ ] **BC-25** Host the release's web zip on an origin the release was built for (or run it locally at `http://localhost:5173`); load the Chromium helper unpacked and the Firefox helper as a temporary add-on. *Expected:* the site's Extension page shows the helper's protocol and version.
- [ ] **BC-25** Connect YouTube Music and Spotify from the helper. *Expected:* your libraries load; disconnecting removes access.
- [ ] **BC-25** Play songs, use the queue and radio, open lyrics. *Expected:* live playback with timed lyrics.
- [ ] **BC-25 / BC-36** Join a party from the browser with a real party server. *Expected:* the browser connects with the token as a WebSocket subprotocol and stays in sync.

## Accounts and services

- [ ] **BC-02** Configure a real artist signature API in Settings. *Expected:* a malformed address is refused, a working one is saved, signatures show at the end of artist pages.
- [ ] **BC-06** Scroll long YouTube Music search results, Home and a large playlist. *Expected:* more items load at the end.
- [ ] **BC-10 / LK-02 / LK-03** Add a real HTTP add-on with settings and album/artist pages. *Expected:* settings save and reach requests, pages open, Atmos is asked for only when the device and you want it.
- [ ] **BC-21** Spotify sign-in, then Disconnect. *Expected:* signs in in the overlay; disconnect signs out of Spotify only.
- [ ] **LK-04** Add the public Mornye repository, review and install a package, then an update. *Expected:* review before install; an update is offered only for a newer version.
- [ ] **LK-06** Turn on Last.fm radio suggestions with a real account. *Expected:* radio includes similar tracks and your top artists; the session key is never sent.
- [ ] **LK-11** Link a public YouTube Music playlist, change it on YouTube Music, preview and apply. *Expected:* additions placed, removals kept as placeholders.
- [ ] **LK-20** Sign in to Last.fm and ListenBrainz on phone and desktop, listen offline, reconnect. *Expected:* scrobbles and listens are sent once, in order.
- [ ] **MN-03** Download from a provider that claims lossless. *Expected:* the label matches the delivered bytes.
- [ ] **MN-07** Add a Navidrome or Gonic server on phone and desktop. *Expected:* a wrong password is refused, search, albums and streaming work.
- [ ] **MG-09** Add a Komga server (https on Android) with an API key, then with a user name and password. *Expected:* catalogue, covers and pages load; a wrong key is refused.
- [ ] **BC-36** Deploy the updated party server with `setup.sh`. *Expected:* `/healthz` answers, parties work from the phone, desktop and browser.

## USB DAC

Needs a USB DAC that shows its input sample rate.

- [ ] **MN-05** Android 14 or newer: turn on "Bit-perfect USB output", set Output precision to 16-bit, play a 16-bit/44.1 kHz FLAC. *Expected:* the DAC shows 44.1 kHz, Settings and the pipeline say bit-perfect confirmed, `adb shell dumpsys media.audio_flinger` lists a bit-perfect output. Turn it off: the normal mixer returns.
- [ ] **MN-05** While bit-perfect is held, play a notification sound and press the volume buttons. *Expected:* note what Android does (other sounds may be muted, volume may be fixed).
- [ ] **MN-05** Play a 24-bit file. *Expected:* on a DAC without float support, "no matching format" and the mixed path.
- [ ] **MN-05** On Android 13, and on a Bluetooth or speaker route. *Expected:* the setting falls back with its reason shown.
- [ ] **BC-24** The TV with a USB DAC (see Android TV).

## Chromecast

Needs a Chromecast or Google TV on the same network.

- [ ] **BC-11 / LK-16** Cast from the phone, skip on the phone and on the receiver. *Expected:* the receiver plays, both skips work, the phone shows the receiver's position.
- [ ] **LK-16** Disconnect choosing "this phone". *Expected:* the song continues on the phone at the same second.
- [ ] **LK-16** Switch the receiver off while it plays. *Expected:* the phone stays paused at the same second.
- [ ] **LK-16** Try to cast a FLAC above 96 kHz and an ALAC file. *Expected:* "cannot be cast" message, nothing sent.
- [ ] **BC-05** Start a Cast session, then tap an album row. *Expected:* the album plays on the receiver (the bounded queue is not used while casting).
