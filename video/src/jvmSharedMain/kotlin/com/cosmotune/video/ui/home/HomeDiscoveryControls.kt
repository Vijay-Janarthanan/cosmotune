package com.cosmotune.video.ui.home

import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitIconButton
import com.cosmotune.ui.kit.KitSettingsRow
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_home_preferences
import com.cosmotune.video.resources.video_home_refresh
import com.cosmotune.video.resources.video_home_reset_feedback
import com.cosmotune.video.resources.video_set_device
import java.util.Locale
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Home's discovery controls on phone and desktop: one button at the head of the chip row that opens a sheet with the region and
 * language (they open the settings), Refresh, and the reset of what Home was told to hide, so nothing stands between the chips and the feed.
 */
@Composable
internal fun HomeDiscoveryControls(region: String?, language: String?, feedbackCount: Int,
    onPreferences: () -> Unit, onRefresh: () -> Unit, onReset: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val preferences = stringResource(Res.string.video_home_preferences,
        region?.let { Locale("", it).getDisplayCountry(Locale.getDefault()) } ?: stringResource(Res.string.video_set_device),
        language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) } ?: stringResource(Res.string.video_set_device))
    OrbitRoundButton(CosmoIcons.Settings, preferences, orbitLook().ink, { open = true })
    if (open) {
        KitSheet(onDismiss = { open = false }) {
            KitSettingsRow(preferences, icon = CosmoIcons.Settings, onClick = { open = false; onPreferences() })
            KitSettingsRow(stringResource(Res.string.video_home_refresh), icon = CosmoIcons.Replay, onClick = { open = false; onRefresh() })
            if (feedbackCount > 0) {
                KitSettingsRow(stringResource(Res.string.video_home_reset_feedback, feedbackCount), icon = CosmoIcons.Close, onClick = { open = false; onReset() })
            }
        }
    }
}

/** One quiet row under the chips (what failed), the Orbit row's fill, with its retry in the accent when there is one. */
@Composable
internal fun HomeNotice(text: String, retry: String? = null, onRetry: () -> Unit = {}) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(22.dp)).background(look.row)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = fonts.body, color = look.dim, modifier = Modifier.weight(1f))
        if (retry != null) {
            Text(retry, style = fonts.chip, color = look.accent, modifier = Modifier.clip(CircleShape).clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}
