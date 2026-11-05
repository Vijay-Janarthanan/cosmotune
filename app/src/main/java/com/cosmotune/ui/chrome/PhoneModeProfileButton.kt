package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cosmotune.design.AppMode
import com.cosmotune.music.R
import com.cosmotune.music.ui.MainViewModel
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.ui.components.CosmoIcons

/** Manga and Podio keep the real account/mode drawer when Music's root leaves composition. */
@Composable
fun PhoneModeProfileButton(settings: CosmoLookSettings, mode: AppMode) {
    val model: MainViewModel = viewModel()
    val account by model.account.collectAsStateWithLifecycle()
    fun open(destination: ModeNavigationRequest.Destination) {
        ModeNavigationRequest.open(destination, mode)
        settings.setMode(AppMode.MUSIC)
    }
    ProfileButtonSwitch(
        account = account,
        onClick = { open(ModeNavigationRequest.Destination.ACCOUNT) },
        entries = listOf(
            CosmoDrawerEntry(CosmoIcons.Settings, stringResource(R.string.settings),
                onClick = { open(ModeNavigationRequest.Destination.SETTINGS) }),
            CosmoDrawerEntry(CosmoIcons.Library, "Sources & providers",
                onClick = { open(ModeNavigationRequest.Destination.SOURCES) }),
        ),
    )
}
