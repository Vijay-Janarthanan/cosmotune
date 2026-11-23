package com.cosmotune.manga.ui

import android.content.ContentValues
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable internal actual fun rememberMangaShare(onResult: (String) -> Unit): MangaShare {
    val context = LocalContext.current
    val result = rememberUpdatedState(onResult)
    return remember(context) {
        MangaShare(
            sharePage = { file ->
                runCatching {
                    // Only the app's shared cache folder is handed to other apps (res/xml/file_paths.xml).
                    val folder = File(context.cacheDir, "shared").apply { mkdirs() }
                    val copy = File(folder, "manga-page.jpg")
                    file.copyTo(copy, overwrite = true)
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", copy)
                    val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share page").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { result.value("This page could not be shared") }
            },
            savePage = { file, name ->
                runCatching {
                    val display = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").take(80).ifBlank { "Manga page" } + ".jpg"
                    if (Build.VERSION.SDK_INT >= 29) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, display); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Cosmotune")
                        }
                        val uri = requireNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                        requireNotNull(context.contentResolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
                    } else {
                        val folder = File(requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)), "Cosmotune").apply { mkdirs() }
                        file.copyTo(File(folder, display), overwrite = true)
                    }
                    result.value("Saved to Pictures")
                }.onFailure { result.value("This page could not be saved") }
            },
            shareLink = { title, url ->
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, url)
                    context.startActivity(Intent.createChooser(send, "Share series").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { result.value("This series could not be shared") }
            },
            shareLabel = "Share page",
        )
    }
}
