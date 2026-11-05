# Android player concepts

Generated with the built-in image generation tool on 22 October 2026. These are design concepts, not screenshots or verified layout measurements. No application source was changed for these mockups.

The Android device could not be inspected live because running the installed ADB executable was denied in this sandbox. The design review used the current shared player source.

## The issue visible in source

`NowPlayingBackdrop` in `sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/nowplaying/NowPlayingMedia.kt` uses a full-size `CanvasArtworkPlayer` in `CanvasContentMode.CROP`. The portrait composition in `CosmoNowPlaying.kt` draws the controls stack on top of that backdrop. This intentionally treats the video as background imagery, so a subject near the lower portion can sit behind the seek bar and buttons. `NowPlayingLandscapeMedia` similarly crop-fills its media pane. This finding is about Music's canvas player; the separate Video-mode player has its own layout and must be checked separately during implementation.

## Options

| Option | Portrait / half-screen | Landscape / fullscreen | Trade-off |
| --- | --- | --- | --- |
| A — Cinema dock | Bounded video above metadata, seek bar and full transport. | Video viewport above a fixed bottom transport deck. | Most predictable access to controls; the reserved deck makes the picture smaller while controls are visible. Recommended starting point. |
| B — Split stage | Larger upper media stage with an anchored lower control surface. | Video on the left, dedicated control rail on the right. | Strong separation and large touch targets; the side rail reduces the horizontal space available to the picture. |
| C — Theatre focus | Open viewing area with a compact controller and secondary controls on demand. | Unobstructed watching state with controls hidden. | Most viewing space; an extra tap is needed for controls. When controls appear, the video viewport must resize above the controller rather than being covered. |

Every option preserves a clear region for the source picture. Intentional letterboxing or pillarboxing is preferable to obscuring the subject. The generated images communicate composition and visual direction; exact source aspect ratios, insets and hit areas still need to be specified and tested in the chosen implementation.

The palette follows Cosmotune's existing artwork-driven warm wine, apricot and ivory system. Archivo-like condensed titles and a Hanken-like body type keep the family resemblance. Each option shows the same fictional track, **Golden Hour — Mira & The Coast**, with a consistent coastal music-video scene.

## Files

- `a-cinema-dock-portrait.png`
- `a-cinema-dock-fullscreen.png`
- `b-split-stage-portrait.png`
- `b-split-stage-fullscreen.png`
- `c-theatre-focus-portrait.png`
- `c-theatre-focus-fullscreen.png`

## Generation prompt set

### Shared portrait direction

High-fidelity premium Android music-player mockup for existing app Cosmotune. One tall image, one straight-on graphite Android phone with a centred punch-hole camera and a subtle thin frame. Generous equal outer margins on matte charcoal. The UI is the hero; no collage or tiny extra screens. Deep warm burgundy `#281918`, off-black `#171412`, soft ivory `#F7EDE5`, muted apricot `#F2A990`, bronze secondary text. Condensed Archivo-like title and Hanken Grotesk-like body. Rounded consistent 2 px custom icons and 48 dp touch targets. Extremely subtle texture, no neon/purple or glass-card clutter. Android time 9:41 and bottom gesture bar, safe areas intact. Fictional coastal music-video still: woman musician from head to knees, dark jacket, guitar, rocky coast, warm afternoon light, open sea to right. Preserve source subject and edges. Title “Golden Hour”, artist “Mira & The Coast”, time “1:42” / “4:18”. No source-image subtitles or overlaid controls.

### A portrait

Presentation label “A · Cinema dock”. Top app bar: collapse, NOW PLAYING, more. Generous bounded video with 20 dp side insets, 22 dp corners and restrained shadow. All controls below it on solid warm-black ground: separate Full screen action, large left-aligned title/byline, right heart, apricot seek bar and times, spacious shuffle/previous/large circular pause/next/repeat. Final tools Lyrics, Queue and output device. Calm, luxurious and buildable.

### B portrait

Presentation label “B · Split stage”. Top app bar: collapse, COSMOTUNE, output. Upper media stage with source picture fitted in a burgundy viewing region. Crisp division into an anchored matte wine lower control panel with 28 dp top corners. Title and heart, artist and expand, seek bar/times, previous/large rounded-square pause/next with quiet shuffle/repeat. Lyrics / Up next segment at the foot. No transport overlaps the picture.

### C portrait

Presentation label “C · Theatre focus”. Small top app bar with back, track title and more. Open viewing region above a compact bottom controller; fit the complete source picture with restrained burgundy letterboxing and a very low-contrast blurred echo where appropriate. Separate solid controller approximately 160 dp tall: title/byline, large pause on right, seek bar and times, previous / More controls / next, fullscreen action. No permanent queue/lyrics panel and no controls over source.

### Shared fullscreen direction

One wide landscape Android phone, thin graphite frame, punch-hole on left, equal outer margins, same design bible and scene. Preserve the source picture without cropping or stretching. Controls have their own space. No title, seek bar or button sits over the video picture. Use the corresponding portrait image as a reference to preserve visual identity.

### A fullscreen

Presentation label “A · Cinema dock — fullscreen”. Restrained native top strip with back, title/artist and more. Fitted source video in its own viewing region, with quiet pillarboxing if needed. Fixed full-width bottom transport deck: seek track/times, shuffle/previous/large circular pause/next/repeat, output and exit fullscreen. Video ends above the deck.

### B fullscreen

Presentation label “B · Split stage — fullscreen”. Left 72 percent uninterrupted fitted-video stage; right 28 percent fixed warm-burgundy control rail with a subtle dividing rule. Rail has collapse/more, title/byline/heart, seek/times, previous/rounded-square pause/next, Lyrics / Up next, output and exit fullscreen. Simplify secondary controls to maintain readable labels.

### C fullscreen

Presentation label “C · Theatre focus — controls hidden”. Show the unobstructed watching state: maximal fitted source picture, rich near-black burgundy letterbox/pillarbox surround, native system bars and all app transport hidden. No faint buttons over the image. On interaction the controller would appear in reserved space, but this image intentionally shows the pure watching state.
