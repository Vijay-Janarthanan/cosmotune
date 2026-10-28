@file:JvmName("AndroidMangaLibraryTools")
package com.cosmotune.manga.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.work.*
import com.cosmotune.manga.data.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*

internal object AndroidMangaLibrary {
    private var instance: MangaRepository? = null
    @Synchronized fun repository(context: Context): MangaRepository = instance ?: run {
        val app = context.applicationContext
        val store = MangaStore(File(app.filesDir, "manga"), platformMangaFileProtection())
        MangaRepository(store) { config, parser -> com.cosmotune.manga.extensions.MangaExtensions(app, store, parser).source(config) }
    }.also { instance = it }
}

class MangaLibraryUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        AndroidMangaLibrary.repository(applicationContext).scheduledUpdate()
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Result.failure() }
}

@Composable internal actual fun MangaBackgroundUpdates(repository: MangaRepository) {
    val context = LocalContext.current.applicationContext
    val state by repository.store.state.collectAsState()
    LaunchedEffect(state.updateIntervalHours) {
        val work = WorkManager.getInstance(context)
        if (state.updateIntervalHours == 0) work.cancelUniqueWork("cosmotune-manga-library-updates")
        else work.enqueueUniquePeriodicWork("cosmotune-manga-library-updates", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<MangaLibraryUpdateWorker>(state.updateIntervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(state.updateIntervalHours.toLong(), TimeUnit.HOURS).build())
    }
}

@Composable internal actual fun rememberMangaBackupTransfer(onImport: (ByteArray) -> Unit, onResult: (Result<Unit>) -> Unit): MangaBackupTransfer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported = rememberUpdatedState(onImport); val result = rememberUpdatedState(onResult)
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val contents = bytes; bytes = null
        if (uri != null && contents != null) scope.launch {
            result.value(withContext(Dispatchers.IO) { runCatching { requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(contents) } } })
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        val contents = withContext(Dispatchers.IO) { runCatching { requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            input.readNBytes(MangaLibraryBackup.MAX_BYTES + 1).also { require(it.size <= MangaLibraryBackup.MAX_BYTES) { "Library backup exceeds 8 MB" } }
        } } }
        contents.fold(onSuccess = { imported.value(it) }, onFailure = { result.value(Result.failure(it)) })
    } }
    return MangaBackupTransfer({ contents -> bytes = contents; save.launch("cosmotune-manga-library.json") }, { load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
}
