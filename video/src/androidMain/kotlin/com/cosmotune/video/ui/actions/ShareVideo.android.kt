package com.cosmotune.video.ui.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Android: the system's share chooser with the address as the text and the title as the subject (spec 5.12). */
@Composable
internal actual fun rememberShareVideo(onCopied: () -> Unit): ShareVideo {
    val context = LocalContext.current
    return remember(context) { ShareVideo { title, url -> shareWithChooser(context, title, url) } }
}

private fun shareWithChooser(context: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TITLE, title)
        .putExtra(Intent.EXTRA_TEXT, url)
    val chooser = Intent.createChooser(send, null)
    // Started from the activity it is a plain chooser; from any other context it needs a task of its own.
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: ActivityNotFoundException) {
        // Nothing on the phone can take text: there is nothing to share with.
    }
}
