package com.cosmotune.video.ui.watch

import androidx.compose.runtime.Composable

/*
 * What the watch page asks of the window around it (spec 5.4 "Phone, full screen or landscape" and the desktop's full window,
 * 6.2 "Picture-in-picture"). Each platform answers in its own file: Android through the activity and its windows, the desktop
 * with nothing to turn, no system bars to hide and no picture-in-picture.
 */

/** How the watch page wants the window turned (spec 5.4): as the user's setting has it, held in landscape, or held in portrait. */
internal enum class WatchOrientation { FREE, LANDSCAPE, PORTRAIT }

/**
 * The watch page's hold on the window while it is composed (spec 5.4, 6.2):
 * - [orientation]: [WatchOrientation.LANDSCAPE] for a landscape video in full screen, [WatchOrientation.PORTRAIT] once full
 *   screen was left while the phone was turned (so the page comes back upright), [WatchOrientation.FREE] otherwise: the user's
 *   own rotation setting.
 * - [lightStatusBar]: the status bar's glyphs are light while the phone's page draws its black band under them.
 *
 * Leaving composition gives everything back: the orientation the activity asked for before, the glyphs as they were.
 *
 * Android: the activity's `requestedOrientation` and the window's `WindowInsetsControllerCompat`. Desktop: nothing (the desktop
 * has no orientation and no status bar; its full screen is the full window, [VideoFullWindow]).
 */
@Composable
internal expect fun FullscreenController(orientation: WatchOrientation, lightStatusBar: Boolean)

/**
 * A window over the whole app for the video alone, drawn while composed (spec 5.4: the phone's full screen and the desktop's full
 * window): [content] fills it. It stands over the shell's floating chrome too, which a layer of the page could not (the tab capsule
 * is drawn over every page but the Shorts pager). Back, and Escape where there is a keyboard, call [onExit].
 *
 * Android: a dialog window drawn edge to edge into the display cutout, with no dim, its system bars hidden through
 * `WindowInsetsControllerCompat` (a swipe from an edge shows them for a moment), as the profile drawer's window is drawn. Desktop:
 * a focusable popup at the window's origin, which fills the window.
 */
@Composable
internal expect fun VideoFullWindow(onExit: () -> Unit, content: @Composable () -> Unit)

/**
 * Enters picture-in-picture now (the full screen's button, spec 6.5), with the shape the page last gave
 * `PictureInPictureState.update`; null where there is none (the desktop, an activity that cannot).
 */
@Composable
internal expect fun rememberEnterPictureInPicture(): (() -> Unit)?

/** Whether turning the device to landscape on the watch page enters full screen (spec 5.4): the phone yes, the desktop never. */
internal expect val rotationEntersFullScreen: Boolean
