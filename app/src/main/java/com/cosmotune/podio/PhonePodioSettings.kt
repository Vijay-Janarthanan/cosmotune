package com.cosmotune.podio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.podio.ui.PodioSettingsSection
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings › Podio on the phone: the shared page with the phone's own file chooser for OPML import and export, sharing through the
 * system sheet, and the background refresh scheduled or stopped as it is turned on or off.
 */
@Composable fun PhonePodioSettings() {
    val context = LocalContext.current
    var pendingFile by remember { mutableStateOf<CompletableDeferred<Uri?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFile?.complete(result.data?.data.takeIf { result.resultCode == Activity.RESULT_OK }); pendingFile = null
    }
    DisposableEffect(Unit) { onDispose { pendingFile?.cancel() } }
    suspend fun chooseFile(saving: Boolean): Uri? {
        check(pendingFile == null) { "Finish the open file chooser first." }
        val answer = CompletableDeferred<Uri?>().also { pendingFile = it }
        return try {
            picker.launch(Intent(if (saving) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = if (saving) "text/x-opml" else "*/*"
                if (saving) putExtra(Intent.EXTRA_TITLE, "Cosmotune-podcasts.opml")
            })
            answer.await()
        } finally {
            if (pendingFile === answer) pendingFile = null
        }
    }
    val library = PodioRefreshWorker.libraryFile(context)
    val saved by remember(library) { PodioLibrary(library).state }.collectAsState()
    // Asked again each time the settings come back into view: the person may have just allowed exact alarms.
    var exactAlarms by remember { mutableStateOf(PodioAlarmReceiver.exactAllowed(context)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { exactAlarms = PodioAlarmReceiver.exactAllowed(context); onPauseOrDispose { } }
    PodioSettingsSection(library,
        onImportOpmlFile = { chooseFile(false)?.let { uri -> withContext(Dispatchers.IO) {
            requireNotNull(context.contentResolver.openInputStream(uri)).use(PodioOpml::read)
        } } },
        onExportOpmlFile = { text ->
            val uri = chooseFile(true)
            if (uri == null) false else withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }; true
            }
        },
        onShareOpmlFile = { text ->
            val file = withContext(Dispatchers.IO) { File(context.cacheDir, "shared/Cosmotune-podcasts.opml").apply { parentFile?.mkdirs(); writeText(text, Charsets.UTF_8) } }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/x-opml"; putExtra(Intent.EXTRA_STREAM, uri); clipData = android.content.ClipData.newRawUri("Podcast subscriptions", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share podcast subscriptions"))
        },
        onRefreshChange = { on -> runCatching { PodioRefreshWorker.schedule(context, on && saved.shows.isNotEmpty()) } },
        exactAlarms = exactAlarms, automation = true,
        onAllowExactAlarms = PodioAlarmReceiver.allowIntent(context)?.let { intent -> { runCatching { context.startActivity(intent) } } })
    // The system alarm follows the radio alarm as it is changed here.
    LaunchedEffect(saved.alarm) { runCatching { PodioAlarmReceiver.sync(context) } }
}
