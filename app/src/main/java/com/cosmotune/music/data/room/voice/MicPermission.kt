package com.cosmotune.music.data.room.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The microphone permission for room voice, asked the first time the person unmutes and never on joining (listening needs no
 * permission). [attach] is called once from MainActivity.onCreate, which registers the system request there, where the Activity
 * Result API needs it to be; [ensure] can then be called from anywhere the room's unmute runs.
 *
 * When the person has refused for good, the system stops showing the request and answers no at once; [isBlocked] says so, and
 * the host then shows "Microphone blocked in system settings" with a button for [openAppSettings].
 */
object MicPermission {
    private const val PERMISSION = Manifest.permission.RECORD_AUDIO
    private const val PREFS = "room_voice"
    private const val KEY_BLOCKED = "mic_blocked"

    private var appContext: Context? = null
    private var activity = WeakReference<ComponentActivity>(null)
    private var launcher: ActivityResultLauncher<String>? = null

    // the request in flight; survives the activity being recreated under it (a rotation with the dialog up)
    private var pending: CancellableContinuation<Boolean>? = null
    private var askedAt = 0L
    private var rationaleBefore = false
    private val asking = Mutex()

    fun attach(host: ComponentActivity) {
        appContext = host.applicationContext
        activity = WeakReference(host)
        launcher = host.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onResult(host, granted) }
        host.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (activity.get() !== host) return
                activity = WeakReference(null)
                launcher = null
                // a recreated activity registers again and receives the answer; one that is finishing never will
                if (!host.isChangingConfigurations) finish(isGranted(host))
            }
        })
    }

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** True when the system will no longer show the request, so only the app's settings page can grant it. */
    fun isBlocked(context: Context): Boolean {
        if (isGranted(context)) return false
        val shown = activity.get()?.shouldShowRequestPermissionRationale(PERMISSION)
        return status(granted = false, blockedBefore = prefs(context).getBoolean(KEY_BLOCKED, false), rationale = shown ?: false) == Status.BLOCKED
    }

    /** True when the microphone may be used, asking the person first if it has not been decided; false when refused or no screen is up. */
    suspend fun ensure(): Boolean {
        val context = appContext ?: return false
        if (isGranted(context)) return true
        return asking.withLock {
            if (isGranted(context)) return@withLock true
            withContext(Dispatchers.Main.immediate) {
                val host = activity.get() ?: return@withContext false
                val request = launcher ?: return@withContext false
                suspendCancellableCoroutine { cont ->
                    pending = cont
                    rationaleBefore = host.shouldShowRequestPermissionRationale(PERMISSION)
                    askedAt = SystemClock.elapsedRealtime()
                    runCatching { request.launch(PERMISSION) }.onFailure { finish(false) }
                    cont.invokeOnCancellation { if (pending === cont) pending = null }
                }
            }
        }
    }

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun onResult(host: ComponentActivity, granted: Boolean) {
        val blocked = !granted && blockedAfterDenial(
            rationaleBefore = rationaleBefore,
            rationaleAfter = host.shouldShowRequestPermissionRationale(PERMISSION),
            answeredInMs = SystemClock.elapsedRealtime() - askedAt,
        )
        prefs(host).edit().putBoolean(KEY_BLOCKED, blocked).apply()
        finish(granted)
    }

    private fun finish(granted: Boolean) {
        val cont = pending ?: return
        pending = null
        if (cont.isActive) cont.resume(granted)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- the decisions, apart from Android so they can be tested ----

    enum class Status { GRANTED, CAN_ASK, BLOCKED }

    /** An answer faster than this was the system's, not the person's: the dialog was never shown. */
    internal const val INSTANT_ANSWER_MS = 300L

    internal fun status(granted: Boolean, blockedBefore: Boolean, rationale: Boolean): Status = when {
        granted -> Status.GRANTED
        // the system offers a rationale only while it would still show the request
        rationale -> Status.CAN_ASK
        blockedBefore -> Status.BLOCKED
        else -> Status.CAN_ASK
    }

    /**
     * Whether a "no" was for good. On Android 11 and later a second refusal is final: the rationale was due before the request and
     * is no longer due after it. An answer that came back at once means the system did not even ask. A first request dismissed by
     * tapping outside it leaves no rationale either, but takes the person's time, and is not counted as final.
     */
    internal fun blockedAfterDenial(rationaleBefore: Boolean, rationaleAfter: Boolean, answeredInMs: Long): Boolean =
        !rationaleAfter && (rationaleBefore || answeredInMs < INSTANT_ANSWER_MS)
}
