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

/** True where Manga can be locked: phones and tablets with a screen lock set up, and every computer (with Manga's own PIN at least). */
@Composable internal expect fun mangaLockAvailable(): Boolean

/**
 * What the lock asks for, in the listener's words ("your screen lock", "Windows Hello"), or null where only Manga's own PIN
 * can lock it (a computer without a sign-in prompt Manga can ask for). Empty while it is still being found out.
 */
@Composable internal expect fun rememberMangaLockPrompt(): String?

/** What Hide from screenshots also does here, or null where Manga's window cannot be kept out of screenshots. */
@Composable internal expect fun mangaHideDetail(): String?

/**
 * Shows [content] once the device's screen lock was passed (Mihon's app lock); until then a plain page with Unlock. The lock is
 * asked again after Manga has been away for five minutes. Where there is no screen lock, [content] shows at once.
 */
@Composable internal expect fun MangaLockGate(enabled: Boolean, pin: String?, content: @Composable () -> Unit)

/** Keeps the window out of screenshots, recordings and the recent-apps preview while [enabled] (Mihon's secure screen). */
@Composable internal expect fun MangaSecureScreen(enabled: Boolean)
/** The way to hold the screen in its current orientation while reading, or null where a window has none (the desktop). */
@Composable internal expect fun rememberReaderRotation(): ReaderRotation?

/** Holds the screen in the orientation it is in ([locked]), or lets it turn again. */
internal class ReaderRotation(val locked: Boolean, val toggle: () -> Unit)
@Composable internal expect fun MangaExtensionControls(repository: MangaRepository, onError: (String) -> Unit)
@Composable internal expect fun rememberMangaVerificationLauncher(onResult: (com.cosmotune.manga.data.MangaVerificationRequest, Result<com.cosmotune.manga.data.MangaBrowserProof>) -> Unit): (com.cosmotune.manga.data.MangaVerificationRequest) -> Unit
