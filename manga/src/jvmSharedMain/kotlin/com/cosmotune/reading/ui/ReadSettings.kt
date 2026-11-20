package com.cosmotune.reading.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.ui.ReadChip
import com.cosmotune.manga.ui.ReadChipRow
import com.cosmotune.manga.ui.ReadOverline
import com.cosmotune.manga.ui.ReadType
import com.cosmotune.reading.data.ReadingPreferences
import com.cosmotune.ui.collectAsUiState
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.rememberUiCoroutineScope
import com.cosmotune.ui.components.CosmoIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings › Read (board frame 15), which used to be only a way into the mode: the reader's look for every book (page colour,
 * typeface, size, spacing, margins, layout, brightness, keep screen on; the same sheet the reader opens) and the connected
 * libraries (OPDS servers), which Browse lists under "My libraries".
 */
@Composable
fun ReadSettingsSection(modifier: Modifier = Modifier) {
    val repository = rememberReadingRepository().getOrNull()
    val look = orbitLook()
    if (repository == null) {
        Text("Your reading library could not be opened. Check storage access and try again.", style = ReadType.body(), color = look.dim, modifier = modifier.padding(16.dp))
        return
    }
    val state by repository.state.collectAsUiState()
    val scope = rememberUiCoroutineScope()
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(value: ReadingPreferences) = scope.launch {
        try { repository.updatePreferences(value); error = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Reader settings could not be saved." }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("settings:read")) {
        error?.let { Text(it, style = ReadType.body(), color = look.accent, modifier = Modifier.padding(horizontal = 20.dp)) }
        ReadOverline("Reader", inset = 16.dp)
        ReadingAppearanceChoices(state.preferences, reflowable = true, onChange = ::update)
        ReadOverline("Connected libraries", inset = 16.dp)
        Text(if (state.sources.isEmpty()) "Connect a library's OPDS address to browse and borrow from it in Browse." else state.sources.joinToString(" · ") { it.name },
            style = ReadType.body(), color = look.dim, modifier = Modifier.padding(horizontal = 20.dp))
        ReadChipRow(Modifier.padding(top = 8.dp)) {
            ReadChip(if (state.sources.isEmpty()) "Connect library" else "Manage libraries", false, { connecting = true }, Modifier.testTag("settings:read:libraries"), icon = CosmoIcons.Plus)
        }
    }
    if (connecting) BookConnections(repository, onDismiss = { connecting = false }, onError = { error = it })
}
