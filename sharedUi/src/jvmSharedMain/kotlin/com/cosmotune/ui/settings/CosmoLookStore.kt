package com.cosmotune.ui.settings

import androidx.compose.runtime.Composable
import com.cosmotune.settings.CosmoLookSettings

/**
 * The look switches, kept where the platform keeps settings: SharedPreferences `cosmotune_look` on Android, the
 * Preferences node `com.cosmotune.desktop/look` on the desktop. One instance per process, so the theme and the
 * settings page see the same switches.
 */
@Composable
expect fun rememberCosmoLookSettings(): CosmoLookSettings
