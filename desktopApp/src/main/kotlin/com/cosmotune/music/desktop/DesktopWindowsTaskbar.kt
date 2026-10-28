package com.cosmotune.music.desktop

import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.graphics.toAwtImage
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * What the Windows taskbar shows for the window: previous, play or pause and next as buttons under
 * the hover preview, and the song's cover (with its name) as the preview itself while a song is
 * loaded. Both are the shell's own features, reached through JNA: the buttons are `ITaskbarList3`'s
 * thumbnail toolbar, the picture is DWM's iconic thumbnail. They exist only while the window has a
 * taskbar button, so while it is closed to the tray there is nothing to hover.
 *
 * Every call is guarded. A failure turns the feature off and leaves the window as it was; setting
 * `COSMOTUNE_TASKBAR=off` in the environment never starts it.
 */
internal object DesktopWindowsTaskbar {

    private const val ID_PREVIOUS = 100
    private const val ID_PLAY_PAUSE = 101
    private const val ID_NEXT = 102

    private const val WM_COMMAND = 0x0111
    private const val WM_DWMSENDICONICTHUMBNAIL = 0x0323
    private const val WM_DWMSENDICONICLIVEPREVIEWBITMAP = 0x0326
    private const val THBN_CLICKED = 0x1800
    private const val DWMWA_FORCE_ICONIC_REPRESENTATION = 7
    private const val DWMWA_HAS_ICONIC_BITMAP = 10
    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_ENABLED = 0x0
    private const val E_INVALIDARG = -2147024809 // 0x80070057

    private val lock = Any()
    private var window: Pointer? = null
    private var previousProc: Pointer? = null
    private var taskbarCreated = 0

    @Volatile private var now = DesktopMiniPlayer.NowPlaying()
    @Volatile private var artwork: BufferedImage? = null
    @Volatile private var artworkUrl: String? = null

    /** What the shell answered when the buttons were last set (an HRESULT), for the live check. */
    @Volatile internal var buttonsResult: Int? = null
        private set

