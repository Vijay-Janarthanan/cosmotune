package com.cosmotune.music.desktop

import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import java.awt.EventQueue
import javax.swing.JFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Hooks a real window and reads back what Windows accepted. Opens a window for a few seconds, so it
 * runs only when asked: `COSMOTUNE_TASKBAR_LIVE=1`, on Windows.
 */
class DesktopWindowsTaskbarLiveTest {

    @Test fun theShellAcceptsTheButtonsAndTheCard() {
        assumeTrue(System.getenv("COSMOTUNE_TASKBAR_LIVE") == "1")
        assumeTrue(DesktopPlatform.isWindows)
        var frame: JFrame? = null
        EventQueue.invokeAndWait {
            frame = JFrame("Cosmotune taskbar check").apply {
                setSize(480, 320)
                isVisible = true
            }
        }
        try {
            Thread.sleep(1_500) // the shell makes the window's taskbar button
            DesktopWindowsTaskbar.install(frame!!)
            DesktopWindowsTaskbar.update(DesktopMiniPlayer.NowPlaying("Chinna Thamarai", "Vijay Antony", null, true, 0.3f))
            Thread.sleep(2_500)
            val result = assertNotNull(DesktopWindowsTaskbar.buttonsResult, "the shell answered the thumbnail toolbar call")
            assertEquals(0, result, "ThumbBarAddButtons returned 0x${Integer.toHexString(result)}")
            assertTrue(DesktopWindowsTaskbar.cardShown(), "the preview is the card while a song is loaded")

            // What the shell sends when the taskbar button is hovered, and when a thumbnail button is clicked.
            val window = WinDef.HWND(Native.getComponentPointer(frame!!))
            User32.INSTANCE.SendMessage(window, 0x0323, WinDef.WPARAM(0), WinDef.LPARAM((200L shl 16) or 112L))
            assertEquals(0, assertNotNull(DesktopWindowsTaskbar.thumbnailResult, "the card was handed to DWM"), "DwmSetIconicThumbnail")
            val clicked = java.util.concurrent.atomic.AtomicInteger()
            DesktopMiniPlayer.actions = DesktopMiniPlayer.Actions({ clicked.addAndGet(1) }, { clicked.addAndGet(10) }, { clicked.addAndGet(100) }, {})
            for (id in listOf(100L, 101L, 102L)) {
                User32.INSTANCE.SendMessage(window, 0x0111, WinDef.WPARAM((0x1800L shl 16) or id), WinDef.LPARAM(0))
            }
            Thread.sleep(500)
            assertEquals(111, clicked.get(), "previous, play/pause and next each ran their action once")

            DesktopWindowsTaskbar.update(DesktopMiniPlayer.NowPlaying())
            Thread.sleep(1_000)
            assertTrue(!DesktopWindowsTaskbar.cardShown(), "the window's own preview comes back when nothing is playing")
        } finally {
            EventQueue.invokeAndWait { frame?.dispose() }
        }
    }
}
