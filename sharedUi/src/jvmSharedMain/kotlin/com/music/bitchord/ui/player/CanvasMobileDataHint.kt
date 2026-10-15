package com.music.bitchord.ui.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.canvasBlockedByMobileData
import com.cosmotune.canvasLookupWanted
import com.music.bitchord.data.model.Song

/**
 * Whether the only reason there is no video for [song] is the mobile-data guard, so the player can say so.
 *
 * Reads the three settings [rememberCanvasArtwork] reads and decides with the same test, seen from the other side:
 * the animated-cover switch is on, the connection is metered (`meteredConnection == true`) and "play over cellular"
 * is off. Whether a lookup was wanted for this track at all comes from [canvasLookupWanted]. On the desktop the host
 * reports an unmetered connection, so this is always false there.
 */
@Composable
internal fun rememberCanvasBlockedByMobileData(song: Song): Boolean {
    val canvasOn by PlayerSettings.animatedCanvas.collectAsStateWithLifecycle()
    val overCellular by PlayerSettings.canvasOverCellular.collectAsStateWithLifecycle()
    val metered by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    val lookupWanted = remember(song.videoId, song.title, song.artist, song.localUri, song.localPath) {
        canvasLookupWanted(
            videoId = song.videoId,
            isLocalFile = song.localUri != null || song.localPath != null,
            title = song.title,
            artist = song.artist,
        )
    }
    return canvasBlockedByMobileData(
        overCellular = overCellular,
        onCellular = metered == true,
        canvasOn = canvasOn,
        lookupWanted = lookupWanted,
    )
}

/**
 * The one line that says why there is no video, drawn by the host ([PlayerHost.CanvasMobileDataHint]) on the
 * artwork's bottom edge, where the nerd-stats line also sits. Draws nothing, and takes no room, unless [blocked].
 * It sits on the artwork rather than in the gap under it because that gap is 20 dp and the credits start right after
 * it: a line with a tappable button does not fit without overlapping them, and an overlay can never move the layout.
 * Lifted clear of the nerd-stats lines when they are switched on.
 */
@Composable
internal fun BoxScope.CanvasMobileDataHintOnSleeve(blocked: Boolean, modifier: Modifier = Modifier) {
    if (!blocked) return
    val nerdStatsShown by PlayerSettings.showNerdStats.collectAsStateWithLifecycle()
    PlayerPlatform.host.CanvasMobileDataHint(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(start = 10.dp, end = 10.dp, bottom = if (nerdStatsShown) NERD_STATS_CLEARANCE else 8.dp),
    )
}

/** Room for the nerd-stats lines (up to three) and their own padding below the sleeve's bottom edge. */
private val NERD_STATS_CLEARANCE = 64.dp
