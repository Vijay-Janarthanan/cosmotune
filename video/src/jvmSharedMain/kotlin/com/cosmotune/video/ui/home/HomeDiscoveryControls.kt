package com.cosmotune.video.ui.home

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
    KitIconButton(CosmoIcons.Settings, preferences, { open = true })
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

/** One quiet line under the chips (what failed), with its retry when there is one. */
@Composable
internal fun HomeNotice(text: String, retry: String? = null, onRetry: () -> Unit = {}) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = text, style = type.forText(type.meta, text), color = Color(palette.textSecondary))
        if (retry != null) KitTextButton(retry, onRetry)
    }
}
