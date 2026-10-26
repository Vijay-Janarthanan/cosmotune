package com.cosmotune.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cosmotune.providers.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/** Browser sign-in uses only this package's protected pending state and authorization code. */
@Composable
fun ProviderAuthenticationEntry(manager: ProviderManager, installed: ProviderInstall,
    openBrowser: ((String) -> Unit)? = null) {
    val state by manager.store.state.collectAsState()
    val signedProvider = installed.manifest.signedSession != null
    val auth = state.auth[installed.manifest.name] ?: JsonObject(emptyMap())
    if (!signedProvider && auth.isEmpty()) return
    val pending = auth["pending"] as? JsonObject
    val signed = auth["signedSession"] as? JsonObject
    val signedExpiry = (signed?.get("expires_at") as? JsonPrimitive)?.contentOrNull
    val authenticated = (auth["access_token"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
        signed?.get("blocked") != JsonPrimitive(true) &&
        (signed?.get("session_id") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true &&
        runCatching { java.time.Instant.parse(signedExpiry).toEpochMilli() > System.currentTimeMillis() }.getOrDefault(false)
    val hasCredentials = (auth["access_token"] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
        (signed?.get("session_id") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true ||
        (signed?.get("session_secret") as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true
    if (pending == null && !hasCredentials && !signedProvider) return
    var open by remember(installed.manifest.name) { mutableStateOf(false) }
    var callback by remember(installed.manifest.name) { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var browserError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var automatic by remember(installed.manifest.name) { mutableStateOf(false) }
    val platformBrowser = rememberProviderAuthOpen(manager, installed) { error = true; browserError = true }
    val launchBrowser = openBrowser ?: platformBrowser
    val scope = rememberCoroutineScope()
    val completedGrant = pending?.get("kind") == JsonPrimitive("signed-grant") && pending["completed"] == JsonPrimitive(true)
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pending) {
        while (pending != null) { clock = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) }
    }
    val expired = pending != null && clock - ((pending["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0L) >=
        if (pending["kind"] == JsonPrimitive("signed-grant")) 180_000L else 600_000L
    LaunchedEffect(pending?.get("completed"), authenticated) {
        if (authenticated || (pending?.get("completed") as? JsonPrimitive)?.booleanOrNull == true) { open = false; callback = "" }
    }
    fun disconnect() { scope.launch {
        busy = true; error = false
        try { manager.clearAuthentication(installed.manifest.name); open = false; callback = "" }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { busy = false }
    } }
    if (signedProvider) {
        val operator = runCatching { java.net.URI(installed.manifest.signedSession?.get("baseUrl")?.jsonPrimitive?.content).host }.getOrNull()
        Text("Verification service: ${operator ?: "Configured provider"}", style = MaterialTheme.typography.bodySmall)
        if (authenticated) Text("Provider access verified", style = MaterialTheme.typography.bodySmall)
        if (!authenticated && (pending == null || expired || completedGrant)) TextButton(enabled = !busy && installed.enabled && installed.manifest.permissions.storage,
            onClick = { scope.launch {
                busy = true; error = false
                try { if (!manager.verifyProviderAccess(installed.manifest.name)) open = true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                finally { busy = false }
            } }) { Text(when { expired -> "Restart provider verification"; completedGrant -> "Finish provider verification"; else -> "Verify provider access" }) }
    }
    if (pending != null && !expired && (pending["completed"] as? JsonPrimitive)?.booleanOrNull != true) {
        TextButton(onClick = { open = true }, enabled = !busy && installed.enabled && installed.manifest.permissions.storage) { Text("Continue provider sign-in") }
    } else if (pending != null && !expired) Text(if (pending["kind"] == JsonPrimitive("signed-grant"))
        "Verification received. Finish provider verification to establish the session."
        else "Authorization code received. Use this provider’s sign-in or check action to finish.", style = MaterialTheme.typography.bodySmall)
    if (expired) Text("Provider sign-in expired. Restart verification or cancel it.", style = MaterialTheme.typography.bodySmall)
    if (hasCredentials && !authenticated && pending == null) Text("Provider session expired. Sign in again or disconnect.", style = MaterialTheme.typography.bodySmall)
    if (hasCredentials || pending != null) TextButton(onClick = ::disconnect, enabled = !busy) { Text(if (hasCredentials) "Disconnect provider account" else "Cancel provider sign-in") }
    if (error && !open) Text(if (completedGrant) "Verification received, but session setup failed. Finish provider verification to retry."
        else "Provider sign-in could not be updated. Check its settings and retry.", color = MaterialTheme.colorScheme.error)
    if (!open) return
    AlertDialog(onDismissRequest = { if (!busy) { open = false; callback = "" } }, title = { Text("${installed.manifest.title} sign-in") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (signedProvider) "Complete verification with this provider's service. If the browser does not return, use Copy link on its page and paste the callback URL or grant=… text below."
                else if (automatic) "Finish signing in with this provider, then return to Cosmotune. If the browser cannot return the result, paste its complete callback URL below."
                else "Sign in with this provider in your browser. Cosmotune can receive supported local callbacks automatically; otherwise return the complete callback URL here.")
            TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; error = false; browserError = false
                    var request: ProviderBrowserAuthentication? = null
                    try { request = manager.prepareBrowserAuthentication(installed.manifest.name)
                        automatic = request.automaticCallback; launchBrowser(request.url) }
                    catch (cancelled: CancellationException) { request?.close(); throw cancelled }
                    catch (_: Exception) { request?.close(); browserError = true; error = true }
                    finally { busy = false }
                }
            }) { Text("Open sign-in page") }
            OutlinedTextField(callback, { if (it.length <= 16_384) callback = it }, enabled = !busy, label = { Text(if (signedProvider) "Callback URL or grant=…" else "Callback URL") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (error) Text(if (browserError) "The sign-in page could not be opened. Restart sign-in or return its complete callback URL here."
                else "The callback was not accepted. Check that it is from this sign-in and has not expired.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !busy && callback.isNotBlank(), onClick = { scope.launch {
        busy = true; error = false; browserError = false
        try { manager.completeAuthentication(installed.manifest.name, callback); callback = ""; open = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = true }
        finally { busy = false }
    } }) { Text("Continue") } }, dismissButton = { TextButton(enabled = !busy, onClick = { open = false; callback = "" }) { Text("Close") } })
}
