package com.cosmotune.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cosmotune.music.R
import com.cosmotune.ui.orbit.OrbitBackHeader
import com.cosmotune.ui.orbit.orbitLook

/** Shares the existing settings theme while keeping pairing steps in their own back stack. */
@Composable
internal fun WearOsSettingsDialog(onDismiss: () -> Unit) {
    val look = orbitLook()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(look.ground).statusBarsPadding()) {
            OrbitBackHeader(
                kind = stringResource(R.string.wear_os_section),
                onBack = onDismiss,
                backLabel = stringResource(android.R.string.cancel),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            WearOsScreen(
                contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
