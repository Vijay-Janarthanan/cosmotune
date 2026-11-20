package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaFolderImport
import java.io.InputStream

@Composable internal expect fun rememberMangaRepository(): Result<MangaRepository>
@Composable internal expect fun rememberMangaImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit
@Composable internal expect fun rememberMangaFolderImport(onPicked: (Result<MangaFolderImport>) -> Unit): () -> Unit
@Composable internal expect fun MangaBackHandler(enabled: Boolean, onBack: () -> Unit)
/** Keeps the screen on while [enabled] (a reader is open); nothing on the desktop. */
@Composable internal expect fun ReaderKeepScreenOn(enabled: Boolean)
/** The way to hold the screen in its current orientation while reading, or null where a window has none (the desktop). */
@Composable internal expect fun rememberReaderRotation(): ReaderRotation?

/** Holds the screen in the orientation it is in ([locked]), or lets it turn again. */
internal class ReaderRotation(val locked: Boolean, val toggle: () -> Unit)
@Composable internal expect fun MangaExtensionControls(repository: MangaRepository, onError: (String) -> Unit)
@Composable internal expect fun rememberMangaVerificationLauncher(onResult: (com.cosmotune.manga.data.MangaVerificationRequest, Result<com.cosmotune.manga.data.MangaBrowserProof>) -> Unit): (com.cosmotune.manga.data.MangaVerificationRequest) -> Unit
