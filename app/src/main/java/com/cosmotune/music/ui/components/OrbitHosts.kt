package com.cosmotune.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.cosmoAmbientGround

/*
 * The phone root's hosts for a sheet and a page, out of the root's own body: written out at each call they made `CosmotuneApp` one
 * method too large for the JVM.
 */

/**
 * A sheet of the phone in Orbit: the song sheet's surface (SurfaceSheetSwitch on CosmoSheetSurface), opened whole, the scrim and
 * the drag Material's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitSheetHost(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    com.cosmotune.ui.sheets.SurfaceSheetSwitch(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** A page laid over another that must hide it: the Orbit ground (the page's gradient and cover wash). */
@Composable
fun OrbitPageGround(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().cosmoAmbientGround(), content = content)
}
