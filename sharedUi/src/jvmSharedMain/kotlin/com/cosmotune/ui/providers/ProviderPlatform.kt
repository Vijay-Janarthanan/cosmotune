package com.cosmotune.ui.providers

import androidx.compose.runtime.Composable
import java.io.InputStream

@Composable internal expect fun rememberProviderPackageImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit
@Composable internal expect fun rememberProviderAuthOpen(): (String) -> Unit
