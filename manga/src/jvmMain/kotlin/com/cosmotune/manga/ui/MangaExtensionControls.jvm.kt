package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import com.cosmotune.manga.data.MangaRepository

@Composable internal actual fun MangaExtensionControls(repository: MangaRepository, onError: (String) -> Unit) = Unit
