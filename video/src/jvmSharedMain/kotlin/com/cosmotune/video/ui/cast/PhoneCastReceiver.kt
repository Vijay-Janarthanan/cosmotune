package com.cosmotune.video.ui.cast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cosmotune.video.cast.lounge.LoungeReceiver
import com.cosmotune.video.cast.lounge.LoungeScreen
import com.cosmotune.video.cast.lounge.LoungeUi
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.nav.VideoRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the "Link with TV code" sheet reads: the code and the phones of the receiver running now (empty when it is off). */
object PhoneCastHost {
    private val flow = MutableStateFlow(LoungeUi())
    val state: StateFlow<LoungeUi> = flow.asStateFlow()

    internal fun show(ui: LoungeUi) { flow.value = ui }
}

/**
 * Lets the YouTube app on a phone cast here while Video is open and "Let the YouTube app cast here" is on: the lounge
 * receiver runs, a video the phone sends opens its watch page (in place of a watch page on show), and the phone's
 * controls drive the player.
 */
@Composable
internal fun PhoneCastReceiver(navigator: VideoNavigator) {
    val graph = LocalVideoGraph.current
    val settings by graph.settings.settings.collectAsState()
    LaunchedEffect(settings.phoneCasting, graph) {
        PhoneCastHost.show(LoungeUi())
        if (!settings.phoneCasting) return@LaunchedEffect
        val ui = this
        val receiver = LoungeReceiver(
            link = LoungeScreen(name = "Cosmotune"),
            playback = graph.playback,
            savedScreenId = graph.settings.settings.value.loungeScreenId,
            saveScreenId = { id -> graph.settings.update { it.copy(loungeScreenId = id) } },
            open = { videoId, startMs ->
                ui.launch {
                    val route = VideoRoute.Watch(videoId, startMs.takeIf { it > 0 })
                    if (navigator.top is VideoRoute.Watch) navigator.replaceTop(route) else navigator.push(route)
                }
            },
        )
        launch { receiver.state.collect { PhoneCastHost.show(it) } }
        try {
            receiver.run()
        } finally {
            PhoneCastHost.show(LoungeUi())
        }
    }
}
