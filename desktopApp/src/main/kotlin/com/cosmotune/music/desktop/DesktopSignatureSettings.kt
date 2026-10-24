package com.cosmotune.music.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.music.data.canvas.ArtistSignatureRepository
import com.cosmotune.music.data.canvas.ArtistSignatureRepository.SaveCheck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The artist-signature provider's two stored values. The store is passed in so a test can use a map instead of the
 * real preferences; the app's instance is [DesktopSignatureSettings].
 */
internal class DesktopSignatureSettingsModel(
    private val readBoolean: (String, Boolean) -> Boolean,
    private val readString: (String, String) -> String,
    private val saveBoolean: (String, Boolean) -> Unit,
    private val saveString: (String, String) -> Unit,
    private val forgetSignatures: () -> Unit = ArtistSignatureRepository::clear,
    private val check: suspend (String) -> SaveCheck = ArtistSignatureRepository::checkForSave,
) {
    private val _enabled = MutableStateFlow(readBoolean(KEY_ENABLED, false))
    private val _url = MutableStateFlow(ArtistSignatureRepository.normalizeBaseUrl(readString(KEY_URL, "")))
    val enabled: StateFlow<Boolean> = _enabled
    val url: StateFlow<String> = _url

    fun enable(value: Boolean) {
        _enabled.value = value
        saveBoolean(KEY_ENABLED, value)
    }

    /**
     * What the editor's Save does with [draft]: a blank address forgets the provider, a malformed or unreachable one
     * changes nothing, and a healthy one is stored, turned on and the cached signatures of the old address dropped.
     */
    suspend fun submit(draft: String): SaveCheck {
        val result = check(draft)
        when (result) {
            SaveCheck.Remove -> store("", enabled = false)
            is SaveCheck.Ready -> store(result.baseUrl, enabled = true)
            SaveCheck.Invalid, SaveCheck.Unreachable -> Unit
        }
        return result
    }

    private fun store(value: String, enabled: Boolean) {
        _url.value = value
        saveString(KEY_URL, value)
        forgetSignatures()
        enable(enabled)
    }

    companion object {
        const val KEY_ENABLED = "signature_api_enabled"
        const val KEY_URL = "signature_api_url"
    }
}

internal val DesktopSignatureSettings: DesktopSignatureSettingsModel by lazy {
    val persistence = DesktopPersistence()
    DesktopSignatureSettingsModel(
        readBoolean = persistence::boolean,
        readString = persistence::string,
        saveBoolean = persistence::saveBoolean,
        saveString = persistence::saveString,
    )
}

@Composable
internal fun DesktopArtistSignatureSettings(settings: DesktopSignatureSettingsModel = DesktopSignatureSettings) {
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val stored by settings.url.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf(false) }
    var draft by remember(editor) { mutableStateOf(stored) }
    var busy by remember(editor) { mutableStateOf(false) }
    var error by remember(editor) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val subtitle = DesktopStrings["artist_signatures_subtitle", "Shows a handwritten signature when Apple Music has no title logo"]
    SettingsToggle(DesktopStrings["artist_signatures", "Artist signatures"], subtitle, enabled, settings::enable)
    SettingsNavigationRow(
        DesktopStrings["artist_signatures_url", "Signature API URL"],
        if (stored.isBlank()) DesktopStrings["artist_signatures_unconfigured", "Not configured — tap to set the API URL"]
        else DesktopStrings["connected", "Connected"],
        onClick = { editor = true },
    )
    if (editor) AlertDialog(
        onDismissRequest = { if (!busy) editor = false },
        title = { Text(DesktopStrings["artist_signatures_url", "Signature API URL"]) },
        text = {
            Column {
                Text(subtitle)
                OutlinedTextField(
                    draft,
                    { draft = it; error = null },
                    placeholder = { Text(DesktopStrings["artist_signatures_url_hint", "https://signatures.example.com"]) },
                    enabled = !busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    when (settings.submit(draft)) {
                        SaveCheck.Remove, is SaveCheck.Ready -> editor = false
                        SaveCheck.Invalid, SaveCheck.Unreachable ->
                            error = DesktopStrings["artist_signatures_test_failed", "Could not reach the API at that URL"]
                    }
                    busy = false
                }
            }) { Text(DesktopStrings["save", "Save"]) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { editor = false }) { Text(DesktopStrings["cancel", "Cancel"]) } },
    )
}
