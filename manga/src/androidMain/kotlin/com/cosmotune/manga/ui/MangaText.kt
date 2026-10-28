@file:JvmName("AndroidMangaText")
package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration

@Composable internal actual fun mangaText(english: String): String {
    val context = LocalContext.current
    LocalConfiguration.current
    val identifier = context.resources.getIdentifier(mangaTextKey(english), "string", context.packageName)
    return if (identifier == 0) english else context.resources.getString(identifier)
}
