package com.cosmotune.video.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.source.newpipe.VideoRestrict

/** Keeps the source's Restricted Mode in step with "Family-friendly only" while Video is open. */
@Composable
internal fun FamilyFriendlyGate() {
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    LaunchedEffect(settings.familyFriendly) { VideoRestrict.strict = settings.familyFriendly }
}
