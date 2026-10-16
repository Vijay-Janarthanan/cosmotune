package com.cosmotune.ui.chrome

import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * A dialog window that draws edge to edge: `usePlatformDefaultWidth = false` and `decorFitsSystemWindows = false` make Compose size
 * the window to the whole screen, behind the status bar and the navigation bar, as soon as its content fills it. The platform's dim
 * is switched off (the drawer's scrim is the only one) and the bars are left transparent, with no contrast scrim of the system's own.
 */
@Composable
internal actual fun DrawerWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = dialogWindowOf(LocalView.current)
        SideEffect {
            if (window == null) return@SideEffect
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setDimAmount(0f)
            // Android 15 ignores these colours and draws transparent bars; before it they are the dialog theme's, which are not.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 35) {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
        }
        content()
    }
}

/** The window of the dialog that [view] is drawn in: the parent that provides it (the content's own view is its child). */
private fun dialogWindowOf(view: View): android.view.Window? {
    var parent = view.parent
    while (parent != null) {
        if (parent is DialogWindowProvider) return parent.window
        parent = parent.parent
    }
    return null
}
