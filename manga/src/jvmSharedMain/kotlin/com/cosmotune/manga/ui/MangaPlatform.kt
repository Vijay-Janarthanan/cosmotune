package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaFolderImport
import java.io.InputStream

@Composable internal expect fun rememberMangaRepository(): Result<MangaRepository>
@Composable internal expect fun rememberMangaImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit
@Composable internal expect fun rememberMangaFolderImport(onPicked: (Result<MangaFolderImport>) -> Unit): () -> Unit
@Composable internal expect fun MangaBackHandler(enabled: Boolean, onBack: () -> Unit)
@Composable internal expect fun MangaExtensionControls(repository: MangaRepository, onError: (String) -> Unit)
@Composable internal expect fun rememberMangaVerificationLauncher(onResult: (com.cosmotune.manga.data.MangaVerificationRequest, Result<com.cosmotune.manga.data.MangaBrowserProof>) -> Unit): (com.cosmotune.manga.data.MangaVerificationRequest) -> Unit
