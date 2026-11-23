package com.cosmotune.music.desktop

import java.io.File

/** Where the desktop keeps the now-playing text file for OBS (Settings, Now playing for other apps): beside its other data. */
internal object DesktopNowPlayingFile {
    val file: File by lazy { File(com.cosmotune.desktop.DesktopWatch.dataDir.parentFile, "now-playing.txt") }
}
