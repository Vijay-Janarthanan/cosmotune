package com.cosmotune.desktop.update

import java.io.File

/**
 * How this copy of Cosmotune was installed, which decides how it can update: an installed Windows copy and a Mac bundle update in
 * one click; a portable Windows copy, Linux, a Mac app still on its disk image and a development run go to the download page.
 */
internal sealed interface DesktopInstall {
    /** Installed by the MSI or setup.exe into [dir]; [writable] false when Windows will ask for permission (such as Program Files). */
    data class WindowsInstalled(val dir: File, val exe: File, val writable: Boolean) : DesktopInstall

    /** Unzipped from the portable zip (its [PORTABLE_MARKER] file sits beside Cosmotune.exe). */
    data class WindowsPortable(val dir: File) : DesktopInstall

    /** The app bundle; [writable] false when it belongs to the system (an old .pkg install), so the swap asks for the password. */
    data class MacBundle(val bundle: File, val writable: Boolean) : DesktopInstall

    /** No one-click update; [reason] is a line for the update card when there is something the person can do about it. */
    data class Browser(val reason: String?) : DesktopInstall

    companion object {
        /** The empty file the release workflow puts beside Cosmotune.exe in the portable zip. */
        const val PORTABLE_MARKER = "portable"

        /** [appPath] is jpackage's `jpackage.app-path` (the launcher), null in a development run. */
        fun detect(
            appPath: String?,
            os: String,
            exists: (File) -> Boolean = File::exists,
            writable: (File) -> Boolean = ::canWrite,
        ): DesktopInstall {
            if (appPath.isNullOrBlank()) return Browser(null)
            return when {
                os.startsWith("Windows", ignoreCase = true) -> {
                    val cut = appPath.lastIndexOfAny(charArrayOf('\\', '/'))
                    if (cut <= 0) return Browser(null)
                    val dir = File(appPath.substring(0, cut))
                    if (exists(File(dir, PORTABLE_MARKER))) WindowsPortable(dir)
                    else WindowsInstalled(dir, File(appPath), writable(dir))
                }
                os.contains("mac", ignoreCase = true) -> {
                    val end = appPath.indexOf(".app/")
                    if (end < 0) return Browser(null)
                    val bundlePath = appPath.substring(0, end + 4)
                    if (bundlePath.startsWith("/Volumes/") || "/AppTranslocation/" in bundlePath) {
                        Browser("Move Cosmotune to Applications to update in one click")
                    } else {
                        val bundle = File(bundlePath)
                        MacBundle(bundle, writable(bundle) && (bundle.parentFile?.let(writable) ?: false))
                    }
                }
                else -> Browser(null)
            }
        }

        /** Whether this user can change [dir]: a file can be made in it and removed again. */
        private fun canWrite(dir: File): Boolean = runCatching {
            val probe = File.createTempFile(".cosmotune-write", null, dir)
            probe.delete()
        }.getOrDefault(false)
    }
}

/** The end of the release asset this install updates from, or null when it updates through the download page. */
internal fun DesktopInstall.assetSuffix(osArch: String): String? = when (this) {
    is DesktopInstall.WindowsInstalled -> "-windows-x64.msi"
    is DesktopInstall.MacBundle -> if (osArch == "aarch64" || osArch == "arm64") "-macos-arm64.zip" else "-macos-x64.zip"
    else -> null
}
