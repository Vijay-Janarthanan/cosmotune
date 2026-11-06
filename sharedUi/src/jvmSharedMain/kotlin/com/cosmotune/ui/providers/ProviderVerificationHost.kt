package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.rememberIsForeground
import com.cosmotune.providers.ProviderAutoVerification
import com.cosmotune.providers.ProviderHub
import com.cosmotune.providers.ProviderManager
import com.cosmotune.providers.ProviderVerificationCoordinator
import com.cosmotune.providers.ProviderVerificationOutcome
import com.cosmotune.providers.ProviderVerificationRequest
import com.cosmotune.providers.ProviderVerificationTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the provider verification windows [ProviderAutoVerification] asks for, from the root of each app: the Android
 * Activity in its private process, or the desktop's isolated browser window. The window closes by itself as soon as the grant
 * has been exchanged, and the search that needed it is asked again. It also tells the coordinator whether the app is in front,
 * so nothing opens over another app.
 *
 * After [ProviderVerificationCoordinator.HELP_AFTER_MS] a small sheet offers to open the page again, copy its link, or paste the
 * callback (checked against the active challenge's address and state) for a browser that cannot return by itself.
 */
@Composable
fun ProviderVerificationHost() {
    val foreground = rememberIsForeground()
    DisposableEffect(foreground) {
        ProviderAutoVerification.setForeground(foreground)
        onDispose { ProviderAutoVerification.setForeground(false) }
    }
    val request by ProviderAutoVerification.request.collectAsState()
    val hub by ProviderHub.state.collectAsState()
    val manager = hub.manager
    val open = rememberProviderVerificationWindow { id, outcome -> ProviderAutoVerification.finish(id, outcome) }
    val current = request
    var opened by remember { mutableIntStateOf(0) }
    if (current == null || manager == null) return
    DisposableEffect(current.id, opened) {
        val window = runCatching { open(manager, current) }
            .onFailure { ProviderAutoVerification.finish(current.id, ProviderVerificationOutcome.FAILED) }.getOrNull()
        onDispose { runCatching { window?.close() } }
    }
    var help by remember(current.id) { mutableStateOf(false) }
    LaunchedEffect(current.id) {
        delay(ProviderVerificationCoordinator.HELP_AFTER_MS)
        help = true
    }
    if (help) VerificationHelpSheet(manager, current, onOpenAgain = { opened++ },
        onCancel = { ProviderAutoVerification.finish(current.id, ProviderVerificationOutcome.CLOSED) })
}

/** Whether [input] is a result of [target]'s own challenge: its exact callback with the right state, or a bare signed grant. */
internal fun acceptsVerificationPaste(target: ProviderVerificationTarget, input: String): Boolean {
    val value = input.trim()
    return value.length in 1..16_384 &&
        (target.completedCallback(value) != null || target.signed && value.matches(Regex("grant=[A-Za-z0-9_-]{1,8192}")))
}

@Composable
private fun VerificationHelpSheet(manager: ProviderManager, request: ProviderVerificationRequest, onOpenAgain: () -> Unit, onCancel: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pasted by remember(request.id) { mutableStateOf("") }
    var error by remember(request.id) { mutableStateOf<String?>(null) }
    var busy by remember(request.id) { mutableStateOf(false) }
    com.cosmotune.ui.sheets.CosmoAlertDialog(
        onDismissRequest = {},
        title = "Waiting for ${request.target.title}",
        content = {
            Column(Modifier.testTag("provider-verification:help"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Finish the check on the provider's page. It closes by itself when it is done. If it did not open or cannot return, open it again, copy its link into your browser, or paste the callback address or grant=… text here.",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cosmotune.ui.sheets.CosmoDialogQuietButton("Open again", onOpenAgain, enabled = !busy)
                    com.cosmotune.ui.sheets.CosmoDialogQuietButton("Copy link", { clipboard.setText(AnnotatedString(request.target.url)) }, enabled = !busy)
                }
                OutlinedTextField(pasted, { if (it.length <= 16_384) { pasted = it; error = null } }, enabled = !busy,
                    label = { Text(if (request.target.signed) "Callback URL or grant=…" else "Callback URL") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("provider-verification:paste"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmLabel = "Continue",
        onConfirm = {
                val value = pasted.trim()
                if (!acceptsVerificationPaste(request.target, value)) {
                    error = "This is not the result of the current verification. Check that it is from this page and has not expired."
                    return@CosmoAlertDialog
                }
                scope.launch {
                    busy = true
                    try {
                        // The coordinator sees the saved session and closes everything.
                        manager.completeAuthentication(request.provider, value, request.target)
                        pasted = ""
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "The provider did not accept this result. Open the page again to retry." }
                    finally { busy = false }
                }
            },
        confirmEnabled = !busy && pasted.isNotBlank(),
        dismissLabel = "Cancel",
        onDismiss = onCancel,
        dismissEnabled = !busy,
    )
}

/** Opens [ProviderVerificationRequest]'s window and reports how it ended; the returned handle closes it. */
@Composable internal expect fun rememberProviderVerificationWindow(
    onOutcome: (Long, ProviderVerificationOutcome) -> Unit,
): (ProviderManager, ProviderVerificationRequest) -> AutoCloseable
