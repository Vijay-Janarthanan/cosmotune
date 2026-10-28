package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Reuses the app's selected language and string catalogues on both hosts; never a second language setting. */
object MangaTextPlatform {
    @Volatile var language: StateFlow<String> = MutableStateFlow("")
    @Volatile var lookup: (String, String) -> String = { _, fallback -> fallback }
}
private val keys = ConcurrentHashMap<String, String>()
fun mangaTextKey(english: String): String = keys.computeIfAbsent(english) { value ->
    "manga_" + value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40) + "_" +
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(3).joinToString("") { "%02x".format(it) }
}
@Composable internal expect fun mangaText(english: String): String
