package com.cosmotune.video.ui.actions

import androidx.compose.runtime.Composable

/**
 * Where the download sheet stands (spec 5.16): composed once by the shell's action host, with the [ask] that
 * [VideoActionHandler.download] or [VideoActionHandler.downloadBatch] set (null when nothing is asked); [onDismiss] clears it.
 *
 * Plan task Y-32 fills this body (the remembered choice or the sheet on the phone and the dialog on the desktop, then
 * `downloads.enqueue` or `enqueueBatch` and the toast) and keeps the signature. Until then nothing is shown.
 */
@Composable
fun DownloadOptionsHost(ask: DownloadAsk?, onDismiss: () -> Unit) {
    // Y-32 fills this body.
}
