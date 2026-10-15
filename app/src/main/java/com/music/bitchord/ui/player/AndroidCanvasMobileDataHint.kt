package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings

/**
 * "Video is paused on mobile data", with an "Allow" button that turns on the same setting as
 * "Play animated cover over cellular" in Settings. The player draws this only while that guard is the reason there
 * is no video, so it goes away by itself once the setting is on (see `rememberCanvasBlockedByMobileData`).
 *
 * The look is the neighbouring sleeve line's (a small white label, as the nerd stats are) on a dark pill so that
 * it reads over any cover; the design pass restyles it later. The button keeps Material's 48 dp touch target and
 * names what it allows for a screen reader.
 */
@Composable
internal fun AndroidCanvasMobileDataHint(modifier: Modifier = Modifier) {
    val allowDescription = stringResource(R.string.canvas_allow_on_mobile_data_description)
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.canvas_paused_on_mobile_data),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(
            onClick = { AppSettings.setCanvasOverCellular(true) },
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            modifier = Modifier.semantics { contentDescription = allowDescription },
        ) {
            Text(
                text = stringResource(R.string.canvas_allow_on_mobile_data),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
