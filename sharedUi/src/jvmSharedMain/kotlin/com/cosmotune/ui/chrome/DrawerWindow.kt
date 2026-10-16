package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable

/**
 * The window the profile drawer is drawn in: as large as the screen, the status bar and the navigation bar included, so the scrim
 * and the panel reach every edge. [content] fills it; the Back key calls [onDismissRequest] (the scrim and the keys are the content's
 * own). A window and not a layer of the page because the drawer has to stand over the whole app, bars and all.
 *
 * Android: a dialog window that draws edge to edge (`decorFitsSystemWindows = false`). A popup would not do: its height is capped at
 * the window's visible display frame, which stops short of the navigation bar, so the tab capsule showed under the panel.
 * Desktop: a popup at the window's origin, which already fills the window.
 */
@Composable
internal expect fun DrawerWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit)