    // Touched on the worker only.
    private var list: TaskbarList? = null
    private var buttonsAdded = false
    @Volatile private var iconic = false
    private val icons = HashMap<DesktopTaskbarGlyphs.Glyph, Pointer>()

    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "Cosmotune-Taskbar").apply { isDaemon = true } }
    }

    private val dwm: DwmApi? by lazy { runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull() }
    private val messages: UserMessages? by lazy { runCatching { Native.load("user32", UserMessages::class.java) }.getOrNull() }
    private val gdi: GdiExtras? by lazy { runCatching { Native.load("gdi32", GdiExtras::class.java) }.getOrNull() }

    /** The window's messages go through here first; everything this does not take is passed on. */
    private val proc = object : WinUser.WindowProc {
        override fun callback(hWnd: WinDef.HWND, uMsg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            val handled = try {
                handle(hWnd.pointer, uMsg, wParam.toLong(), lParam.toLong())
            } catch (failure: Throwable) {
                null
            }
            if (handled != null) return WinDef.LRESULT(handled)
            val next = previousProc ?: return User32.INSTANCE.DefWindowProc(hWnd, uMsg, wParam, lParam)
            return User32.INSTANCE.CallWindowProc(next, hWnd, uMsg, wParam, lParam)
        }
    }

    private fun enabled(): Boolean =
        DesktopPlatform.isWindows && Native.POINTER_SIZE == 8 && !"off".equals(System.getenv("COSMOTUNE_TASKBAR"), ignoreCase = true)

    /** Takes the window's taskbar button over; once per window. */
    fun install(frame: java.awt.Component) {
        if (!enabled()) return
        val handle = runCatching { Native.getComponentPointer(frame) }.getOrNull() ?: return
        synchronized(lock) {
            if (window != null) return
            window = handle
        }
        runCatching {
            taskbarCreated = messages?.RegisterWindowMessageW(WString("TaskbarButtonCreated")) ?: 0
            previousProc = User32.INSTANCE.SetWindowLongPtr(WinDef.HWND(handle), WinUser.GWL_WNDPROC, CallbackReference.getFunctionPointer(proc))
            DesktopTrackLog.log("taskbar: window hooked")
        }.onFailure {
            DesktopTrackLog.log("taskbar: unavailable (${it.message})")
            synchronized(lock) { window = null }
            return
        }
        refresh(force = true)
    }

    /** What DWM answered when the card was last handed over (an HRESULT), for the live check. */
    @Volatile internal var thumbnailResult: Int? = null
        private set

    /** Whether the preview is currently the card rather than the window; for the live check. */
    internal fun cardShown(): Boolean = iconic

    /** Called as playback changes; cheap when nothing the taskbar shows has changed. */
    fun update(next: DesktopMiniPlayer.NowPlaying) {
        val previous = now
        now = next
        if (window == null) return
        val changed = previous.title != next.title || previous.artist != next.artist ||
            previous.artworkUrl != next.artworkUrl || previous.playing != next.playing
        if (changed) refresh(force = false)
    }

    private fun refresh(force: Boolean) {
        worker.execute {
            runCatching {
                loadArtwork()
                updateButtons()
                val wanted = now.title.isNotBlank()
                if (wanted != iconic || force) setIconic(wanted)
                if (wanted) window?.let { dwm?.DwmInvalidateIconicBitmaps(it) }
            }.onFailure { DesktopTrackLog.log("taskbar: update failed (${it.message})") }
        }
    }

    // --- messages -------------------------------------------------------------------------------

    private fun handle(hwnd: Pointer, message: Int, wParam: Long, lParam: Long): Long? = when {
        message == WM_COMMAND && ((wParam shr 16) and 0xFFFF).toInt() == THBN_CLICKED -> {
            val action = DesktopMiniPlayer.actions
            when ((wParam and 0xFFFF).toInt()) {
                ID_PREVIOUS -> EventQueue.invokeLater(action.previous)
                ID_PLAY_PAUSE -> EventQueue.invokeLater(action.playPause)
                ID_NEXT -> EventQueue.invokeLater(action.next)
            }
            0L
        }
        // The shell made the window's button (again): the buttons have to be added to the new one.
        taskbarCreated != 0 && message == taskbarCreated -> {
            worker.execute { buttonsAdded = false; runCatching { updateButtons() } }
            null
        }
        message == WM_DWMSENDICONICTHUMBNAIL -> {
            sendThumbnail(hwnd, ((lParam shr 16) and 0xFFFF).toInt(), (lParam and 0xFFFF).toInt())
            0L
        }
        message == WM_DWMSENDICONICLIVEPREVIEWBITMAP -> {
            sendLivePreview(hwnd)
            0L
        }
        else -> null
    }

    private fun sendThumbnail(hwnd: Pointer, maxWidth: Int, maxHeight: Int) {
        var width = maxWidth.takeIf { it > 0 } ?: 200
        var height = maxHeight.takeIf { it > 0 } ?: 112
        // DWM refuses a bitmap larger than the thumbnail it allows the window; it names that size, and a
        // smaller card is better than a blank preview if it ever turns out to mean less than it says.
        repeat(3) {
            val bitmap = bitmapOf(DesktopTaskbarCard.render(width, height, artwork, now.title, now.artist)) ?: return
            try {
                thumbnailResult = dwm?.DwmSetIconicThumbnail(hwnd, bitmap.pointer, 0)
            } finally {
                GDI32.INSTANCE.DeleteObject(bitmap)
            }
            if (thumbnailResult != E_INVALIDARG) return
            width = width * 3 / 4
            height = height * 3 / 4
        }
    }

    /** What the window shows when the preview is hovered: the same card, at the window's size. */
    private fun sendLivePreview(hwnd: Pointer) {
        val rect = WinDef.RECT()
        if (!User32.INSTANCE.GetClientRect(WinDef.HWND(hwnd), rect)) return
        val bitmap = bitmapOf(DesktopTaskbarCard.render(rect.right - rect.left, rect.bottom - rect.top, artwork, now.title, now.artist)) ?: return
        try {
            dwm?.DwmSetIconicLivePreviewBitmap(hwnd, bitmap.pointer, null, 0)
        } finally {
            GDI32.INSTANCE.DeleteObject(bitmap)
        }
    }

    // --- worker ---------------------------------------------------------------------------------

    private fun setIconic(on: Boolean) {
        val target = window ?: return
        val flag = IntByReference(if (on) 1 else 0)
        dwm?.DwmSetWindowAttribute(target, DWMWA_HAS_ICONIC_BITMAP, flag, 4)
        dwm?.DwmSetWindowAttribute(target, DWMWA_FORCE_ICONIC_REPRESENTATION, flag, 4)
        iconic = on
    }

    private fun loadArtwork() {
        val url = now.artworkUrl
        if (url == artworkUrl) return
        artworkUrl = url
        artwork = if (url == null) null else runCatching {
            runBlocking { DesktopArtworkCache.load(url) }?.toAwtImage()?.let { image ->
                // toAwtImage hands back a type the card's drawing handles; copy to a plain ARGB one all the same.
                BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB).also { copy ->
                    copy.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
                }
            }
        }.getOrNull()
    }

    private fun updateButtons() {
        val target = window ?: return
        val taskbar = list ?: createList()?.also { list = it } ?: return
        val playing = now.playing
        val buttons = Structure.newInstance(ThumbButton::class.java).toArray(3) as Array<ThumbButton>
        val specs = listOf(
            Triple(ID_PREVIOUS, DesktopTaskbarGlyphs.Glyph.PREVIOUS, DesktopStrings["widget_previous", "Previous"]),
            Triple(ID_PLAY_PAUSE, if (playing) DesktopTaskbarGlyphs.Glyph.PAUSE else DesktopTaskbarGlyphs.Glyph.PLAY, if (playing) "Pause" else "Play"),
            Triple(ID_NEXT, DesktopTaskbarGlyphs.Glyph.NEXT, DesktopStrings["widget_next", "Next"]),
        )
        specs.forEachIndexed { index, (id, glyph, tip) ->
            buttons[index].apply {
                dwMask = THB_ICON or THB_TOOLTIP or THB_FLAGS
                iId = id
                hIcon = iconFor(glyph)
                tip.take(259).toCharArray().copyInto(szTip)
                dwFlags = THBF_ENABLED
                write()
            }
        }
        val hr = if (buttonsAdded) taskbar.updateButtons(target, buttons) else taskbar.addButtons(target, buttons)
        // E_FAIL while the window has no taskbar button yet; the shell announces the button, and that retries.
        if (hr >= 0) buttonsAdded = true
        buttonsResult = hr
        DesktopTrackLog.log("taskbar: thumbnail buttons ${if (buttonsAdded) "set" else "not set yet"} (0x${Integer.toHexString(hr)})")
    }

    private fun createList(): TaskbarList? {
        Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED)
        val created = PointerByReference()
        val hr = Ole32.INSTANCE.CoCreateInstance(
            Guid.CLSID("{56FDF344-FD6D-11d0-958A-006097C9A090}"), null, WTypes.CLSCTX_INPROC_SERVER,
            Guid.IID("{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}"), created,
        )
        if (hr.toInt() < 0) {
            DesktopTrackLog.log("taskbar: no thumbnail toolbar (0x${Integer.toHexString(hr.toInt())})")
            return null
        }
        return TaskbarList(created.value).also { it.init() }
    }

    private fun iconFor(glyph: DesktopTaskbarGlyphs.Glyph): Pointer? {
        icons[glyph]?.let { return it }
        val size = User32.INSTANCE.GetSystemMetrics(WinUser.SM_CXSMICON).coerceAtLeast(16)
        val color = bitmapOf(DesktopTaskbarGlyphs.render(glyph, size)) ?: return null
        val maskBits = Memory(((size + 15) / 16 * 2 * size).toLong()).apply { clear() }
        val maskPointer = gdi?.CreateBitmap(size, size, 1, 1, maskBits)
        val mask = maskPointer?.let { WinDef.HBITMAP(it) }
        val info = WinGDI.ICONINFO().apply { fIcon = true; hbmColor = color; hbmMask = mask }
        val icon = messages?.CreateIconIndirect(info)
        GDI32.INSTANCE.DeleteObject(color)
        mask?.let { GDI32.INSTANCE.DeleteObject(it) }
        return icon?.also { icons[glyph] = it }
    }

    /** [image] as a 32-bit bitmap with premultiplied alpha, the form DWM and icons take. */
    private fun bitmapOf(image: BufferedImage): WinDef.HBITMAP? {
        val width = image.width
        val height = image.height
        val info = WinGDI.BITMAPINFO()
        info.bmiHeader.biSize = 40
        info.bmiHeader.biWidth = width
        info.bmiHeader.biHeight = -height
        info.bmiHeader.biPlanes = 1
        info.bmiHeader.biBitCount = 32
        info.bmiHeader.biCompression = WinGDI.BI_RGB
        val bits = PointerByReference()
        val bitmap = GDI32.INSTANCE.CreateDIBSection(null, info, WinGDI.DIB_RGB_COLORS, bits, null, 0) ?: return null
        val pixels = image.getRGB(0, 0, width, height, null, 0, width)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            val r = ((p shr 16) and 0xFF) * a / 255
            val g = ((p shr 8) and 0xFF) * a / 255
            val b = (p and 0xFF) * a / 255
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bits.value.write(0L, pixels, 0, pixels.size)
        return bitmap
    }

    // --- the native side ------------------------------------------------------------------------

    internal interface DwmApi : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
        fun DwmSetIconicThumbnail(hwnd: Pointer, bitmap: Pointer, flags: Int): Int
        fun DwmSetIconicLivePreviewBitmap(hwnd: Pointer, bitmap: Pointer, clientOffset: Pointer?, flags: Int): Int
        fun DwmInvalidateIconicBitmaps(hwnd: Pointer): Int
    }

    internal interface UserMessages : StdCallLibrary {
        fun RegisterWindowMessageW(name: WString): Int
        fun CreateIconIndirect(info: WinGDI.ICONINFO): Pointer?
    }

    /** The GDI call JNA's own binding leaves out. */
    internal interface GdiExtras : StdCallLibrary {
        fun CreateBitmap(width: Int, height: Int, planes: Int, bitCount: Int, bits: Pointer?): Pointer?
    }

    @Structure.FieldOrder("dwMask", "iId", "iBitmap", "hIcon", "szTip", "dwFlags")
    class ThumbButton : Structure() {
        @JvmField var dwMask: Int = 0
        @JvmField var iId: Int = 0
        @JvmField var iBitmap: Int = 0
        @JvmField var hIcon: Pointer? = null
        @JvmField var szTip: CharArray = CharArray(260)
        @JvmField var dwFlags: Int = 0
    }

    /** `ITaskbarList3`, called through its table of functions. */
    private class TaskbarList(private val self: Pointer) {
        private fun function(index: Int): Function {
            val table = self.getPointer(0)
            return Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        }

        fun init(): Int = function(3).invokeInt(arrayOf(self))
        fun addButtons(window: Pointer, buttons: Array<ThumbButton>): Int =
            function(15).invokeInt(arrayOf(self, window, buttons.size, buttons[0].pointer))
        fun updateButtons(window: Pointer, buttons: Array<ThumbButton>): Int =
            function(16).invokeInt(arrayOf(self, window, buttons.size, buttons[0].pointer))
    }
}
