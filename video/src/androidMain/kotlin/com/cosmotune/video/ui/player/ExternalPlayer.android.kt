package com.cosmotune.video.ui.player

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.player.ExternalStream

/** The phone and the TV: the system's "Open with" list of the apps that play video (VLC, MX Player, Kodi, a browser). */
@Composable
internal actual fun rememberExternalPlayer(): ExternalPlayer {
    val context = LocalContext.current
    return remember(context) {
        object : ExternalPlayer {
            override fun open(stream: ExternalStream, title: String): Boolean {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(stream.url), stream.mimeType)
                    .putExtra("title", title)
                    .putExtra(Intent.EXTRA_TITLE, title)
                val chooser = Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    context.startActivity(chooser)
                    true
                } catch (_: ActivityNotFoundException) {
                    false
                }
            }
        }
    }
}
