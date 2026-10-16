package com.cosmotune.video.ui.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.text.intl.Locale
import com.cosmotune.video.downloads.DownloadController
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_dl_audio_only
import com.cosmotune.video.resources.video_quality_auto
import com.cosmotune.video.ui.desktop.DesktopVideoState
import com.cosmotune.video.ui.downloads.DownloadAskResolver
import com.cosmotune.video.ui.downloads.DownloadDecision
import com.cosmotune.video.ui.downloads.DownloadOptionsDialog
import com.cosmotune.video.ui.downloads.DownloadOptionsSheet
import com.cosmotune.video.ui.downloads.DownloadSheetState
import com.cosmotune.video.ui.downloads.LatestCount
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

/**
 * Where the download sheet stands (spec 5.16): composed once by the shell's action host, with the [ask] that
 * [VideoActionHandler.download] or [VideoActionHandler.downloadBatch] set (null when nothing is asked); [onDismiss] clears it.
 *
 * Every download asks first unless a choice is remembered ([DownloadAskResolver.resolve], read once per ask from the settings and the
 * engine's list):
 * - the sheet on the phone ([DownloadOptionsSheet]) or the dialog on the desktop ([DownloadOptionsDialog]); "Download" stores the choice
 *   when "Remember my choice" is ticked (`DownloadPrefs(askBeforeEach = false, remembered = request)`; unticked leaves the settings as
 *   they were), queues the video (`enqueue`) or the batch (`enqueueBatch`, the newest N of a channel's latest), and says "Added to
 *   Downloads · View" (View opens Downloads);
 * - a remembered choice queues at once and says "Downloading in 720p · Change" (Change takes back what it queued and opens the sheet);
 * - a video already downloaded says "Already downloaded"; one already in the queue "Already in Downloads · View".
 * Settings > Downloads > "Ask before each download" (plan task Y-25) turns the question back on.
 */
@Composable
fun DownloadOptionsHost(ask: DownloadAsk?, onDismiss: () -> Unit) {
    if (ask == null) return
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val toasts = VideoActionsState.shared
    // Read once for this ask: a running download's progress does not redraw the sheet, and a choice stored meanwhile does not flip it.
    val entries = remember(ask) { graph.downloads.downloads.value }
    val prefs = remember(ask) { graph.settings.settings.value.downloads }
    val forced = remember(ask) { DownloadReopen.pending === ask }
    val decision = remember(ask) { DownloadAskResolver.resolve(prefs, ask, entries, forceAsk = forced) }
    val auto = stringResource(Res.string.video_quality_auto)
    val audioOnly = stringResource(Res.string.video_dl_audio_only)
    when (decision) {
        DownloadDecision.Ask -> AskForOptions(ask, prefs, entries, onDismiss)
        is DownloadDecision.Start -> LaunchedEffect(ask) {
            DownloadReopen.pending = null
            val started = startDownload(graph.downloads, ask, decision.request, LatestCount.DEFAULT)
            val label = DownloadAskResolver.rungLabel(decision.request.quality) ?: if (decision.request.quality == DownloadQuality.Auto) auto else audioOnly
            toasts.showToast(ActionToastKind.DOWNLOAD_STARTED, detail = label, onAction = { reopen(graph.downloads, toasts, ask, started) })
            onDismiss()
        }
        DownloadDecision.AlreadyDownloaded -> LaunchedEffect(ask) {
            DownloadReopen.pending = null
            toasts.showToast(ActionToastKind.ALREADY_DOWNLOADED)
            onDismiss()
        }
        DownloadDecision.AlreadyInDownloads -> LaunchedEffect(ask) {
            DownloadReopen.pending = null
            toasts.showToast(ActionToastKind.ALREADY_IN_DOWNLOADS, onAction = { openDownloads(navigator) })
            onDismiss()
        }
    }
}

/** The sheet (phone) or the dialog (desktop) for [ask], with its options loaded from the engine, and what "Download" does. */
@Composable
private fun AskForOptions(ask: DownloadAsk, prefs: DownloadPrefs, entries: List<DownloadEntry>, onDismiss: () -> Unit) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val appLanguage = Locale.current.language
    val state = remember(ask) { DownloadSheetState(ask, prefs, appLanguage, entries) }
    // The options: one video's from its streams (the source is asked); a batch's estimated from the videos it would still queue,
    // again when the latest picker's count changes. "Try again" asks again.
    LaunchedEffect(ask, state.count, state.attempt) {
        try {
            val found = when (ask) {
                is DownloadAsk.One -> graph.downloads.options(ask.item)
                is DownloadAsk.Batch -> graph.downloads.batchOptions(state.items)
            }
            state.loaded(found)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            state.failed(failure)
        }
    }
    val close = {
        DownloadReopen.pending = null
        onDismiss()
    }
    val confirm = {
        if (state.canDownload) {
            val request = state.request()
            val remember = state.remember
            graph.settings.update { DownloadAskResolver.confirm(it, request, remember) }
            startDownload(graph.downloads, ask, request, state.count)
            VideoActionsState.shared.showToast(ActionToastKind.DOWNLOAD_ADDED, onAction = { openDownloads(navigator) })
            close()
        }
    }
    // The desktop's Video page is drawn under its one process-wide navigator; the phone's shell has its own.
    if (navigator === DesktopVideoState.navigator) {
        DownloadOptionsDialog(state, onConfirm = confirm, onDismiss = close)
    } else {
        DownloadOptionsSheet(state, onConfirm = confirm, onDismiss = close)
    }
}

/**
 * The ask that "Change" brought back: it opens the sheet even though a choice is remembered. Cleared once that ask is answered or
 * dismissed. One for the process, as the action host's state is.
 */
internal object DownloadReopen {
    @Volatile var pending: DownloadAsk? = null
}

/**
 * Queues [ask] with [request] (the newest [count] of a batch with the latest picker) and answers the ids of the entries it added or
 * brought back from a failure, so "Change" can take them back. The engine skips what is downloaded or queued already.
 */
internal fun startDownload(downloads: DownloadController, ask: DownloadAsk, request: DownloadRequest, count: Int): List<String> {
    val before = downloads.downloads.value.associate { it.id to it.state }
    when (ask) {
        is DownloadAsk.One -> downloads.enqueue(ask.item, request)
        is DownloadAsk.Batch -> downloads.enqueueBatch(ask.info, DownloadAskResolver.batchItems(ask, count), request)
    }
    return downloads.downloads.value
        .filter { entry ->
            val was = before[entry.id]
            was == null || (was is DownloadState.Failed && entry.state !is DownloadState.Failed)
        }
        .map { it.id }
}

/** "Change": cancels what the remembered choice queued (a finished one stays) and asks [ask] again with the sheet. */
internal fun reopen(downloads: DownloadController, toasts: VideoActionsState, ask: DownloadAsk, started: List<String>) {
    cancelStarted(downloads, started)
    DownloadReopen.pending = ask
    toasts.downloadAsk = ask
}

/** Cancels the entries [ids] that have not finished (their partial files go with them). */
internal fun cancelStarted(downloads: DownloadController, ids: List<String>) {
    val unfinished = downloads.downloads.value.filter { it.id in ids && it.state !is DownloadState.Done }.map { it.id }
    unfinished.forEach(downloads::cancel)
}

/** "View": the Downloads page. */
private fun openDownloads(navigator: VideoNavigator) {
    if (navigator.top != VideoRoute.Downloads) navigator.push(VideoRoute.Downloads)
}
