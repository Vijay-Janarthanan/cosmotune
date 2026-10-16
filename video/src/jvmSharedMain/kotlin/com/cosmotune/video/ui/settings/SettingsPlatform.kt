package com.cosmotune.video.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable

/**
 * What differs between the phone and the desktop on the settings page (spec 5.11: "Only on Wi-Fi" and "Pause when the battery is
 * low" are Android's; the download folder is the desktop's).
 *
 * - Android: a connection can be metered and the battery can run low, so those two rows show; the files live in the app's own
 *   folder, which is not chosen.
 * - Desktop: its connection is never counted as metered and it has no battery hold (the download controller never holds for either),
 *   so those rows do not show; the folder row opens a folder chooser, and an unset folder is `~/Videos/Cosmotune`.
 */
@Stable
internal interface SettingsPlatform {
    /** Whether "Only on Wi-Fi" and "Pause when the battery is low" mean something here. */
    val hasMeteredNetwork: Boolean

    /** Whether the person may choose the folder downloads are written to. */
    val canChooseFolder: Boolean

    /** The folder used while none is chosen, as a path to show (empty where the folder cannot be chosen). */
    val defaultFolder: String

    /**
     * Asks the person for a folder, starting at [current] (null: the default), with [title] over the chooser; [onChosen] gets the
     * path of the folder picked, and is not called when the chooser is cancelled. Does nothing where [canChooseFolder] is false.
     */
    fun chooseFolder(title: String, current: String?, onChosen: (String) -> Unit)
}

/** The [SettingsPlatform] of the platform this runs on. */
@Composable
internal expect fun rememberSettingsPlatform(): SettingsPlatform
