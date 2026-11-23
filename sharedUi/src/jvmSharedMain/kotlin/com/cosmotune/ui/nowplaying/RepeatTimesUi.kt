package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.playback.RepeatTimes
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem

/** The More menu's line for "Repeat this song": what is left while a hold runs. */
internal fun repeatTimesItem(hold: RepeatTimes.Hold?, onOpen: () -> Unit) =
    OrbitMenuItem("Repeat this song", hold?.line ?: "Play it 2, 3, 5 or 10 times, then carry on", CosmoIcons.RepeatOne, selected = hold != null, onClick = onOpen)

/** The rows of [RepeatTimesMenu], apart so a test can read them. */
internal fun repeatTimesMenuItems(videoId: String, hold: RepeatTimes.Hold?): List<OrbitMenuItem> = buildList {
    for (plays in RepeatTimes.CHOICES) {
        add(OrbitMenuItem(if (plays == 2) "Twice" else "$plays times", "Then the queue carries on", CosmoIcons.RepeatOne) { RepeatTimes.set(videoId, plays) })
    }
    if (hold != null) add(OrbitMenuItem("Stop repeating", "Carry on with the queue after this play", CosmoIcons.Close) { RepeatTimes.clear() })
}

/** How many times to play the song: the choices, and Stop while one runs. */
@Composable
internal fun RepeatTimesMenu(open: Boolean, onDismiss: () -> Unit, videoId: String, fonts: OrbitFonts, look: OrbitLook) {
    val hold by RepeatTimes.hold.collectAsState()
    OrbitMenu(open, onDismiss, repeatTimesMenuItems(videoId, hold), fonts, look,
        header = hold?.let { "Repeat this song · ${it.line}" } ?: "Repeat this song", width = 260.dp)
}

/** While a song is set to repeat: a solid chip saying how many plays are left; it opens the choices. */
@Composable
internal fun RepeatTimesChip(fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val hold by RepeatTimes.hold.collectAsState()
    val current = hold ?: return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OrbitChip(
            current.line, fonts, look, onClick = { open = true }, selected = true,
            leading = { Icon(CosmoIcons.RepeatOne, contentDescription = "Repeat this song", modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("player:repeat-times"),
        )
        RepeatTimesMenu(open, { open = false }, current.videoId, fonts, look)
    }
}
