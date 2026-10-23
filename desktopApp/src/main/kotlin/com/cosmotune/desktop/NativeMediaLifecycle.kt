package com.cosmotune.desktop

/** A failed start may still own a native thread; release it before allowing another attempt. */
internal fun acquireNativeMediaControls(start: () -> Boolean, stop: () -> Unit): Boolean {
    var acquired = false
    try {
        acquired = start()
        return acquired
    } finally {
        if (!acquired) stop()
    }
}
