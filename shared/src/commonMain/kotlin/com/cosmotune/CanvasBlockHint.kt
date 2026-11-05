package com.cosmotune

/**
 * Whether the player should say "video is paused on mobile data" (with a button to allow it).
 *
 * True exactly when the player's existing guard is what keeps a video away: the animated-cover switch is on
 * ([canvasOn]), the phone is on a metered connection ([onCellular]), "play over cellular" is off
 * ([overCellular] false), and a lookup was wanted for this track ([lookupWanted]). It is the guard in
 * `rememberCanvasArtwork`, `canvasAllowedNow = canvasEnabled && (meteredConnection != true || canvasOverCellular)`,
 * seen from the other side, so the hint can never show where the video would not have been looked up anyway.
 *
 * @param overCellular the setting `canvasOverCellular` ("Play animated cover over cellular"); true means allowed.
 * @param onCellular the connection is metered, the same test the guard uses (`meteredConnection == true`):
 *   mobile data, and also a Wi-Fi that Android marks as metered, such as a phone hotspot. No active network is false.
 * @param canvasOn the animated-cover switch (`animatedCanvas`).
 * @param lookupWanted a lookup for this track was suppressed by the guard, that is, it would have gone ahead without
 *   it (see [canvasLookupWanted]). This is deliberately not "a video exists": nobody can know that before looking
 *   it up, and the look-up is exactly what the guard prevents. (The brief's name for it was `hasVideo`.)
 */
fun canvasBlockedByMobileData(
    overCellular: Boolean,
    onCellular: Boolean,
    canvasOn: Boolean,
    lookupWanted: Boolean,
): Boolean = canvasOn && lookupWanted && onCellular && !overCellular

/**
 * Whether a canvas lookup is made at all for a track once nothing blocks it. These are the first lines of
 * `CanvasRepository.canvasFor`, which answers "no canvas" without any request for a local file that has no
 * catalogue identity (no [videoId]) and for a song with no title or no artist. Keep the two in step.
 *
 * @param isLocalFile the song comes from a local file or a downloaded file (`localUri` or `localPath` is set). A
 *   downloaded song still carries its [videoId], so it does want a lookup.
 */
fun canvasLookupWanted(videoId: String, isLocalFile: Boolean, title: String, artist: String): Boolean =
    !(videoId.isBlank() && isLocalFile) && title.isNotBlank() && artist.isNotBlank()

/**
 * Whether the music video of a track that has just started is looked up in the background, before anyone opens the
 * player (`CanvasRepository.prefetch`, called on every track change, screen off included). Each such lookup reads a
 * search page and up to seven file headers, so it never runs on a metered connection: not even when "play over
 * cellular" is on, because that setting allows a video the person is looking at, not one found for every song in turn.
 * With cellular allowed, the lookup then happens when the player is on screen.
 *
 * @param animatedCanvas the animated-cover switch (`animatedCanvas`).
 * @param openCanvasEnabled the OpenCanvas switch (`openCanvasEnabled`).
 * @param metered the connection is metered (`meteredConnection == true`): mobile data, or a Wi-Fi that Android marks as
 *   metered. No active network is false.
 * @param hasIds the song has a video id and a title and an artist to search on.
 */
fun prefetchAllowed(animatedCanvas: Boolean, openCanvasEnabled: Boolean, metered: Boolean, hasIds: Boolean): Boolean =
    animatedCanvas && openCanvasEnabled && !metered && hasIds
