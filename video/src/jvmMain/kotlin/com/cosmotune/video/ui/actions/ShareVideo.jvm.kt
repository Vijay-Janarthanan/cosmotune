package com.cosmotune.video.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** The desktop: the address goes to the clipboard and [onCopied] lets the action host show its toast (spec 5.12). */
@Composable
internal actual fun rememberShareVideo(onCopied: () -> Unit): ShareVideo {
    val copied by rememberUpdatedState(onCopied)
    return remember { ShareVideo { _, url -> if (copyToClipboard(url)) copied() } }
}

/** Puts [text] on the system clipboard; false where there is none (a headless machine) or it is busy. */
internal fun copyToClipboard(text: String): Boolean = try {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    true
} catch (e: Exception) {
    // HeadlessException, or IllegalStateException while another program holds the clipboard
    false
}
