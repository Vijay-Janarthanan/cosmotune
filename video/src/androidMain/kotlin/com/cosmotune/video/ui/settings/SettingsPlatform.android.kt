package com.cosmotune.video.ui.settings

import androidx.compose.runtime.Composable

/** Android: Wi-Fi only and the battery hold apply, and the downloads' folder is the app's own (no chooser). */
@Composable
internal actual fun rememberSettingsPlatform(): SettingsPlatform = AndroidSettingsPlatform

private object AndroidSettingsPlatform : SettingsPlatform {
    override val hasMeteredNetwork: Boolean get() = true
    override val canChooseFolder: Boolean get() = false
    override val defaultFolder: String get() = ""
    override fun chooseFolder(title: String, current: String?, onChosen: (String) -> Unit) {}
}
