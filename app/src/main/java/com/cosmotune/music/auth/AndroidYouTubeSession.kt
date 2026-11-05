package com.cosmotune.music.auth

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.youtube.account.CredentialVault
import com.cosmotune.youtube.account.SessionRecovery
import com.cosmotune.youtube.account.YouTubeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The phone's side of the one Google session ([YouTubeSession.shared]): where a renewed jar is kept (the encrypted [AuthStore]),
 * where a fresher copy is looked for before an expiry (the WebView's own cookie store), and when the session is refreshed (before
 * the first signed request after the app comes to the foreground, every ten minutes while it is on screen, and every few hours in
 * the background through WorkManager, on a network and a battery that is not low).
 */
object AndroidYouTubeSession {
    @Volatile
    private var installed = false

    /** Call once from `Application.onCreate`, on the main thread. */
    fun install(context: Context, authStore: AuthStore) {
        if (installed) return
        installed = true
        val session = YouTubeSession.shared
        session.vault = object : CredentialVault {
            override fun store(cookie: String) = authStore.rotateCookie(cookie)
            override fun storeGoogle(cookie: String) = authStore.rotateGoogleCookie(cookie)
        }
        session.recovery = SessionRecovery {
            // CookieManager belongs to the WebView's thread.
            withContext(Dispatchers.Main) { BrowserSession.currentCookies() }
                ?.let { (youtube, google) -> SessionRecovery.Recovered(youtube, google) }
        }
        val foreground = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var timer: Job? = null

            override fun onStart(owner: LifecycleOwner) {
                session.onResume()
                timer?.cancel()
                timer = session.startPeriodicRefresh(foreground)
            }

            override fun onStop(owner: LifecycleOwner) {
                timer?.cancel()
                timer = null
                session.flush()
            }
        })
        YouTubeSessionWorker.schedule(context)
    }

    /**
     * Puts the active stored session in force for this process: its cookie, its accounts jar and the channel it acts as. For a
     * process that starts without the activity (the music service, a background refresh).
     */
    fun publishActive(store: AuthStore) {
        val active = store.activeSession
        Innertube.cookie = active?.cookie
        if (active == null) return
        YouTubeSession.shared.installGoogle(active.googleCookie)
        val profileId = store.activeProfileId ?: active.activeProfileId
        val profile = active.profiles.firstOrNull { it.profileId == profileId } ?: active.profiles.firstOrNull()
        Innertube.selectChannel(profile?.pageId, profile?.dataSyncId, profile?.authUser)
    }
}

/** The background refresh: rotates the session's short-lived cookies every few hours so a quiet week does not end the sign-in. */
class YouTubeSessionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (Innertube.cookie == null) AndroidYouTubeSession.publishActive(AuthStore(applicationContext))
        if (Innertube.cookie == null) return Result.success()
        YouTubeSession.shared.refresh(verify = false)
        YouTubeSession.shared.flush()
        return Result.success()
    }

    companion object {
        private const val NAME = "cosmotune-youtube-session-refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<YouTubeSessionWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}
