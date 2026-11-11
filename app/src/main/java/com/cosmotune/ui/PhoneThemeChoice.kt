package com.cosmotune.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.ThemeMode
import com.cosmotune.ui.theme.ThemeChoice
import com.cosmotune.ui.theme.ThemeChoiceHost

/**
 * The phone's theme choice for the shared chrome: [AppSettings.themeMode] (Settings > Appearance > Theme) as the shared
 * [ThemeChoice], so the profile drawer's quick switch and the Settings row change the one setting.
 */
internal object PhoneThemeChoice : ThemeChoiceHost {
    @Composable override fun collect(): ThemeChoice = AppSettings.themeMode.collectAsStateWithLifecycle().value.toChoice()
    override fun choose(choice: ThemeChoice) = AppSettings.setThemeMode(choice.toMode())

    fun ThemeMode.toChoice(): ThemeChoice = when (this) {
        ThemeMode.SYSTEM -> ThemeChoice.SYSTEM
        ThemeMode.LIGHT -> ThemeChoice.LIGHT
        ThemeMode.DARK -> ThemeChoice.DARK
    }

    fun ThemeChoice.toMode(): ThemeMode = when (this) {
        ThemeChoice.SYSTEM -> ThemeMode.SYSTEM
        ThemeChoice.LIGHT -> ThemeMode.LIGHT
        ThemeChoice.DARK -> ThemeMode.DARK
    }
}
