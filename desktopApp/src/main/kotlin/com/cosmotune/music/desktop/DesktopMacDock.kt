package com.cosmotune.music.desktop

import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.Taskbar

/**
 * The Dock menu on macOS: what is playing, and Play or Pause, Next and Previous, so the player can
 * be driven from the Dock without bringing the window forward. The Dock icon itself is left alone.
 */
internal object DesktopMacDock {
    private var last: Pair<String, Boolean>? = null

    fun update(now: DesktopMiniPlayer.NowPlaying) {
        if (!DesktopPlatform.isMac) return
        val key = now.title to now.playing
        if (key == last) return
        last = key
        EventQueue.invokeLater {
            runCatching {
                if (!Taskbar.isTaskbarSupported()) return@runCatching
                val dock = Taskbar.getTaskbar()
                if (!dock.isSupported(Taskbar.Feature.MENU)) return@runCatching
                val actions = DesktopMiniPlayer.actions
                dock.menu = PopupMenu().apply {
                    if (now.title.isNotBlank()) {
                        add(MenuItem(now.title).apply { isEnabled = false })
                        addSeparator()
                    }
                    add(MenuItem(if (now.playing) "Pause" else "Play").apply { addActionListener { EventQueue.invokeLater(actions.playPause) } })
                    add(MenuItem(DesktopStrings["widget_next", "Next"]).apply { addActionListener { EventQueue.invokeLater(actions.next) } })
                    add(MenuItem(DesktopStrings["widget_previous", "Previous"]).apply { addActionListener { EventQueue.invokeLater(actions.previous) } })
                }
            }.onFailure { DesktopTrackLog.log("dock menu unavailable: ${it.message}") }
        }
    }
}
