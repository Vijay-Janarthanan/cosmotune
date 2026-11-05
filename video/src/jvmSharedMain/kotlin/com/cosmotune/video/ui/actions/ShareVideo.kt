package com.cosmotune.video.ui.actions

import androidx.compose.runtime.Composable

/**
 * Hands a video's address to the platform (spec 5.12, Share): on Android the system's share chooser (`ACTION_SEND`), on the
 * desktop the clipboard, after which the action host shows a toast. [title] is the video's title, [url] its short address.
 */
fun interface ShareVideo {
    fun share(title: String, url: String)
}

/**
 * The platform's [ShareVideo] for the screen being drawn. [onCopied] is called when the address went to the clipboard instead of
 * a chooser (the desktop), so the caller can say so.
 */
@Composable
internal expect fun rememberShareVideo(onCopied: () -> Unit): ShareVideo
