package com.cosmotune.ui.watch

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/** The system's chooser of video apps; the headers go as MX Player's "headers" pairs and the title as "title". */
@Composable internal actual fun rememberExternalPlayers(): List<ExternalPlayer> {
    val context = LocalContext.current
    return remember(context) {
        listOf(ExternalPlayer("Another app") { stream, title ->
            val uri = externalUri(context, stream.url)
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                .putExtra("title", title)
                .putExtra("headers", stream.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
            if (uri.scheme == "content") {
                // A downloaded copy: the other app may read this one file, for as long as it has it open.
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).clipData = ClipData.newRawUri(title, uri)
            }
            context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
    }
}

/**
 * The link another app is handed for [url]. Android refuses bare `file:` links between apps, so a downloaded copy goes as a
 * `content:` link from the app's FileProvider (which serves only Watch's download folders); a copy kept anywhere else can't be
 * handed over, and the menu says so.
 */
private fun externalUri(context: Context, url: String): Uri {
    val parsed = Uri.parse(url)
    if (parsed.scheme != "file") return parsed
    val file = File(parsed.path ?: error("This copy has no file."))
    if (!file.isFile) error("The downloaded file is missing.")
    return runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }
        .getOrElse { error("Only copies in Watch's download folder can be opened in another app.") }
}
