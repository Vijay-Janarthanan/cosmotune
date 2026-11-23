package com.cosmotune.podio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Podio for automation apps (Tasker, MacroDroid…): a broadcast of [PodioAutomation.PLAY_STATION] (with "name" or "url"),
 * [PodioAutomation.PLAY_FOR_YOU] or [PodioAutomation.PAUSE] to this app's package. Open to every app on purpose: all it can do
 * is play or pause Podio.
 */
class PodioAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val library = PodioLibrary(PodioRefreshWorker.libraryFile(app))
                val saved = library.state.value
                when (intent.action) {
                    PodioAutomation.PLAY_STATION -> {
                        val station = PodioAutomation.station(saved, intent.getStringExtra(PodioAutomation.EXTRA_NAME), intent.getStringExtra(PodioAutomation.EXTRA_URL))
                        if (station != null && PodioRemotePlay.play(app, station.item().song()))
                            runCatching { library.update { PodioHistory.played(it, station) } }
                    }
                    PodioAutomation.PLAY_FOR_YOU -> PodioAutomation.forYou(saved, library.queue().state.value)?.let { (item, at) -> PodioRemotePlay.play(app, item.song(), at) }
                    PodioAutomation.PAUSE -> PodioRemotePlay.pause(app)
                }
            } finally { runCatching { pending.finish() } }
        }
    }

    private companion object { val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}
