package com.cosmotune.video.ui.settings

import androidx.compose.runtime.Composable
import java.io.File
import javax.swing.JFileChooser

/**
 * The desktop: no metered connection and no battery hold, and a folder chooser. The chooser is Swing's `JFileChooser` in directory
 * mode, the one Music's local-music folder uses: AWT's `FileDialog` has a directory mode only on macOS.
 */
@Composable
internal actual fun rememberSettingsPlatform(): SettingsPlatform = DesktopSettingsPlatform

private object DesktopSettingsPlatform : SettingsPlatform {
    override val hasMeteredNetwork: Boolean get() = false
    override val canChooseFolder: Boolean get() = true

    /** `~/Videos/Cosmotune`, where the download controller writes while no folder is chosen. */
    override val defaultFolder: String get() = File(System.getProperty("user.home").orEmpty(), "Videos/Cosmotune").path

    override fun chooseFolder(title: String, current: String?, onChosen: (String) -> Unit) {
        val start = File(current?.takeIf { it.isNotBlank() } ?: defaultFolder).let { if (it.isDirectory) it else it.parentFile?.takeIf { p -> p.isDirectory } }
        try {
            val chooser = JFileChooser(start ?: File(System.getProperty("user.home").orEmpty()))
            chooser.dialogTitle = title
            chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            chooser.isAcceptAllFileFilterUsed = false
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.path?.let(onChosen)
        } catch (e: java.awt.HeadlessException) {
            // no screen to ask on: nothing is chosen
        }
    }
}
