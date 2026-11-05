package com.cosmotune.video.ui.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cosmotune.video.local.LocalRecent
import com.cosmotune.video.local.LocalRecentStore
import com.cosmotune.video.local.LocalVideo
import com.cosmotune.video.local.VideoAspect
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * What the local player keeps between its pages while the app runs (main thread, like composition): the ids of the files being
 * played through, in order ([queue]); the picture's shape ([aspect]), which stays as the person set it; whether the last tap on a
 * file found it gone ([missingNotice], shown once by the list); and the button that loads a subtitle file ([subtitlePicker], set by
 * the page that is on show, for the player's settings).
 */
internal object LocalPlayerSession {
    /** The ids of the files played through, in order; the file on show is one of them. */
    var queue: List<String> by mutableStateOf(emptyList())

    /** The picture's shape for every local file, until the app ends. */
    var aspect: VideoAspect by mutableStateOf(VideoAspect.FIT)

    /** The last file opened from the list was gone, and was removed: the list says so once. */
    var missingNotice: Boolean by mutableStateOf(false)

    /** Opens the subtitle picker for the file on show, or null when no local page is on show. */
    var subtitlePicker: (() -> Unit)? by mutableStateOf(null)

    /** The id played after [id], or null at the end of the queue. */
    fun nextAfter(id: String): String? {
        val at = queue.indexOf(id)
        return if (at < 0) null else queue.getOrNull(at + 1)
    }

    /** The id played before [id], or null at the start of the queue. */
    fun previousBefore(id: String): String? {
        val at = queue.indexOf(id)
        return if (at <= 0) null else queue[at - 1]
    }

    /** The ids after [id]. */
    fun upNext(id: String): List<String> {
        val at = queue.indexOf(id)
        return if (at < 0) emptyList() else queue.drop(at + 1)
    }
}

/**
 * Opens local files in the player: the files picked (or dropped) are put at the front of the recent list, become the queue, and the
 * first is opened on the local player's page. A recent entry whose file is gone is removed instead, with a notice.
 */
internal class LocalVideoOpener(
    private val store: LocalRecentStore,
    private val access: LocalFileAccess,
    private val navigator: VideoNavigator,
) {
    /** Plays [files] one after the other, starting with the first. */
    fun open(files: List<PickedVideo>) {
        val entries = files.distinctBy { it.source }.reversed().map { file ->
            access.keepAccess(file.source)
            store.touch(file.source, LocalVideo.titleOf(file.name))
        }.reversed()
        val first = entries.firstOrNull() ?: return
        LocalPlayerSession.queue = entries.map { it.id }
        navigator.push(VideoRoute.LocalPlayer(first.id))
    }

    /** Plays [entry] from the list; false when its file is gone (it is removed from the list and the notice is set). */
    fun openRecent(entry: LocalRecent): Boolean {
        if (!access.exists(entry.source)) {
            store.remove(entry.id)
            LocalPlayerSession.missingNotice = true
            return false
        }
        LocalPlayerSession.queue = listOf(entry.id)
        navigator.push(VideoRoute.LocalPlayer(entry.id))
        return true
    }
}

/** The opener for the screen being drawn. */
@Composable
internal fun rememberLocalVideoOpener(): LocalVideoOpener {
    val store = rememberLocalRecentStore()
    val access = rememberLocalFileAccess()
    val navigator = LocalVideoNavigator.current
    return remember(store, access, navigator) { LocalVideoOpener(store, access, navigator) }
}

/**
 * The playback the local player's chrome is given: the controller's own, except that Next and Previous move through the local queue
 * ([onNext], [onPrevious]; the controller's queue is the source's) and the state's queue is [upNext], so the chrome shows the buttons
 * while there is a file to go to. Playing a local id again (Replay, "Try again") starts the file over through [onReplay]: the
 * controller would ask the source for it.
 */
internal class LocalQueuePlayback(
    private val base: VideoPlayback,
    private val upNext: List<VideoItem>,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onReplay: (startMs: Long?) -> Unit,
) : VideoPlayback by base {
    override val state: StateFlow<PlaybackUiState> = QueuedState(base.state, upNext)

    override fun play(videoId: String, startMs: Long?, queue: List<VideoItem>, short: Boolean) {
        if (LocalVideo.isLocal(videoId)) onReplay(startMs) else base.play(videoId, startMs, queue, short)
    }

    override fun play(item: VideoItem, startMs: Long?, queue: List<VideoItem>, short: Boolean) = play(item.id, startMs, queue, short)

    override fun playNext() = onNext()

    override fun playPrevious() = onPrevious()
}

/** [source] with `queue` replaced by [queue]; a state flow whose value and updates are the source's, one for one. */
private class QueuedState(private val source: StateFlow<PlaybackUiState>, private val queue: List<VideoItem>) : StateFlow<PlaybackUiState> {
    override val value: PlaybackUiState get() = source.value.copy(queue = queue)

    override val replayCache: List<PlaybackUiState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<PlaybackUiState>): Nothing {
        source.collect { collector.emit(it.copy(queue = queue)) }
    }
}
