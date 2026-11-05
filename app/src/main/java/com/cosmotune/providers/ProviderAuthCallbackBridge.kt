package com.cosmotune.providers

import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.*

/** Provider codes go only to their pending protected state; they are never music deep links. */
object ProviderAuthCallbackBridge {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun configure(context: Context) {
        val legacy = if (context.packageName.startsWith("com.cosmotune.tv")) "cosmotune-tv" else "cosmotune"
        val route = ProviderNativeAuthRoute(context.packageName, legacy)
        ProviderHub.configureNativeAuthentication(route::allows, "${context.packageName}://provider-auth/session-grant")
    }

    fun consume(context: Context, intent: Intent): Boolean {
        if (intent.action != Intent.ACTION_VIEW) return false
        val input = intent.dataString ?: return false
        if (!ProviderHub.acceptsNativeAuthentication(input)) return false
        // Do not retain a code in the Activity's current intent or pass it to music/share handlers.
        intent.data = null
        val app = context.applicationContext
        scope.launch {
            val received = try { ProviderHub.receiveNativeAuthentication(input) }
                catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled; false }
                catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                Toast.makeText(app, if (received) "Provider sign-in received. Finish its setup in Sources."
                    else "Provider sign-in was not accepted. Restart it in Sources.", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
}
