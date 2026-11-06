package com.cosmotune.manga.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.cosmotune.manga.data.*
import kotlinx.coroutines.*

@Composable internal actual fun rememberMangaVerificationLauncher(onResult: (MangaVerificationRequest, Result<MangaBrowserProof>) -> Unit): (MangaVerificationRequest) -> Unit {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onResult)
    var request by remember { mutableStateOf<MangaVerificationRequest?>(null) }
    var browser by remember { mutableStateOf<MangaVerificationBrowser?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun close() { val old = browser; browser = null; old?.let { scope.launch(Dispatchers.IO) { it.close() } } }
    fun cancel() { val old = request; request = null; close(); old?.let { latest.value(it, Result.failure(IllegalStateException("Verification was cancelled."))) } }
    DisposableEffect(Unit) { onDispose { browser?.let { old -> Thread({ old.close() }, "Manga browser cleanup").apply { isDaemon = true; start() } } } }
    request?.let { pending ->
        com.cosmotune.ui.sheets.CosmoAlertDialog(
            onDismissRequest = { if (!busy) cancel() },
            title = "Verify Manga source",
            message = error ?: "Complete the website's check in the separate browser window. Then choose Use verified session here. Browser support depends on the website.",
            confirmLabel = "Use verified session",
            onConfirm = {
                busy = true; error = null
                scope.launch {
                    try {
                        val proof = withContext(Dispatchers.IO) { requireNotNull(browser).complete() }
                        request = null; close(); latest.value(pending, Result.success(proof))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Complete the source check first." }
                    finally { busy = false }
                }
            },
            confirmEnabled = browser != null && !busy,
            dismissLabel = "Cancel",
            onDismiss = { cancel() },
            dismissEnabled = !busy,
        )
    }
    return { pending ->
        request = pending; error = null; busy = true
        scope.launch {
            var acquired: MangaVerificationBrowser? = null
            try {
                withContext(Dispatchers.IO) { acquired = MangaVerificationWindows.launcher(pending) }
                browser = acquired; acquired = null
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { request = null; latest.value(pending, Result.failure(failure)) }
            finally { acquired?.let { old -> withContext(NonCancellable + Dispatchers.IO) { old.close() } }; busy = false }
        }
    }
}
