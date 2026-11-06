package com.cosmotune.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.R
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.ui.orbit.OrbitSheetButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitPopupGround

/**
 * "Video is paused on mobile data", with an "Allow" button that turns on the same setting as
 * "Play animated cover over cellular" in Settings. The player draws this only while that guard is the reason there
 * is no video, so it goes away by itself once the setting is on (see `rememberCanvasBlockedByMobileData`).
 *
 * The Orbit notice pill (the problem toast's look): the floating card's flat ground with its faint edge, the line in
 * the ink and Allow as the one peach pill, in the player's light or dark colours. The button keeps a 44 dp touch
 * target and names what it allows for a screen reader.
 */
@Composable
internal fun AndroidCanvasMobileDataHint(modifier: Modifier = Modifier) {
    val allowDescription = stringResource(R.string.canvas_allow_on_mobile_data_description)
    val look = orbitLook()
    val fonts = orbitFonts()
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(orbitPopupGround())
            .border(1.dp, look.ink.copy(alpha = 0.08f), CircleShape)
            .padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.canvas_paused_on_mobile_data),
            style = fonts.chip.copy(fontSize = 13.sp),
            color = look.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).padding(end = 10.dp),
        )
        OrbitSheetButton(
            label = stringResource(R.string.canvas_allow_on_mobile_data),
            onClick = { AppSettings.setCanvasOverCellular(true) },
            primary = true,
            modifier = Modifier.semantics { contentDescription = allowDescription },
        )
    }
}
