package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.cosmotune.design.AppMode
import com.cosmotune.design.AppModes
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_amoled
import com.cosmotune.music.sharedui.resources.cosmo_amoled_summary
import com.cosmotune.music.sharedui.resources.cosmo_theme
import com.cosmotune.music.sharedui.resources.cosmo_theme_dark
import com.cosmotune.music.sharedui.resources.cosmo_theme_light
import com.cosmotune.music.sharedui.resources.cosmo_theme_summary
import com.cosmotune.music.sharedui.resources.cosmo_theme_system
import com.cosmotune.ui.settings.rememberCosmoLookSettings
import com.cosmotune.ui.theme.ThemeChoice
import com.cosmotune.ui.theme.ThemeChoiceHost
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** The desktop's theme choice for the shared chrome (the profile drawer, the account popover): [DesktopAppearanceSettings.themeMode]. */
internal object DesktopThemeChoice : ThemeChoiceHost {
    @Composable override fun collect(): ThemeChoice = DesktopAppearanceSettings.themeMode.collectAsState().value
    override fun choose(choice: ThemeChoice) = DesktopAppearanceSettings.setThemeMode(choice)
}

/**
 * Whether the system is in its dark theme now. Compose Desktop's `isSystemInDarkTheme()` reads it once per process (its
 * `LocalSystemTheme` is a static default that nothing provides again), so a change in Windows' "Choose your mode" would never
 * reach the app; Skiko's own reading asks the system every time (on Windows the `AppsUseLightTheme` value), which costs
 * microseconds. Where the system cannot say (most Linux desktops, a headless test) it is dark, the desktop's look until now.
 */
internal object DesktopSystemTheme {
    fun dark(): Boolean = runCatching { org.jetbrains.skiko.currentSystemTheme != org.jetbrains.skiko.SystemTheme.LIGHT }.getOrDefault(true)

    /** How often the system's theme is asked for while the choice is System. */
    const val POLL_MS = 1_500L
}

/**
 * Whether the desktop draws its dark theme: [DesktopAppearanceSettings.themeMode] resolved against the system's theme (asked again
 * every [DesktopSystemTheme.POLL_MS] while the choice is System, so a change in Windows' settings reaches an open window) and the
 * phone's rule that Video is dark first. Every desktop window reads this: the main window's theme, the mini player, the tray flyout.
 */
@Composable
internal fun rememberDesktopDark(): Boolean {
    val choice by DesktopAppearanceSettings.themeMode.collectAsState()
    val look = rememberCosmoLookSettings()
    val mode by look.mode.collectAsState()
    var systemDark by remember { mutableStateOf(DesktopSystemTheme.dark()) }
    if (choice == ThemeChoice.SYSTEM) {
        LaunchedEffect(Unit) {
            while (true) {
                systemDark = DesktopSystemTheme.dark()
                delay(DesktopSystemTheme.POLL_MS)
            }
        }
    }
    val videoFirst = mode == AppMode.VIDEO && AppModes.available(AppMode.VIDEO)
    return choice.isDark(systemDark, videoFirst)
}

/**
 * The top of Settings > Appearance: Theme, the phone's own choice of System, Light and Dark as Orbit's segmented chips under its
 * row, then Pure black, which only the dark theme has and is drawn disabled otherwise. A choice goes through the app's theme choice
 * ([com.cosmotune.ui.theme.LocalThemeChoice]: the same cross-fade as the account popover's switch). [visible] is the settings page's
 * own search filter.
 */
@Composable
internal fun DesktopThemeRows(visible: @Composable (String, String) -> Boolean) {
    val look = rememberCosmoLookSettings()
    val amoled by look.amoled.collectAsState()
    val choice by DesktopAppearanceSettings.themeMode.collectAsState()
    val host = com.cosmotune.ui.theme.LocalThemeChoice.current
    val dark = com.cosmotune.ui.theme.LocalCosmoColors.current.dark
    val title = stringResource(SharedRes.string.cosmo_theme)
    val summary = stringResource(SharedRes.string.cosmo_theme_summary)
    if (visible(title, summary)) {
        com.cosmotune.ui.settings.CosmoSettingsRow(
            icon = com.cosmotune.ui.orbit.themeGlyph(choice),
            title = title,
            subtitle = summary,
        )
        val options = listOf(ThemeChoice.SYSTEM, ThemeChoice.LIGHT, ThemeChoice.DARK)
        com.cosmotune.ui.settings.CosmoSettingsSegmented(
            options = listOf(
                stringResource(SharedRes.string.cosmo_theme_system),
                stringResource(SharedRes.string.cosmo_theme_light),
                stringResource(SharedRes.string.cosmo_theme_dark),
            ),
            selectedIndex = options.indexOf(choice),
            onSelect = { index -> options[index].let { host?.choose(it) ?: DesktopAppearanceSettings.setThemeMode(it) } },
            modifier = androidx.compose.ui.Modifier.padding(
                start = com.cosmotune.ui.settings.CosmoSettingsInsets.textStart,
                end = com.cosmotune.ui.settings.CosmoSettingsInsets.side,
                bottom = 12.dp,
            ),
        )
    }
    val blackTitle = stringResource(SharedRes.string.cosmo_amoled)
    val blackSummary = stringResource(SharedRes.string.cosmo_amoled_summary)
    if (visible(blackTitle, blackSummary)) {
        // Only the dark theme has a black page; in light it stays as it was set, drawn disabled.
        com.cosmotune.ui.settings.CosmoSettingsRow(
            title = blackTitle,
            subtitle = blackSummary,
            enabled = dark,
            onClick = if (dark) ({ look.setAmoled(!amoled) }) else null,
            trailing = { com.cosmotune.ui.components.CosmoSwitch(checked = amoled, onCheckedChange = look::setAmoled, enabled = dark) },
        )
    }
}
