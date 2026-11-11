/*
 * Source: FORK.md companion source record TV-0018
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.music.BuildConfig
import com.cosmotune.music.data.AppUpdateChecker
import com.cosmotune.music.data.AppUpdateChecker.DownloadState
import com.cosmotune.music.ui.tv.auth.TvQrCodeView
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvType
import com.cosmotune.ui.whatsnew.WhatsNew
import com.cosmotune.ui.whatsnew.WhatsNewAction
import com.cosmotune.ui.whatsnew.WhatsNewDialog
import com.cosmotune.ui.whatsnew.WhatsNewSurface
import com.cosmotune.ui.whatsnew.whatsNewMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Outlives the alert on purpose: "Hide" closes it, and the download has to keep
 * going behind it. [AppUpdateChecker] holds the one download there can be.
 */
private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** A TV dialog closes on Back only: a stray press beside the card must not throw away the offer. */
private val TV_DIALOG = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false)

/**
 * A new Cosmotune TV release is out: the Orbit "What's new" card ([WhatsNewDialog] on the TV's surface), with the release's
 * Highlights as rows, the version line, and Update now beside Later, the peach action holding D-pad focus and every button
 * showing the TV's ring. Update now downloads the APK and hands it to the system installer without leaving the app, as the
 * phone's update sheet does.
 *
 * Closing the card mid-download only hides it: the download keeps going, and Settings' "Software Update" row reopens the card
 * onto wherever it got to. A TV has no browser to send anyone to, so "Full release notes" shows the release page as a code to
 * scan with a phone in the rows' place, and a release with no TV APK on it offers the same code to get the update with a phone.
 */
@Composable
fun TvUpdateDialog(update: AppUpdateChecker.UpdateInfo, onDismiss: () -> Unit) {
    val state by AppUpdateChecker.download.collectAsState()
    val context = LocalContext.current.applicationContext
    var showCode by remember { mutableStateOf(false) }
    val download = { downloadScope.launch { AppUpdateChecker.downloadApk(context) }; Unit }
    val later = WhatsNewAction("Later", onDismiss)
    val notes = { showCode = !showCode }
    val card = when {
        update.apkUrl == null -> WhatsNew(update.version, update.highlights, WhatsNewAction("OK", onDismiss), onFullNotes = notes, meta = versionLine(update))
        else -> when (val s = state) {
            is DownloadState.Downloading -> WhatsNew(
                update.version, update.highlights, WhatsNewAction("Hide", onDismiss),
                WhatsNewAction("Cancel download", AppUpdateChecker::cancelDownload), notes, progress = s.fraction,
            )
            is DownloadState.Ready -> WhatsNew(
                update.version, update.highlights,
                WhatsNewAction("Install now") { AppUpdateChecker.installApk(context, s.file) }, later, notes,
                meta = "Cosmotune ${update.version} is ready to install",
            )
            is DownloadState.Failed -> WhatsNew(
                update.version, update.highlights, WhatsNewAction("Try again", download),
                WhatsNewAction("Later") {
                    AppUpdateChecker.resetDownload()
                    onDismiss()
                },
                notes, problem = s.message,
            )
            DownloadState.Idle -> WhatsNew(update.version, update.highlights, WhatsNewAction("Update now", download), later, notes, meta = versionLine(update))
        }
    }
    val link = when {
        showCode -> "Back to what's new"
        update.apkUrl == null -> "Get it with your phone"
        else -> null
    }
    WhatsNewDialog(card.showingCode(showCode, link), onDismiss, WhatsNewSurface.TV, TV_DIALOG) {
        if (showCode) {
            ReleaseCode(
                update.releaseUrl,
                if (update.apkUrl == null) "Scan with your phone to get this update from GitHub." else "Scan with your phone to read the full release notes.",
            )
        }
    }
}

/**
 * The first launch after an update on the TV: the same card with the running build's own Highlights and a single Got it. The
 * full notes are a code to scan, as on the update card.
 */
@Composable
fun TvWhatsNewDialog(welcome: AppUpdateChecker.WelcomeInfo, onDismiss: () -> Unit) {
    var showCode by remember { mutableStateOf(false) }
    val card = WhatsNew(welcome.version, welcome.highlights, WhatsNewAction("Got it", onDismiss), onFullNotes = { showCode = !showCode })
    WhatsNewDialog(card.showingCode(showCode, if (showCode) "Back to what's new" else null), onDismiss, WhatsNewSurface.TV, TV_DIALOG) {
        if (showCode) ReleaseCode(welcome.releaseUrl, "Scan with your phone to read the full release notes.")
    }
}

private fun versionLine(update: AppUpdateChecker.UpdateInfo) = whatsNewMeta(BuildConfig.VERSION_NAME, update.version, update.apkSize)

/** The card with the code to scan in its rows' place while [code], and its link's words changed to [label] when given. */
private fun WhatsNew.showingCode(code: Boolean, label: String?): WhatsNew =
    if (!code && label == null) this
    else WhatsNew(version, if (code) emptyList() else highlights, primary, secondary, onFullNotes, meta, progress, problem, title, overline, label ?: fullNotesLabel)

/** The release page as a code to scan, with what scanning it does under it. */
@Composable
private fun ColumnScope.ReleaseCode(url: String, caption: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TvQrCodeView(content = url, size = 132.dp)
        Text(caption, style = TvType.Caption, color = TvGlass.TextSecondary, textAlign = TextAlign.Center)
    }
}
