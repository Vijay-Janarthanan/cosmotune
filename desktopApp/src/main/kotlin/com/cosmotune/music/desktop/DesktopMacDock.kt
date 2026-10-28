package com.cosmotune.music.desktop

import androidx.compose.ui.graphics.toAwtImage
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import cosmotune.desktopapp.generated.resources.Res
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The Dock on macOS. While a song is loaded the Dock icon is its cover, in the rounded square a Mac
 * icon is, and the Dock menu has what is playing with Play or Pause, Next and Previous, so the player
 * can be driven without bringing the window forward. With nothing playing the app's own icon is back:
 * the very one the Dock showed before, kept aside before the first cover replaces it.
 */
internal object DesktopMacDock {
    private var last: Pair<String, Boolean>? = null

    /** What the Dock icon shows: the cover's address, or null for the logo; [UNTOUCHED] until this changes it. */
    private var shown: String? = UNTOUCHED
    private const val UNTOUCHED = "\u0000untouched"

    private val worker by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "Cosmotune-Dock").apply { isDaemon = true } }
    }

    fun update(now: DesktopMiniPlayer.NowPlaying) {
        if (!DesktopPlatform.isMac) return
        updateMenu(now)
        updateIcon(now)
    }

    private fun updateMenu(now: DesktopMiniPlayer.NowPlaying) {
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

    private fun updateIcon(now: DesktopMiniPlayer.NowPlaying) {
        val wanted = now.artworkUrl.takeIf { now.title.isNotBlank() }
        // Nothing playing and the icon never changed: the bundle's own icon stays.
        if (wanted == shown || (wanted == null && shown == UNTOUCHED)) return
        shown = wanted
        worker.execute {
            runCatching {
                if (!Taskbar.isTaskbarSupported()) return@runCatching
                val dock = Taskbar.getTaskbar()
                if (!dock.isSupported(Taskbar.Feature.ICON_IMAGE)) return@runCatching
                if (wanted == null) {
                    // The icon the Dock had. Only if that could not be kept is the logo drawn instead.
                    if (!MacAppIcon.restore()) {
                        val logo = runBlocking { Res.readBytes("drawable/logo.svg") }.let { renderLogo(it) }
                        if (logo != null) EventQueue.invokeLater { runCatching { dock.iconImage = logo } }
                    }
                    return@runCatching
                }
                val icon = runBlocking { DesktopArtworkCache.load(wanted) }?.toAwtImage()?.let { DesktopDockIcon.render(it) }
                // A cover that would not load leaves the icon as it was.
                if (icon != null) {
                    MacAppIcon.keep()
                    EventQueue.invokeLater { runCatching { dock.iconImage = icon } }
                }
            }.onFailure { DesktopTrackLog.log("dock icon unavailable: ${it.message}") }
        }
    }
}

/** The Dock icon for a cover: the cover in a rounded square with the margin and the shadow a Mac icon has. */
internal object DesktopDockIcon {
    private const val SIZE = 512

    fun render(art: java.awt.Image): BufferedImage {
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            // 80% of the canvas, as the grid of a Mac icon has it, leaving room for the shadow.
            val side = SIZE * 8 / 10
            val left = (SIZE - side) / 2
            val top = left - SIZE / 60
            val arc = side * 0.45f
            for (step in 12 downTo 1) {
                g.color = Color(0, 0, 0, 5)
                g.fill(RoundRectangle2D.Float(left.toFloat() - step, (top + SIZE / 40f) - step / 2f, side + step * 2f, side + step * 2f, arc, arc))
            }
            val shape = RoundRectangle2D.Float(left.toFloat(), top.toFloat(), side.toFloat(), side.toFloat(), arc, arc)
            g.clip(shape)
            val width = art.getWidth(null).coerceAtLeast(1)
            val height = art.getHeight(null).coerceAtLeast(1)
            val scale = max(side.toDouble() / width, side.toDouble() / height)
            val drawnW = (width * scale).toInt()
            val drawnH = (height * scale).toInt()
            g.drawImage(art, left + (side - drawnW) / 2, top + (side - drawnH) / 2, drawnW, drawnH, null)
        } finally {
            g.dispose()
        }
        return image
    }
}

/**
 * The application's own icon, as AppKit holds it. Java can set the Dock icon but has no way back to
 * the one the bundle supplies, so the original is kept (retained) before the first cover replaces it,
 * and handed back to AppKit on its main thread.
 */
private object MacAppIcon {
    private val runtime by lazy { NativeLibrary.getInstance("objc") }
    private var original: Pointer? = null

    private fun selector(name: String): Pointer = runtime.getFunction("sel_registerName").invokePointer(arrayOf(name))
    private fun send(receiver: Pointer, name: String, vararg arguments: Any?): Pointer? =
        runtime.getFunction("objc_msgSend").invokePointer(arrayOf(receiver, selector(name), *arguments))
    private fun application(): Pointer? =
        runtime.getFunction("objc_getClass").invokePointer(arrayOf("NSApplication"))?.let { send(it, "sharedApplication") }

    /** Keeps the icon the Dock has now, once. */
    fun keep() {
        if (original != null) return
        runCatching {
            val app = application() ?: return
            val image = send(app, "applicationIconImage") ?: return
            original = send(image, "retain")
        }
    }

    /** Puts the kept icon back; false when there is none to put back. */
    fun restore(): Boolean = runCatching {
        val image = original ?: return false
        val app = application() ?: return false
        // AppKit is for its main thread.
        send(app, "performSelectorOnMainThread:withObject:waitUntilDone:", selector("setApplicationIconImage:"), image, 1)
        true
    }.getOrDefault(false)
}
