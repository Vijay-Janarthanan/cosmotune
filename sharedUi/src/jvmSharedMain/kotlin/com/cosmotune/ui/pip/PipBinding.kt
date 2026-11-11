package com.cosmotune.ui.pip

import androidx.compose.ui.geometry.Rect

/**
 * A page's hold on picture-in-picture: the wish the page builds each time it draws ([update]) and the picture's place in the
 * window ([bounds], the rectangle the phone animates from), sent to [handle] together whenever either changes. Not snapshot state:
 * a scroll moves the bounds every frame and must not redraw the page. UI thread only.
 */
class PipBinding(val handle: PipHandle) {
    private var wish: PipWish? = null
    private var rect: Rect? = null

    fun update(wish: PipWish) {
        this.wish = wish
        send()
    }

    /** The picture's bounds in the window, in pixels; kept in whole pixels so a sub-pixel move sends nothing. */
    fun bounds(rect: Rect) {
        val whole = Rect(rect.left.toInt().toFloat(), rect.top.toInt().toFloat(), rect.right.toInt().toFloat(), rect.bottom.toInt().toFloat())
        if (whole == this.rect) return
        this.rect = whole
        send()
    }

    private fun send() {
        val w = wish ?: return
        handle.update(w.withSourceRect(rect))
    }
}
