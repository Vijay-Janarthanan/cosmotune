package com.cosmotune.music.desktop

import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The tray icon on the platforms that have no StatusNotifierItem. */
internal object DesktopAwtTray {

    private val lock = Any()
    private var icon: TrayIcon? = null
    private var image: BufferedImage? = null
    private var tooltip: String = APP_NAME

    /**
     * Windows and macOS draw the menu and the player card themselves (see [DesktopTrayFlyout]): the
     * native popup is a plain grey list with no room for the song. Elsewhere AWT's own menu stays.
     */
    private val flyout: Boolean get() = DesktopPlatform.isWindows || DesktopPlatform.isMac

    /** Whether this platform has a tray AWT can reach. */
    fun isSupported(): Boolean = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /** The logo, rasterised. */
    fun setIcon(rendered: BufferedImage?) {
        if (rendered == null) return
        synchronized(lock) {
            image = rendered
            icon?.image = rendered
        }
    }

    fun start(onActivate: () -> Unit) {
        if (!isSupported()) return
        EventQueue.invokeLater {
            synchronized(lock) {
                if (icon != null) return@invokeLater
                val tray = runCatching { SystemTray.getSystemTray() }.getOrNull() ?: return@invokeLater
                val art = image ?: blank(tray.trayIconSize.width.coerceAtLeast(16))
                val created = (if (flyout) TrayIcon(art, tooltip) else TrayIcon(art, tooltip, DesktopTrayMenu.awtPopup())).apply {
                    isImageAutoSize = true
                    // A double click is the shortcut back to the window.
                    addActionListener { EventQueue.invokeLater { DesktopTrayFlyout.close(); onActivate() } }
                    addMouseListener(object : MouseAdapter() {
                        // The popup trigger comes on release on Windows and on press on macOS and
                        // Linux; the right button is the same thing on all of them.
                        override fun mouseReleased(event: MouseEvent) {
                            if (flyout && (event.isPopupTrigger || event.button == MouseEvent.BUTTON3)) {
                                DesktopTrayFlyout.toggle(DesktopTrayFlyout.Mode.MENU)
                            }
                        }

                        override fun mouseClicked(event: MouseEvent) {
                            if (event.button != MouseEvent.BUTTON1 || event.clickCount != 1) return
                            // One click shows what is playing, with the controls; the window itself is a double click.
                            if (flyout) DesktopTrayFlyout.toggle(DesktopTrayFlyout.Mode.PLAYER) else onActivate()
                        }
                    })
                }
                runCatching { tray.add(created) }
                    .onSuccess { icon = created }
                    .onFailure { DesktopTrackLog.log("tray unavailable: ${it.message}") }
            }
        }
    }

    fun stop() {
        val remove = Runnable {
            synchronized(lock) {
                icon?.let { current -> runCatching { SystemTray.getSystemTray().remove(current) } }
                icon = null
            }
        }
        // Quit calls exitProcess immediately afterwards; queued removal never gets to run.
        if (EventQueue.isDispatchThread()) remove.run() else EventQueue.invokeAndWait(remove)
    }

    /**
     * Re-labels the icon, and rebuilds the menu because one of its entries is Play or Pause
     * depending on this.
     */
    fun update(title: String?, isPlaying: Boolean) {
        val text = title?.let { "$APP_NAME — $it" } ?: APP_NAME
        EventQueue.invokeLater {
            synchronized(lock) {
                tooltip = text
                val current = icon ?: return@invokeLater
                current.toolTip = text
                if (!flyout) current.popupMenu = DesktopTrayMenu.awtPopup()
            }
        }
    }

    /** PNG bytes to an image AWT can hand the tray. */
    fun decode(png: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull()

    /** A placeholder while the logo is still being rasterised. */
    private fun blank(size: Int) = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)

    private const val APP_NAME = "Cosmotune"
}
