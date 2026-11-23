package com.cosmotune.video.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoSheetHeading
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.cast.lounge.LoungeProtocol
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_tvcode_allow
import com.cosmotune.video.resources.video_tvcode_failed
import com.cosmotune.video.resources.video_tvcode_how
import com.cosmotune.video.resources.video_tvcode_linked
import com.cosmotune.video.resources.video_tvcode_making
import com.cosmotune.video.resources.video_tvcode_none
import com.cosmotune.video.resources.video_tvcode_off
import com.cosmotune.video.resources.video_tvcode_title
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.cast.PhoneCastHost
import org.jetbrains.compose.resources.stringResource

/**
 * "Link with TV code": the switch that lets the YouTube app cast here, then the twelve-digit code to type on the phone
 * (YouTube app, Cast, Link with TV code; a new code every few minutes), and the phones linked now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TvCodeSheet(settings: VideoSettings, update: ((VideoSettings) -> VideoSettings) -> Unit, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val lounge by PhoneCastHost.state.collectAsState()
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CosmoSheetHeading(stringResource(Res.string.video_tvcode_title))
        Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.video_tvcode_allow), style = fonts.title, color = look.ink, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                CosmoSwitch(checked = settings.phoneCasting, onCheckedChange = { value -> update { it.copy(phoneCasting = value) } })
            }
            Spacer(Modifier.height(16.dp))
            if (!settings.phoneCasting) {
                Text(stringResource(Res.string.video_tvcode_off), style = fonts.body, color = look.dim)
            } else {
                Text(stringResource(Res.string.video_tvcode_how), style = fonts.body, color = look.dim)
                Spacer(Modifier.height(16.dp))
                val code = lounge.code
                when {
                    lounge.failed -> Text(stringResource(Res.string.video_tvcode_failed), style = fonts.body, color = look.dim)
                    code == null -> Text(stringResource(Res.string.video_tvcode_making), style = fonts.body, color = look.dim)
                    // the code is read out digit by digit, not as one large number
                    else -> Text(
                        LoungeProtocol.spaced(code),
                        style = fonts.section,
                        color = look.ink,
                        modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") },
                    )
                }
                Spacer(Modifier.height(16.dp))
                OrbitLabel(stringResource(Res.string.video_tvcode_linked), fonts, look.dim)
                Spacer(Modifier.height(8.dp))
                val names = lounge.remotes.map { it.name.ifBlank { "Phone" } }
                Text(if (names.isEmpty()) stringResource(Res.string.video_tvcode_none) else names.joinToString(", "), style = fonts.body, color = look.ink)
            }
        }
    }
}
