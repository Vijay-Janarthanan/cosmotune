package com.cosmotune.desktop.update

import com.cosmotune.music.desktop.DesktopUpdateChecker
import com.cosmotune.update.UpdateCheckSchedule
import com.cosmotune.update.UpdateTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a one-click update is: nothing running, downloading ([fraction] 0..1, negative while the size is unknown), handing over, or failed. */
internal sealed interface UpdateStep {
    data object Idle : UpdateStep
    data class Downloading(val fraction: Float) : UpdateStep
    data object Installing : UpdateStep
    data class Failed(val message: String) : UpdateStep
}

/**
 * The desktop's update: the offer found by [check], whether the person put it off, and the one download there can be. A one-click
 * install ([DesktopInstall.assetSuffix] not null) downloads the release's package into [updatesDir] and gives it to [handOver],
 * which installs it after the app quits and opens the app again; any other install opens the download page with [openPage].
 */
internal class DesktopUpdater(
    private val check: suspend () -> DesktopUpdateChecker.UpdateInfo?,
    val install: DesktopInstall,
    private val osArch: String,
    private val fetch: suspend (url: String, into: File, onProgress: (Float) -> Unit) -> Unit,
    private val updatesDir: File,
    private val openPage: (String) -> Unit,
    private val handOver: (pkg: File, info: DesktopUpdateChecker.UpdateInfo) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _offer = MutableStateFlow<DesktopUpdateChecker.UpdateInfo?>(null)
    private val _step = MutableStateFlow<UpdateStep>(UpdateStep.Idle)
    private val _postponed = MutableStateFlow(false)
    private val _lastFailure = MutableStateFlow<String?>(null)
    private var lastAttempt: Long? = null
    private var job: Job? = null

    val offer: StateFlow<DesktopUpdateChecker.UpdateInfo?> = _offer.asStateFlow()
    val step: StateFlow<UpdateStep> = _step.asStateFlow()
    val postponed: StateFlow<Boolean> = _postponed.asStateFlow()

    /** Set at launch when the previous update did not install; cleared when the person closes or retries it. */
    val lastFailure: StateFlow<String?> = _lastFailure.asStateFlow()

    /** Looks for a newer release when [UpdateCheckSchedule] says so. An offer once found stays until the app updates. */
    suspend fun refresh(trigger: UpdateTrigger) {
        val now = clock()
        if (!UpdateCheckSchedule.shouldCheck(now, lastAttempt, trigger)) return
        lastAttempt = now
        val found = runCatching { check() }.getOrNull() ?: return
        if (found.version != _offer.value?.version) _postponed.value = false
        _offer.value = found
    }

    fun postpone() { _postponed.value = true }

    fun reopen() { _postponed.value = false }

    fun reportFailure(message: String) { _lastFailure.value = message }

    fun clearFailure() { _lastFailure.value = null }

    /** The package this install updates from, when the release has it. */
    fun packageOf(info: DesktopUpdateChecker.UpdateInfo): DesktopUpdateChecker.ReleaseAsset? {
        val suffix = install.assetSuffix(osArch) ?: return null
        return info.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
    }

    /** One click on Update: the download and hand-over, or the download page. A second click while one runs changes nothing. */
    fun startUpdate(scope: CoroutineScope) {
        val info = _offer.value ?: return
        if (job?.isActive == true) return
        _lastFailure.value = null
        val asset = packageOf(info)
        if (asset == null) {
            openPage(info.downloadUrl ?: info.releaseUrl)
            return
        }
        _step.value = UpdateStep.Downloading(-1f)
        // ATOMIC: a cancel that lands before the task runs still runs it to its first suspension, so its clean-up below sets Idle.
        job = scope.launch(start = CoroutineStart.ATOMIC) {
            updatesDir.mkdirs()
            val part = File(updatesDir, asset.name + ".part")
            val whole = File(updatesDir, asset.name)
            try {
                // Off the window's thread: the download writes the whole package to disk. Progress moves in whole percents.
                withContext(Dispatchers.IO) {
                    var shown = -2
                    fetch(asset.url, part) { fraction ->
                        val percent = if (fraction < 0f) -1 else (fraction * 100).toInt()
                        if (percent != shown) {
                            shown = percent
                            _step.value = UpdateStep.Downloading(fraction)
                        }
                    }
                    whole.delete()
                    check(part.renameTo(whole)) { "Could not keep the download" }
                }
                _step.value = UpdateStep.Installing
                handOver(whole, info)
            } catch (cancel: CancellationException) {
                part.delete()
                _step.value = UpdateStep.Idle
                throw cancel
            } catch (failure: Throwable) {
                part.delete()
                whole.delete()
                _step.value = UpdateStep.Failed(failure.message ?: "Download failed")
            }
        }
    }

    fun cancel() { job?.cancel() }
}
