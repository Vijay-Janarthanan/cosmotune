package com.cosmotune.video.ui.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.ui.shell.VideoNavigator

/**
 * What the desktop's two halves of Video share (spec 5.1): the page that fills the window's content area and the sidebar that stands
 * beside it are composed apart by the desktop frame, so neither can hand the other anything through composition. The state lives as
 * long as the process: leaving for Music and coming back finds the same page, the same query and the same graph.
 */
internal object DesktopVideoState {
    /** The desktop's one navigator. The sidebar's rows push pages on it and its selected row follows its top page. */
    val navigator = VideoNavigator()

    /** What the sidebar's search field holds. */
    var query by mutableStateOf("")

    /** Set by the search shortcut (Ctrl K, Cmd K on macOS); the sidebar takes the focus and clears it. */
    var focusSearch by mutableStateOf(false)

    /** The graph, published by the page for the sidebar's channel list; null until the page has been composed once. */
    var graph by mutableStateOf<VideoGraph?>(null)

    fun requestSearchFocus() {
        focusSearch = true
    }
}
