package com.cosmotune.music.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.AppUpdateChecker
import com.cosmotune.music.ui.components.UpdateAvailableDialog
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/**
 * The phone's update sheets, out of the app's root composable (which is at the JVM's 64 KB method limit): the offered update with
 * its download and install while [showUpdate] (an update offered comes first), then the first launch after an update's "What's new".
 * A download keeps running behind a closed sheet; the top bar's update icon reopens it onto whatever state it reached.
 */
@Composable
internal fun PhoneUpdateSheets(
    showUpdate: Boolean,
    onShowUpdateChange: (Boolean) -> Unit,
    update: AppUpdateChecker.UpdateInfo?,
    hazeState: HazeState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    if (showUpdate && update != null) {
        UpdateAvailableDialog(
            update = update,
            hazeState = hazeState,
            onDismiss = { onShowUpdateChange(false) },
            onDownload = {
                if (update.apkUrl != null) {
                    scope.launch { AppUpdateChecker.downloadApk(context) }
                } else {
                    open(update.releaseUrl)
                    onShowUpdateChange(false)
                }
            },
            onCancelDownload = { AppUpdateChecker.cancelDownload() },
            onInstall = {
                val ready = AppUpdateChecker.download.value as? AppUpdateChecker.DownloadState.Ready
                ready?.let { AppUpdateChecker.installApk(context, it.file) }
            },
            onOpenReleasePage = {
                AppUpdateChecker.resetDownload()
                open(update.releaseUrl)
                onShowUpdateChange(false)
            },
            onOpenFullNotes = { open(update.releaseUrl) },
        )
    }
    val welcome by AppUpdateChecker.welcome.collectAsStateWithLifecycle()
    if (!showUpdate) {
        welcome?.let { shown ->
            BackHandler { AppUpdateChecker.welcomeSeen(context) }
            com.cosmotune.music.ui.components.WhatsNewWelcomeSheet(
                welcome = shown,
                onDismiss = { AppUpdateChecker.welcomeSeen(context) },
                onOpenFullNotes = { open(shown.releaseUrl) },
            )
        }
    }
}
