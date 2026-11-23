package com.cosmotune.ui.watch

import androidx.compose.runtime.Composable

/** Asks where to save a text file named [name] (the device's save picker) and writes [text] there; [onDone] says whether it was saved. */
@Composable internal expect fun rememberTextFileSaver(): (name: String, text: String, onDone: (Boolean) -> Unit) -> Unit

/** Opens the device's file picker for a text file (at most 32 MB) and hands its text to [onPicked]. */
@Composable internal expect fun rememberTextFileOpener(onPicked: (String) -> Unit): () -> Unit
