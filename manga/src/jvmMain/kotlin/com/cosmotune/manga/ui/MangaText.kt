@file:JvmName("DesktopMangaText")
package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState

@Composable internal actual fun mangaText(english: String): String {
    MangaTextPlatform.language.collectAsState().value
    return MangaTextPlatform.lookup(mangaTextKey(english), english)
}
