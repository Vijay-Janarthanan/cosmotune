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
        AlertDialog(onDismissRequest = { if (!busy) cancel() }, title = { Text("Verify Manga source") },
            text = { Text(error ?: "Complete the website's check in the separate browser window. Then choose Use verified session here. Browser support depends on the website.") },
            confirmButton = { TextButton(enabled = browser != null && !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val proof = withContext(Dispatchers.IO) { requireNotNull(browser).complete() }
                        request = null; close(); latest.value(pending, Result.success(proof))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Complete the source check first." }
                    finally { busy = false }
                }
            }) { Text("Use verified session") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { cancel() }) { Text("Cancel") } })
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
