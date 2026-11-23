package com.cosmotune.ui.watch

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The system's chooser of video apps; the headers go as MX Player's "headers" pairs and the title as "title". */
@Composable internal actual fun rememberExternalPlayers(): List<ExternalPlayer> {
    val context = LocalContext.current
    return remember(context) {
        listOf(ExternalPlayer("Another app") { stream, title ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(stream.url), "video/*")
                .putExtra("title", title)
                .putExtra("headers", stream.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
}
