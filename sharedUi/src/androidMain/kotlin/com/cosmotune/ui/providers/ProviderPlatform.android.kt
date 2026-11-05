package com.cosmotune.ui.providers

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import java.io.InputStream
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.cosmotune.providers.ProviderManager
import com.cosmotune.providers.ProviderInstall
import com.cosmotune.providers.ProviderVerificationTarget

@Composable internal actual fun rememberProviderAuthOpen(manager: ProviderManager, installed: ProviderInstall,
    onFailure: () -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onFailure)
    val target = remember(manager, installed.manifest.name) { java.util.concurrent.atomic.AtomicReference<ProviderVerificationTarget?>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected = target.getAndSet(null)
        val answer = result.data?.getStringExtra(ProviderVerificationActivity.RESULT)
        if (result.resultCode == android.app.Activity.RESULT_OK && expected != null && answer != null) {
            scope.launch {
                try {
                    require(expected.completedCallback(answer) != null || expected.signed && answer.matches(Regex("grant=[A-Za-z0-9_-]{1,8192}")))
                    manager.completeAuthentication(installed.manifest.name, answer, expected)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { latest.value() }
            }
        } else if (result.resultCode == android.app.Activity.RESULT_FIRST_USER) latest.value()
    }
    return remember(context, manager, installed.manifest.name, launcher) { { url ->
        require(url.startsWith("https://"))
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val page = manager.verificationTarget(installed.manifest.name)
            require(page.url == url)
            target.set(page)
            launcher.launch(android.content.Intent(context, ProviderVerificationActivity::class.java)
                .putExtra(ProviderVerificationActivity.TARGET, Json.encodeToString(page)))
        } else context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    } }
}

@Composable internal actual fun rememberProviderPackageImport(onPicked: (String, () -> InputStream) -> Unit): () -> Unit {
    val context = LocalContext.current
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Provider package"
            latest.value(name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open provider package" } }
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) } }
}

/**
 * The automatic verification's window: the same private, separate-process [ProviderVerificationActivity]. Its result is checked
 * against the request's own target before the grant is exchanged; closing it without a result reports CLOSED.
 */
@Composable internal actual fun rememberProviderVerificationWindow(
    onOutcome: (Long, com.cosmotune.providers.ProviderVerificationOutcome) -> Unit,
): (ProviderManager, com.cosmotune.providers.ProviderVerificationRequest) -> AutoCloseable {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onOutcome)
    val active = remember { java.util.concurrent.atomic.AtomicReference<Pair<ProviderManager, com.cosmotune.providers.ProviderVerificationRequest>?>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (manager, request) = active.getAndSet(null) ?: return@rememberLauncherForActivityResult
        val answer = result.data?.getStringExtra(ProviderVerificationActivity.RESULT)
        if (result.resultCode == android.app.Activity.RESULT_OK && answer != null) {
            scope.launch {
                val outcome = try {
                    require(acceptsVerificationPaste(request.target, answer))
                    manager.completeAuthentication(request.provider, answer, request.target)
                    com.cosmotune.providers.ProviderVerificationOutcome.VERIFIED
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { com.cosmotune.providers.ProviderVerificationOutcome.FAILED }
                latest.value(request.id, outcome)
            }
        } else latest.value(request.id, if (result.resultCode == android.app.Activity.RESULT_FIRST_USER)
            com.cosmotune.providers.ProviderVerificationOutcome.FAILED else com.cosmotune.providers.ProviderVerificationOutcome.CLOSED)
    }
    return remember(context, launcher) { { manager, request ->
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            active.set(manager to request)
            launcher.launch(android.content.Intent(context, ProviderVerificationActivity::class.java)
                .putExtra(ProviderVerificationActivity.TARGET, Json.encodeToString(request.target)))
            // The Activity closes itself on a result or at the challenge's expiry; it lives in its own process.
            AutoCloseable { }
        } else {
            // Android 8: the system browser, returning through the registered callback; the saved state ends the request.
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(request.target.url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            AutoCloseable { }
        }
    } }
}
