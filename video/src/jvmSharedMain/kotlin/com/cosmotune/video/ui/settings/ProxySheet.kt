package com.cosmotune.video.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitActionPill
import com.cosmotune.ui.orbit.OrbitInputField
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_set_proxy
import com.cosmotune.video.resources.video_set_proxy_bad
import com.cosmotune.video.resources.video_set_proxy_hint
import com.cosmotune.video.resources.video_set_proxy_note
import com.cosmotune.video.resources.video_set_proxy_save
import com.cosmotune.video.source.VideoProxy
import org.jetbrains.compose.resources.stringResource

/**
 * The proxy's sheet: the address field ("socks5://127.0.0.1:9050" for Tor), what it does in one line under the field, and Save
 * 16 dp under that; an address that is not one says so instead of saving. An empty field saves "none".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProxySheet(settings: com.cosmotune.video.settings.VideoSettings, update: SettingsUpdate, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var text by rememberSaveable { mutableStateOf(settings.proxy) }
    var bad by rememberSaveable { mutableStateOf(false) }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 16.dp)) {
            Text(stringResource(Res.string.video_set_proxy), style = fonts.title, color = look.ink)
            Spacer(Modifier.height(12.dp))
            OrbitInputField(
                value = text,
                onValueChange = { text = it; bad = false },
                placeholder = stringResource(Res.string.video_set_proxy_hint),
                fonts = fonts,
                look = look,
                modifier = Modifier.fillMaxWidth(),
                error = bad,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(if (bad) Res.string.video_set_proxy_bad else Res.string.video_set_proxy_note),
                style = fonts.label,
                color = if (bad) look.accent else look.dim,
            )
            Spacer(Modifier.height(16.dp))
            OrbitActionPill(
                label = stringResource(Res.string.video_set_proxy_save),
                onClick = {
                    if (VideoProxy.parse(text) == null) {
                        bad = true
                    } else {
                        update { it.copy(proxy = text.trim()) }
                        onDismiss()
                    }
                },
                fonts = fonts,
                look = look,
                primary = true,
            )
        }
    }
}
