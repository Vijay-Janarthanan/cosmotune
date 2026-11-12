package com.cosmotune.desktop.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopInstallTest {
    private fun detect(path: String?, os: String, marker: Boolean = false, writable: Boolean = true) =
        DesktopInstall.detect(path, os, exists = { marker && it.name == DesktopInstall.PORTABLE_MARKER }, writable = { writable })

    @Test fun windowsInstalledFromLauncherPath() {
        val install = assertIs<DesktopInstall.WindowsInstalled>(detect("C:\\Users\\a\\AppData\\Local\\Cosmotune\\Cosmotune.exe", "Windows 11"))
        assertEquals("C:\\Users\\a\\AppData\\Local\\Cosmotune", install.dir.path)
        assertEquals("C:\\Users\\a\\AppData\\Local\\Cosmotune\\Cosmotune.exe", install.exe.path)
    }

    @Test fun windowsPortableWhenMarkerPresent() {
        assertIs<DesktopInstall.WindowsPortable>(detect("D:\\Tools\\Cosmotune\\Cosmotune.exe", "Windows 11", marker = true))
    }

    @Test fun windowsFolderThatNeedsPermission() {
        val install = assertIs<DesktopInstall.WindowsInstalled>(detect("C:\\Program Files\\Cosmotune\\Cosmotune.exe", "Windows 11", writable = false))
        assertEquals(false, install.writable)
    }

    @Test fun macBundleFromLauncherPath() {
        val install = assertIs<DesktopInstall.MacBundle>(detect("/Applications/Cosmotune.app/Contents/MacOS/Cosmotune", "Mac OS X"))
        assertEquals("/Applications/Cosmotune.app", install.bundle.path.replace('\\', '/'))
    }

    @Test fun macFromDiskImageIsNotUpdatable() {
        assertNotNull(assertIs<DesktopInstall.Browser>(detect("/Volumes/Cosmotune/Cosmotune.app/Contents/MacOS/Cosmotune", "Mac OS X")).reason)
        assertNotNull(
            assertIs<DesktopInstall.Browser>(
                detect("/private/var/folders/x/T/AppTranslocation/ABC/d/Cosmotune.app/Contents/MacOS/Cosmotune", "Mac OS X"),
            ).reason,
        )
    }

    @Test fun linuxIsBrowser() {
        assertNull(assertIs<DesktopInstall.Browser>(detect("/opt/cosmotune/bin/Cosmotune", "Linux")).reason)
    }

    @Test fun devRunIsBrowser() {
        assertNull(assertIs<DesktopInstall.Browser>(detect(null, "Windows 11")).reason)
    }

    @Test fun assetSuffixPerPlatform() {
        val win = detect("C:\\Cosmotune\\Cosmotune.exe", "Windows 11")
        val mac = detect("/Applications/Cosmotune.app/Contents/MacOS/Cosmotune", "Mac OS X")
        assertEquals("-windows-x64.msi", win.assetSuffix("amd64"))
        assertEquals("-macos-arm64.zip", mac.assetSuffix("aarch64"))
        assertEquals("-macos-arm64.zip", mac.assetSuffix("arm64"))
        assertEquals("-macos-x64.zip", mac.assetSuffix("x86_64"))
        assertNull(detect("D:\\Cosmotune\\Cosmotune.exe", "Windows 11", marker = true).assetSuffix("amd64"))
        assertNull(detect(null, "Linux").assetSuffix("amd64"))
    }
}
