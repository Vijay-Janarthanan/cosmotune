package com.cosmotune.video.graph

import androidx.compose.runtime.Composable
import com.cosmotune.video.ui.VideoHostLinks

/**
 * The process-wide [VideoGraph] (spec 4.3): the same instance for every call in the process, so the library, the player and the
 * queue outlive the screen that draws them (leaving for Music and coming back finds the video where it was). Android builds it from
 * the application context, the desktop on first use. [links] is the host of the screen now drawn; the graph's [VideoGraph.music]
 * reaches Music through whichever links are attached at the time (see [HostLinksRelay]), so no activity is held after its screen
 * leaves.
 *
 * Today every slot is a fake of `:shared` (plan task Y-14); the wiring task (Y-16) replaces them one line each.
 */
@Composable
expect fun rememberVideoGraph(links: VideoHostLinks): VideoGraph
