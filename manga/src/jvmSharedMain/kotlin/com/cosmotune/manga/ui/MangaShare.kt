package com.cosmotune.manga.ui

import androidx.compose.runtime.Composable
import java.io.File

/**
 * Handing a page or a series out of Cosmotune: [sharePage] (the share sheet on a phone, the clipboard on a computer),
 * [savePage] (Pictures on a phone, a file chosen on a computer) and [shareLink] (a series' address). Each reports a short
 * line for the listener through [onResult].
 */
internal class MangaShare(val sharePage: (File) -> Unit, val savePage: (File, String) -> Unit, val shareLink: (String, String) -> Unit,
    /** What sharing a page is called here: "Share page" or "Copy page". */
    val shareLabel: String)

@Composable internal expect fun rememberMangaShare(onResult: (String) -> Unit): MangaShare
